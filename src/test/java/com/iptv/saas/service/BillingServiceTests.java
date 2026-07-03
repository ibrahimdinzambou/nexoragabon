package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.OrganizationRepository;
import com.iptv.saas.repository.PaymentMethodRepository;
import com.iptv.saas.repository.PaymentTransactionRepository;
import com.iptv.saas.repository.PlanRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class BillingServiceTests {
    @Test
    void blocksSecondTrialForTheSameOwnerAndPlanAcrossOrganizations() {
        PlanRepository plans = mock(PlanRepository.class);
        PaymentMethodRepository paymentMethods = mock(PaymentMethodRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
                organizationRepository,
                organizations,
                invoices,
                telegram,
                mock(TelegramActivityService.class),
                audit,
                "free",
                7,
                24
        );

        UserEntity owner = new UserEntity();
        owner.id = 7L;
        Organization newOrganization = new Organization();
        newOrganization.owner = owner;
        Plan pro = new Plan();
        pro.code = "pro";
        pro.priceMonthly = BigDecimal.valueOf(15000);
        pro.trialDays = 7;

        when(organizations.currentOrganization(owner)).thenReturn(newOrganization);
        when(plans.findByCodeAndActiveTrue("pro")).thenReturn(Optional.of(pro));
        when(subscriptions.existsByOrganizationAndPlanAndTrialEndsAtIsNotNull(newOrganization, pro))
                .thenReturn(false);
        when(subscriptions.countTrialsForOwnerAndPlan(owner, pro)).thenReturn(1L);

        assertThrows(ApiException.class, () -> service.startTrial(owner, "pro"));
        verify(subscriptions, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void paidTrialEndUsesOwnerCreationDateAndCurrentPlanTrialDays() {
        PlanRepository plans = mock(PlanRepository.class);
        PaymentMethodRepository paymentMethods = mock(PaymentMethodRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
                organizationRepository,
                organizations,
                invoices,
                telegram,
                mock(TelegramActivityService.class),
                audit,
                "free",
                7,
                24
        );

        UserEntity owner = new UserEntity();
        owner.createdAt = Instant.parse("2026-07-01T08:00:00Z");
        Organization organization = new Organization();
        organization.owner = owner;
        Plan pro = new Plan();
        pro.code = "pro";
        pro.priceMonthly = BigDecimal.valueOf(15000);
        pro.trialDays = 24;

        when(organizations.currentOrganization(owner)).thenReturn(organization);
        when(plans.findByCodeAndActiveTrue("pro")).thenReturn(Optional.of(pro));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var subscription = service.startTrial(owner, "pro");

        assertEquals(Enums.SubscriptionStatus.TRIALING, subscription.status);
        assertEquals(Instant.parse("2026-07-25T08:00:00Z"), subscription.trialEndsAt);
        assertEquals(subscription.trialEndsAt, subscription.currentPeriodEnd);
    }

    @Test
    void freePlanGetsCurrentPeriodEndFromTrialDuration() {
        PlanRepository plans = mock(PlanRepository.class);
        PaymentMethodRepository paymentMethods = mock(PaymentMethodRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
                organizationRepository,
                organizations,
                invoices,
                telegram,
                mock(TelegramActivityService.class),
                audit,
                "free",
                7,
                24
        );

        UserEntity owner = new UserEntity();
        Organization organization = new Organization();
        Plan free = new Plan();
        free.code = "free";
        free.priceMonthly = BigDecimal.ZERO;
        free.trialDays = 24;
        free.billingPeriodDays = 30;

        when(organizations.currentOrganization(owner)).thenReturn(organization);
        when(plans.findByCodeAndActiveTrue("free")).thenReturn(Optional.of(free));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        var subscription = service.startTrial(owner, "free");

        assertEquals(free, subscription.plan);
        assertNotNull(subscription.currentPeriodEnd);
        assertNotNull(subscription.startedAt);
        assertEquals(subscription.startedAt.plusSeconds(24L * 86_400L), subscription.currentPeriodEnd);
        verify(subscriptions).save(argThat(saved -> saved.currentPeriodEnd != null
                && !saved.currentPeriodEnd.isBefore(before.plusSeconds(24L * 86_400L - 2))));
    }

    @Test
    void blocksSecondFreePeriodForSameOwnerAndPlan() {
        PlanRepository plans = mock(PlanRepository.class);
        PaymentMethodRepository paymentMethods = mock(PaymentMethodRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
                organizationRepository,
                organizations,
                invoices,
                telegram,
                mock(TelegramActivityService.class),
                audit,
                "free",
                7,
                24
        );

        UserEntity owner = new UserEntity();
        Organization organization = new Organization();
        organization.owner = owner;
        Plan free = new Plan();
        free.code = "free";
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 1;

        when(organizations.currentOrganization(owner)).thenReturn(organization);
        when(plans.findByCodeAndActiveTrue("free")).thenReturn(Optional.of(free));
        when(subscriptions.existsByOrganizationAndPlan(organization, free)).thenReturn(false);
        when(subscriptions.countSubscriptionsForOwnerAndPlan(owner, free)).thenReturn(1L);

        assertThrows(ApiException.class, () -> service.startTrial(owner, "free"));
        verify(subscriptions, never()).save(any());
    }

    @Test
    void reconcileExpiredSubscriptionsMarksPastDueAndSuspendsOrganization() {
        PlanRepository plans = mock(PlanRepository.class);
        PaymentMethodRepository paymentMethods = mock(PaymentMethodRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
                organizationRepository,
                organizations,
                invoices,
                telegram,
                mock(TelegramActivityService.class),
                audit,
                "free",
                7,
                24
        );

        Organization organization = new Organization();
        organization.status = Enums.OrganizationStatus.ACTIVE;
        Plan free = new Plan();
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 1;
        Subscription expired = new Subscription();
        expired.organization = organization;
        expired.plan = free;
        expired.status = Enums.SubscriptionStatus.ACTIVE;
        expired.startedAt = Instant.now().minusSeconds(3 * 86_400L);

        when(subscriptions.findByStatusIn(any())).thenReturn(List.of(expired));

        assertEquals(1, service.reconcileExpiredSubscriptions());
        assertEquals(Enums.SubscriptionStatus.PAST_DUE, expired.status);
        assertEquals(Enums.OrganizationStatus.SUSPENDED, organization.status);
        assertEquals(expired.startedAt.plusSeconds(86_400L), expired.currentPeriodEnd);
        verify(subscriptions).save(expired);
        verify(organizationRepository).save(organization);
    }
}
