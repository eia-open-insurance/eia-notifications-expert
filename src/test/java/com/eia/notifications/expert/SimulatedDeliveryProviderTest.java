package com.eia.notifications.expert;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedDeliveryProviderTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID NOTIFICATION_ID = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");

    private final SimulatedDeliveryProvider provider = new SimulatedDeliveryProvider();

    @Test
    void reportsTheSimulatorProviderCode() {
        assertThat(provider.provider()).isEqualTo("SIMULATOR");
    }

    @Test
    void alwaysDeliversWithADeterministicSimReference() {
        DeliveryRequest request = new DeliveryRequest(TENANT, NOTIFICATION_ID, 1, "EMAIL",
                UUID.fromString("0192f3a4-0000-7000-8000-000000000002"),
                "Claim requires attention", "Claim CLM-2026-000000001 requires attention.");

        DeliveryResult first = provider.deliver(request);
        DeliveryResult second = provider.deliver(request);

        assertThat(first.providerReference()).isEqualTo("SIM-" + NOTIFICATION_ID);
        assertThat(first.status()).isEqualTo(DeliveryResult.DELIVERED);
        assertThat(first.isDelivered()).isTrue();
        assertThat(first.errorCode()).isNull();
        assertThat(first.errorMessage()).isNull();
        assertThat(second).isEqualTo(first);
    }
}
