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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TorBoxTorrentResolver {
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts"
    );

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final String apiToken;
    private final Set<String> allowedDownloadHosts;
    private final Duration maxWait;
    private final Duration pollInterval;
    private final Map<String, CachedLink> resolvedLinks = new ConcurrentHashMap<>();
    private final Map<String, Long> torrentIds = new ConcurrentHashMap<>();

    @Autowired
    public TorBoxTorrentResolver(
            ObjectMapper mapper,
            @Value("${app.addons.torbox.base-url:https://api.torbox.app}") String baseUrl,
            @Value("${app.addons.torbox.api-token:}") String apiToken,
            @Value("${app.addons.torbox.allowed-download-hosts:.torbox.app,.tb-cdn.io}") String allowedDownloadHosts,
            @Value("${app.addons.torbox.max-wait-seconds:90}") long maxWaitSeconds,
            @Value("${app.addons.torbox.poll-interval-millis:2000}") long pollIntervalMillis
    ) {
        this(
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                baseUrl,
                apiToken,
                allowedDownloadHosts,
                maxWaitSeconds,
                pollIntervalMillis
        );
    }

    TorBoxTorrentResolver(
            ObjectMapper mapper,
            HttpClient httpClient,
            String baseUrl,
            String apiToken,
            String allowedDownloadHosts,
            long maxWaitSeconds,
            long pollIntervalMillis
    ) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.baseUri = normalizedBaseUri(baseUrl);
        this.apiToken = apiToken == null ? "" : apiToken.strip();
        this.allowedDownloadHosts = parseHosts(allowedDownloadHosts);
        this.maxWait = Duration.ofSeconds(Math.max(1, maxWaitSeconds));
        this.pollInterval = Duration.ofMillis(Math.max(50, pollIntervalMillis));
    }

    public boolean configured() {
        return !apiToken.isBlank();
    }

    public boolean supports(JsonNode stream) {
        return stream != null && validHash(stream.path("infoHash").asText(null)) != null;
    }

    public TorrentAvailability availability(JsonNode stream) {
        if (!configured()) {
            return new TorrentAvailability(
                    false,
                    "Le flux est un torrent mais TORBOX_API_TOKEN n'est pas configure"
            );
        }
        String hash = validHash(stream == null ? null : stream.path("infoHash").asText(null));
        if (hash == null) {
            return new TorrentAvailability(false, "Le flux torrent ne contient pas un infoHash valide");
        }
        CachedLink cached = resolvedLinks.get(hash);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return new TorrentAvailability(true, null);
        }
        Long knownTorrentId = torrentIds.get(hash);
        try {
            if (knownTorrentId != null) {
                Torrent torrent = torrent(knownTorrentId);
                if (torrent.ready() && selectVideo(torrent.files()).isPresent()) {
                    return new TorrentAvailability(true, null);
                }
            }
            OptionalLong existingTorrentId = existingTorrentId(hash);
            if (existingTorrentId.isPresent()) {
                torrentIds.put(hash, existingTorrentId.getAsLong());
                Torrent torrent = torrent(existingTorrentId.getAsLong());
                if (torrent.ready() && selectVideo(torrent.files()).isPresent()) {
                    return new TorrentAvailability(true, null);
                }
            }
            if (cachedByTorBox(hash)) {
                return new TorrentAvailability(true, null);
            }
            return new TorrentAvailability(
                    false,
                    "Ce flux torrent n'est pas encore stocke ou pret dans TorBox."
            );
        } catch (ApiException exception) {
            return new TorrentAvailability(false, exception.getMessage());
        }
    }

    public String resolve(JsonNode stream) {
        if (!configured()) {
            throw ApiException.validation(
                    "Le flux est un torrent mais TORBOX_API_TOKEN n'est pas configure"
            );
        }
        String hash = validHash(stream.path("infoHash").asText(null));
        if (hash == null) {
            throw ApiException.validation("Le flux torrent ne contient pas un infoHash valide");
        }

        CachedLink cached = resolvedLinks.get(hash);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.url();
        }

        long torrentId = torrentIds.computeIfAbsent(
                hash,
                ignored -> torrentId(hash, stream)
        );
        Instant deadline = Instant.now().plus(maxWait);
        ApiException lastFailure = null;
        do {
            try {
                Torrent torrent = torrent(torrentId);
                Optional<TorrentFile> selected = selectVideo(torrent.files());
                if (selected.isPresent() && torrent.ready()) {
                    String url = requestDownload(torrentId, selected.get().id());
                    resolvedLinks.put(hash, new CachedLink(url, Instant.now().plus(Duration.ofMinutes(15))));
                    return url;
                }
            } catch (ApiException exception) {
                lastFailure = exception;
            }
            pause();
        } while (Instant.now().isBefore(deadline));

        if (lastFailure != null) {
            throw ApiException.serviceUnavailable(
                    "TorBox n'a pas encore rendu le fichier video disponible"
            );
        }
        throw ApiException.serviceUnavailable(
                "Le torrent a ete ajoute a TorBox mais son telechargement n'est pas termine"
        );
    }

    public Map<String, Object> status() {
        return Map.of(
                "provider", "TorBox",
                "configured", configured(),
                "endpoint", baseUri.toString(),
                "allowedDownloadHosts", allowedDownloadHosts
        );
    }

    private long createTorrent(String magnet) {
        String boundary = "----NexoraTorBox" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, magnet);
        HttpRequest request = authenticated(
                endpoint("/v1/api/torrents/createtorrent")
        ).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode response = sendJson(request);
        JsonNode data = response.path("data");
        long torrentId = firstLong(data, "torrent_id", "torrentId", "id");
        if (torrentId <= 0) {
            torrentId = firstLong(response, "torrent_id", "torrentId", "id");
        }
        if (torrentId <= 0) {
            throw ApiException.serviceUnavailable("TorBox n'a pas retourne l'identifiant du torrent");
        }
        return torrentId;
    }

    private long torrentId(String hash, JsonNode stream) {
        OptionalLong existing = existingTorrentIdIfReachable(hash);
        if (existing.isPresent()) {
            return existing.getAsLong();
        }
        try {
            return createTorrent(magnet(hash, stream.path("sources")));
        } catch (ApiException exception) {
            OptionalLong retryExisting = existingTorrentIdIfReachable(hash);
            if (retryExisting.isPresent()) {
                return retryExisting.getAsLong();
            }
            throw exception;
        }
    }

    private OptionalLong existingTorrentIdIfReachable(String hash) {
        try {
            return existingTorrentId(hash);
        } catch (ApiException exception) {
            return OptionalLong.empty();
        }
    }

    private OptionalLong existingTorrentId(String hash) {
        JsonNode response = sendJson(
                authenticated(endpoint("/v1/api/torrents/mylist?bypass_cache=true&limit=1000")).GET().build()
        );
        for (JsonNode torrent : torrentNodes(response.path("data"))) {
            if (torrentMatchesHash(torrent, hash)) {
                long id = firstLong(torrent, "id", "torrent_id", "torrentId");
                if (id > 0) {
                    return OptionalLong.of(id);
                }
            }
        }
        return OptionalLong.empty();
    }

    private List<JsonNode> torrentNodes(JsonNode data) {
        List<JsonNode> values = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(values::add);
            return values;
        }
        if (data.isObject()) {
            JsonNode torrents = data.path("torrents");
            if (torrents.isArray()) {
                torrents.forEach(values::add);
                return values;
            }
            values.add(data);
        }
        return values;
    }

    private boolean torrentMatchesHash(JsonNode torrent, String hash) {
        String normalized = hash.toLowerCase(Locale.ROOT);
        String value = firstText(torrent, "hash", "info_hash", "infoHash", "torrent_hash", "torrentHash");
        if (value != null && value.toLowerCase(Locale.ROOT).equals(normalized)) {
            return true;
        }
        String magnet = firstText(torrent, "magnet", "magnet_uri", "magnetUri");
        return magnet != null && magnet.toLowerCase(Locale.ROOT).contains(normalized);
    }

    private boolean cachedByTorBox(String hash) {
        JsonNode response = sendJson(authenticated(endpoint(
                "/v1/api/torrents/checkcached?hash="
                        + encode(hash)
                        + "&format=object&list_files=true"
        )).GET().build());
        return cachedNode(response.path("data"), hash.toLowerCase(Locale.ROOT));
    }

    private boolean cachedNode(JsonNode node, String hash) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String value = node.asText("").toLowerCase(Locale.ROOT);
            return "true".equals(value) || "cached".equals(value) || "ready".equals(value);
        }
        if (node.isArray()) {
            if (!node.isEmpty() && node.get(0).isObject() && !node.get(0).has("hash")) {
                return true;
            }
            for (JsonNode child : node) {
                if (cachedNode(child, hash)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }

        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().equalsIgnoreCase(hash)) {
                return cachedHashValue(field.getValue());
            }
        }

        String nodeHash = firstText(node, "hash", "info_hash", "infoHash", "torrent_hash", "torrentHash");
        boolean hashMatches = nodeHash != null && nodeHash.toLowerCase(Locale.ROOT).equals(hash);
        JsonNode cached = firstExisting(node, "cached", "is_cached", "isCached", "available", "ready");
        if (!cached.isMissingNode()) {
            return cached.asBoolean(false) && (!hashMatches || hasVideoCandidate(node));
        }
        return hashMatches && hasVideoCandidate(node);
    }

    private boolean cachedHashValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isArray()) {
            return !value.isEmpty();
        }
        if (value.isObject()) {
            JsonNode cached = firstExisting(value, "cached", "is_cached", "isCached", "available", "ready");
            return cached.isMissingNode() ? hasVideoCandidate(value) : cached.asBoolean(false);
        }
        return false;
    }

    private boolean hasVideoCandidate(JsonNode node) {
        JsonNode files = node.path("files");
        if (files.isArray()) {
            for (JsonNode file : files) {
                String name = firstText(file, "name", "short_name", "path");
                if (name == null || isVideo(name)) {
                    return true;
                }
            }
        }
        String name = firstText(node, "name", "short_name", "path", "filename");
        return name == null || isVideo(name);
    }

    private Torrent torrent(long torrentId) {
        URI uri = endpoint("/v1/api/torrents/mylist?id=" + torrentId + "&bypass_cache=true");
        JsonNode response = sendJson(authenticated(uri).GET().build());
        JsonNode data = response.path("data");
        JsonNode value = data.isArray() ? data.path(0) : data;
        if (!value.isObject()) {
            throw ApiException.serviceUnavailable("TorBox ne trouve pas le torrent ajoute");
        }

        String state = firstText(value, "download_state", "downloadState", "state", "status");
        List<TorrentFile> files = new ArrayList<>();
        JsonNode fileNodes = value.path("files");
        if (fileNodes.isArray()) {
            for (JsonNode file : fileNodes) {
                long id = firstLong(file, "id", "file_id", "fileId");
                String name = firstText(file, "name", "short_name", "path");
                long size = firstLong(file, "size", "bytes");
                if (id >= 0 && name != null) {
                    files.add(new TorrentFile(id, name, size));
                }
            }
        }
        return new Torrent(state, files);
    }

    private String requestDownload(long torrentId, long fileId) {
        ApiException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return requestDownloadOnce(torrentId, fileId);
            } catch (ApiException exception) {
                lastFailure = exception;
                if (attempt < 2) {
                    pause();
                }
            }
        }
        throw lastFailure == null
                ? ApiException.serviceUnavailable("TorBox n'a pas retourne de lien de telechargement")
                : lastFailure;
    }

    private String requestDownloadOnce(long torrentId, long fileId) {
        String query = "token=" + encode(apiToken)
                + "&torrent_id=" + torrentId
                + "&file_id=" + fileId
                + "&zip_link=false&redirect=false";
        JsonNode response = sendJson(
                authenticated(endpoint("/v1/api/torrents/requestdl?" + query)).GET().build()
        );
        JsonNode data = response.path("data");
        String url = data.isTextual()
                ? data.asText()
                : firstText(data, "url", "download_url", "link");
        if (url == null) {
            url = firstText(response, "url", "download_url", "link");
        }
        return validatedDownloadUrl(url);
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream input = response.body()) {
                JsonNode body = mapper.readTree(readLimited(input));
                boolean success = !body.has("success") || body.path("success").asBoolean();
                if (response.statusCode() < 200 || response.statusCode() >= 300 || !success) {
                    String detail = firstText(body, "detail", "error", "message");
                    throw torBoxFailure(detail);
                }
                return body;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Communication avec TorBox interrompue");
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("Impossible de joindre TorBox");
        }
    }

    private ApiException torBoxFailure(String detail) {
        if (detail != null && isPlanRestriction(detail)) {
            return ApiException.paymentRequired(
                    "TorBox refuse la resolution torrent: le jeton configure n'a pas acces a l'API. "
                            + "Passez le compte TorBox sur un forfait avec API ou utilisez un flux direct."
            );
        }
        return ApiException.serviceUnavailable(
                detail == null ? "TorBox a refuse la requete" : "TorBox: " + detail
        );
    }

    private boolean isPlanRestriction(String detail) {
        String normalized = detail.toLowerCase(Locale.ROOT);
        return normalized.contains("api feature not available")
                || normalized.contains("not available on your plan")
                || normalized.contains("upgrade to a paid plan")
                || normalized.contains("paid plan to access the api");
    }

    private HttpRequest.Builder authenticated(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent", "Nexora-TorBox/1.0");
    }

    private Optional<TorrentFile> selectVideo(List<TorrentFile> files) {
        return files.stream()
                .filter(file -> isVideo(file.name()))
                .max(Comparator.comparingLong(TorrentFile::size));
    }

    private boolean isVideo(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }

    private String magnet(String hash, JsonNode sources) {
        StringBuilder value = new StringBuilder("magnet:?xt=urn:btih:").append(hash);
        Set<String> trackers = new LinkedHashSet<>();
        if (sources.isArray()) {
            for (JsonNode source : sources) {
                String candidate = source.asText("");
                if (candidate.startsWith("tracker:")) {
                    trackers.add(candidate.substring("tracker:".length()));
                }
            }
        }
        trackers.forEach(tracker -> value.append("&tr=").append(encode(tracker)));
        return value.toString();
    }

    private String validatedDownloadUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || !hostAllowed(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApiException.serviceUnavailable("TorBox a retourne une URL de telechargement non autorisee");
        }
    }

    private boolean hostAllowed(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedDownloadHosts.stream().anyMatch(candidate -> candidate.startsWith(".")
                ? normalized.endsWith(candidate) && normalized.length() > candidate.length()
                : normalized.equals(candidate));
    }

    private URI endpoint(String pathAndQuery) {
        return baseUri.resolve(pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery);
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Attente TorBox interrompue");
        }
    }

    private byte[] multipart(String boundary, String magnet) {
        String separator = "--" + boundary + "\r\n";
        String body = separator
                + "Content-Disposition: form-data; name=\"magnet\"\r\n\r\n"
                + magnet + "\r\n"
                + separator
                + "Content-Disposition: form-data; name=\"allow_zip\"\r\n\r\n"
                + "false\r\n"
                + separator
                + "Content-Disposition: form-data; name=\"add_only_if_cached\"\r\n\r\n"
                + "false\r\n"
                + "--" + boundary + "--\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > 2_097_152) {
                throw new IOException("TorBox response too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private long firstLong(JsonNode value, String... fields) {
        for (String field : fields) {
            JsonNode candidate = value.path(field);
            if (candidate.canConvertToLong()) {
                return candidate.asLong();
            }
            if (candidate.isTextual()) {
                try {
                    return Long.parseLong(candidate.asText());
                } catch (NumberFormatException ignored) {
                    // Continue with the next known field.
                }
            }
        }
        return -1;
    }

    private String firstText(JsonNode value, String... fields) {
        for (String field : fields) {
            JsonNode candidate = value.path(field);
            if (candidate.isValueNode() && !candidate.asText().isBlank()) {
                return candidate.asText().strip();
            }
        }
        return null;
    }

    private JsonNode firstExisting(JsonNode value, String... fields) {
        for (String field : fields) {
            JsonNode candidate = value.path(field);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                return candidate;
            }
        }
        return mapper.missingNode();
    }

    private String validHash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.matches("(?i)^[a-f0-9]{40}$") || normalized.matches("(?i)^[a-z2-7]{32}$")
                ? normalized
                : null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI normalizedBaseUri(String value) {
        URI uri = URI.create(value == null ? "" : value.strip());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid TorBox base URL");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    private static Set<String> parseHosts(String value) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String raw : String.valueOf(value == null ? "" : value).split("[,\\s]+")) {
            String host = raw.strip().toLowerCase(Locale.ROOT);
            if (host.startsWith("*.")) {
                host = "." + host.substring(2);
            }
            if (host.matches("^\\.?[a-z0-9.-]+$") && !host.isBlank()) {
                hosts.add(host);
            }
        }
        if (hosts.isEmpty()) {
            hosts.add(".torbox.app");
            hosts.add(".tb-cdn.io");
        }
        return Set.copyOf(hosts);
    }

    private record Torrent(String state, List<TorrentFile> files) {
        boolean ready() {
            if (state == null || state.isBlank()) {
                return !files.isEmpty();
            }
            String normalized = state.toLowerCase(Locale.ROOT);
            return normalized.contains("complete")
                    || normalized.contains("cached")
                    || normalized.contains("upload")
                    || normalized.contains("seed");
        }
    }

    private record TorrentFile(long id, String name, long size) {
    }

    private record CachedLink(String url, Instant expiresAt) {
    }

    public record TorrentAvailability(boolean ready, String reason) {
    }
}
