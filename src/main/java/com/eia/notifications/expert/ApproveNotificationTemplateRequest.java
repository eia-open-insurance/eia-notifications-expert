package com.eia.notifications.expert;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Approval command. The approver is audit evidence and is copied to the
 * template row together with the approval instant.
 */
public record ApproveNotificationTemplateRequest(
        UUID tenantId,
        @NotNull UUID approvedBy
) {
}
