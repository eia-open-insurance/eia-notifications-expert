package com.eia.notifications.expert;

/**
 * Outcome of one provider call. Status is the persisted attempt status:
 * {@link #DELIVERED} or {@link #FAILED}.
 */
public record DeliveryResult(
        String providerReference,
        String status,
        String errorCode,
        String errorMessage
) {

    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";

    public static DeliveryResult delivered(String providerReference) {
        return new DeliveryResult(providerReference, DELIVERED, null, null);
    }

    public static DeliveryResult failed(String errorCode, String errorMessage) {
        return new DeliveryResult(null, FAILED, errorCode, errorMessage);
    }

    public boolean isDelivered() {
        return DELIVERED.equals(status);
    }
}
