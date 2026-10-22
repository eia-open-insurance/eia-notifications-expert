package com.eia.notifications.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification aggregate. The API representation follows SPEC 11.8.4 while the
 * first slice carries the core fields: number, type, channel, status, recipient,
 * subject, body, schedule and send/delivery evidence.
 */
public record Notification(
        UUID id,
        UUID tenantId,
        String notificationNumber,
        String notificationType,
        String channel,
        String status,
        UUID recipientPartyId,
        String subject,
        String body,
        String templateCode,
        Integer templateVersion,
        Instant scheduledAt,
        Instant sentAt,
        Instant deliveredAt,
        String providerReference,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String sourceType,
        UUID sourceId,
        String sourceReference
) {
}
