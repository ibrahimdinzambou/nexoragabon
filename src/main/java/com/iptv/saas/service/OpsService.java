package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UptimeCheck;
import com.iptv.saas.repository.*;
import com.iptv.saas.web.ApiException;
import com.iptv.saas.web.Responses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpsService {
    private final IptvAccountRepository accounts;
    private final UserSessionRepository sessions;
    private final AuditLogRepository auditLogs;
    private final UptimeCheckRepository uptimeChecks;
    private final PaymentTransactionRepository payments;
    private final SupportTicketRepository tickets;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OpsService(
            IptvAccountRepository accounts,
            UserSessionRepository sessions,
            AuditLogRepository auditLogs,
            UptimeCheckRepository uptimeChecks,
            PaymentTransactionRepository payments,
            SupportTicketRepository tickets
    ) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.auditLogs = auditLogs;
        this.uptimeChecks = uptimeChecks;
        this.payments = payments;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        boolean hasCapacity = usableAccounts().stream()
                .anyMatch(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams);
        Map<String, Object> body = Responses.map();
        body.put("status", hasCapacity ? "ok" : "degraded");
        body.put("database", "ok");
        body.put("iptvCapacityAvailable", hasCapacity);
        body.put("checkedAt", Instant.now());
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> metrics() {
        List<IptvAccount> usableAccounts = usableAccounts();
        int activeStreams = usableAccounts.stream()
                .mapToInt(account -> Math.max(account.activeStreams, 0))
                .sum();
        int capacity = usableAccounts.stream()
                .mapToInt(account -> Math.max(account.maxStreams, 0))
                .sum();
        Map<String, Object> body = Responses.map();
        body.put("activeStreams", activeStreams);
        body.put("streamCapacity", capacity);
        body.put("healthyAccounts", usableAccounts.size());
        body.put("pendingPayments", payments.countByStatus(Enums.PaymentStatus.PENDING));
        body.put("openTickets", tickets.countByStatus(Enums.TicketStatus.OPEN));
        body.put("auditLogs", auditLogs.count());
        return body;
    }

    private List<IptvAccount> usableAccounts() {
        return accounts.findByActiveTrueAndDisabledFalse().stream()
                .filter(this::usableForStreaming)
                .toList();
    }

    private boolean usableForStreaming(IptvAccount account) {
        if (account == null || !account.active || account.disabled) {
            return false;
        }
        if (account.expiresAt != null && account.expiresAt.isBefore(Instant.now())) {
            return false;
        }
        String status = account.lastHealthStatus == null ? "" : account.lastHealthStatus.toLowerCase();
        return !Set.of(
                "disabled",
                "expired",
                "misconfigured",
                "stream-failed",
                "unreachable",
                "catalog-unavailable",
                "empty-catalog"
        ).contains(status);
    }

    @Transactional
    public UptimeCheck saveCheck(Long id, String name, String url, String method, Boolean enabled) {
        UptimeCheck check = id == null ? new UptimeCheck() : uptimeChecks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Uptime check introuvable"));
        if (name != null && !name.isBlank()) check.name = name;
        if (url != null && !url.isBlank()) check.url = url;
        if (method != null && !method.isBlank()) check.method = method;
        if (enabled != null) check.enabled = enabled;
        return uptimeChecks.save(check);
    }

    @Transactional
    public UptimeCheck runCheck(Long id) {
        UptimeCheck check = uptimeChecks.findById(id).orElseThrow(() -> ApiException.notFound("Uptime check introuvable"));
        Instant start = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(check.url))
                    .timeout(Duration.ofSeconds(8))
                    .method(check.method == null ? "GET" : check.method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            check.lastLatencyMs = Duration.between(start, Instant.now()).toMillis();
            check.lastCheckedAt = Instant.now();
            check.lastStatus = response.statusCode() >= 200 && response.statusCode() < 400
                    ? Enums.UptimeStatus.OK
                    : Enums.UptimeStatus.DEGRADED;
            check.lastError = response.statusCode() >= 400 ? "HTTP " + response.statusCode() : null;
        } catch (Exception exception) {
            check.lastLatencyMs = Duration.between(start, Instant.now()).toMillis();
            check.lastCheckedAt = Instant.now();
            check.lastStatus = Enums.UptimeStatus.DOWN;
            check.lastError = exception.getMessage();
        }
        return uptimeChecks.save(check);
    }
}
