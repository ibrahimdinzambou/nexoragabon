package com.iptv.saas.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionExpiryJob {
    private final BillingService billing;

    public SubscriptionExpiryJob(BillingService billing) {
        this.billing = billing;
    }

    @Scheduled(
            initialDelayString = "${app.billing.expiry-check-initial-delay-ms:15000}",
            fixedDelayString = "${app.billing.expiry-check-interval-ms:300000}"
    )
    public void expireSubscriptions() {
        billing.reconcileExpiredSubscriptions();
    }
}
