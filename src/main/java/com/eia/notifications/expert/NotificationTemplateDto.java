package com.eia.notifications.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Template-management representation. Internal persistence fields such as the
 * tenant id and effective dates are deliberately not exposed.
 */
public record NotificationTemplateDto(
        UUID id,
        String code,
        int version,
        String channel,
        String locale,
        String bodyTemplate,
        String status,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static NotificationTemplateDto from(NotificationTemplate template) {
        return new NotificationTemplateDto(
                template.id(),
                template.templateCode(),
                template.templateVersion(),
                template.channel(),
                template.locale(),
                template.bodyTemplate(),
                template.status(),
                template.approvedBy(),
                template.approvedAt(),
                template.createdAt(),
                template.updatedAt());
    }
}
