package com.eia.notifications.expert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local delivery simulator. It never contacts an external system and always
 * confirms delivery, returning the deterministic reference
 * {@code SIM-<notification uuid>} so retries share one provider idempotency key
 * (NTF-11).
 *
 * <p>Selected by {@code eia.notifications.provider=simulator}, which is also the
 * default when the property is absent.</p>
 */
@Component
@ConditionalOnProperty(prefix = "eia.notifications", name = "provider",
        havingValue = "simulator", matchIfMissing = true)
public class SimulatedDeliveryProvider implements DeliveryProvider {

    public static final String PROVIDER = "SIMULATOR";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public DeliveryResult deliver(DeliveryRequest request) {
        return DeliveryResult.delivered("SIM-" + request.notificationId());
    }
}
