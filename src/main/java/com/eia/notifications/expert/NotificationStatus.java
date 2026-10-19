package com.eia.notifications.expert;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * SPEC 11.8.5 NotificationStatus. The expert persists the status and the
 * database CHECK constraint is the final guard.
 */
public enum NotificationStatus {
    CREATED,
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    ACKNOWLEDGED,
    ACTIONED,
    FAILED,
    SUPPRESSED,
    EXPIRED,
    CANCELLED;

    public static NotificationStatus from(String value) {
        for (NotificationStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new ApiException(ErrorCode.INVALID_STATUS,
                "Status '" + value + "' is not one of: " + Arrays.stream(values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")) + " (SPEC 11.8.5).");
    }
}
