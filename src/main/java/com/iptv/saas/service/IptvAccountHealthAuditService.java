package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class IptvAccountHealthAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IptvAccountHealthAuditService.class);
    private static final int TELEGRAM_DETAIL_LIMIT = 12;

    private final boolean scheduledEnabled;
    private final IptvAccountRepository accounts;
    private final UserSessionRepository sessions;
    private final IptvCatalogService catalog;
    private final M3uPlaylistService playlists;
    private final XtreamCatalogService xtream;

    public IptvAccountHealthAuditService(
            @Value("${app.iptv.health-audit-enabled:true}") boolean scheduledEnabled,
            IptvAccountRepository accounts,
            UserSessionRepository sessions,
            IptvCatalogService catalog,
            M3uPlaylistService playlists,
            XtreamCatalogService xtream
    ) {
        this.scheduledEnabled = scheduledEnabled;
        this.accounts = accounts;
        this.sessions = sessions;
        this.catalog = catalog;
        this.playlists = playlists;
        this.xtream = xtream;
    }

    @Scheduled(cron = "${app.iptv.health-audit-cron:0 0 0 * * *}", zone = "${app.iptv.health-audit-zone:Africa/Lagos}")
    @Transactional
    public void scheduledAudit() {
        if (!scheduledEnabled) {
            return;
        }
        AuditReport report = auditAndDisableUnresponsive("scheduled");
        LOGGER.info("Audit IPTV planifie termine: {}", report.compactSummary());
    }

    @Transactional
    public AuditReport auditAndDisableUnresponsive(String trigger) {
        String source = trigger == null || trigger.isBlank() ? "manual" : trigger.strip();
        List<AccountAudit> results = new ArrayList<>();
        for (IptvAccount account : accounts.findAll()) {
            if (IptvCatalogService.ARCHIVED_BY_ADMIN.equals(account.disabledReason)) {
                continue;
            }
            results.add(auditAccount(account, source));
        }
        AuditReport report = AuditReport.from(source, results);
        LOGGER.info("Audit IPTV {}: {}", source, report.compactSummary());
        return report;
    }

    private AccountAudit auditAccount(IptvAccount account, String trigger) {
        if (account.disabled || !account.active) {
            int closed = closeActiveSessions(account);
            if (account.active || !account.disabled || account.activeStreams != 0
                    || !"disabled".equalsIgnoreCase(account.lastHealthStatus)) {
                account.active = false;
                account.disabled = true;
                account.activeStreams = 0;
                account.lastHealthStatus = "disabled";
                accounts.save(account);
            }
            return AccountAudit.skipped(account, "disabled", "Compte deja desactive", closed);
        }

        String localHealth = catalog.health(account);
        if (!"ok".equalsIgnoreCase(localHealth) && !"saturated".equalsIgnoreCase(localHealth)) {
            account.lastHealthStatus = localHealth;
            accounts.save(account);
            return AccountAudit.warning(account, localHealth, "Etat local: " + localHealth, 0);
        }

        try {
            int itemCount = probeProvider(account);
            if (itemCount == 0) {
                account.lastHealthStatus = "empty-catalog";
                accounts.save(account);
                LOGGER.warn(
                        "Compte IPTV joignable mais sans flux detecte: id={}, nom={}, trigger={}",
                        account.id,
                        account.name,
                        trigger
                );
                return AccountAudit.warning(account, "empty-catalog", "Fournisseur joignable, catalogue vide", 0);
            }
            account.failureCount = 0;
            account.lastHealthStatus = localHealth;
            accounts.save(account);
            return AccountAudit.ok(account, localHealth, itemCount + " entree(s) catalogue", 0);
        } catch (ApiException exception) {
            String code = exception.code() == null ? "" : exception.code();
            String status = "provider_unavailable".equals(code) ? "unreachable" : "catalog-unavailable";
            int closed = disableAccount(account, status, exception.getMessage(), trigger);
            return AccountAudit.disabled(account, status, exception.getMessage(), closed);
        } catch (RuntimeException exception) {
            int closed = disableAccount(account, "unreachable", rootMessage(exception), trigger);
            return AccountAudit.disabled(account, "unreachable", rootMessage(exception), closed);
        }
    }

    private int probeProvider(IptvAccount account) {
        M3uPlaylistService.Playlist playlist = switch (account.accountType) {
            case M3U -> playlists.refreshNow(account);
            case XTREAM -> xtream.refreshNow(account);
        };
        return playlist.entries().size() + playlist.series().size();
    }

    private int disableAccount(IptvAccount account, String status, String reason, String trigger) {
        int closed = closeActiveSessions(account);
        account.active = false;
        account.disabled = true;
        account.activeStreams = 0;
        account.failureCount += 1;
        account.lastHealthStatus = status;
        account.disabledReason = "health-audit:" + trigger;
        accounts.save(account);
        playlists.invalidate(account.id);
        xtream.invalidate(account.id);
        LOGGER.warn(
                "Compte IPTV desactive automatiquement: id={}, nom={}, status={}, raison={}, sessionsFermees={}",
                account.id,
                account.name,
                status,
                reason,
                closed
        );
        return closed;
    }

    private int closeActiveSessions(IptvAccount account) {
        List<UserSession> active = sessions.findByIptvAccountAndStatus(account, Enums.SessionStatus.ACTIVE);
        Instant now = Instant.now();
        for (UserSession session : active) {
            session.status = Enums.SessionStatus.CLOSED;
            session.closedAt = now;
            session.lastHeartbeatAt = now;
            sessions.save(session);
        }
        return active.size();
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    public record AuditReport(
            String trigger,
            int scanned,
            int ok,
            int warnings,
            int disabled,
            int skipped,
            List<AccountAudit> accounts
    ) {
        static AuditReport from(String trigger, List<AccountAudit> accounts) {
            int ok = 0;
            int warnings = 0;
            int disabled = 0;
            int skipped = 0;
            for (AccountAudit result : accounts) {
                switch (result.action()) {
                    case "ok" -> ok++;
                    case "warning" -> warnings++;
                    case "disabled" -> disabled++;
                    case "skipped" -> skipped++;
                    default -> {
                    }
                }
            }
            return new AuditReport(trigger, accounts.size(), ok, warnings, disabled, skipped, List.copyOf(accounts));
        }

        public String compactSummary() {
            return "scanned=%d ok=%d warnings=%d disabled=%d skipped=%d"
                    .formatted(scanned, ok, warnings, disabled, skipped);
        }

        public String telegramSummary() {
            StringBuilder builder = new StringBuilder("""
                    Audit IPTV

                    Comptes scannes: %d
                    OK: %d
                    Alertes: %d
                    Desactives: %d
                    Ignores: %d
                    """.formatted(scanned, ok, warnings, disabled, skipped));
            accounts.stream()
                    .filter(result -> !"ok".equals(result.action()))
                    .limit(TELEGRAM_DETAIL_LIMIT)
                    .forEach(result -> builder.append("\n#")
                            .append(result.accountId())
                            .append(' ')
                            .append(result.name())
                            .append(" - ")
                            .append(result.action().toUpperCase(Locale.ROOT))
                            .append(" - ")
                            .append(result.status())
                            .append("\n")
                            .append(result.reason()));
            return builder.toString();
        }
    }

    public record AccountAudit(
            Long accountId,
            String name,
            String action,
            String status,
            String reason,
            int closedSessions
    ) {
        static AccountAudit ok(IptvAccount account, String status, String reason, int closedSessions) {
            return of(account, "ok", status, reason, closedSessions);
        }

        static AccountAudit warning(IptvAccount account, String status, String reason, int closedSessions) {
            return of(account, "warning", status, reason, closedSessions);
        }

        static AccountAudit disabled(IptvAccount account, String status, String reason, int closedSessions) {
            return of(account, "disabled", status, reason, closedSessions);
        }

        static AccountAudit skipped(IptvAccount account, String status, String reason, int closedSessions) {
            return of(account, "skipped", status, reason, closedSessions);
        }

        private static AccountAudit of(IptvAccount account, String action, String status, String reason, int closedSessions) {
            return new AccountAudit(
                    account.id,
                    account.name,
                    action,
                    status,
                    reason == null ? "" : reason,
                    closedSessions
            );
        }
    }
}
