package com.iptv.saas.service;

import com.iptv.saas.domain.AuditLog;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.domain.SupportTicket;
import com.iptv.saas.repository.AuditLogRepository;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.PaymentTransactionRepository;
import com.iptv.saas.repository.SupportTicketRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.repository.UserSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TelegramDailyDigestService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> REGISTRATION_ACTIONS = Set.of(
            "auth.registered",
            "auth.registered.pending_email"
    );
    private static final Set<String> LOGIN_ACTIONS = Set.of(
            "auth.login",
            "auth.2fa.verified"
    );

    private final boolean enabled;
    private final String zoneName;
    private final TelegramAlertService telegram;
    private final AuditLogRepository auditLogs;
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final IptvAccountRepository accounts;
    private final PaymentTransactionRepository payments;
    private final SupportTicketRepository tickets;
    private final OpsService ops;

    public TelegramDailyDigestService(
            @Value("${app.telegram.digest.enabled:false}") boolean enabled,
            @Value("${app.telegram.digest.zone:Africa/Lagos}") String zoneName,
            TelegramAlertService telegram,
            AuditLogRepository auditLogs,
            UserRepository users,
            UserSessionRepository sessions,
            IptvAccountRepository accounts,
            PaymentTransactionRepository payments,
            SupportTicketRepository tickets,
            OpsService ops
    ) {
        this.enabled = enabled;
        this.zoneName = zoneName;
        this.telegram = telegram;
        this.auditLogs = auditLogs;
        this.users = users;
        this.sessions = sessions;
        this.accounts = accounts;
        this.payments = payments;
        this.tickets = tickets;
        this.ops = ops;
    }

    @Scheduled(
            cron = "${app.telegram.digest.cron:0 0 22 * * *}",
            zone = "${app.telegram.digest.zone:Africa/Lagos}"
    )
    @Transactional(readOnly = true)
    public void sendScheduledDigest() {
        if (!enabled || !telegram.configured()) {
            return;
        }
        telegram.send("Resume quotidien Nexora", todayDigest());
    }

    @Transactional(readOnly = true)
    public String todayDigest() {
        ZoneId zone = zone();
        LocalDate today = LocalDate.now(zone);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = Instant.now();
        return digest(start, end, "Resume Nexora - " + DAY.format(today));
    }

    @Transactional(readOnly = true)
    public String digest(Instant start, Instant end, String title) {
        ZoneId zone = zone();
        List<AuditLog> logs = auditLogs.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);
        Map<String, Object> health = ops.health();
        Map<String, Object> metrics = ops.metrics();

        long registrations = count(logs, REGISTRATION_ACTIONS);
        long logins = count(logs, LOGIN_ACTIONS);
        long failedLogins = count(logs, "auth.login.failed");
        long resetRequests = count(logs, "auth.password.reset.requested");
        long resetCompleted = count(logs, "auth.password.reset");
        long passwordChanges = count(logs, "auth.password.changed");

        long paymentRequests = count(logs, "billing.payment.requested");
        long verifiedPayments = count(logs, "billing.payment.verified");
        long rejectedPayments = count(logs, "billing.payment.rejected");
        BigDecimal verifiedRevenue = verifiedRevenue(start, end);

        long ticketsCreated = count(logs, "support.ticket.created");
        long ticketReplies = count(logs, "support.ticket.replied");
        long openTickets = tickets.countByStatus(Enums.TicketStatus.OPEN)
                + tickets.countByStatus(Enums.TicketStatus.PENDING);
        long urgentTickets = urgentOpenTickets();

        long streamsOpened = count(logs, "stream.opened");
        long streamFailovers = count(logs, "stream.failover");
        long activeSessions = sessions.countByStatus(Enums.SessionStatus.ACTIVE);
        long warningAccounts = warningAccounts();

        String latest = logs.stream()
                .limit(5)
                .map(this::auditLine)
                .collect(Collectors.joining("\n"));

        return """
                %s

                Periode: %s -> %s (%s)

                Utilisateurs:
                + %d inscription(s)
                %d connexion(s) reussie(s)
                %d echec(s) de connexion
                %d demande(s) reset mot de passe
                %d reset(s) finalise(s)
                %d changement(s) mot de passe

                Business:
                %d paiement(s) demande(s)
                %d paiement(s) valide(s)
                %d paiement(s) refuse(s)
                CA valide: %s FCFA
                Paiements en attente: %s
                Utilisateurs actifs: %d

                Streaming:
                %d session(s) ouverte(s)
                %d bascule(s)/incident(s) streaming
                Sessions actives maintenant: %d
                Comptes IPTV a surveiller: %d
                Capacite: %s/%s

                Support:
                %d nouveau(x) ticket(s)
                %d reponse(s) ticket
                Tickets ouverts: %d
                Tickets urgents: %d

                Technique:
                API: %s
                DB: %s
                Comptes IPTV sains: %s
                Logs audit total: %s

                Dernieres actions:
                %s
                """.formatted(
                title,
                TIME.withZone(zone).format(start),
                TIME.withZone(zone).format(end),
                zone.getId(),
                registrations,
                logins,
                failedLogins,
                resetRequests,
                resetCompleted,
                passwordChanges,
                paymentRequests,
                verifiedPayments,
                rejectedPayments,
                verifiedRevenue,
                metrics.get("pendingPayments"),
                users.findByActive(true).size(),
                streamsOpened,
                streamFailovers,
                activeSessions,
                warningAccounts,
                metrics.get("activeStreams"),
                metrics.get("streamCapacity"),
                ticketsCreated,
                ticketReplies,
                openTickets,
                urgentTickets,
                health.get("status"),
                health.get("database"),
                metrics.get("healthyAccounts"),
                metrics.get("auditLogs"),
                latest.isBlank() ? "Aucune action sur la periode." : latest
        ).strip();
    }

    private BigDecimal verifiedRevenue(Instant start, Instant end) {
        return payments.findByStatusOrderByCreatedAtDesc(Enums.PaymentStatus.VERIFIED).stream()
                .filter(payment -> payment.verifiedAt != null
                        && !payment.verifiedAt.isBefore(start)
                        && !payment.verifiedAt.isAfter(end))
                .map(payment -> payment.amount == null ? BigDecimal.ZERO : payment.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long urgentOpenTickets() {
        return tickets.findByStatusInOrderByUpdatedAtDesc(List.of(
                        Enums.TicketStatus.OPEN,
                        Enums.TicketStatus.PENDING
                )).stream()
                .filter(ticket -> ticket.priority == Enums.TicketPriority.HIGH
                        || ticket.priority == Enums.TicketPriority.URGENT)
                .count();
    }

    private long warningAccounts() {
        Instant soon = Instant.now().plusSeconds(7 * 86_400L);
        return accounts.findAll().stream()
                .filter(account -> !healthy(account) || (account.expiresAt != null && !account.expiresAt.isAfter(soon)))
                .count();
    }

    private boolean healthy(IptvAccount account) {
        if (account == null || !account.active || account.disabled) {
            return false;
        }
        String status = account.lastHealthStatus == null ? "" : account.lastHealthStatus.toLowerCase(Locale.ROOT);
        return status.isBlank() || "ok".equals(status);
    }

    private long count(List<AuditLog> logs, String action) {
        return logs.stream().filter(log -> action.equals(log.action)).count();
    }

    private long count(List<AuditLog> logs, Set<String> actions) {
        return logs.stream().filter(log -> actions.contains(log.action)).count();
    }

    private String auditLine(AuditLog log) {
        String user = log.user == null ? "-" : "#" + log.user.id + " " + log.user.email;
        return "- %s: %s (%s)".formatted(TIME.withZone(zone()).format(log.createdAt), log.action, user);
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(zoneName == null || zoneName.isBlank() ? "Africa/Lagos" : zoneName);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Africa/Lagos");
        }
    }
}
