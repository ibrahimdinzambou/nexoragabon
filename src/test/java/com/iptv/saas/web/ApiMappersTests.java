package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.Subscription;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiMappersTests {
    @Test
    void freeSubscriptionEndUsesPlanDurationInsteadOfStoredLegacyDate() {
        Plan free = new Plan();
        free.code = "free";
        free.name = "Free";
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 30;

        Subscription subscription = new Subscription();
        subscription.plan = free;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.startedAt = Instant.parse("2026-07-01T10:00:00Z");
        subscription.currentPeriodEnd = Instant.parse("2027-07-01T10:00:00Z");

        var mapped = ApiMappers.subscription(subscription);

        assertEquals(Instant.parse("2026-07-31T10:00:00Z"), mapped.get("currentPeriodEnd"));
    }

    @Test
    void expiredFreeSubscriptionIsExposedAsPastDue() {
        Plan free = new Plan();
        free.code = "free";
        free.name = "Free";
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 1;

        Subscription subscription = new Subscription();
        subscription.plan = free;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.startedAt = Instant.now().minusSeconds(3 * 86_400L);

        var mapped = ApiMappers.subscription(subscription);

        assertEquals(Enums.SubscriptionStatus.PAST_DUE, mapped.get("status"));
        assertEquals(subscription.startedAt.plusSeconds(86_400L), mapped.get("currentPeriodEnd"));
    }
}
