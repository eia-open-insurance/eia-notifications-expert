package com.eia.notifications.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal create request. Tenant identity is carried by the platform on the
 * internal call; it is part of the body here because the expert is not exposed
 * publicly and the domain forwards the resolved tenant.
 */
public record CreateNotificationRequest(
        @NotNull UUID tenantId,
        @NotBlank @Size(max = 64) String notificationType,
        @NotBlank @Size(max = 32) String channel,
        @NotNull UUID recipientPartyId,
        @NotBlank @Size(max = 300) String subject,
        @NotBlank String body,
        @Size(max = 64) String templateCode,
        Integer templateVersion,
        Instant scheduledAt,
        String sourceType,
        UUID sourceId,
        @Size(max = 64) String sourceReference
) {
}
