package com.iptv.saas.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.*;
import com.iptv.saas.repository.*;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.ConsumetContentService;
import com.iptv.saas.service.TmdbCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class DataSeeder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataSeeder.class);
    private static final String LEGACY_SUPER_ADMIN_EMAIL = "admin@example.com";
    private static final String USER_SUPPLIED_LICENSE = "Manifeste fourni par l'utilisateur";
    private static final int FREE_TRIAL_REPAIR_DAYS = 24;
    private static final int FREE_BILLING_REPAIR_DAYS = 30;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Bean
    CommandLineRunner seedData(
            UserRepository users,
            OrganizationRepository organizations,
            OrganizationMembershipRepository memberships,
            PlanRepository plans,
            PaymentMethodRepository paymentMethods,
            SubscriptionRepository subscriptions,
            IptvAccountRepository iptvAccounts,
            LegalDocumentRepository legalDocuments,
            UptimeCheckRepository uptimeChecks,
            CommunityAddonRepository communityAddons,
            CommunityAddonService communityAddonService,
            PasswordEncoder passwordEncoder,
            ObjectMapper mapper,
            ConsumetContentService consumet,
            TmdbCatalogService tmdb,
            Environment environment
    ) {
        return args -> {
            Plan free = plans.findByCode("free").orElseGet(() -> plans.save(plan("free", "Free", "0.00", 1, 1, 1, 1)));
            Plan basic = plans.findByCode("basic").orElseGet(() -> plans.save(plan("basic", "Basic", "5000.00", 2, 1, 1, 5)));
            Plan sports = plans.findByCode("sports").orElseGet(() -> plans.save(plan("sports", "Sports", "3500.00", 2, 1, 1, 5)));
            Plan pro = plans.findByCode("pro").orElseGet(() -> plans.save(plan("pro", "Pro", "15000.00", 5, 3, 3, 20)));
            Plan enterprise = plans.findByCode("enterprise").orElseGet(() -> plans.save(plan("enterprise", "Enterprise", "50000.00", 25, 10, 10, 100)));
            tunePlans(plans, free, basic, sports, pro, enterprise);
            repairFreePlanOneDayMisconfiguration(plans, subscriptions, organizations, free);

            paymentMethods.findByCode("mobile_money").orElseGet(() -> paymentMethods.save(method(
                    "mobile_money",
                    "Mobile Money",
                    "Envoyez le paiement a votre numero marchand puis renseignez la preuve."
            )));
            paymentMethods.findByCode("bank_transfer").orElseGet(() -> paymentMethods.save(method(
                    "bank_transfer",
                    "Virement bancaire",
                    "Effectuez un virement et ajoutez la reference dans la demande de paiement."
            )));

            UserEntity admin = seedSuperAdmin(users, passwordEncoder, environment);

            UserEntity test = users.findByEmailIgnoreCase("test@example.com").orElseGet(() -> {
                UserEntity user = new UserEntity();
                user.name = "Test User";
                user.email = "test@example.com";
                user.passwordHash = passwordEncoder.encode("password");
                user.role = Enums.UserRole.USER;
                user.active = true;
                user.emailVerified = true;
                return users.save(user);
            });

            Organization org = organizations.findBySlug("demo-organization").orElseGet(() -> {
                Organization organization = new Organization();
                organization.name = "Demo Organization";
                organization.slug = "demo-organization";
                organization.owner = test;
                organization.billingEmail = test.email;
                organization.status = Enums.OrganizationStatus.ACTIVE;
                return organizations.save(organization);
            });
            if (test.currentOrganization == null) {
                test.currentOrganization = org;
                users.save(test);
            }
            if (admin.currentOrganization == null) {
                admin.currentOrganization = org;
                users.save(admin);
            }
            if (!memberships.existsByOrganizationAndUser(org, test)) {
                OrganizationMembership membership = new OrganizationMembership();
                membership.organization = org;
                membership.user = test;
                membership.role = "owner";
                memberships.save(membership);
            }

            if (subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(org)
                    .filter(this::subscriptionUsable)
                    .isEmpty()) {
                Subscription subscription = new Subscription();
                subscription.organization = org;
                subscription.plan = free;
                subscription.status = Enums.SubscriptionStatus.ACTIVE;
                subscription.startedAt = Instant.now();
                subscription.currentPeriodEnd = Instant.now().plus(365, ChronoUnit.DAYS);
                subscriptions.save(subscription);
            }

            if (iptvAccounts.count() == 0) {
                IptvAccount account = new IptvAccount();
                account.name = "Demo Xtream";
                account.accountType = Enums.IptvAccountType.XTREAM;
                account.baseUrl = "https://example.com";
                account.username = "demo";
                account.password = "demo";
                account.maxStreams = 5;
                account.active = true;
                account.expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);
                account.lastHealthStatus = "ok";
                iptvAccounts.save(account);
            }

            legalDocuments.findByDocumentType("terms").orElseGet(() -> legalDocuments.save(legal(
                    "terms",
                    "Conditions d'utilisation",
                    "Conditions legales de demonstration."
            )));
            legalDocuments.findByDocumentType("privacy").orElseGet(() -> legalDocuments.save(legal(
                    "privacy",
                    "Confidentialite",
                    "Politique de confidentialite de demonstration."
            )));

            if (uptimeChecks.count() == 0) {
                UptimeCheck check = new UptimeCheck();
                check.name = "API Health";
                check.url = trimSlash(environment.getProperty(
                        "app.public.api-base-url",
                        "https://api.nexoragabon.com"
                )) + "/actuator/health";
                check.method = "GET";
                check.enabled = true;
                uptimeChecks.save(check);
            }

            seedDefaultAddon(
                    communityAddons,
                    communityAddonService,
                    "https://addon.notorrent2.workers.dev/manifest.json",
                    "addon.notorrent2.workers.dev",
                    false
            );
            seedDefaultAddon(
                    communityAddons,
                    communityAddonService,
                    "https://hdhub.thevolecitor.qzz.io/eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwLDcyMHAiLCJzb3J0IjoiZGVzYyJ9/manifest.json",
                    ".hwmce.com,.fodcyy.com,.bhcxy.com,.fhxod.com,hub.noirspy.buzz,hub.whistle.lat,cdn.fsl-buckets.work,.hubcloud.cx",
                    false
            );
            String stremioPornAddonUrl = stremioPornAddonUrl(environment);
            if (stremioPornAddonUrl != null) {
                seedDefaultAddon(
                        communityAddons,
                        communityAddonService,
                        stremioPornAddonUrl,
                        environment.getProperty("app.addons.stremio-porn.allowed-stream-hosts", "*"),
                        true
                );
            }
            ensureDemoExternalCatalogAccess(users, mapper, consumet, tmdb, communityAddonService, test);
        };
    }

    private UserEntity seedSuperAdmin(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            Environment environment
    ) {
        String email = normalizeEmail(environment.getProperty(
                "app.seed.super-admin-email",
                "alexandredinzambou@gmail.com"
        ));
        String name = environment.getProperty("app.seed.super-admin-name", "Alexandre Dinzambou");
        String configuredPassword = environment.getProperty("app.seed.super-admin-password", "");

        UserEntity admin = users.findByEmailIgnoreCase(email)
                .orElseGet(() -> users.findByEmailIgnoreCase(LEGACY_SUPER_ADMIN_EMAIL)
                        .map(user -> {
                            user.email = email;
                            return user;
                        })
                        .orElseGet(UserEntity::new));
        boolean isNew = admin.id == null;
        admin.name = name == null || name.isBlank() ? "Alexandre Dinzambou" : name.trim();
        admin.email = email;
        if (isNew || (configuredPassword != null && !configuredPassword.isBlank())) {
            admin.passwordHash = passwordEncoder.encode(
                    configuredPassword == null || configuredPassword.isBlank()
                            ? "password"
                            : configuredPassword
            );
        }
        admin.role = Enums.UserRole.SUPER_ADMIN;
        admin.active = true;
        admin.emailVerified = true;
        return users.save(admin);
    }

    private String normalizeEmail(String email) {
        String value = email == null || email.isBlank()
                ? "alexandredinzambou@gmail.com"
                : email.trim().toLowerCase();
        return value;
    }

    private String trimSlash(String value) {
        return String.valueOf(value == null ? "" : value).replaceAll("/+$", "");
    }

    private String stremioPornAddonUrl(Environment environment) {
        String value = environment.getProperty("app.addons.stremio-porn.manifest-url", "");
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().replaceAll("/+$", "");
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        String legacyPath = "/stremioget/stremio/v1";
        if (!normalized.toLowerCase().endsWith(legacyPath)) {
            normalized = normalized + legacyPath;
        }
        return normalized;
    }

    private void seedDefaultAddon(
            CommunityAddonRepository communityAddons,
            CommunityAddonService communityAddonService,
            String manifestUrl,
            String allowedStreamHosts,
            boolean adultContent
    ) {
        try {
            var existing = communityAddons.findByManifestUrl(manifestUrl);
            boolean created = existing.isEmpty();
            CommunityAddon addon = existing.orElseGet(() -> communityAddonService.install(
                    manifestUrl,
                    allowedStreamHosts,
                    USER_SUPPLIED_LICENSE,
                    manifestUrl,
                    adultContent
            ));
            String manifestHost = URI.create(manifestUrl).getHost();
            if (created
                    || addon.allowedStreamHosts == null
                    || addon.allowedStreamHosts.isBlank()
                    || addon.allowedStreamHosts.equalsIgnoreCase(manifestHost)) {
                addon.allowedStreamHosts = allowedStreamHosts;
            }
            if (addon.licenseName == null || addon.licenseName.isBlank()) {
                addon.licenseName = USER_SUPPLIED_LICENSE;
            }
            if (addon.licenseUrl == null || addon.licenseUrl.isBlank()) {
                addon.licenseUrl = manifestUrl;
            }
            addon.adultContent = adultContent;
            if (addon.status != Enums.AddonStatus.DISABLED) {
                addon.status = Enums.AddonStatus.APPROVED;
            }
            communityAddons.save(addon);
        } catch (RuntimeException exception) {
            LOGGER.warn("Impossible de preinstaller l'add-on {}: {}", manifestUrl, exception.getMessage());
        }
    }

    private Plan plan(String code, String name, String price, int users, int accounts, int streams, int storage) {
        Plan plan = new Plan();
        plan.code = code;
        plan.name = name;
        plan.priceMonthly = new BigDecimal(price);
        plan.currency = "FCFA";
        plan.trialDays = 7;
        plan.maxUsers = users;
        plan.maxIptvAccounts = accounts;
        plan.maxConcurrentStreams = streams;
        plan.storageGb = storage;
        plan.active = true;
        return plan;
    }

    private void tunePlans(PlanRepository plans, Plan free, Plan basic, Plan sports, Plan pro, Plan enterprise) {
        free.description = "Une porte d'entree simple pour tester l'espace client.";
        free.currency = "FCFA";
        free.highlight = "Decouverte";
        basic.description = "Le socle familial avec le catalogue general.";
        basic.currency = "FCFA";
        basic.highlight = "Essentiel";
        sports.description = "Acces automatique aux chaines et categories sportives.";
        sports.currency = "FCFA";
        sports.highlight = "3 jours d'essai";
        sports.trialDays = 3;
        sports.billingPeriodDays = 30;
        pro.description = "Plusieurs utilisateurs, plusieurs sources et plus de streams.";
        pro.currency = "FCFA";
        pro.highlight = "Le plus complet";
        enterprise.description = "Exploitation avancee pour grandes equipes et volumes eleves.";
        enterprise.currency = "FCFA";
        enterprise.highlight = "Sur mesure";
        if (sports.entitlements.isEmpty()) {
            sports.entitlements.add(entitlement(
                    sports,
                    Enums.PlanEntitlementMode.KEYWORD,
                    "all",
                    null,
                    "sport",
                    "Sports automatiques",
                    0
            ));
        }
        plans.save(free);
        plans.save(basic);
        plans.save(sports);
        plans.save(pro);
        plans.save(enterprise);
    }

    private void repairFreePlanOneDayMisconfiguration(
            PlanRepository plans,
            SubscriptionRepository subscriptions,
            OrganizationRepository organizations,
            Plan free
    ) {
        if (free == null || free.priceMonthly == null || free.priceMonthly.signum() != 0) {
            return;
        }
        List<Subscription> freeSubscriptions = subscriptions.findAll().stream()
                .filter(subscription -> subscription.plan != null
                        && subscription.plan.id != null
                        && subscription.plan.id.equals(free.id))
                .toList();
        boolean hasRepairableSubscriptions = freeSubscriptions.stream().anyMatch(this::hasOneDayStoredPeriod);
        boolean hasBadFreeConfig = (free.trialDays <= 0 || free.trialDays == 7)
                || free.billingPeriodDays == null
                || free.billingPeriodDays <= 1;
        if (!hasBadFreeConfig && !hasRepairableSubscriptions) {
            return;
        }
        if (free.trialDays <= 0 || free.trialDays == 7 || hasRepairableSubscriptions) {
            free.trialDays = FREE_TRIAL_REPAIR_DAYS;
        }
        if (free.billingPeriodDays == null || free.billingPeriodDays <= 1 || hasRepairableSubscriptions) {
            free.billingPeriodDays = FREE_BILLING_REPAIR_DAYS;
        }
        plans.save(free);

        Instant now = Instant.now();
        int repaired = 0;
        for (Subscription subscription : freeSubscriptions) {
            if (!hasOneDayStoredPeriod(subscription)) {
                continue;
            }
            subscription.plan = free;
            subscription.status = Enums.SubscriptionStatus.TRIALING;
            subscription.trialEndsAt = SubscriptionPeriods.trialEndsAt(subscription);
            subscription.currentPeriodEnd = subscription.trialEndsAt;
            if (subscription.currentPeriodEnd != null && !subscription.currentPeriodEnd.isAfter(now)) {
                subscription.status = Enums.SubscriptionStatus.PAST_DUE;
            } else if (subscription.organization != null
                    && subscription.organization.status == Enums.OrganizationStatus.SUSPENDED) {
                subscription.organization.status = Enums.OrganizationStatus.ACTIVE;
                organizations.save(subscription.organization);
            }
            subscriptions.save(subscription);
            repaired += 1;
        }
        LOGGER.info(
                "Correction configuration Free appliquee: essai {} jours, abonnement {} jours, {} abonnement(s) corrige(s)",
                FREE_TRIAL_REPAIR_DAYS,
                FREE_BILLING_REPAIR_DAYS,
                repaired
        );
    }

    private boolean hasOneDayStoredPeriod(Subscription subscription) {
        if (subscription == null || subscription.currentPeriodEnd == null) {
            return false;
        }
        Instant anchor = subscriptionAccountAnchor(subscription);
        if (anchor == null) {
            return false;
        }
        Instant oneDayEnd = anchor.plus(1, ChronoUnit.DAYS);
        long seconds = Math.abs(Duration.between(subscription.currentPeriodEnd, oneDayEnd).toSeconds());
        if (seconds <= 300) {
            return true;
        }
        Instant expectedTrialEnd = anchor.plus(FREE_TRIAL_REPAIR_DAYS, ChronoUnit.DAYS);
        return subscription.currentPeriodEnd.isBefore(expectedTrialEnd)
                && Duration.between(anchor, subscription.currentPeriodEnd).toDays() <= 2;
    }

    private Instant subscriptionAccountAnchor(Subscription subscription) {
        if (subscription.organization != null
                && subscription.organization.owner != null
                && subscription.organization.owner.createdAt != null) {
            return subscription.organization.owner.createdAt;
        }
        if (subscription.organization != null && subscription.organization.createdAt != null) {
            return subscription.organization.createdAt;
        }
        if (subscription.startedAt != null) {
            return subscription.startedAt;
        }
        return subscription.createdAt;
    }

    private void ensureDemoExternalCatalogAccess(
            UserRepository users,
            ObjectMapper mapper,
            ConsumetContentService consumet,
            TmdbCatalogService tmdb,
            CommunityAddonService communityAddonService,
            UserEntity user
    ) {
        if (user == null
                || user.allowedCategories == null
                || user.allowedCategories.isBlank()) {
            return;
        }
        try {
            Set<String> allowed = new LinkedHashSet<>(mapper.readValue(user.allowedCategories, STRING_LIST));
            boolean changed = false;
            if (consumet != null && consumet.isEnabled()) {
                changed = addCatalogCategoryIds(allowed, consumet.categories(null)) || changed;
            }
            if (tmdb != null && tmdb.isEnabled()) {
                changed = addCatalogCategoryIds(allowed, tmdb.categories(null)) || changed;
                changed = allowed.add("tmdb-movie-search") || changed;
                changed = allowed.add("tmdb-series-search") || changed;
            }
            if (communityAddonService != null) {
                changed = addCatalogCategoryIds(allowed, publicCatalogCategories(
                        communityAddonService.categories(null, user)
                )) || changed;
            }
            if (changed) {
                user.allowedCategories = mapper.writeValueAsString(new ArrayList<>(allowed));
                users.save(user);
            }
        } catch (Exception exception) {
            LOGGER.warn("Impossible d'ajouter les categories externes au compte demo: {}", exception.getMessage());
        }
    }

    private boolean addCatalogCategoryIds(Set<String> allowed, List<Map<String, Object>> categories) {
        boolean changed = false;
        for (Map<String, Object> category : categories) {
            Object id = category.get("id");
            if (id != null) {
                changed = allowed.add(String.valueOf(id)) || changed;
            }
        }
        return changed;
    }

    private List<Map<String, Object>> publicCatalogCategories(List<Map<String, Object>> categories) {
        return categories.stream()
                .filter(category -> !Boolean.TRUE.equals(category.get("adult")))
                .filter(category -> !Boolean.TRUE.equals(category.get("privateUse")))
                .toList();
    }

    private boolean subscriptionUsable(Subscription subscription) {
        return SubscriptionPeriods.isUsable(subscription, Instant.now());
    }

    private PlanEntitlement entitlement(
            Plan plan,
            Enums.PlanEntitlementMode mode,
            String contentType,
            String categoryId,
            String keyword,
            String label,
            int priority
    ) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.plan = plan;
        entitlement.mode = mode;
        entitlement.contentType = contentType;
        entitlement.categoryId = categoryId;
        entitlement.keyword = keyword;
        entitlement.label = label;
        entitlement.priority = priority;
        entitlement.enabled = true;
        return entitlement;
    }

    private PaymentMethod method(String code, String name, String instructions) {
        PaymentMethod method = new PaymentMethod();
        method.code = code;
        method.name = name;
        method.instructions = instructions;
        method.active = true;
        return method;
    }

    private LegalDocument legal(String type, String title, String content) {
        LegalDocument document = new LegalDocument();
        document.documentType = type;
        document.title = title;
        document.content = content;
        document.published = true;
        return document;
    }
}
