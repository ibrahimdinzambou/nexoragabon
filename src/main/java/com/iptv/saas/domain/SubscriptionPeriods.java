package com.iptv.saas.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class SubscriptionPeriods {
    private static final int DEFAULT_BILLING_PERIOD_DAYS = 30;

    private SubscriptionPeriods() {
    }

    public static Instant currentPeriodEnd(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        if (usesTrialWindow(subscription)) {
            return trialEndsAt(subscription);
        }
        if (subscription.currentPeriodEnd != null) {
            return subscription.currentPeriodEnd;
        }
        Instant anchor = subscriptionAnchor(subscription);
        return anchor == null ? null : anchor.plus(billingPeriodDays(subscription), ChronoUnit.DAYS);
    }

    public static Instant trialEndsAt(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        Instant anchor = accountCreatedAt(subscription);
        if (anchor != null && subscription.plan != null) {
            return anchor.plus(trialDays(subscription), ChronoUnit.DAYS);
        }
        if (subscription.trialEndsAt != null) {
            return subscription.trialEndsAt;
        }
        return anchor == null ? null : anchor.plus(trialDays(subscription), ChronoUnit.DAYS);
    }

    public static Enums.SubscriptionStatus effectiveStatus(Subscription subscription, Instant now) {
        if (subscription == null) {
            return null;
        }
        Instant end = currentPeriodEnd(subscription);
        boolean expired = end != null && !end.isAfter(now);
        if (expired && isPeriodControlledStatus(subscription.status)) {
            return Enums.SubscriptionStatus.PAST_DUE;
        }
        if (!expired && subscription.status == Enums.SubscriptionStatus.PAST_DUE && usesTrialWindow(subscription)) {
            return Enums.SubscriptionStatus.TRIALING;
        }
        return subscription.status;
    }

    public static boolean isUsable(Subscription subscription, Instant now) {
        Enums.SubscriptionStatus status = effectiveStatus(subscription, now);
        if (status != Enums.SubscriptionStatus.ACTIVE && status != Enums.SubscriptionStatus.TRIALING) {
            return false;
        }
        Instant end = currentPeriodEnd(subscription);
        return end == null || end.isAfter(now);
    }

    public static boolean usesTrialWindow(Subscription subscription) {
        if (subscription == null) {
            return false;
        }
        if (subscription.status == Enums.SubscriptionStatus.TRIALING) {
            return true;
        }
        return subscription.status == Enums.SubscriptionStatus.PAST_DUE
                && subscription.trialEndsAt != null
                && (subscription.currentPeriodEnd == null
                || subscription.currentPeriodEnd.equals(subscription.trialEndsAt));
    }

    public static int billingPeriodDays(Subscription subscription) {
        return subscription == null
                || subscription.plan == null
                || subscription.plan.billingPeriodDays == null
                || subscription.plan.billingPeriodDays <= 0
                ? DEFAULT_BILLING_PERIOD_DAYS
                : subscription.plan.billingPeriodDays;
    }

    public static int trialDays(Subscription subscription) {
        return subscription == null || subscription.plan == null || subscription.plan.trialDays <= 0
                ? 0
                : subscription.plan.trialDays;
    }

    private static boolean isPeriodControlledStatus(Enums.SubscriptionStatus status) {
        return status == Enums.SubscriptionStatus.ACTIVE
                || status == Enums.SubscriptionStatus.TRIALING;
    }

    private static Instant accountCreatedAt(Subscription subscription) {
        if (subscription.organization != null
                && subscription.organization.owner != null
                && subscription.organization.owner.createdAt != null) {
            return subscription.organization.owner.createdAt;
        }
        if (subscription.organization != null && subscription.organization.createdAt != null) {
            return subscription.organization.createdAt;
        }
        return subscriptionAnchor(subscription);
    }

    private static Instant subscriptionAnchor(Subscription subscription) {
        if (subscription.startedAt != null) {
            return subscription.startedAt;
        }
        return subscription.createdAt;
    }
}
