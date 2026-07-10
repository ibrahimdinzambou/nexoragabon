package com.iptv.saas.service;

import com.iptv.saas.domain.*;
import com.iptv.saas.repository.*;
import com.iptv.saas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private final PlanRepository plans;
    private final PaymentMethodRepository paymentMethods;
    private final PaymentTransactionRepository payments;
    private final SubscriptionRepository subscriptions;
    private final OrganizationRepository organizations;
    private final OrganizationService organizationService;
    private final InvoiceService invoiceService;
    private final TelegramAlertService telegram;
    private final TelegramActivityService activity;
    private final AuditService audit;
    private final String defaultTrialPlan;
    private final int defaultTrialDays;
    private final int paymentExpiresHours;

    public BillingService(
            PlanRepository plans,
            PaymentMethodRepository paymentMethods,
            PaymentTransactionRepository payments,
            SubscriptionRepository subscriptions,
            OrganizationRepository organizations,
            OrganizationService organizationService,
            InvoiceService invoiceService,
            TelegramAlertService telegram,
            TelegramActivityService activity,
            AuditService audit,
            @Value("${app.billing.default-trial-plan:free}") String defaultTrialPlan,
            @Value("${app.billing.default-trial-days:7}") int defaultTrialDays,
            @Value("${app.billing.payment-request-expires-hours:24}") int paymentExpiresHours
    ) {
        this.plans = plans;
        this.paymentMethods = paymentMethods;
        this.payments = payments;
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.organizationService = organizationService;
        this.invoiceService = invoiceService;
        this.telegram = telegram;
        this.activity = activity;
        this.audit = audit;
        this.defaultTrialPlan = defaultTrialPlan;
        this.defaultTrialDays = defaultTrialDays;
        this.paymentExpiresHours = paymentExpiresHours;
    }

    @Transactional(readOnly = true)
    public List<Plan> publicPlans() {
        return plans.findByActiveTrueOrderByPriceMonthlyAsc();
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> publicPaymentMethods() {
        return paymentMethods.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public Subscription currentSubscription(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization).orElse(null);
    }

    @Transactional
    public Subscription startTrial(UserEntity user, String planCode) {
        Organization organization = organizationService.currentOrganization(user);
        Subscription existing = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization).orElse(null);
        if (subscriptionUsable(existing)) {
            return existing;
        }
        String selectedPlan = planCode == null || planCode.isBlank() ? defaultTrialPlan : planCode;
        Plan plan = plans.findByCodeAndActiveTrue(selectedPlan)
                .orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        Instant now = Instant.now();
        subscription.startedAt = now;
        if (plan.priceMonthly == null || plan.priceMonthly.signum() == 0) {
            if (trialAlreadyUsed(user, organization, plan)) {
                throw ApiException.paymentRequired("Periode gratuite deja utilisee pour cette formule. Veuillez choisir un abonnement payant.");
            }
            startFreeTrial(subscription);
        } else {
            int days = trialDays(plan);
            if (days <= 0) {
                throw ApiException.paymentRequired("Cette formule ne propose pas d'essai gratuit");
            }
            if (trialAlreadyUsed(user, organization, plan)) {
                throw ApiException.paymentRequired("Essai deja utilise pour cette formule. Veuillez valider un paiement.");
            }
            subscription.status = Enums.SubscriptionStatus.TRIALING;
            subscription.trialEndsAt = SubscriptionPeriods.trialEndsAt(subscription);
            subscription.currentPeriodEnd = subscription.trialEndsAt;
        }
        audit.log(
                user,
                subscription.status == Enums.SubscriptionStatus.TRIALING
                        ? "billing.trial.started"
                        : "billing.subscription.started",
                "Subscription",
                null,
                plan.code
        );
        return subscriptions.save(subscription);
    }

    @Transactional
    public RegistrationBilling registerPlanSelection(UserEntity user, String planCode, String paymentMethodCode, String proofUrl) {
        String selectedPlan = planCode == null || planCode.isBlank() ? defaultTrialPlan : planCode;
        Plan plan = plans.findByCodeAndActiveTrue(selectedPlan)
                .orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        if (isFreePlan(plan)) {
            return new RegistrationBilling(startTrial(user, selectedPlan), null);
        }
        if (!hasManualPaymentDetails(paymentMethodCode, proofUrl)) {
            return new RegistrationBilling(startTrial(user, selectedPlan), null);
        }
        return startPendingManualPayment(
                user,
                plan,
                paymentMethodCode,
                proofUrl,
                true,
                "Inscription payante en attente",
                "billing.payment.signup_requested"
        );
    }

    @Transactional(readOnly = true)
    public PaymentTransaction latestPendingPayment(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return payments.findByOrganizationOrderByCreatedAtDesc(organization).stream()
                .filter(payment -> payment.status == Enums.PaymentStatus.PENDING)
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public PaymentTransaction createPayment(UserEntity user, String planCode, String paymentMethodCode, String proofUrl) {
        Organization organization = organizationService.currentOrganization(user);
        Plan plan = plans.findByCodeAndActiveTrue(planCode).orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        if (isFreePlan(plan)) {
            throw ApiException.validation("Cette formule ne necessite pas de paiement manuel");
        }
        return startPendingManualPayment(
                user,
                organization,
                plan,
                paymentMethodCode,
                proofUrl,
                false,
                "Renouvellement en attente",
                "billing.payment.requested"
        ).payment();
    }

    private RegistrationBilling startPendingManualPayment(
            UserEntity user,
            Plan plan,
            String paymentMethodCode,
            String proofUrl,
            boolean suspendUntilValidation,
            String notificationTitle,
            String auditAction
    ) {
        return startPendingManualPayment(
                user,
                organizationService.currentOrganization(user),
                plan,
                paymentMethodCode,
                proofUrl,
                suspendUntilValidation,
                notificationTitle,
                auditAction
        );
    }

    private RegistrationBilling startPendingManualPayment(
            UserEntity user,
            Organization organization,
            Plan plan,
            String paymentMethodCode,
            String proofUrl,
            boolean suspendUntilValidation,
            String notificationTitle,
            String auditAction
    ) {
        PaymentMethod method = paymentMethod(paymentMethodCode);
        String proof = paymentProof(proofUrl);
        ensureNoPendingPayment(organization, plan);
        Subscription subscription = preparePendingManualSubscription(organization, plan, suspendUntilValidation);

        PaymentTransaction payment = createPaymentTransaction(user, organization, plan, method, proof);
        telegram.send(
                notificationTitle,
                """
                Reference: %s
                Client: %s
                Organisation: %s
                Formule: %s
                Montant: %s %s
                Methode: %s
                Preuve: %s
                Expire: %s
                """.formatted(
                        payment.paymentReference,
                        user.email,
                        organization.name,
                        plan.name,
                        payment.amount,
                        payment.currency,
                        method.name,
                        proof,
                        payment.expiresAt
                ),
                List.of(List.of(
                        new TelegramAlertService.Action("Valider #" + payment.id, "confirm:verify_payment:" + payment.id),
                        new TelegramAlertService.Action("Refuser #" + payment.id, "confirm:reject_payment:" + payment.id)
                ))
        );
        audit.log(user, auditAction, "PaymentTransaction", payment.id, payment.paymentReference);
        return new RegistrationBilling(subscription, payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> history(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return payments.findByOrganizationOrderByCreatedAtDesc(organization);
    }

    @Transactional
    public Subscription changePlan(UserEntity user, String planCode) {
        Organization organization = organizationService.currentOrganization(user);
        Plan plan = plans.findByCodeAndActiveTrue(planCode).orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)
                .orElseGet(() -> {
                    Subscription created = new Subscription();
                    created.organization = organization;
                    created.startedAt = Instant.now();
                    return created;
                });
        if (subscriptionUsable(subscription)
                && subscription.plan != null
                && Objects.equals(subscription.plan.id, plan.id)) {
            return subscription;
        }
        subscription.plan = plan;
        Instant now = Instant.now();
        subscription.startedAt = now;
        if (plan.priceMonthly == null || plan.priceMonthly.signum() == 0) {
            if (trialAlreadyUsed(user, organization, plan)) {
                throw ApiException.paymentRequired("Periode gratuite deja utilisee pour cette formule. Veuillez choisir un abonnement payant.");
            }
            startFreeTrial(subscription);
        } else {
            int days = trialDays(plan);
            if (days <= 0 || trialAlreadyUsed(user, organization, plan)) {
                throw ApiException.paymentRequired("Paiement requis pour activer cette formule");
            }
            subscription.status = Enums.SubscriptionStatus.TRIALING;
            subscription.trialEndsAt = SubscriptionPeriods.trialEndsAt(subscription);
            subscription.currentPeriodEnd = subscription.trialEndsAt;
        }
        audit.log(user, "billing.plan.changed", "Subscription", subscription.id, plan.code);
        Subscription saved = subscriptions.save(subscription);
        activity.planChanged(user, saved);
        return saved;
    }

    @Transactional
    public Subscription cancel(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)
                .orElseThrow(() -> ApiException.notFound("Abonnement introuvable"));
        subscription.cancelAtPeriodEnd = true;
        subscription.status = Enums.SubscriptionStatus.CANCELLED;
        audit.log(user, "billing.subscription.cancelled", "Subscription", subscription.id, subscription.plan.code);
        return subscriptions.save(subscription);
    }

    @Transactional
    public PaymentTransaction verifyPayment(UserEntity admin, Long paymentId) {
        PaymentTransaction payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Paiement introuvable"));
        if (payment.status != Enums.PaymentStatus.PENDING) {
            throw ApiException.validation("Ce paiement n'est pas en attente");
        }
        payment.status = Enums.PaymentStatus.VERIFIED;
        payment.verifiedAt = Instant.now();
        payment.verifiedBy = admin;
        payment = payments.save(payment);
        activateSubscription(payment);
        audit.log(admin, "billing.payment.verified", "PaymentTransaction", payment.id, payment.paymentReference);

        String invoiceNumber = null;
        try {
            Invoice invoice = invoiceService.createForPayment(payment);
            invoiceNumber = invoice.invoiceNumber;
            invoiceService.resend(admin, invoice.id);
        } catch (Exception e) {
            log.warn("Facture non generee/envoyee pour le paiement {}: {}", paymentId, e.getMessage(), e);
        }

        try {
            telegram.send(
                    "Paiement valide",
                    """
                    Reference: %s
                    Client: %s
                    Organisation: %s
                    Formule: %s
                    Montant: %s %s
                    Admin: %s
                    Facture: %s
                    """.formatted(
                            payment.paymentReference,
                            payment.user == null ? "-" : payment.user.email,
                            payment.organization == null ? "-" : payment.organization.name,
                            payment.plan == null ? "-" : payment.plan.name,
                            payment.amount,
                            payment.currency,
                            admin.email,
                            invoiceNumber == null ? "-" : invoiceNumber
                    )
            );
        } catch (Exception e) {
            log.warn("Notification Telegram echouee pour le paiement {}: {}", paymentId, e.getMessage());
        }

        return payment;
    }

    @Transactional
    public PaymentTransaction rejectPayment(UserEntity admin, Long paymentId, String reason) {
        PaymentTransaction payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Paiement introuvable"));
        if (payment.status != Enums.PaymentStatus.PENDING) {
            throw ApiException.validation("Ce paiement n'est pas en attente");
        }
        payment.status = Enums.PaymentStatus.REJECTED;
        payment.rejectionReason = reason == null || reason.isBlank() ? "Rejete par l'administration" : reason;
        payment.verifiedBy = admin;
        payment.verifiedAt = Instant.now();
        telegram.send(
                "Paiement rejete",
                """
                Reference: %s
                Client: %s
                Organisation: %s
                Formule: %s
                Raison: %s
                Admin: %s
                """.formatted(
                        payment.paymentReference,
                        payment.user == null ? "-" : payment.user.email,
                        payment.organization == null ? "-" : payment.organization.name,
                        payment.plan == null ? "-" : payment.plan.name,
                        payment.rejectionReason,
                        admin.email
                )
        );
        audit.log(admin, "billing.payment.rejected", "PaymentTransaction", payment.id, payment.rejectionReason);
        return payments.save(payment);
    }

    @Transactional
    public Plan savePlan(Long id, String code, String name, BigDecimal price, String currency, Integer trialDays,
                         Integer billingPeriodDays, String description, String highlight, Integer maxUsers,
                         Integer maxIptvAccounts, Integer maxConcurrentStreams, Integer storageGb, Boolean active,
                         List<PlanEntitlementSpec> entitlementSpecs) {
        Plan plan = id == null ? new Plan() : plans.findById(id).orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        if (code != null && !code.isBlank()) plan.code = code;
        if (name != null && !name.isBlank()) plan.name = name;
        if (price != null) plan.priceMonthly = price;
        if (currency != null && !currency.isBlank()) plan.currency = normalizeCurrency(currency);
        if (trialDays != null) plan.trialDays = Math.max(0, trialDays);
        if (billingPeriodDays != null) plan.billingPeriodDays = Math.max(1, billingPeriodDays);
        if (description != null) plan.description = description;
        if (highlight != null) plan.highlight = highlight;
        if (maxUsers != null) plan.maxUsers = maxUsers;
        if (maxIptvAccounts != null) plan.maxIptvAccounts = maxIptvAccounts;
        if (maxConcurrentStreams != null) plan.maxConcurrentStreams = maxConcurrentStreams;
        if (storageGb != null) plan.storageGb = storageGb;
        if (active != null) plan.active = active;
        if (entitlementSpecs != null) {
            plan.entitlements.clear();
            for (int index = 0; index < entitlementSpecs.size(); index += 1) {
                PlanEntitlementSpec spec = entitlementSpecs.get(index);
                PlanEntitlement entitlement = entitlement(spec, index);
                entitlement.plan = plan;
                plan.entitlements.add(entitlement);
            }
        }
        return plans.save(plan);
    }

    @Transactional
    public PaymentMethod savePaymentMethod(Long id, String code, String name, String instructions, Boolean active) {
        PaymentMethod method = id == null ? new PaymentMethod() : paymentMethods.findById(id)
                .orElseThrow(() -> ApiException.notFound("Moyen de paiement introuvable"));
        if (code != null && !code.isBlank()) method.code = code;
        if (name != null && !name.isBlank()) method.name = name;
        if (instructions != null) method.instructions = instructions;
        if (active != null) method.active = active;
        return paymentMethods.save(method);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> allPayments() {
        return payments.findTop10ByOrderByCreatedAtDesc();
    }

    @Transactional
    public int reconcileExpiredSubscriptions() {
        Instant now = Instant.now();
        List<Subscription> activeSubscriptions = subscriptions.findByStatusIn(List.of(
                Enums.SubscriptionStatus.ACTIVE,
                Enums.SubscriptionStatus.TRIALING
        ));
        int expired = 0;
        for (Subscription subscription : activeSubscriptions) {
            Instant end = SubscriptionPeriods.currentPeriodEnd(subscription);
            if (end == null || end.isAfter(now)) {
                continue;
            }
            subscription.currentPeriodEnd = end;
            subscription.status = Enums.SubscriptionStatus.PAST_DUE;
            subscriptions.save(subscription);
            if (subscription.organization != null
                    && subscription.organization.status == Enums.OrganizationStatus.ACTIVE) {
                subscription.organization.status = Enums.OrganizationStatus.SUSPENDED;
                organizations.save(subscription.organization);
            }
            expired += 1;
        }
        return expired;
    }

    @Transactional
    public Subscription reactivateSuspendedSubscription(UserEntity actor, Long organizationId) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> ApiException.notFound("Client introuvable"));
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)
                .orElseThrow(() -> ApiException.notFound("Abonnement introuvable"));
        Instant now = Instant.now();
        Instant end = SubscriptionPeriods.currentPeriodEnd(subscription);
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.cancelAtPeriodEnd = false;
        if (subscription.startedAt == null) {
            subscription.startedAt = now;
        }
        subscription.trialEndsAt = null;
        if (end == null || !end.isAfter(now)) {
            subscription.currentPeriodEnd = now.plus(billingPeriodDays(subscription.plan), ChronoUnit.DAYS);
        } else {
            subscription.currentPeriodEnd = end;
        }
        if (organization.status != Enums.OrganizationStatus.ACTIVE) {
            organization.status = Enums.OrganizationStatus.ACTIVE;
            organizations.save(organization);
        }
        audit.log(actor, "billing.subscription.reactivated", "Subscription", subscription.id, organization.name);
        Subscription saved = subscriptions.save(subscription);
        activity.planChanged(actor, saved);
        return saved;
    }

    private PlanEntitlement entitlement(PlanEntitlementSpec spec, int fallbackPriority) {
        PlanEntitlement entitlement = new PlanEntitlement();
        Enums.PlanEntitlementMode mode = spec == null || spec.mode() == null
                ? Enums.PlanEntitlementMode.ALL
                : spec.mode();
        entitlement.mode = mode;
        entitlement.contentType = normalizeContentType(spec == null ? null : spec.contentType());
        entitlement.categoryId = blankToNull(spec == null ? null : spec.categoryId());
        entitlement.keyword = blankToNull(spec == null ? null : spec.keyword());
        entitlement.label = blankToNull(spec == null ? null : spec.label());
        entitlement.enabled = spec == null || spec.enabled() == null || spec.enabled();
        entitlement.priority = spec == null || spec.priority() == null ? fallbackPriority : spec.priority();
        if (entitlement.label == null) {
            entitlement.label = defaultEntitlementLabel(entitlement);
        }
        if (mode == Enums.PlanEntitlementMode.CATEGORY && entitlement.categoryId == null) {
            throw ApiException.validation("Une regle par categorie doit contenir une categorie");
        }
        if (mode == Enums.PlanEntitlementMode.ADDON && entitlement.categoryId == null) {
            throw ApiException.validation("Une regle par add-on doit contenir un add-on");
        }
        if (mode == Enums.PlanEntitlementMode.CONNECTOR && entitlement.keyword == null) {
            throw ApiException.validation("Une regle par connecteur doit contenir un connecteur");
        }
        if (mode == Enums.PlanEntitlementMode.KEYWORD && entitlement.keyword == null) {
            throw ApiException.validation("Une regle automatique doit contenir un mot-cle");
        }
        return entitlement;
    }

    private String defaultEntitlementLabel(PlanEntitlement entitlement) {
        return switch (entitlement.mode) {
            case ALL -> "Tout le catalogue";
            case TYPE -> "Type " + entitlement.contentType;
            case CATEGORY -> "Categorie " + entitlement.categoryId;
            case KEYWORD -> "Mot-cle " + entitlement.keyword;
            case ADDON -> "Add-on " + entitlement.categoryId;
            case CONNECTOR -> "Connecteur " + entitlement.keyword;
        };
    }

    private String normalizeCurrency(String currency) {
        String value = currency == null ? "" : currency.trim().toUpperCase();
        if (value.isBlank() || "XOF".equals(value) || "XAF".equals(value)) {
            return "FCFA";
        }
        return value;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "all";
        }
        String normalized = contentType.trim().toLowerCase();
        return switch (normalized) {
            case "live", "movie", "series", "all" -> normalized;
            default -> "all";
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int trialDays(Plan plan) {
        if (plan == null) {
            return Math.max(0, defaultTrialDays);
        }
        return plan.trialDays > 0 ? plan.trialDays : 0;
    }

    private boolean trialAlreadyUsed(UserEntity user, Organization organization, Plan plan) {
        if (isFreePlan(plan)) {
            return subscriptions.existsByOrganizationAndPlan(organization, plan)
                    || (user != null && subscriptions.countSubscriptionsForOwnerAndPlan(user, plan) > 0);
        }
        return subscriptions.existsByOrganizationAndPlanAndTrialEndsAtIsNotNull(organization, plan)
                || (user != null && subscriptions.countTrialsForOwnerAndPlan(user, plan) > 0);
    }

    private int billingPeriodDays(Plan plan) {
        return plan == null || plan.billingPeriodDays == null || plan.billingPeriodDays <= 0 ? 30 : plan.billingPeriodDays;
    }

    private void startFreeTrial(Subscription subscription) {
        if (trialDays(subscription.plan) > 0) {
            subscription.status = Enums.SubscriptionStatus.TRIALING;
            subscription.trialEndsAt = SubscriptionPeriods.trialEndsAt(subscription);
            subscription.currentPeriodEnd = subscription.trialEndsAt;
            return;
        }
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.trialEndsAt = null;
        subscription.currentPeriodEnd = subscription.startedAt.plus(billingPeriodDays(subscription.plan), ChronoUnit.DAYS);
    }

    private boolean subscriptionUsable(Subscription subscription) {
        return SubscriptionPeriods.isUsable(subscription, Instant.now());
    }

    private boolean isFreePlan(Plan plan) {
        return plan != null && (plan.priceMonthly == null || plan.priceMonthly.signum() == 0);
    }

    private PaymentMethod paymentMethod(String paymentMethodCode) {
        if (paymentMethodCode == null || paymentMethodCode.isBlank()) {
            throw ApiException.validation("Moyen de paiement obligatoire pour une formule payante");
        }
        PaymentMethod method = paymentMethods.findByCode(paymentMethodCode.trim())
                .orElseThrow(() -> ApiException.notFound("Moyen de paiement introuvable"));
        if (!method.active) {
            throw ApiException.validation("Ce moyen de paiement n'est pas disponible");
        }
        return method;
    }

    private String paymentProof(String proofUrl) {
        String proof = proofUrl == null ? "" : proofUrl.trim();
        if (proof.length() < 3) {
            throw ApiException.validation("Reference ou preuve de paiement obligatoire pour une formule payante");
        }
        return proof;
    }

    private boolean hasManualPaymentDetails(String paymentMethodCode, String proofUrl) {
        return (paymentMethodCode != null && !paymentMethodCode.isBlank())
                || (proofUrl != null && !proofUrl.isBlank());
    }

    private void ensureNoPendingPayment(Organization organization, Plan plan) {
        List<PaymentTransaction> organizationPayments = payments.findByOrganizationOrderByCreatedAtDesc(organization);
        if (organizationPayments == null) {
            return;
        }
        boolean alreadyPending = organizationPayments.stream()
                .anyMatch(payment -> payment.status == Enums.PaymentStatus.PENDING && samePlan(payment.plan, plan));
        if (alreadyPending) {
            throw ApiException.validation("Un paiement est deja en attente pour cette formule");
        }
    }

    private Subscription preparePendingManualSubscription(
            Organization organization,
            Plan plan,
            boolean suspendUntilValidation
    ) {
        Instant now = Instant.now();
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization).orElse(null);
        boolean hasCurrentAccess = organization.status == Enums.OrganizationStatus.ACTIVE && subscriptionUsable(subscription);
        boolean mustBlockAccess = suspendUntilValidation || !hasCurrentAccess;
        if (subscription == null) {
            subscription = new Subscription();
            subscription.organization = organization;
            subscription.startedAt = now;
            mustBlockAccess = true;
        }
        if (!mustBlockAccess) {
            return subscription;
        }

        Instant previousEnd = SubscriptionPeriods.currentPeriodEnd(subscription);
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.PAST_DUE;
        if (subscription.startedAt == null) {
            subscription.startedAt = now;
        }
        subscription.trialEndsAt = null;
        subscription.currentPeriodEnd = previousEnd != null && !previousEnd.isAfter(now) ? previousEnd : null;
        subscription.cancelAtPeriodEnd = false;
        Subscription saved = subscriptions.save(subscription);

        if (organization.status == Enums.OrganizationStatus.ACTIVE) {
            organization.status = Enums.OrganizationStatus.SUSPENDED;
            organizations.save(organization);
        }
        return saved;
    }

    private PaymentTransaction createPaymentTransaction(
            UserEntity user,
            Organization organization,
            Plan plan,
            PaymentMethod method,
            String proofUrl
    ) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.organization = organization;
        payment.user = user;
        payment.plan = plan;
        payment.paymentMethod = method;
        payment.paymentReference = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        payment.amount = plan.priceMonthly == null ? BigDecimal.ZERO : plan.priceMonthly;
        payment.currency = plan.currency;
        payment.status = Enums.PaymentStatus.PENDING;
        payment.proofUrl = proofUrl;
        payment.expiresAt = Instant.now().plus(paymentExpiresHours, ChronoUnit.HOURS);
        return payments.save(payment);
    }

    private void activateSubscription(PaymentTransaction payment) {
        Instant now = Instant.now();
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(payment.organization)
                .orElseGet(() -> {
                    Subscription created = new Subscription();
                    created.organization = payment.organization;
                    created.startedAt = now;
                    return created;
                });
        Instant previousEnd = SubscriptionPeriods.currentPeriodEnd(subscription);
        boolean extendCurrentPeriod = payment.organization != null
                && payment.organization.status == Enums.OrganizationStatus.ACTIVE
                && subscriptionUsable(subscription)
                && samePlan(subscription.plan, payment.plan)
                && previousEnd != null
                && previousEnd.isAfter(now);
        subscription.plan = payment.plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.cancelAtPeriodEnd = false;
        if (!extendCurrentPeriod || subscription.startedAt == null) {
            subscription.startedAt = now;
        }
        subscription.trialEndsAt = null;
        Instant periodAnchor = extendCurrentPeriod ? previousEnd : now;
        subscription.currentPeriodEnd = periodAnchor.plus(billingPeriodDays(payment.plan), ChronoUnit.DAYS);
        if (payment.organization != null && payment.organization.status != Enums.OrganizationStatus.ACTIVE) {
            payment.organization.status = Enums.OrganizationStatus.ACTIVE;
            organizations.save(payment.organization);
        }
        subscriptions.save(subscription);
    }

    private boolean samePlan(Plan left, Plan right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.id != null && right.id != null) {
            return Objects.equals(left.id, right.id);
        }
        return Objects.equals(left.code, right.code);
    }

    public record PlanEntitlementSpec(
            Enums.PlanEntitlementMode mode,
            String contentType,
            String categoryId,
            String keyword,
            String label,
            Boolean enabled,
            Integer priority
    ) {
    }

    public record RegistrationBilling(Subscription subscription, PaymentTransaction payment) {
    }
}
