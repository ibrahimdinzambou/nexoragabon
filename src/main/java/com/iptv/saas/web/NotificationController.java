package com.iptv.saas.web;

import com.iptv.saas.domain.UserNotification;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.UserNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/notifications", "/notifications"})
public class NotificationController {
    private final UserNotificationService notifications;

    public NotificationController(UserNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Object notifications() {
        return Responses.ok(notifications.forUser(SecurityUtils.currentUser()).stream()
                .map(this::notification)
                .toList());
    }

    private Map<String, Object> notification(UserNotification notification) {
        Map<String, Object> payload = Responses.map();
        payload.put("id", notification.id);
        payload.put("title", notification.title);
        payload.put("body", notification.body);
        payload.put("source", notification.source);
        payload.put("senderName", notification.sender == null ? null : notification.sender.name);
        payload.put("createdAt", notification.createdAt);
        return payload;
    }
}
