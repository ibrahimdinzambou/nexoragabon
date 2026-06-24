package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.security.CatalogCategoryAccess;
import com.iptv.saas.security.SecurityUtils;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EpornerContentService {
    public static final String CATEGORY_ADULTS = "adults-eporner";
    private static final String CATEGORY_NAME = "Adults";
    private static final String ITEM_PREFIX = "eporner";
    private static final String TYPE_MOVIE = "movie";
    private static final String SOURCE = "Eporner";
    private static final String DEFAULT_QUERY = "all";
    private static final Set<String> SEARCH_ORDERS = Set.of(
            "latest",
            "longest",
            "shortest",
            "top-rated",
            "most-popular",
            "top-weekly",
            "top-monthly"
    );

    private final ObjectMapper mapper;
    private final CatalogImageService images;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final int maxPerPage;
    private final Duration cacheTtl;
    private final String defaultOrder;
    private final Map<String, CachedJson> cache = new ConcurrentHashMap<>();

    @Autowired
    public EpornerContentService(
            ObjectMapper mapper,
            CatalogImageService images,
            @Value("${app.eporner.enabled:false}") boolean enabled,
            @Value("${app.eporner.base-url:https://www.eporner.com/api/v2}") String baseUrl,
            @Value("${app.eporner.timeout-seconds:20}") long timeoutSeconds,
            @Value("${app.eporner.max-response-bytes:4194304}") int maxResponseBytes,
            @Value("${app.eporner.max-per-page:60}") int maxPerPage,
            @Value("${app.eporner.cache-ttl-seconds:300}") long cacheTtlSeconds,
            @Value("${app.eporner.default-order:latest}") String defaultOrder
    ) {
        this(
                mapper,
                images,
                enabled,
                baseUrl,
                timeoutSeconds,
                maxResponseBytes,
                maxPerPage,
                cacheTtlSeconds,
                defaultOrder,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
        );
    }

    EpornerContentService(
            ObjectMapper mapper,
            CatalogImageService images,
            boolean enabled,
            String baseUrl,
            long timeoutSeconds,
            int maxResponseBytes,
            int maxPerPage,
            long cacheTtlSeconds,
            String defaultOrder,
            HttpClient httpClient
    ) {
        this.mapper = mapper;
        this.images = images;
        this.baseUri = normalizeBaseUri(baseUrl);
        this.timeout = Duration.ofSeconds(Math.max(2, timeoutSeconds));
        this.maxResponseBytes = Math.max(65_536, maxResponseBytes);
        this.maxPerPage = Math.max(1, Math.min(1000, maxPerPage));
        this.cacheTtl = Duration.ofSeconds(Math.max(30, cacheTtlSeconds));
        this.defaultOrder = normalizeOrder(defaultOrder);
        this.httpClient = httpClient;
    }

    public boolean isEnabled() {
        return baseUri != null;
    }

    public boolean isEpornerItem(String itemId) {
        return itemId != null && itemId.startsWith(ITEM_PREFIX + "~video~");
    }

    public boolean hasAccess(UserEntity user) {
        return SecurityUtils.isAdminLike(user)
                || CatalogCategoryAccess.permitsExplicitly(user, CATEGORY_ADULTS);
    }

    public List<Map<String, Object>> categories(String type) {
        if (!isEnabled() || !matchesType(type)) {
            return List.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", CATEGORY_ADULTS);
        payload.put("name", CATEGORY_NAME);
        payload.put("type", TYPE_MOVIE);
        payload.put("source", SOURCE);
        payload.put("sourceCode", "eporner");
        payload.put("metadataAvailable", true);
        payload.put("streamAvailable", true);
        payload.put("privateUse", true);
        payload.put("privateAccess", true);
        payload.put("adult", true);
        return List.of(payload);
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit
    ) {
        return items(type, query, categoryId, language, sort, requestedLimit, 1);
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit,
            int requestedPage
    ) {
        if (!isEnabled() || !matchesType(type) || !matchesCategory(categoryId)) {
            return List.of();
        }
        int page = Math.max(1, requestedPage);
        int perPage = requestedLimit <= 0 ? Math.min(30, maxPerPage) : Math.min(requestedLimit, maxPerPage);
        if (query == null || query.isBlank()) {
            return browseItems(sort, Math.max(perPage, Math.min(96, maxPerPage)), page);
        }
        return searchItems(normalizedQuery(query), sort, perPage, page);
    }

    private List<Map<String, Object>> searchItems(String query, String sort, int perPage, int page) {
        URI uri = endpoint("video/search/", Map.of(
                "query", query,
                "per_page", String.valueOf(Math.min(perPage, maxPerPage)),
                "page", String.valueOf(Math.max(1, page)),
                "thumbsize", "big",
                "order", order(sort),
                "gay", "1",
                "lq", "1",
                "format", "json"
        ));
        JsonNode response = cachedJson(uri);
        JsonNode videos = response.path("videos");
        if (!videos.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode video : videos) {
            Map<String, Object> payload = videoPayload(video);
            if (!payload.isEmpty()) {
                result.add(payload);
            }
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> browseItems(String sort, int targetLimit, int requestedPage) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int perPage = Math.max(20, Math.min(40, targetLimit));
        int pagesPerBatch = Math.max(1, (int) Math.ceil(targetLimit / (double) perPage));
        int startPage = ((Math.max(1, requestedPage) - 1) * pagesPerBatch) + 1;
        int endPage = startPage + pagesPerBatch - 1;
        for (int page = startPage; page <= endPage; page++) {
            for (Map<String, Object> payload : searchItems(DEFAULT_QUERY, sort, perPage, page)) {
                String id = String.valueOf(payload.getOrDefault("id", ""));
                if (!id.isBlank() && seen.add(id)) {
                    result.add(payload);
                }
                if (result.size() >= targetLimit) {
                    return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    public Map<String, Object> itemInfo(String publicItemId) {
        String id = parseItemId(publicItemId);
        URI uri = endpoint("video/id/", Map.of(
                "id", id,
                "thumbsize", "big",
                "format", "json"
        ));
        JsonNode response = cachedJson(uri);
        JsonNode video = response.isArray() ? null : response;
        if (video == null || !video.isObject() || text(video, "id").isBlank()) {
            throw ApiException.notFound("Video adults introuvable ou retiree");
        }
        Map<String, Object> payload = videoPayload(video);
        payload.put("url", text(video, "url"));
        payload.put("embedUrl", embedUrl(id, text(video, "embed")));
        payload.put("keywords", text(video, "keywords"));
        payload.put("thumbs", thumbs(video.path("thumbs")));
        images.rewrite(payload);
        return payload;
    }

    public CatalogAccessDescriptor accessForItem(String publicItemId) {
        parseItemId(publicItemId);
        return new CatalogAccessDescriptor(CATEGORY_ADULTS, CATEGORY_NAME, TYPE_MOVIE, true);
    }

    public StreamResolution selectStream(String publicItemId) {
        String id = parseItemId(publicItemId);
        return new StreamResolution(embedUrl(id, ""), Map.of());
    }

    public boolean isEmbedPlayback(String itemId, String streamUrl) {
        return isEpornerItem(itemId) && isAllowedEmbedUrl(streamUrl);
    }

    private Map<String, Object> videoPayload(JsonNode video) {
        String id = text(video, "id");
        String title = text(video, "title");
        if (id.isBlank() || title.isBlank()) {
            return Map.of();
        }
        String thumb = thumbnail(video.path("default_thumb"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", publicItemId(id));
        payload.put("remoteId", id);
        payload.put("name", title);
        payload.put("type", TYPE_MOVIE);
        payload.put("categoryId", CATEGORY_ADULTS);
        payload.put("categoryName", CATEGORY_NAME);
        payload.put("poster", thumb);
        payload.put("logo", thumb);
        payload.put("image", thumb);
        payload.put("backdrop", thumb);
        payload.put("summary", "");
        payload.put("duration", text(video, "length_min"));
        payload.put("lengthSec", video.path("length_sec").asInt(0));
        payload.put("views", video.path("views").asLong(0));
        payload.put("rating", text(video, "rate"));
        payload.put("releaseDate", text(video, "added"));
        payload.put("releaseYear", releaseYear(text(video, "added")));
        payload.put("genres", keywords(video, 8));
        payload.put("source", SOURCE);
        payload.put("sourceCode", "eporner");
        payload.put("provider", SOURCE);
        payload.put("metadataAvailable", true);
        payload.put("streamAvailable", true);
        payload.put("playbackProvider", "eporner");
        payload.put("playbackProviderName", "Eporner embed");
        payload.put("externalPlayback", true);
        payload.put("privateUse", true);
        payload.put("privateAccess", true);
        payload.put("adult", true);
        images.rewrite(payload);
        return payload;
    }

    private List<Map<String, Object>> thumbs(JsonNode thumbs) {
        if (!thumbs.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode thumb : thumbs) {
            String source = thumbnail(thumb);
            if (source.isBlank()) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("size", text(thumb, "size"));
            payload.put("width", thumb.path("width").asInt(0));
            payload.put("height", thumb.path("height").asInt(0));
            payload.put("src", source);
            result.add(payload);
        }
        return result;
    }

    private List<String> keywords(JsonNode video, int limit) {
        String value = text(video, "keywords");
        if (value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String keyword = part.strip();
            if (!keyword.isBlank() && !result.contains(keyword)) {
                result.add(keyword);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private JsonNode cachedJson(URI uri) {
        String key = uri.toString();
        CachedJson cached = cache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.value();
        }
        JsonNode fresh = requestJson(uri);
        cache.put(key, new CachedJson(fresh, now.plus(cacheTtl)));
        return fresh;
    }

    private JsonNode requestJson(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "Nexora-Eporner/1.0")
                .GET()
                .build();
        try {
            IOException lastIo = null;
            InterruptedException lastInterrupted = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    byte[] body;
                    try (InputStream input = response.body()) {
                        body = readLimited(input);
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return mapper.readTree(body);
                    }
                    if (response.statusCode() < 500 || attempt == 2) {
                        throw ApiException.providerUnavailable("Eporner a refuse la requete (HTTP " + response.statusCode() + ")");
                    }
                } catch (InterruptedException exception) {
                    lastInterrupted = exception;
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException exception) {
                    lastIo = exception;
                    if (attempt == 2) {
                        break;
                    }
                }
                try {
                    Thread.sleep(250L * (attempt + 1));
                } catch (InterruptedException exception) {
                    lastInterrupted = exception;
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (lastInterrupted != null) {
                throw ApiException.serviceUnavailable("Requete Eporner interrompue");
            }
            if (lastIo != null) {
                throw ApiException.providerUnavailable("Eporner est momentanement indisponible");
            }
            throw ApiException.providerUnavailable("Eporner est momentanement indisponible");
        } catch (ApiException exception) {
            throw exception;
        }
    }

    private URI endpoint(String method, Map<String, String> parameters) {
        StringBuilder builder = new StringBuilder(baseUri.resolve(method).toString());
        if (!parameters.isEmpty()) {
            builder.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return URI.create(builder.toString());
    }

    private String parseItemId(String publicItemId) {
        String[] parts = String.valueOf(publicItemId).split("~", 3);
        if (parts.length != 3 || !ITEM_PREFIX.equals(parts[0]) || !"video".equals(parts[1])) {
            throw ApiException.validation("Identifiant adults invalide");
        }
        String id = parts[2].strip();
        if (!id.matches("[A-Za-z0-9_-]{4,32}")) {
            throw ApiException.validation("Identifiant Eporner invalide");
        }
        return id;
    }

    private String publicItemId(String id) {
        return ITEM_PREFIX + "~video~" + id;
    }

    private String embedUrl(String id, String provided) {
        if (isAllowedEmbedUrl(provided)) {
            return provided;
        }
        return "https://www.eporner.com/embed/" + id + "/";
    }

    private boolean isAllowedEmbedUrl(String value) {
        try {
            URI uri = URI.create(String.valueOf(value));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return Set.of("http", "https").contains(scheme)
                    && ("www.eporner.com".equals(host) || "eporner.com".equals(host))
                    && path.startsWith("/embed/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizedQuery(String query) {
        String value = query == null ? "" : query.strip();
        return value.isBlank() ? DEFAULT_QUERY : value;
    }

    private String order(String sort) {
        if (sort == null || sort.isBlank()) {
            return defaultOrder;
        }
        String normalized = sort.strip().toLowerCase(Locale.ROOT);
        if ("recent".equals(normalized) || "title-asc".equals(normalized) || "title-desc".equals(normalized)) {
            return "latest";
        }
        return normalizeOrder(normalized);
    }

    private String normalizeOrder(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return SEARCH_ORDERS.contains(normalized) ? normalized : "latest";
    }

    private boolean matchesType(String type) {
        return type == null || type.isBlank() || TYPE_MOVIE.equals(type.strip().toLowerCase(Locale.ROOT));
    }

    private boolean matchesCategory(String categoryId) {
        return categoryId == null || categoryId.isBlank() || CATEGORY_ADULTS.equals(categoryId);
    }

    private String thumbnail(JsonNode thumb) {
        String source = text(thumb, "src");
        return isHttpUrl(source) ? source : "";
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(String.valueOf(value));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return Set.of("http", "https").contains(scheme) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private URI normalizeBaseUri(String value) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(cleaned);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null) {
                return null;
            }
            String normalized = uri.toString();
            if (!normalized.endsWith("/")) {
                normalized += "/";
            }
            return URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 65_536));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxResponseBytes) {
                throw new IOException("Reponse Eporner trop volumineuse");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String releaseYear(String value) {
        if (value == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    public record CatalogAccessDescriptor(String categoryId, String categoryName, String contentType, boolean adult) {
    }

    public record StreamResolution(String streamUrl, Map<String, String> headers) {
    }

    private record CachedJson(JsonNode value, Instant expiresAt) {
    }
}
