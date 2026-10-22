package com.eia.notifications.expert;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Notification template lifecycle. A template starts DRAFT, becomes ACTIVE
 * only through approval (NTF-14), and can be retired from DRAFT or ACTIVE.
 */
public enum NotificationTemplateStatus {
    DRAFT,
    ACTIVE,
    RETIRED;

    public static NotificationTemplateStatus from(String value) {
        for (NotificationTemplateStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new ApiException(ErrorCode.INVALID_TEMPLATE_STATUS,
                "Template status '" + value + "' is not one of: " + Arrays.stream(values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")) + ".");
    }
}
