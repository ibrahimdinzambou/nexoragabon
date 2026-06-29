package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iptv.saas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DramaApiService {
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration timeout;

    public DramaApiService(
            ObjectMapper mapper,
            @Value("${app.dramas.base-url:http://127.0.0.1:5000/api/v1/reelshort}") String baseUrl,
            @Value("${app.dramas.timeout-seconds:20}") long timeoutSeconds
    ) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.baseUri = normalizedBaseUri(baseUrl);
        this.timeout = Duration.ofSeconds(Math.max(2, timeoutSeconds));
    }

    public JsonNode bookshelves(String language) {
        return get("bookshelves", Map.of("lang", lang(language)));
    }

    public JsonNode search(String query, int page, String language) {
        String q = clean(query);
        if (q.isBlank()) {
            throw ApiException.validation("Recherche drama vide");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keywords", q);
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("lang", lang(language));
        return get("search", params);
    }

    public JsonNode episodes(String bookId, String filteredTitle, String language) {
        String id = clean(bookId);
        String title = repairUtf8(clean(filteredTitle));
        if (id.isBlank() || title.isBlank()) {
            throw ApiException.validation("Identifiant drama ou slug manquant");
        }
        return get("episodes/" + encodePath(id), Map.of(
                "filtered_title", title,
                "lang", lang(language)
        ));
    }

    public JsonNode video(String bookId, int episode, String filteredTitle, String chapterId, String language) {
        String id = clean(bookId);
        String title = repairUtf8(clean(filteredTitle));
        String chapter = clean(chapterId);
        if (id.isBlank() || title.isBlank() || chapter.isBlank() || episode < 1) {
            throw ApiException.validation("Parametres video drama invalides");
        }
        return get("video/" + encodePath(id) + "/" + episode, Map.of(
                "filtered_title", title,
                "chapter_id", chapter,
                "lang", lang(language)
        ));
    }

    private JsonNode get(String path, Map<String, String> query) {
        URI uri = endpoint(path, query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Nexora-Dramas/1.0")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
            json = repairUtf8(json);
            if (response.statusCode() == 404) {
                throw ApiException.notFound(message(json, "Drama introuvable"));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.providerUnavailable(message(json, "API dramas indisponible"));
            }
            return json;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.providerUnavailable("Communication dramas interrompue");
        } catch (IOException exception) {
            throw ApiException.providerUnavailable("Impossible de joindre l'API dramas");
        }
    }

    private URI endpoint(String path, Map<String, String> query) {
        String normalizedPath = clean(path);
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        StringBuilder url = new StringBuilder(baseUri.resolve(normalizedPath).toString());
        String separator = url.indexOf("?") >= 0 ? "&" : "?";
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            url.append(separator)
                    .append(encodeQuery(entry.getKey()))
                    .append("=")
                    .append(encodeQuery(entry.getValue()));
            separator = "&";
        }
        return URI.create(url.toString());
    }

    private String message(JsonNode json, String fallback) {
        if (json == null) {
            return fallback;
        }
        for (String field : new String[]{"message", "error"}) {
            JsonNode value = json.path(field);
            if (value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return fallback;
    }

    private String lang(String language) {
        String value = clean(language);
        return value.isBlank() ? "fr" : value;
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private JsonNode repairUtf8(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(repairUtf8(node.asText()));
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, repairUtf8(array.get(i)));
            }
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.fieldNames().forEachRemaining(field -> object.set(field, repairUtf8(object.get(field))));
        }
        return node;
    }

    private String repairUtf8(String value) {
        if (value == null || value.isBlank() || !looksMojibake(value)) {
            return value;
        }
        String repaired = new String(value.getBytes(Charset.forName("ISO-8859-1")), StandardCharsets.UTF_8);
        return mojibakeScore(repaired) <= mojibakeScore(value) ? repaired : value;
    }

    private boolean looksMojibake(String value) {
        return value.indexOf('\u00C3') >= 0 || value.indexOf('\u00C2') >= 0 || value.indexOf('\uFFFD') >= 0;
    }

    private int mojibakeScore(String value) {
        int score = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u00C3' || c == '\u00C2' || c == '\uFFFD') {
                score++;
            }
        }
        return score;
    }

    private static URI normalizedBaseUri(String value) {
        URI uri = URI.create(value == null ? "" : value.strip());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid dramas base URL");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }
}
