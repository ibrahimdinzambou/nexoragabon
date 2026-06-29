package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReelShortService {
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final String authToken;
    private final String defaultLanguage;
    private final Duration timeout;
    private final int maxResponseBytes;

    @Autowired
    public ReelShortService(
            ObjectMapper mapper,
            @Value("${app.reelshort.base-url:https://captain.sapimu.au/reelshort/api/v1}") String baseUrl,
            @Value("${app.reelshort.auth-token:}") String authToken,
            @Value("${app.reelshort.default-language:in}") String defaultLanguage,
            @Value("${app.reelshort.timeout-seconds:15}") long timeoutSeconds,
            @Value("${app.reelshort.max-response-bytes:2097152}") int maxResponseBytes
    ) {
        this(
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                baseUrl,
                authToken,
                defaultLanguage,
                timeoutSeconds,
                maxResponseBytes
        );
    }

    ReelShortService(
            ObjectMapper mapper,
            HttpClient httpClient,
            String baseUrl,
            String authToken,
            String defaultLanguage,
            long timeoutSeconds,
            int maxResponseBytes
    ) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.baseUri = normalizedBaseUri(baseUrl);
        this.authToken = clean(authToken);
        this.defaultLanguage = clean(defaultLanguage).isBlank() ? "in" : clean(defaultLanguage);
        this.timeout = Duration.ofSeconds(Math.max(2, timeoutSeconds));
        this.maxResponseBytes = Math.max(65_536, maxResponseBytes);
    }

    public boolean configured() {
        return !authToken.isBlank();
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provider", "ReelShort");
        status.put("configured", configured());
        status.put("endpoint", baseUri.toString());
        status.put("defaultLanguage", defaultLanguage);
        status.put("authentication", configured() ? "bearer" : "none");
        return status;
    }

    public Map<String, Object> test() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured());
        result.put("endpoint", baseUri.toString());
        result.put("trending", safeStep(() -> trending(defaultLanguage)));
        result.put("search", safeStep(() -> search("love", 1, defaultLanguage)));
        result.put("suggestions", safeStep(() -> suggestions(defaultLanguage)));
        return result;
    }

    public JsonNode trending(String language) {
        return get("trending", Map.of("lang", lang(language)));
    }

    public JsonNode search(String query, int page, String language) {
        String normalizedQuery = clean(query);
        if (normalizedQuery.isBlank()) {
            throw ApiException.validation("La recherche ReelShort est vide");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("q", normalizedQuery);
        parameters.put("page", String.valueOf(Math.max(1, page)));
        parameters.put("lang", lang(language));
        return get("search", parameters);
    }

    public JsonNode suggestions(String language) {
        return get("search/suggestions", Map.of("lang", lang(language)));
    }

    public JsonNode book(String id, String language) {
        String normalizedId = clean(id);
        if (normalizedId.isBlank()) {
            throw ApiException.validation("Identifiant ReelShort manquant");
        }
        return get("book/" + encodePath(normalizedId), Map.of("lang", lang(language)));
    }

    public JsonNode chapters(String id, String language) {
        String normalizedId = clean(id);
        if (normalizedId.isBlank()) {
            throw ApiException.validation("Identifiant ReelShort manquant");
        }
        return get("book/" + encodePath(normalizedId) + "/chapters", Map.of("lang", lang(language)));
    }

    public JsonNode video(String id, String chapter) {
        String normalizedId = clean(id);
        String normalizedChapter = clean(chapter);
        if (normalizedId.isBlank() || normalizedChapter.isBlank()) {
            throw ApiException.validation("Identifiant ReelShort ou chapitre manquant");
        }
        return get("book/" + encodePath(normalizedId) + "/chapter/" + encodePath(normalizedChapter) + "/video", Map.of());
    }

    private Map<String, Object> safeStep(Step step) {
        Map<String, Object> item = new LinkedHashMap<>();
        try {
            item.put("success", true);
            item.put("body", step.run());
        } catch (ApiException exception) {
            item.put("success", false);
            item.put("status", exception.status().value());
            item.put("code", exception.code());
            item.put("message", exception.getMessage());
        }
        return item;
    }

    private JsonNode get(String path, Map<String, String> query) {
        if (!configured()) {
            throw ApiException.serviceUnavailable("ReelShort n'est pas configure: REELSHORT_AUTH_TOKEN est manquant");
        }
        URI uri = endpoint(path, query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Authorization", "Bearer " + authToken)
                .header("User-Agent", "Nexora-ReelShort/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input);
            }
            JsonNode json = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw ApiException.unauthorized(message(json, "ReelShort a refuse le token"));
            }
            if (response.statusCode() == 404) {
                throw ApiException.notFound(message(json, "Contenu ReelShort introuvable"));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.providerUnavailable(message(json, "ReelShort ne repond pas correctement"));
            }
            return json;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.providerUnavailable("Communication ReelShort interrompue");
        } catch (IOException exception) {
            throw ApiException.providerUnavailable("Impossible de joindre ReelShort");
        }
    }

    private URI endpoint(String path, Map<String, String> query) {
        String normalizedPath = clean(path);
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        StringBuilder url = new StringBuilder(baseUri.resolve(normalizedPath).toString());
        appendQuery(url, query);
        return URI.create(url.toString());
    }

    private void appendQuery(StringBuilder url, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return;
        }
        String separator = url.indexOf("?") >= 0 ? "&" : "?";
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            url.append(separator)
                    .append(encodeQuery(entry.getKey()))
                    .append('=')
                    .append(encodeQuery(value));
            separator = "&";
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxResponseBytes) {
                throw new IOException("ReelShort response too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String message(JsonNode json, String fallback) {
        if (json == null) {
            return fallback;
        }
        for (String field : new String[]{"message", "error", "status_message"}) {
            JsonNode candidate = json.path(field);
            if (candidate.isValueNode() && !candidate.asText("").isBlank()) {
                return candidate.asText().strip();
            }
        }
        return fallback;
    }

    private String lang(String value) {
        String language = clean(value);
        return language.isBlank() ? defaultLanguage : language;
    }

    private String encodePath(String value) {
        return URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static URI normalizedBaseUri(String value) {
        URI uri = URI.create(value == null ? "" : value.strip());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid ReelShort base URL");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    @FunctionalInterface
    private interface Step {
        JsonNode run();
    }
}
