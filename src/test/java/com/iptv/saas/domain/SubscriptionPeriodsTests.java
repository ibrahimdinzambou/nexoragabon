package com.iptv.saas.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionPeriodsTests {
    @Test
    void trialEndUsesOwnerCreationDateAndCurrentPlanTrialDays() {
        UserEntity owner = new UserEntity();
        owner.createdAt = Instant.parse("2026-07-01T08:00:00Z");

        Organization organization = new Organization();
        organization.owner = owner;

        Plan plan = new Plan();
        plan.trialDays = 24;

        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.TRIALING;
        subscription.startedAt = Instant.parse("2026-07-03T12:00:00Z");
        subscription.trialEndsAt = Instant.parse("2026-07-08T12:00:00Z");
        subscription.currentPeriodEnd = subscription.trialEndsAt;

        assertEquals(Instant.parse("2026-07-25T08:00:00Z"), SubscriptionPeriods.trialEndsAt(subscription));
        assertEquals(Instant.parse("2026-07-25T08:00:00Z"), SubscriptionPeriods.currentPeriodEnd(subscription));
    }

    @Test
    void activePaidSubscriptionKeepsStoredPeriodEvenWhenHistoricalTrialExists() {
        Plan plan = new Plan();
        plan.trialDays = 24;
        plan.billingPeriodDays = 30;

        Subscription subscription = new Subscription();
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.trialEndsAt = Instant.parse("2026-07-08T12:00:00Z");
        subscription.currentPeriodEnd = Instant.parse("2026-08-15T12:00:00Z");

        assertEquals(Instant.parse("2026-08-15T12:00:00Z"), SubscriptionPeriods.currentPeriodEnd(subscription));
    }

    @Test
    void pastDueTrialBecomesEffectivelyTrialingWhenAdminExtendsTrialWindow() {
        UserEntity owner = new UserEntity();
        owner.createdAt = Instant.parse("2026-07-01T08:00:00Z");

        Organization organization = new Organization();
        organization.owner = owner;

        Plan plan = new Plan();
        plan.trialDays = 24;

        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.PAST_DUE;
        subscription.trialEndsAt = Instant.parse("2026-07-08T08:00:00Z");
        subscription.currentPeriodEnd = subscription.trialEndsAt;

        assertEquals(Enums.SubscriptionStatus.TRIALING,
                SubscriptionPeriods.effectiveStatus(subscription, Instant.parse("2026-07-10T08:00:00Z")));
    }
}
