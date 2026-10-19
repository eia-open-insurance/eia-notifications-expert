package com.eia.notifications.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Notifications vertical.
 *
 * <p>The canonical envelope and {@code EventVerticals} require the vertical to be
 * {@code notifications} (plural) and the event type to start with that vertical,
 * so the singular {@code notification.notification.*} names cannot be built by
 * the shared library. The slice therefore emits
 * {@code notifications.notification.created} and
 * {@code notifications.notification.sent}, which still differ from the planned
 * catalog verbs ({@code issued}, {@code delivered}).</p>
 *
 * <p>Notifications are shared attention signals used from both books and the
 * current API has no book input; the canonical envelope requires a book, so
 * these events are stamped DIRECT until book scoping reaches Notifications.</p>
 *
 * <p>Rows start at version 0 for API compatibility, while the canonical envelope
 * requires {@code aggregateVersion >= 1}; the event version is therefore the row
 * version plus one (create -> 1, first send -> 2).</p>
 */
final class NotificationEvents {
    static final String CREATED = "notifications.notification.created";
    static final String SENT = "notifications.notification.sent";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "notifications";
    private static final String AGGREGATE_TYPE = "Notification";
    private static final String SOURCE_BUILD = "eia-notifications-expert@0.1.0-SNAPSHOT";

    private NotificationEvents() {
    }

    static EventEnvelope created(Notification notification, Instant now) {
        Map<String, Object> data = identity(notification);
        data.put("notificationType", notification.notificationType());
        data.put("channel", notification.channel());
        data.put("status", notification.status());
        data.put("recipientPartyId", notification.recipientPartyId());
        data.put("version", notification.version());
        if (notification.templateCode() != null) {
            data.put("templateCode", notification.templateCode());
        }
        if (notification.templateVersion() != null) {
            data.put("templateVersion", notification.templateVersion());
        }
        if (notification.scheduledAt() != null) {
            data.put("scheduledAt", notification.scheduledAt().toString());
        }

        return envelope(notification, CREATED, notification.version() + 1L, now, data);
    }

    static EventEnvelope sent(Notification before, Notification after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("notificationType", after.notificationType());
        data.put("channel", after.channel());
        data.put("status", after.status());
        data.put("recipientPartyId", after.recipientPartyId());
        data.put("version", after.version());
        data.put("from", before.status());
        data.put("to", after.status());
        if (after.sentAt() != null) {
            data.put("sentAt", after.sentAt().toString());
        }

        return envelope(after, SENT, after.version() + 1L, now, data);
    }

    private static Map<String, Object> identity(Notification notification) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", notification.id());
        data.put("notificationNumber", notification.notificationNumber());
        return data;
    }

    private static EventEnvelope envelope(
            Notification notification, String eventType, long aggregateVersion, Instant now,
            Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                notification.tenantId(), AGGREGATE_TYPE, notification.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(notification.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(notification.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-notifications-expert"))
                .subject(EventSubject.of(VERTICAL, "notification", notification.id(),
                        notification.notificationNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
