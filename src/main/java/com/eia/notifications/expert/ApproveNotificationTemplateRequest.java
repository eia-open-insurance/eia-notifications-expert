package com.eia.notifications.expert;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Approval command. The approver is audit evidence and is copied to the
 * template row together with the approval instant. The approver is a stable
 * directory identifier such as {@code a-adjuster}, not a UUID; the service
 * trims it and enforces the 1..128 character range.
 */
public record ApproveNotificationTemplateRequest(
        UUID tenantId,
        @NotNull String approvedBy
) {
}
