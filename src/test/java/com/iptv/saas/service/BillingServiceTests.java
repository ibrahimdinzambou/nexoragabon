package com.iptv.saas.service;

import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.PaymentMethodRepository;
import com.iptv.saas.repository.PaymentTransactionRepository;
import com.iptv.saas.repository.PlanRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
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
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
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
    void freePlanGetsCurrentPeriodEndFromBillingDuration() {
        PlanRepository plans = mock(PlanRepository.class);
        PaymentMethodRepository paymentMethods = mock(PaymentMethodRepository.class);
        PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        BillingService service = new BillingService(
                plans,
                paymentMethods,
                payments,
                subscriptions,
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
        free.billingPeriodDays = 45;

        when(organizations.currentOrganization(owner)).thenReturn(organization);
        when(plans.findByCodeAndActiveTrue("free")).thenReturn(Optional.of(free));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        var subscription = service.startTrial(owner, "free");

        assertEquals(free, subscription.plan);
        assertNotNull(subscription.currentPeriodEnd);
        assertNotNull(subscription.startedAt);
        assertEquals(subscription.startedAt.plusSeconds(45L * 86_400L), subscription.currentPeriodEnd);
        verify(subscriptions).save(argThat(saved -> saved.currentPeriodEnd != null
                && !saved.currentPeriodEnd.isBefore(before.plusSeconds(45L * 86_400L - 2))));
    }
}
