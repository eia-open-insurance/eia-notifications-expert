package com.eia.notifications.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Template creation command. Tenant identity normally comes from the
 * {@code X-EIA-Tenant} header or the {@code tenantId} query parameter; the
 * optional body field mirrors the internal expert API so direct callers can
 * pass it in the payload.
 */
public record CreateNotificationTemplateRequest(
        UUID tenantId,
        @NotBlank @Size(max = 64) String templateCode,
        @NotNull @Min(1) Integer templateVersion,
        @Size(max = 32) String channel,
        @NotBlank @Size(max = 16) String locale,
        @NotBlank String bodyTemplate
) {
}
