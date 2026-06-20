package com.iptv.saas.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class StreamRequestHeaders {
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final int MAX_HEADER_VALUE_LENGTH = 2048;
    private static final Map<String, String> CANONICAL_NAMES = Map.of(
            "accept", "Accept",
            "accept-language", "Accept-Language",
            "origin", "Origin",
            "referer", "Referer",
            "user-agent", "User-Agent"
    );
    private static final Set<String> ALLOWED_HEADERS = CANONICAL_NAMES.keySet();

    private StreamRequestHeaders() {
    }

    public static Map<String, String> sanitize(Map<String, String> rawHeaders) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            return sanitized;
        }
        rawHeaders.forEach((name, value) -> {
            String canonical = canonicalName(name);
            String cleanValue = cleanValue(value);
            if (canonical != null && cleanValue != null) {
                sanitized.put(canonical, cleanValue);
            }
        });
        return sanitized;
    }

    public static String encode(Map<String, String> rawHeaders) {
        Map<String, String> sanitized = sanitize(rawHeaders);
        if (sanitized.isEmpty()) {
            return null;
        }
        StringBuilder encoded = new StringBuilder();
        sanitized.forEach((name, value) -> encoded
                .append(name)
                .append('=')
                .append(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .append('\n'));
        return encoded.toString();
    }

    public static Map<String, String> decode(String encodedHeaders) {
        Map<String, String> decoded = new LinkedHashMap<>();
        if (encodedHeaders == null || encodedHeaders.isBlank()) {
            return decoded;
        }
        for (String line : encodedHeaders.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator + 1 >= line.length()) {
                continue;
            }
            String name = line.substring(0, separator);
            String value;
            try {
                value = new String(
                        Base64.getUrlDecoder().decode(line.substring(separator + 1)),
                        StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException exception) {
                continue;
            }
            String canonical = canonicalName(name);
            String cleanValue = cleanValue(value);
            if (canonical != null && cleanValue != null) {
                decoded.put(canonical, cleanValue);
            }
        }
        return decoded;
    }

    private static String canonicalName(String name) {
        if (name == null || !HEADER_NAME.matcher(name).matches()) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HEADERS.contains(normalized)) {
            return null;
        }
        return CANONICAL_NAMES.get(normalized);
    }

    private static String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isBlank()
                || cleaned.length() > MAX_HEADER_VALUE_LENGTH
                || cleaned.indexOf('\r') >= 0
                || cleaned.indexOf('\n') >= 0) {
            return null;
        }
        return cleaned;
    }
}
