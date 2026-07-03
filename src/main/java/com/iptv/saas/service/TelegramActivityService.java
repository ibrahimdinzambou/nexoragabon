package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.SubscriptionPeriods;
import com.iptv.saas.domain.SupportTicket;
import com.iptv.saas.domain.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TelegramActivityService {
    private final boolean enabled;
    private final boolean loginAlertsEnabled;
    private final boolean failedLoginAlertsEnabled;
    private final boolean passwordAlertsEnabled;
    private final TelegramAlertService telegram;
    private final RequestInfoService requestInfo;

    public TelegramActivityService(
            @Value("${app.telegram.activity.enabled:false}") boolean enabled,
            @Value("${app.telegram.activity.login-alerts-enabled:true}") boolean loginAlertsEnabled,
            @Value("${app.telegram.activity.failed-login-alerts-enabled:true}") boolean failedLoginAlertsEnabled,
            @Value("${app.telegram.activity.password-alerts-enabled:true}") boolean passwordAlertsEnabled,
            TelegramAlertService telegram,
            RequestInfoService requestInfo
    ) {
        this.enabled = enabled;
        this.loginAlertsEnabled = loginAlertsEnabled;
        this.failedLoginAlertsEnabled = failedLoginAlertsEnabled;
        this.passwordAlertsEnabled = passwordAlertsEnabled;
        this.telegram = telegram;
        this.requestInfo = requestInfo;
    }

    public void userRegistered(UserEntity user, Organization organization, Subscription subscription, boolean pendingEmail) {
        if (!enabled) {
            return;
        }
        telegram.send(
                "Nouvelle inscription Nexora",
                """
                Client: %s
                Email: %s
                Organisation: %s
                Plan: %s
                Verification email: %s
                IP: %s
                Appareil: %s
                Heure: %s
                """.formatted(
                        displayName(user),
                        safeEmail(user),
                        organization == null ? "-" : organization.name,
                        subscription == null || subscription.plan == null ? "-" : subscription.plan.name,
                        pendingEmail ? "en attente" : "validee",
                        requestInfo.maskedIp(),
                        requestInfo.userAgentSummary(),
                        Instant.now()
                ),
                userAction(user)
        );
    }

    public void loginSuccess(UserEntity user, String mode) {
        if (!enabled || !loginAlertsEnabled) {
            return;
        }
        telegram.send(
                isAdminLike(user) ? "Connexion admin Nexora" : "Connexion utilisateur Nexora",
                """
                Client: %s
                Email: %s
                Role: %s
                Mode: %s
                IP: %s
                Appareil: %s
                Heure: %s
                """.formatted(
                        displayName(user),
                        safeEmail(user),
                        user == null ? "-" : user.role,
                        mode,
                        requestInfo.maskedIp(),
                        requestInfo.userAgentSummary(),
                        Instant.now()
                ),
                userAction(user)
        );
    }

    public void loginFailed(UserEntity user, String email, String reason) {
        if (!enabled || !failedLoginAlertsEnabled) {
            return;
        }
        telegram.send(
                "Echec connexion Nexora",
                """
                Email tente: %s
                Compte connu: %s
                Raison: %s
                IP: %s
                Appareil: %s
                Heure: %s
                """.formatted(
                        email == null || email.isBlank() ? "-" : email,
                        user == null ? "non" : "oui (#" + user.id + ")",
                        reason,
                        requestInfo.maskedIp(),
                        requestInfo.userAgentSummary(),
                        Instant.now()
                )
        );
    }

    public void passwordResetRequested(UserEntity user) {
        if (!enabled || !passwordAlertsEnabled) {
            return;
        }
        telegram.send(
                "Demande reset mot de passe",
                """
                Client: %s
                Email: %s
                IP: %s
                Appareil: %s
                Heure: %s
                """.formatted(
                        displayName(user),
                        safeEmail(user),
                        requestInfo.maskedIp(),
                        requestInfo.userAgentSummary(),
                        Instant.now()
                ),
                userAction(user)
        );
    }

    public void passwordResetCompleted(UserEntity user) {
        if (!enabled || !passwordAlertsEnabled) {
            return;
        }
        telegram.send(
                "Mot de passe reinitialise",
                """
                Client: %s
                Email: %s
                Role: %s
                IP: %s
                Appareil: %s
                Heure: %s
                """.formatted(
                        displayName(user),
                        safeEmail(user),
                        user == null ? "-" : user.role,
                        requestInfo.maskedIp(),
                        requestInfo.userAgentSummary(),
                        Instant.now()
                ),
                userAction(user)
        );
    }

    public void planChanged(UserEntity user, Subscription subscription) {
        if (!enabled) {
            return;
        }
        telegram.send(
                "Changement de formule",
                """
                Client: %s
                Email: %s
                Plan: %s
                Statut: %s
                Fin periode: %s
                Heure: %s
                """.formatted(
                        displayName(user),
                        safeEmail(user),
                        subscription == null || subscription.plan == null ? "-" : subscription.plan.name,
                        subscription == null ? "-" : subscription.status,
                        subscription == null ? "-" : SubscriptionPeriods.currentPeriodEnd(subscription),
                        Instant.now()
                ),
                userAction(user)
        );
    }

    public void ticketOpened(SupportTicket ticket) {
        if (!enabled || ticket == null) {
            return;
        }
        UserEntity user = ticket.user;
        telegram.send(
                ticket.priority == Enums.TicketPriority.HIGH || ticket.priority == Enums.TicketPriority.URGENT
                        ? "Ticket support prioritaire"
                        : "Nouveau ticket support",
                """
                Ticket: #%d
                Priorite: %s
                Client: %s
                Email: %s
                Sujet: %s
                Heure: %s
                """.formatted(
                        ticket.id,
                        ticket.priority,
                        displayName(user),
                        safeEmail(user),
                        ticket.subject,
                        Instant.now()
                ),
                List.of(List.of(
                        new TelegramAlertService.Action("Repondu #" + ticket.id, "answer_ticket:" + ticket.id),
                        new TelegramAlertService.Action("Fermer #" + ticket.id, "confirm:close_ticket:" + ticket.id)
                ))
        );
    }

    private List<List<TelegramAlertService.Action>> userAction(UserEntity user) {
        if (user == null || user.id == null) {
            return List.of();
        }
        return List.of(List.of(new TelegramAlertService.Action("Fiche client #" + user.id, "client:" + user.id)));
    }

    private boolean isAdminLike(UserEntity user) {
        return user != null && (
                user.role == Enums.UserRole.SUPER_ADMIN
                        || user.role == Enums.UserRole.ADMIN
                        || user.role == Enums.UserRole.BILLING
                        || user.role == Enums.UserRole.SUPPORT
                        || user.role == Enums.UserRole.OPS
        );
    }

    private String displayName(UserEntity user) {
        if (user == null) {
            return "-";
        }
        return "#" + user.id + " " + (user.name == null || user.name.isBlank() ? "-" : user.name);
    }

    private String safeEmail(UserEntity user) {
        return user == null || user.email == null || user.email.isBlank() ? "-" : user.email;
    }
}
