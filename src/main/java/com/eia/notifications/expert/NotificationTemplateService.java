package com.eia.notifications.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Template-management use cases for the Notifications expert.
 *
 * <p>Approval is the only path to ACTIVE (NTF-14): a template is created DRAFT,
 * may be approved once, and may be retired from DRAFT or ACTIVE. Every write
 * appends its domain event to the transactional outbox in the same transaction
 * (SPEC 7.5.1).</p>
 */
@Service
public class NotificationTemplateService {

    private final NotificationTemplateRepository templates;
    private final OutboxRepository outbox;

    public NotificationTemplateService(NotificationTemplateRepository templates, OutboxRepository outbox) {
        this.templates = templates;
        this.outbox = outbox;
    }

    public List<NotificationTemplateDto> list(UUID tenantId, String status, String code) {
        requireTenant(tenantId);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            NotificationTemplateStatus.from(normalizedStatus);
        }
        return templates.findAll(tenantId, normalizedStatus, blankToNull(code)).stream()
                .map(NotificationTemplateDto::from)
                .toList();
    }

    @Transactional
    public NotificationTemplateDto create(UUID tenantId, CreateNotificationTemplateRequest request) {
        UUID owner = tenantId != null ? tenantId : request.tenantId();
        requireTenant(owner);

        String templateCode = requiredText(request.templateCode(), "templateCode", 64);
        int templateVersion = requireVersion(request.templateVersion());
        String locale = requiredText(request.locale(), "locale", 16);
        String bodyTemplate = request.bodyTemplate();
        if (bodyTemplate == null || bodyTemplate.isBlank()) {
            throw validation("bodyTemplate", "must not be blank");
        }
        String channel = blankToNull(request.channel());
        if (channel != null) {
            NotificationChannel.from(channel);
        }

        if (templates.exists(owner, templateCode, templateVersion, locale)) {
            throw alreadyExists(owner, templateCode, templateVersion, locale);
        }

        NotificationTemplate created;
        try {
            created = templates.create(owner, templateCode, templateVersion, channel, locale, bodyTemplate);
        } catch (DuplicateKeyException ex) {
            // The pre-check can race with a concurrent create; the unique key is
            // the authority and still maps to the same conflict response.
            throw alreadyExists(owner, templateCode, templateVersion, locale);
        }

        outbox.insert(OutboxEvent.fromEnvelope(NotificationTemplateEvents.created(created, Instant.now())));
        return NotificationTemplateDto.from(created);
    }

    @Transactional
    public NotificationTemplateDto approve(UUID tenantId, UUID id, UUID approvedBy) {
        requireTenant(tenantId);
        if (approvedBy == null) {
            throw validation("approvedBy", "must not be null");
        }

        NotificationTemplate current = findOrThrow(tenantId, id);
        if (NotificationTemplateStatus.ACTIVE.name().equals(current.status())) {
            throw new ApiException(ErrorCode.TEMPLATE_ALREADY_ACTIVE,
                    "Template '" + id + "' is already ACTIVE and cannot be approved again.");
        }
        if (!NotificationTemplateStatus.DRAFT.name().equals(current.status())) {
            throw new ApiException(ErrorCode.TEMPLATE_STATUS_CONFLICT,
                    "Template '" + id + "' is " + current.status() + "; only DRAFT templates can be approved.");
        }

        Instant approvedAt = Instant.now();
        NotificationTemplate approved = templates.markApproved(tenantId, id, approvedBy, approvedAt)
                .orElseThrow(() -> new ApiException(ErrorCode.TEMPLATE_STATUS_CONFLICT,
                        "Template '" + id + "' changed concurrently and was not approved; reload and retry."));
        outbox.insert(OutboxEvent.fromEnvelope(NotificationTemplateEvents.approved(approved, approvedAt)));
        return NotificationTemplateDto.from(approved);
    }

    @Transactional
    public NotificationTemplateDto retire(UUID tenantId, UUID id) {
        requireTenant(tenantId);

        NotificationTemplate current = findOrThrow(tenantId, id);
        if (NotificationTemplateStatus.RETIRED.name().equals(current.status())) {
            throw new ApiException(ErrorCode.TEMPLATE_STATUS_CONFLICT,
                    "Template '" + id + "' is already RETIRED.");
        }
        if (!NotificationTemplateStatus.DRAFT.name().equals(current.status())
                && !NotificationTemplateStatus.ACTIVE.name().equals(current.status())) {
            throw new ApiException(ErrorCode.TEMPLATE_STATUS_CONFLICT,
                    "Template '" + id + "' is " + current.status()
                            + "; only DRAFT or ACTIVE templates can be retired.");
        }

        Instant retiredAt = Instant.now();
        NotificationTemplate retired = templates.markRetired(tenantId, id, retiredAt)
                .orElseThrow(() -> new ApiException(ErrorCode.TEMPLATE_STATUS_CONFLICT,
                        "Template '" + id + "' changed concurrently and was not retired; reload and retry."));
        outbox.insert(OutboxEvent.fromEnvelope(NotificationTemplateEvents.retired(retired, retiredAt)));
        return NotificationTemplateDto.from(retired);
    }

    private NotificationTemplate findOrThrow(UUID tenantId, UUID id) {
        return templates.findById(tenantId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Template '" + id + "' was not found for tenant '" + tenantId + "'."));
    }

    private void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new ApiException(ErrorCode.TENANT_REQUIRED, "Tenant is required.");
        }
    }

    private int requireVersion(Integer version) {
        if (version == null || version < 1) {
            throw new ApiException(ErrorCode.INVALID_TEMPLATE_VERSION,
                    "templateVersion must be a positive integer.");
        }
        return version;
    }

    private String requiredText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw validation(field, "must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw validation(field, "must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private ApiException validation(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more request fields are invalid.",
                List.of(new FieldViolation(field, "INVALID_FIELD", message)));
    }

    private ApiException alreadyExists(UUID tenantId, String templateCode, int templateVersion, String locale) {
        return new ApiException(ErrorCode.TEMPLATE_ALREADY_EXISTS,
                "Template '" + templateCode + "' version " + templateVersion + " locale '" + locale
                        + "' already exists for tenant '" + tenantId + "'.");
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
