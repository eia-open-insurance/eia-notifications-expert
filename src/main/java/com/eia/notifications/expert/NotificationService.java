package com.eia.notifications.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Expert-side enforcement of the notification invariants. The public domain
 * service also validates these rules, but the expert is the last line of
 * defence before persistence and must not trust its caller.
 *
 * <p>Every aggregate write also appends its domain event to the transactional
 * outbox in the same transaction (SPEC 7.5.1), so a rollback leaves neither the
 * notification mutation nor the event behind.</p>
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final NotificationDeliveryAttemptRepository deliveryAttempts;
    private final DeliveryProvider deliveryProvider;
    private final OutboxRepository outbox;

    public NotificationService(NotificationRepository notifications,
                               NotificationTemplateRepository templates,
                               NotificationDeliveryAttemptRepository deliveryAttempts,
                               DeliveryProvider deliveryProvider,
                               OutboxRepository outbox) {
        this.notifications = notifications;
        this.templates = templates;
        this.deliveryAttempts = deliveryAttempts;
        this.deliveryProvider = deliveryProvider;
        this.outbox = outbox;
    }

    public List<Notification> list(UUID tenantId) {
        return list(tenantId, null, null);
    }

    /**
     * Tenant-scoped list, optionally narrowed to the notifications linked to
     * one source. The filter is all-or-nothing: a partial pair is rejected
     * rather than silently widening the result.
     */
    public List<Notification> list(UUID tenantId, String rawSourceType, UUID sourceId) {
        String sourceType = NotificationSourceType.normalize(rawSourceType);
        NotificationSourceType.validateFilter(sourceType, sourceId);
        if (sourceType == null) {
            return notifications.findAll(tenantId);
        }
        return notifications.findAllBySource(tenantId, sourceType, sourceId);
    }

    public Notification get(UUID tenantId, UUID id) {
        return notifications.findById(tenantId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Notification '" + id + "' was not found for tenant '" + tenantId + "'."));
    }

    /**
     * Tenant-scoped delivery history. The notification lookup is the tenant wall:
     * a notification owned by another tenant is a 404 even though its attempt
     * rows carry the same notification id.
     */
    public List<NotificationDeliveryAttempt> attempts(UUID tenantId, UUID id) {
        get(tenantId, id);
        return deliveryAttempts.findAll(tenantId, id);
    }

    @Transactional
    public Notification create(CreateNotificationRequest request) {
        NotificationType.from(request.notificationType());
        NotificationChannel.from(request.channel());
        CreateNotificationRequest normalized = normalizeSourceLinkage(request);

        String templateCode = blankToNull(normalized.templateCode());
        Integer templateVersion = normalizeTemplateVersion(templateCode, normalized.templateVersion());
        if (templateCode != null) {
            requireApprovedTemplate(normalized.tenantId(), templateCode, templateVersion, normalized.channel());
        }

        Notification notification = notifications.create(normalized, templateCode, templateVersion);
        outbox.insert(OutboxEvent.fromEnvelope(NotificationEvents.created(notification, Instant.now())));
        return notification;
    }

    /**
     * Source linkage is all-or-nothing for the classified pair: sourceType and
     * sourceId are supplied together or not at all. sourceReference is optional
     * metadata and never substitutes for sourceId.
     */
    private CreateNotificationRequest normalizeSourceLinkage(CreateNotificationRequest request) {
        String sourceType = NotificationSourceType.normalize(request.sourceType());
        UUID sourceId = request.sourceId();
        if (sourceType == null && sourceId != null) {
            throw new ApiException(ErrorCode.INVALID_SOURCE_LINKAGE,
                    "sourceType is required when sourceId is supplied.");
        }
        if (sourceType != null && sourceId == null) {
            throw new ApiException(ErrorCode.INVALID_SOURCE_LINKAGE,
                    "sourceId is required when sourceType is supplied.");
        }

        String sourceReference = blankToNull(request.sourceReference());
        if (sourceReference != null && sourceReference.length() > 64) {
            throw new ApiException(ErrorCode.INVALID_SOURCE_REFERENCE,
                    "sourceReference must be at most 64 characters.");
        }

        if (sourceType == null && sourceId == null && sourceReference == null) {
            return request;
        }
        return new CreateNotificationRequest(
                request.tenantId(), request.notificationType(), request.channel(), request.recipientPartyId(),
                request.subject(), request.body(), request.templateCode(), request.templateVersion(),
                request.scheduledAt(), sourceType, sourceId, sourceReference);
    }

    /**
     * Issues a drafted notification. NTF-01/NTF-14 require an approved template,
     * and sent_at may never precede scheduled_at.
     */
    @Transactional
    public Notification send(UUID tenantId, UUID id) {
        Notification notification = get(tenantId, id);

        if (!isSendable(notification.status())) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Notification '" + id + "' is in status '" + notification.status()
                            + "'; only CREATED or QUEUED notifications can be sent.");
        }
        if (notification.templateCode() == null) {
            throw new ApiException(ErrorCode.TEMPLATE_REQUIRED,
                    "Notification '" + id + "' has no template; an approved template is required before sending (NTF-01, NTF-14).");
        }

        Instant sentAt = Instant.now();
        if (notification.scheduledAt() != null && sentAt.isBefore(notification.scheduledAt())) {
            throw new ApiException(ErrorCode.SEND_BEFORE_SCHEDULED,
                    "Notification '" + id + "' is scheduled for " + notification.scheduledAt()
                            + " and cannot be sent at " + sentAt + "; sent_at must not precede scheduled_at.");
        }

        requireApprovedTemplate(tenantId, notification.templateCode(), notification.templateVersion(),
                notification.channel());

        int attemptNumber = 1;
        String provider = deliveryProvider.provider();
        DeliveryResult delivery = deliveryProvider.deliver(new DeliveryRequest(
                tenantId, id, attemptNumber, notification.channel(), notification.recipientPartyId(),
                notification.subject(), notification.body()));
        if (!delivery.isDelivered()) {
            throw new ApiException(ErrorCode.DELIVERY_FAILED,
                    "Delivery provider '" + provider + "' failed for notification '"
                            + id + "': " + delivery.errorCode() + " " + delivery.errorMessage());
        }

        deliveryAttempts.insert(NotificationDeliveryAttempt.from(tenantId, id, attemptNumber,
                notification.channel(), provider, delivery, sentAt));
        Notification sent = notifications.markSent(notification, sentAt, sentAt, delivery.providerReference())
                .orElseThrow(() -> new ApiException(ErrorCode.STALE_VERSION,
                        "Notification '" + id + "' changed concurrently and was not sent; reload and retry."));
        outbox.insert(OutboxEvent.fromEnvelope(NotificationEvents.sent(notification, sent, sentAt)));
        return sent;
    }

    private void requireApprovedTemplate(UUID tenantId, String templateCode, Integer templateVersion,
                                         String channel) {
        NotificationTemplate template = templates.find(tenantId, templateCode, templateVersion, channel)
                .orElseThrow(() -> new ApiException(ErrorCode.TEMPLATE_NOT_FOUND,
                        "Template '" + templateCode + "' version " + templateVersion
                                + " is not registered for tenant '" + tenantId + "'."));
        if (!template.isApproved()) {
            throw new ApiException(ErrorCode.TEMPLATE_NOT_APPROVED,
                    "Template '" + templateCode + "' version " + templateVersion + " is " + template.status()
                            + " and has no approver; templates must be approved before activation (NTF-14).");
        }
        if (template.channel() != null && !template.channel().equals(channel)) {
            throw new ApiException(ErrorCode.TEMPLATE_CHANNEL_MISMATCH,
                    "Template '" + templateCode + "' version " + templateVersion + " is restricted to channel '"
                            + template.channel() + "' but the notification uses '" + channel + "'.");
        }
    }

    private Integer normalizeTemplateVersion(String templateCode, Integer templateVersion) {
        if (templateCode == null) {
            if (templateVersion != null) {
                throw new ApiException(ErrorCode.TEMPLATE_REQUIRED,
                        "templateVersion was supplied without templateCode.");
            }
            return null;
        }
        if (templateVersion == null) {
            return 1;
        }
        if (templateVersion < 1) {
            throw new ApiException(ErrorCode.INVALID_TEMPLATE_VERSION,
                    "templateVersion must be a positive integer.");
        }
        return templateVersion;
    }

    private boolean isSendable(String status) {
        return NotificationStatus.CREATED.name().equals(status) || NotificationStatus.QUEUED.name().equals(status);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
