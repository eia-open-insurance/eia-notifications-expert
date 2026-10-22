package com.eia.notifications.expert;

/**
 * Outbound delivery transport for one notification channel. The service owns the
 * attempt bookkeeping; a provider only performs the transport call and reports
 * its reference and outcome.
 */
public interface DeliveryProvider {

    /**
     * Provider code persisted on delivery attempts, for example {@code SIMULATOR}.
     */
    String provider();

    DeliveryResult deliver(DeliveryRequest request);
}
