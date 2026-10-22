package com.eia.notifications.expert;

import java.util.UUID;

/**
 * Immutable input for one delivery attempt. The provider sees only what it needs
 * to render and address the message; persistence stays in the expert.
 */
public record DeliveryRequest(
        UUID tenantId,
        UUID notificationId,
        int attemptNumber,
        String channel,
        UUID recipientPartyId,
        String subject,
        String body
) {
}
