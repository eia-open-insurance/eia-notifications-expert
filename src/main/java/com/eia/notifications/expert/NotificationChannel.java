package com.eia.notifications.expert;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * SPEC 11.8.5 Channel. Stored as varchar and validated against this closed set.
 */
public enum NotificationChannel {
    IN_APP,
    EMAIL,
    SMS,
    PUSH,
    WEBHOOK,
    TASK,
    DIGEST;

    public static NotificationChannel from(String value) {
        for (NotificationChannel channel : values()) {
            if (channel.name().equals(value)) {
                return channel;
            }
        }
        throw new ApiException(ErrorCode.INVALID_CHANNEL,
                "Channel '" + value + "' is not one of: " + Arrays.stream(values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")) + " (SPEC 11.8.5).");
    }
}
