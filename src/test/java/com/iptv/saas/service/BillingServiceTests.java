package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Invoice;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PaymentMethod;
import com.iptv.saas.domain.PaymentTransaction;
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
    void paidRegistrationCreatesPendingPaymentAndSuspendsOrganizationUntilAdminValidation() {
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
        owner.email = "client@example.com";
        Organization organization = new Organization();
        organization.name = "Client Space";
        organization.owner = owner;
        organization.status = Enums.OrganizationStatus.ACTIVE;
        Plan pro = new Plan();
        pro.code = "pro";
        pro.name = "Pro";
        pro.priceMonthly = BigDecimal.valueOf(15000);
        pro.currency = "FCFA";
        PaymentMethod method = new PaymentMethod();
        method.code = "mobile-money";
        method.name = "Mobile Money";
        method.active = true;

        when(organizations.currentOrganization(owner)).thenReturn(organization);
        when(plans.findByCodeAndActiveTrue("pro")).thenReturn(Optional.of(pro));
        when(paymentMethods.findByCode("mobile-money")).thenReturn(Optional.of(method));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.save(any())).thenAnswer(invocation -> {
            PaymentTransaction payment = invocation.getArgument(0);
            payment.id = 42L;
            return payment;
        });

        var result = service.registerPlanSelection(owner, "pro", "mobile-money", "TX-12345");

        assertEquals(Enums.SubscriptionStatus.PAST_DUE, result.subscription().status);
        assertEquals(Enums.OrganizationStatus.SUSPENDED, organization.status);
        assertEquals(Enums.PaymentStatus.PENDING, result.payment().status);
        assertEquals("TX-12345", result.payment().proofUrl);
        assertEquals(pro, result.payment().plan);
        verify(organizationRepository).save(organization);
        verify(payments).save(argThat(payment -> payment.amount.equals(BigDecimal.valueOf(15000))
                && payment.paymentMethod == method
                && payment.paymentReference.startsWith("PAY-")));
    }

    @Test
    void paidUpgradeFromActiveFreePlanCreatesPaymentWithoutSuspendingCurrentAccess() {
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
        owner.email = "client@example.com";
        Organization organization = new Organization();
        organization.name = "Client Space";
        organization.owner = owner;
        organization.status = Enums.OrganizationStatus.ACTIVE;
        Plan free = new Plan();
        free.id = 1L;
        free.code = "free";
        free.priceMonthly = BigDecimal.ZERO;
        free.billingPeriodDays = 30;
        Plan pro = new Plan();
        pro.id = 2L;
        pro.code = "pro";
        pro.name = "Pro";
        pro.priceMonthly = BigDecimal.valueOf(15000);
        pro.currency = "FCFA";
        Subscription current = new Subscription();
        current.organization = organization;
        current.plan = free;
        current.status = Enums.SubscriptionStatus.ACTIVE;
        current.startedAt = Instant.now();
        current.currentPeriodEnd = Instant.now().plusSeconds(10 * 86_400L);
        PaymentMethod method = new PaymentMethod();
        method.code = "mobile-money";
        method.name = "Mobile Money";
        method.active = true;

        when(organizations.currentOrganization(owner)).thenReturn(organization);
        when(plans.findByCodeAndActiveTrue("pro")).thenReturn(Optional.of(pro));
        when(paymentMethods.findByCode("mobile-money")).thenReturn(Optional.of(method));
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)).thenReturn(Optional.of(current));
        when(payments.findByOrganizationOrderByCreatedAtDesc(organization)).thenReturn(List.of());
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentTransaction payment = service.createPayment(owner, "pro", "mobile-money", "TX-UPGRADE");

        assertEquals(Enums.PaymentStatus.PENDING, payment.status);
        assertEquals(pro, payment.plan);
        assertEquals(Enums.OrganizationStatus.ACTIVE, organization.status);
        assertEquals(Enums.SubscriptionStatus.ACTIVE, current.status);
        assertEquals(free, current.plan);
        verify(subscriptions, never()).save(any());
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void verifyingPaymentReactivatesSuspendedAccountAfterNonPayment() {
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

        UserEntity admin = new UserEntity();
        admin.email = "admin@example.com";
        UserEntity owner = new UserEntity();
        owner.email = "client@example.com";
        Organization organization = new Organization();
        organization.name = "Client Space";
        organization.owner = owner;
        organization.status = Enums.OrganizationStatus.SUSPENDED;
        Plan pro = new Plan();
        pro.id = 7L;
        pro.code = "pro";
        pro.name = "Pro";
        pro.priceMonthly = BigDecimal.valueOf(15000);
        pro.currency = "FCFA";
        pro.billingPeriodDays = 30;
        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = pro;
        subscription.status = Enums.SubscriptionStatus.PAST_DUE;
        subscription.startedAt = Instant.now().minusSeconds(45 * 86_400L);
        subscription.currentPeriodEnd = Instant.now().minusSeconds(15 * 86_400L);
        PaymentTransaction payment = new PaymentTransaction();
        payment.id = 42L;
        payment.organization = organization;
        payment.user = owner;
        payment.plan = pro;
        payment.status = Enums.PaymentStatus.PENDING;
        payment.paymentReference = "PAY-RENEW";
        payment.amount = BigDecimal.valueOf(15000);
        payment.currency = "FCFA";
        Invoice invoice = new Invoice();
        invoice.invoiceNumber = "INV-42";

        when(payments.findById(42L)).thenReturn(Optional.of(payment));
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)).thenReturn(Optional.of(subscription));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoices.createForPayment(any())).thenReturn(invoice);

        PaymentTransaction verified = service.verifyPayment(admin, 42L);

        assertEquals(Enums.PaymentStatus.VERIFIED, verified.status);
        assertEquals(Enums.OrganizationStatus.ACTIVE, organization.status);
        assertEquals(Enums.SubscriptionStatus.ACTIVE, subscription.status);
        assertNotNull(subscription.currentPeriodEnd);
        verify(organizationRepository).save(organization);
        verify(subscriptions).save(subscription);
    }

    @Test
    void verifiedRenewalExtendsCurrentPaidPeriod() {
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

        UserEntity admin = new UserEntity();
        admin.email = "admin@example.com";
        Organization organization = new Organization();
        organization.name = "Client Space";
        organization.status = Enums.OrganizationStatus.ACTIVE;
        Plan pro = new Plan();
        pro.id = 7L;
        pro.code = "pro";
        pro.name = "Pro";
        pro.priceMonthly = BigDecimal.valueOf(15000);
        pro.currency = "FCFA";
        pro.billingPeriodDays = 30;
        Instant existingEnd = Instant.now().plusSeconds(10 * 86_400L);
        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = pro;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.startedAt = Instant.now().minusSeconds(20 * 86_400L);
        subscription.currentPeriodEnd = existingEnd;
        PaymentTransaction payment = new PaymentTransaction();
        payment.id = 43L;
        payment.organization = organization;
        payment.plan = pro;
        payment.status = Enums.PaymentStatus.PENDING;
        payment.paymentReference = "PAY-EXTEND";
        payment.amount = BigDecimal.valueOf(15000);
        payment.currency = "FCFA";
        Invoice invoice = new Invoice();
        invoice.invoiceNumber = "INV-43";

        when(payments.findById(43L)).thenReturn(Optional.of(payment));
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)).thenReturn(Optional.of(subscription));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoices.createForPayment(any())).thenReturn(invoice);

        service.verifyPayment(admin, 43L);

        assertEquals(existingEnd.plusSeconds(30L * 86_400L), subscription.currentPeriodEnd);
        assertEquals(Enums.OrganizationStatus.ACTIVE, organization.status);
        verify(organizationRepository, never()).save(any());
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
