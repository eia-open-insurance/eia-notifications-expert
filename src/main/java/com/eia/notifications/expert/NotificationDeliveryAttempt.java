package com.eia.notifications.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt for a notification on a channel. Attempt numbers are
 * sequential per notification and unique together with the notification id.
 * Provider reference is the idempotency key; error fields are populated only for
 * a {@code FAILED} attempt (SPEC 11.8.4, NTF-11).
 */
public record NotificationDeliveryAttempt(
        UUID id,
        UUID tenantId,
        UUID notificationId,
        int attemptNumber,
        String channel,
        String provider,
        String providerReference,
        String status,
        String errorCode,
        String errorMessage,
        Instant attemptedAt
) {

    static NotificationDeliveryAttempt from(UUID tenantId, UUID notificationId, int attemptNumber,
                                            String channel, String provider, DeliveryResult result,
                                            Instant attemptedAt) {
        return new NotificationDeliveryAttempt(Uuid7.generate(), tenantId, notificationId, attemptNumber,
                channel, provider, result.providerReference(), result.status(), result.errorCode(),
                result.errorMessage(), attemptedAt);
    }
}
