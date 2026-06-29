package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserNotification;
import com.iptv.saas.repository.UserNotificationRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class UserNotificationService {
    private final UserRepository users;
    private final UserNotificationRepository notifications;
    private final TransactionalMailService mail;
    private final EmailTemplateService templates;

    public UserNotificationService(
            UserRepository users,
            UserNotificationRepository notifications,
            TransactionalMailService mail,
            EmailTemplateService templates
    ) {
        this.users = users;
        this.notifications = notifications;
        this.mail = mail;
        this.templates = templates;
    }

    public List<UserNotification> forUser(UserEntity user) {
        return notifications.findTop50ByRecipientOrderByCreatedAtDesc(user);
    }

    @Transactional
    public BroadcastResult send(UserEntity sender, BroadcastRequest request) {
        if (sender == null || !isAdmin(sender)) {
            throw ApiException.forbidden("Permission insuffisante");
        }
        String title = clean(request.title(), 160);
        String body = clean(request.body(), 4000);
        if (title.isBlank()) {
            throw ApiException.validation("Le titre du message est requis.");
        }
        if (body.isBlank()) {
            throw ApiException.validation("Le contenu du message est requis.");
        }
        boolean sendEmail = request.email() == null || request.email();
        boolean sendInApp = request.inApp() == null || request.inApp();
        if (!sendEmail && !sendInApp) {
            throw ApiException.validation("Activez au moins l'e-mail ou la notification.");
        }

        List<UserEntity> recipients = resolveRecipients(request);
        if (recipients.isEmpty()) {
            throw ApiException.validation("Aucun utilisateur actif selectionne.");
        }

        int inAppCount = 0;
        int emailCount = 0;
        for (UserEntity recipient : recipients) {
            if (sendInApp) {
                UserNotification notification = new UserNotification();
                notification.recipient = recipient;
                notification.sender = sender;
                notification.title = title;
                notification.body = body;
                notification.emailRequested = sendEmail;
                notification.emailQueued = sendEmail;
                if (sendEmail) {
                    notification.emailQueuedAt = java.time.Instant.now();
                }
                notifications.save(notification);
                inAppCount += 1;
            }
            if (sendEmail) {
                mail.sendHtml(recipient.email, title, templates.adminMessage(title, body, sender.name));
                emailCount += 1;
            }
        }

        return new BroadcastResult(recipients.size(), inAppCount, emailCount);
    }

    private List<UserEntity> resolveRecipients(BroadcastRequest request) {
        String mode = request.targetMode() == null ? "USERS" : request.targetMode().trim().toUpperCase();
        if ("ALL".equals(mode)) {
            return users.findByActive(true).stream()
                    .filter(user -> user.email != null && !user.email.startsWith("deleted-user-"))
                    .toList();
        }
        Set<Long> ids = new LinkedHashSet<>(request.userIds() == null ? List.of() : request.userIds());
        if (ids.isEmpty()) {
            throw ApiException.validation("Selectionnez au moins un utilisateur.");
        }
        return users.findAllById(ids).stream()
                .filter(user -> user.active)
                .filter(user -> user.email != null && !user.email.startsWith("deleted-user-"))
                .toList();
    }

    private boolean isAdmin(UserEntity user) {
        return user.role == Enums.UserRole.SUPER_ADMIN
                || user.role == Enums.UserRole.ADMIN
                || user.role == Enums.UserRole.SUPPORT
                || user.role == Enums.UserRole.BILLING
                || user.role == Enums.UserRole.OPS;
    }

    private String clean(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s+\\R", "\n");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public record BroadcastRequest(String targetMode, List<Long> userIds, String title, String body, Boolean email, Boolean inApp) {
    }

    public record BroadcastResult(int recipients, int notifications, int emails) {
    }
}
