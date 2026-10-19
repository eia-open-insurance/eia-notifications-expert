package com.eia.notifications.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the notification template registry. Only an ACTIVE template with an
 * approver satisfies NTF-14 ("templates MUST be approved before activation").
 */
public record NotificationTemplate(
        UUID id,
        UUID tenantId,
        String templateCode,
        int templateVersion,
        String channel,
        String locale,
        String status,
        UUID approvedBy,
        Instant approvedAt
) {

    public boolean isApproved() {
        return "ACTIVE".equals(status) && approvedBy != null;
    }
}
