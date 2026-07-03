package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiMappersTests {
    @Test
    void subscriptionEndUsesStoredPeriodEndEvenForFreePlan() {
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

        assertEquals(Instant.parse("2027-07-01T10:00:00Z"), mapped.get("currentPeriodEnd"));
    }

    @Test
    void subscriptionEndFallsBackToAdminPlanDurationWhenMissing() {
        Plan free = new Plan();
        free.code = "free";
        free.name = "Free";
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 15;

        Subscription subscription = new Subscription();
        subscription.plan = free;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.startedAt = Instant.parse("2026-06-22T08:00:00Z");

        var mapped = ApiMappers.subscription(subscription);

        assertEquals(Instant.parse("2026-07-07T08:00:00Z"), mapped.get("currentPeriodEnd"));
    }

    @Test
    void trialSubscriptionEndUsesAccountCreationDateAndCurrentAdminTrialDays() {
        UserEntity owner = new UserEntity();
        owner.createdAt = Instant.parse("2099-07-01T08:00:00Z");

        Organization organization = new Organization();
        organization.owner = owner;

        Plan plan = new Plan();
        plan.code = "pro";
        plan.name = "Pro";
        plan.priceMonthly = BigDecimal.valueOf(15000);
        plan.trialDays = 24;

        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.TRIALING;
        subscription.startedAt = Instant.parse("2099-07-03T10:00:00Z");
        subscription.trialEndsAt = Instant.parse("2099-07-10T10:00:00Z");
        subscription.currentPeriodEnd = subscription.trialEndsAt;

        var mapped = ApiMappers.subscription(subscription);

        assertEquals(Enums.SubscriptionStatus.TRIALING, mapped.get("status"));
        assertEquals(Instant.parse("2099-07-25T08:00:00Z"), mapped.get("trialEndsAt"));
        assertEquals(Instant.parse("2099-07-25T08:00:00Z"), mapped.get("currentPeriodEnd"));
    }

    @Test
    void expiredSubscriptionIsExposedAsPastDue() {
        Plan free = new Plan();
        free.code = "free";
        free.name = "Free";
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 30;

        Subscription subscription = new Subscription();
        subscription.plan = free;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.startedAt = Instant.now().minusSeconds(3 * 86_400L);
        subscription.currentPeriodEnd = Instant.now().minusSeconds(86_400L);

        var mapped = ApiMappers.subscription(subscription);

        assertEquals(Enums.SubscriptionStatus.PAST_DUE, mapped.get("status"));
        assertEquals(subscription.currentPeriodEnd, mapped.get("currentPeriodEnd"));
    }
}
