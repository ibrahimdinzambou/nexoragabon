package com.iptv.saas.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

@Service
public class RequestInfoService {
    private static final int MAX_IP_LENGTH = 128;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    public String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String value = firstHeader(
                request,
                "CF-Connecting-IP",
                "Fly-Client-IP",
                "X-Forwarded-For",
                "X-Real-IP",
                "Forwarded"
        );
        if (value == null || value.isBlank()) {
            value = request.getRemoteAddr();
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        value = normalizeForwarded(value);
        return truncate(value, MAX_IP_LENGTH);
    }

    public String maskedIp() {
        String ip = clientIp();
        if (ip == null || ip.isBlank()) {
            return "-";
        }
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + ".xxx.xxx." + parts[3];
            }
        }
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            if (parts.length >= 3) {
                return parts[0] + ":" + parts[1] + ":***";
            }
        }
        return ip.length() <= 6 ? "***" : ip.substring(0, 3) + "***" + ip.substring(ip.length() - 3);
    }

    public String userAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH);
    }

    public String userAgentSummary() {
        String value = userAgent();
        if (value == null || value.isBlank()) {
            return "-";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String device;
        if (lower.contains("iphone")) {
            device = "iPhone";
        } else if (lower.contains("ipad")) {
            device = "iPad";
        } else if (lower.contains("android")) {
            device = "Android";
        } else if (lower.contains("windows")) {
            device = "Windows";
        } else if (lower.contains("mac os")) {
            device = "Mac";
        } else {
            device = "Appareil inconnu";
        }
        String browser;
        if (lower.contains("edg/")) {
            browser = "Edge";
        } else if (lower.contains("chrome/") && !lower.contains("chromium")) {
            browser = "Chrome";
        } else if (lower.contains("safari/") && lower.contains("version/")) {
            browser = "Safari";
        } else if (lower.contains("firefox/")) {
            browser = "Firefox";
        } else {
            browser = "Navigateur inconnu";
        }
        return device + " / " + browser;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeForwarded(String value) {
        String normalized = value.strip();
        if (normalized.contains(",")) {
            normalized = normalized.split(",", 2)[0].strip();
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("for=")) {
            normalized = normalized.substring(4).replace("\"", "").strip();
            int separator = normalized.indexOf(';');
            if (separator >= 0) {
                normalized = normalized.substring(0, separator).strip();
            }
        }
        return normalized;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
