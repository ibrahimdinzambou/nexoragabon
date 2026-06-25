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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TmdbMetadataService {
    private static final String DEFAULT_LANGUAGE = "fr-FR";

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final URI imageBaseUri;
    private final String apiKey;
    private final String readAccessToken;
    private final String language;
    private final String region;
    private final boolean includeAdult;
    private final Duration timeout;
    private final int maxResponseBytes;

    @Autowired
    public TmdbMetadataService(
            ObjectMapper mapper,
            @Value("${app.tmdb.base-url:https://api.themoviedb.org/3}") String baseUrl,
            @Value("${app.tmdb.image-base-url:https://image.tmdb.org/t/p}") String imageBaseUrl,
            @Value("${app.tmdb.api-key:}") String apiKey,
            @Value("${app.tmdb.read-access-token:}") String readAccessToken,
            @Value("${app.tmdb.language:fr-FR}") String language,
            @Value("${app.tmdb.region:FR}") String region,
            @Value("${app.tmdb.include-adult:false}") boolean includeAdult,
            @Value("${app.tmdb.timeout-seconds:12}") long timeoutSeconds,
            @Value("${app.tmdb.max-response-bytes:2097152}") int maxResponseBytes
    ) {
        this(
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                baseUrl,
                imageBaseUrl,
                apiKey,
                readAccessToken,
                language,
                region,
                includeAdult,
                timeoutSeconds,
                maxResponseBytes
        );
    }

    TmdbMetadataService(
            ObjectMapper mapper,
            HttpClient httpClient,
            String baseUrl,
            String imageBaseUrl,
            String apiKey,
            String readAccessToken,
            String language,
            String region,
            boolean includeAdult,
            long timeoutSeconds,
            int maxResponseBytes
    ) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.baseUri = normalizedBaseUri(baseUrl);
        this.imageBaseUri = normalizedBaseUri(imageBaseUrl);
        this.apiKey = clean(apiKey);
        this.readAccessToken = clean(readAccessToken);
        this.language = clean(language).isBlank() ? DEFAULT_LANGUAGE : clean(language);
        this.region = clean(region).toUpperCase(Locale.ROOT);
        this.includeAdult = includeAdult;
        this.timeout = Duration.ofSeconds(Math.max(2, timeoutSeconds));
        this.maxResponseBytes = Math.max(65_536, maxResponseBytes);
    }

    public boolean configured() {
        return !readAccessToken.isBlank() || !apiKey.isBlank();
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provider", "TMDB");
        status.put("configured", configured());
        status.put("endpoint", baseUri.toString());
        status.put("imageEndpoint", imageBaseUri.toString());
        status.put("authentication", authenticationMode());
        status.put("language", language);
        status.put("region", region);
        status.put("includeAdult", includeAdult);
        return status;
    }

    public JsonNode configuration() {
        return get("configuration", Map.of());
    }

    public JsonNode searchMulti(String query, int page) {
        String normalizedQuery = clean(query);
        if (normalizedQuery.isBlank()) {
            throw ApiException.validation("La recherche TMDB est vide");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query", normalizedQuery);
        parameters.put("include_adult", String.valueOf(includeAdult));
        parameters.put("language", language);
        parameters.put("page", String.valueOf(Math.max(1, page)));
        return get("search/multi", parameters);
    }

    public JsonNode search(String mediaType, String query, int page) {
        String normalizedQuery = clean(query);
        if (normalizedQuery.isBlank()) {
            throw ApiException.validation("La recherche TMDB est vide");
        }
        String normalizedType = normalizeMediaType(mediaType);
        if ("person".equals(normalizedType)) {
            throw ApiException.validation("La recherche TMDB de personnes n'est pas exposee au catalogue");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query", normalizedQuery);
        parameters.put("include_adult", String.valueOf(includeAdult));
        parameters.put("language", language);
        parameters.put("page", String.valueOf(Math.max(1, page)));
        if ("movie".equals(normalizedType) && !region.isBlank()) {
            parameters.put("region", region);
        }
        return get("search/" + normalizedType, parameters);
    }

    public JsonNode movieList(String endpoint, int page) {
        String normalizedEndpoint = normalizeCatalogEndpoint(endpoint, "movie");
        Map<String, String> parameters = defaultCatalogQuery(page);
        if (!region.isBlank()) {
            parameters.put("region", region);
        }
        return get("movie/" + normalizedEndpoint, parameters);
    }

    public JsonNode tvList(String endpoint, int page) {
        return get("tv/" + normalizeCatalogEndpoint(endpoint, "tv"), defaultCatalogQuery(page));
    }

    public JsonNode discoverTv(Map<String, String> filters, int page) {
        Map<String, String> parameters = defaultCatalogQuery(page);
        if (filters != null) {
            filters.forEach((key, value) -> {
                String normalizedKey = clean(key);
                String normalizedValue = clean(value);
                if (!normalizedKey.isBlank() && !normalizedValue.isBlank()) {
                    parameters.put(normalizedKey, normalizedValue);
                }
            });
        }
        return get("discover/tv", parameters);
    }

    public JsonNode trending(String mediaType, int page) {
        String normalizedType = normalizeMediaType(mediaType);
        if ("person".equals(normalizedType)) {
            throw ApiException.validation("Le catalogue TMDB de personnes n'est pas pris en charge");
        }
        Map<String, String> parameters = defaultCatalogQuery(page);
        return get("trending/" + normalizedType + "/week", parameters);
    }

    public JsonNode details(String mediaType, long id) {
        return details(mediaType, id, "");
    }

    public JsonNode details(String mediaType, long id, String appendToResponse) {
        if (id <= 0) {
            throw ApiException.validation("Identifiant TMDB invalide");
        }
        String normalizedType = normalizeMediaType(mediaType);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("language", language);
        String append = clean(appendToResponse);
        if (!append.isBlank()) {
            parameters.put("append_to_response", append);
        }
        return get(normalizedType + "/" + id, parameters);
    }

    public JsonNode season(long tvId, int seasonNumber) {
        if (tvId <= 0 || seasonNumber <= 0) {
            throw ApiException.validation("Saison TMDB invalide");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("language", language);
        return get("tv/" + tvId + "/season/" + seasonNumber, parameters);
    }

    public String imageUrl(String filePath, String size) {
        String path = clean(filePath);
        if (path.isBlank()) {
            return "";
        }
        String normalizedSize = clean(size).isBlank() ? "w500" : clean(size);
        if (!normalizedSize.matches("[A-Za-z0-9_]+")) {
            throw ApiException.validation("Taille d'image TMDB invalide");
        }
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return imageBaseUri.resolve(normalizedSize + "/" + encodePath(normalizedPath)).toString();
    }

    private Map<String, String> defaultCatalogQuery(int page) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("language", language);
        parameters.put("page", String.valueOf(Math.max(1, page)));
        return parameters;
    }

    private JsonNode get(String path, Map<String, String> query) {
        if (!configured()) {
            throw ApiException.serviceUnavailable("TMDB n'est pas configure");
        }
        URI uri = endpoint(path, authenticatedQuery(query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Nexora-TMDB/1.0")
                .GET();
        if (!readAccessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + readAccessToken);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input);
            }
            JsonNode json = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
            if (response.statusCode() == 401) {
                throw ApiException.unauthorized(message(json, "TMDB a refuse les identifiants"));
            }
            if (response.statusCode() == 404) {
                throw ApiException.notFound(message(json, "Contenu TMDB introuvable"));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.providerUnavailable(message(json, "TMDB ne repond pas correctement"));
            }
            return json;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.providerUnavailable("Communication TMDB interrompue");
        } catch (IOException exception) {
            throw ApiException.providerUnavailable("Impossible de joindre TMDB");
        }
    }

    private Map<String, String> authenticatedQuery(Map<String, String> query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query != null) {
            values.putAll(query);
        }
        if (readAccessToken.isBlank()) {
            values.put("api_key", apiKey);
        }
        return values;
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
                throw new IOException("TMDB response too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String authenticationMode() {
        if (!readAccessToken.isBlank()) {
            return "bearer";
        }
        if (!apiKey.isBlank()) {
            return "api_key";
        }
        return "none";
    }

    private String normalizeMediaType(String mediaType) {
        String normalized = clean(mediaType).toLowerCase(Locale.ROOT);
        if ("series".equals(normalized)) {
            return "tv";
        }
        if ("movie".equals(normalized) || "tv".equals(normalized) || "person".equals(normalized)) {
            return normalized;
        }
        throw ApiException.validation("Type TMDB non pris en charge");
    }

    private String normalizeCatalogEndpoint(String endpoint, String mediaType) {
        String normalized = clean(endpoint).toLowerCase(Locale.ROOT).replace('-', '_');
        Set<String> allowed = "movie".equals(mediaType)
                ? Set.of("popular", "top_rated", "now_playing", "upcoming")
                : Set.of("popular", "top_rated", "on_the_air", "airing_today");
        if (allowed.contains(normalized)) {
            return normalized;
        }
        throw ApiException.validation("Catalogue TMDB non pris en charge");
    }

    private String message(JsonNode json, String fallback) {
        String message = firstText(json, "status_message", "message", "error");
        return message.isBlank() ? fallback : message;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode candidate = node.path(field);
            if (candidate.isValueNode() && !candidate.asText("").isBlank()) {
                return candidate.asText().strip();
            }
        }
        return "";
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
            throw new IllegalArgumentException("Invalid TMDB base URL");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }
}
