package com.iptv.saas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;

@Service
public class ApplicationAlertService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationAlertService.class);

    private final boolean enabled;
    private final String siteUrl;
    private final String apiBaseUrl;
    private final Environment environment;
    private final HealthEndpoint healthEndpoint;
    private final TelegramAlertService telegram;
    private Status lastHealthStatus;

    public ApplicationAlertService(
            @Value("${app.alerts.enabled:false}") boolean enabled,
            @Value("${app.public.site-url:https://nexoragabon.com}") String siteUrl,
            @Value("${app.public.api-base-url:https://api.nexoragabon.com}") String apiBaseUrl,
            Environment environment,
            HealthEndpoint healthEndpoint,
            TelegramAlertService telegram
    ) {
        this.enabled = enabled;
        this.siteUrl = trimSlash(siteUrl);
        this.apiBaseUrl = trimSlash(apiBaseUrl);
        this.environment = environment;
        this.healthEndpoint = healthEndpoint;
        this.telegram = telegram;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String profileLabel = profiles();
        log.info("Nexora API demarree: profiles={}, site={}, api={}", profileLabel, siteUrl, apiBaseUrl);
        if (!alertsEnabled()) {
            return;
        }
        telegram.send(
                "Nexora API demarree",
                """
                Environnement: %s
                Profils: %s
                Site: %s
                API: %s
                Base: PostgreSQL
                Heure: %s
                """.formatted(environmentLabel(), profileLabel, siteUrl, apiBaseUrl, Instant.now())
        );
        sendHealthSnapshot(true);
    }

    @Scheduled(
            fixedDelayString = "${app.alerts.health-interval-ms:900000}",
            initialDelayString = "${app.alerts.health-initial-delay-ms:60000}"
    )
    public void checkHealth() {
        if (!alertsEnabled()) {
            return;
        }
        sendHealthSnapshot(false);
    }

    private void sendHealthSnapshot(boolean forceOk) {
        try {
            HealthComponent health = healthEndpoint.health();
            Status status = health.getStatus();
            if (Status.UP.equals(status)) {
                if (forceOk || (lastHealthStatus != null && !Status.UP.equals(lastHealthStatus))) {
                    telegram.send(
                            "Nexora API health OK",
                            "Status: UP\nEndpoint: " + apiBaseUrl + "/actuator/health\nHeure: " + Instant.now()
                    );
                }
            } else if (lastHealthStatus == null || !status.equals(lastHealthStatus)) {
                telegram.send(
                        "Nexora API health en erreur",
                        "Status: " + status + "\nEndpoint: " + apiBaseUrl + "/actuator/health\nHeure: " + Instant.now()
                );
            }
            lastHealthStatus = status;
        } catch (Exception exception) {
            log.warn("Health alert check failed: {}", rootMessage(exception));
            if (lastHealthStatus == null || Status.UP.equals(lastHealthStatus)) {
                telegram.send(
                        "Nexora API health en erreur",
                        "Erreur: " + rootMessage(exception) + "\nEndpoint: " + apiBaseUrl + "/actuator/health"
                );
            }
            lastHealthStatus = Status.DOWN;
        }
    }

    private boolean alertsEnabled() {
        return enabled && telegram.configured();
    }

    private String profiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        return Arrays.stream(profiles)
                .filter(profile -> profile != null && !profile.isBlank())
                .reduce((left, right) -> left + "," + right)
                .orElse("default");
    }

    private String environmentLabel() {
        return Arrays.asList(environment.getActiveProfiles()).contains("postgres") ? "production" : "local";
    }

    private String trimSlash(String value) {
        String normalized = value == null || value.isBlank()
                ? "https://nexoragabon.com"
                : value.trim();
        return normalized.replaceAll("/+$", "");
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
