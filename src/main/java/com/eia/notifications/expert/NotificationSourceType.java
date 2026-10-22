package com.eia.notifications.expert;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Source record type for notification linkage. Stored as varchar(32) and
 * validated against this closed set before persistence.
 *
 * <p>A notification may point at the business record that caused it (a claim,
 * policy, contract, payment or an unclassified source). source_type and
 * source_id are supplied as a pair; sourceReference is optional metadata.</p>
 */
public enum NotificationSourceType {
    DIRECT_CLAIM,
    ASSUMED_CLAIM,
    POLICY,
    CONTRACT,
    PAYMENT,
    OTHER;

    /**
     * Trims and uppercases the supplied type. A blank value means no source
     * linkage and returns {@code null}; anything outside the enum is rejected.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (NotificationSourceType type : values()) {
            if (type.name().equals(normalized)) {
                return type.name();
            }
        }
        throw new ApiException(ErrorCode.INVALID_SOURCE_TYPE,
                "Source type '" + value + "' is not one of: " + Arrays.stream(values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")) + ".");
    }
}
