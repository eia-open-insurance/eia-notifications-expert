package com.eia.notifications.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the notification template registry. Only an ACTIVE template with an
 * approver satisfies NTF-14 ("templates MUST be approved before activation").
 *
 * <p>{@code bodyTemplate}, {@code createdAt} and {@code updatedAt} are part of
 * the template-management representation; the compatibility constructor keeps
 * the lookup-only shape used before template management existed.</p>
 */
public record NotificationTemplate(
        UUID id,
        UUID tenantId,
        String templateCode,
        int templateVersion,
        String channel,
        String locale,
        String bodyTemplate,
        String status,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Lookup-only shape retained for callers that do not need the body or the
     * audit timestamps.
     */
    public NotificationTemplate(UUID id,
                                UUID tenantId,
                                String templateCode,
                                int templateVersion,
                                String channel,
                                String locale,
                                String status,
                                String approvedBy,
                                Instant approvedAt) {
        this(id, tenantId, templateCode, templateVersion, channel, locale, null, status,
                approvedBy, approvedAt, null, null);
    }

    public boolean isApproved() {
        return NotificationTemplateStatus.ACTIVE.name().equals(status) && approvedBy != null;
    }
}
