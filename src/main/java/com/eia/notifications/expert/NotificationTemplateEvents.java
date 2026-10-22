package com.eia.notifications.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;
import com.fasterxml.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the notification template registry.
 *
 * <p>Template events share the {@code notifications} vertical with
 * notification events. The aggregate version is assigned by the lifecycle
 * step (created 1, approved 2, retired 3) because the template row has no
 * version column; the outbox uniqueness key also includes the event type.</p>
 */
final class NotificationTemplateEvents {
    static final String CREATED = "notifications.template.created";
    static final String APPROVED = "notifications.template.approved";
    static final String RETIRED = "notifications.template.retired";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "notifications";
    private static final String AGGREGATE_TYPE = "NotificationTemplate";
    private static final String SOURCE_BUILD = "eia-notifications-expert@0.1.0-SNAPSHOT";

    private NotificationTemplateEvents() {
    }

    static EventEnvelope created(NotificationTemplate template, Instant now) {
        return envelope(template, CREATED, 1L, now);
    }

    static EventEnvelope approved(NotificationTemplate template, Instant now) {
        return envelope(template, APPROVED, 2L, now);
    }

    static EventEnvelope retired(NotificationTemplate template, Instant now) {
        return envelope(template, RETIRED, 3L, now);
    }

    private static EventEnvelope envelope(NotificationTemplate template, String eventType,
                                          long aggregateVersion, Instant now) {
        UUID eventId = EventIds.deterministic(
                template.tenantId(), AGGREGATE_TYPE, template.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(template.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(template.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-notifications-expert"))
                .subject(EventSubject.of(VERTICAL, "template", template.id(), template.templateCode()))
                .links(List.of())
                .data(payload(template))
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    /**
     * The canonical JSON writer omits null map values. A {@link NullNode}
     * keeps {@code approvedBy} present (as JSON null) on DRAFT events, so the
     * published payload always carries the documented shape.
     */
    private static Map<String, Object> payload(NotificationTemplate template) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateId", template.id());
        data.put("code", template.templateCode());
        data.put("version", template.templateVersion());
        data.put("locale", template.locale());
        data.put("status", template.status());
        data.put("approvedBy", template.approvedBy() == null
                ? NullNode.getInstance()
                : template.approvedBy());
        return data;
    }
}
