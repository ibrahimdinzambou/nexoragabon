package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.UserNotificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/admin/notifications", "/admin/notifications"})
public class AdminNotificationController {
    private final UserNotificationService notifications;

    public AdminNotificationController(UserNotificationService notifications) {
        this.notifications = notifications;
    }

    @PostMapping({"/broadcasts", "/broadcast", "/messages"})
    public Object broadcast(@RequestBody UserNotificationService.BroadcastRequest request) {
        return Responses.ok(notifications.send(SecurityUtils.currentUser(), request));
    }
}
