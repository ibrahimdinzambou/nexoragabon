package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.ConsumetContentService;
import com.iptv.saas.service.EpornerContentService;
import com.iptv.saas.service.StreamRelayService;
import com.iptv.saas.service.StreamRequestHeaders;
import com.iptv.saas.service.StreamingService;
import com.iptv.saas.service.VlcRemuxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
public class StreamController {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamController.class);
    private static final int HLS_PREFLIGHT_MAX_BYTES = 1_048_576;
    private static final int STREAM_FAILOVER_ATTEMPTS = 3;

    private final StreamingService streams;
    private final StreamRelayService relay;
    private final VlcRemuxService remux;
    private final CommunityAddonService addons;
    private final ConsumetContentService consumet;
    private final EpornerContentService eporner;

    public StreamController(
            StreamingService streams,
            StreamRelayService relay,
            VlcRemuxService remux,
            CommunityAddonService addons
    ) {
        this(streams, relay, remux, addons, null, null);
    }

    public StreamController(
            StreamingService streams,
            StreamRelayService relay,
            VlcRemuxService remux,
            CommunityAddonService addons,
            ConsumetContentService consumet
    ) {
        this(streams, relay, remux, addons, consumet, null);
    }

    @Autowired
    public StreamController(
            StreamingService streams,
            StreamRelayService relay,
            VlcRemuxService remux,
            CommunityAddonService addons,
            ConsumetContentService consumet,
            EpornerContentService eporner
    ) {
        this.streams = streams;
        this.relay = relay;
        this.remux = remux;
        this.addons = addons;
        this.consumet = consumet;
        this.eporner = eporner;
    }

    @PostMapping("/api/stream/open")
    public Object open(@Valid @RequestBody OpenRequest request) {
        var session = streams.open(
                SecurityUtils.currentUser(),
                request.type(),
                request.itemId(),
                request.quality()
        );
        return Responses.ok(sessionPayload(session));
    }

    @PostMapping("/api/stream/open/{channelId}")
    public Object openLegacy(@PathVariable String channelId) {
        var session = streams.open(SecurityUtils.currentUser(), "live", channelId);
        return Responses.ok(sessionPayload(session));
    }

    @GetMapping("/api/stream/url/{sessionToken}")
    public Object url(@PathVariable String sessionToken) {
        return Responses.ok(sessionPayload(streams.getActiveByToken(sessionToken)));
    }

    @PostMapping("/api/stream/heartbeat/{sessionToken}")
    public Object heartbeat(@PathVariable String sessionToken) {
        return Responses.ok(ApiMappers.session(streams.heartbeat(sessionToken)));
    }

    @PostMapping("/api/stream/failover/{sessionToken}")
    public Object failover(@PathVariable String sessionToken) {
        return Responses.ok(sessionPayload(streams.failover(streams.getActiveByToken(sessionToken))));
    }

    @PostMapping("/api/stream/preflight/{sessionToken}")
    public Object preflight(@PathVariable String sessionToken) {
        return Responses.ok(preflightPayload(streams.getActiveByToken(sessionToken)));
    }

    @PostMapping("/api/stream/quality/{sessionToken}")
    public Object quality(@PathVariable String sessionToken, @RequestBody QualityRequest request) {
        return Responses.ok(sessionPayload(streams.changeQuality(
                SecurityUtils.currentUser(),
                sessionToken,
                request.quality()
        )));
    }

    @DeleteMapping("/api/stream/close/{sessionToken}")
    public Object close(@PathVariable String sessionToken) {
        return Responses.ok(ApiMappers.session(streams.close(SecurityUtils.currentUser(), sessionToken)));
    }

    @GetMapping("/api/stream/proxy/{sessionToken}")
    public ResponseEntity<StreamingResponseBody> proxy(
            @PathVariable String sessionToken,
            HttpServletRequest request
    ) {
        var session = streams.getActiveByToken(sessionToken);
        ApiException originalFailure = null;
        Set<Long> failedAccountIds = new LinkedHashSet<>();
        for (int attempt = 0; attempt < STREAM_FAILOVER_ATTEMPTS; attempt++) {
            try {
                return proxyResponse(session, request);
            } catch (ApiException failure) {
                if (originalFailure == null) {
                    originalFailure = failure;
                } else {
                    originalFailure.addSuppressed(failure);
                }
                if (session.iptvAccount == null) {
                    throw originalFailure;
                }
                if (session.iptvAccount.id != null) {
                    failedAccountIds.add(session.iptvAccount.id);
                }
                logStreamFailure("proxy", session, failure);
                if (!canFailover(session)) {
                    throw originalFailure;
                }
                if (attempt + 1 >= STREAM_FAILOVER_ATTEMPTS) {
                    throw originalFailure;
                }
                try {
                    session = streams.failover(session, failedAccountIds, failure.code());
                } catch (ApiException failoverFailure) {
                    originalFailure.addSuppressed(failoverFailure);
                    throw originalFailure;
                }
            }
        }
        throw originalFailure == null
                ? ApiException.serviceUnavailable("Flux indisponible")
                : originalFailure;
    }

    @GetMapping("/api/stream/hls/{sessionToken}")
    public ResponseEntity<StreamingResponseBody> hls(
            @PathVariable String sessionToken,
            @RequestParam("u") String encodedUrl,
            HttpServletRequest request
    ) {
        UserSession session = streams.getActiveByToken(sessionToken);
        String targetUrl = decodeHlsUrl(encodedUrl);
        requireAllowedHlsUrl(session, targetUrl);
        return directProxyResponse(session, targetUrl, request);
    }

    private Object preflightPayload(UserSession initialSession) {
        UserSession session = initialSession;
        if (isEmbedSession(session)) {
            return sessionPayload(session);
        }
        Map<String, Object> payload = sessionPayload(session);
        try {
            verifySessionReadable(session);
            payload.put("preflightOk", true);
        } catch (ApiException failure) {
            logStreamFailure("preflight", session, failure);
            payload.put("preflightOk", false);
            payload.put("preflightCode", failure.code());
            payload.put("preflightMessage", failure.getMessage());
        }
        return payload;
    }

    private void verifySessionReadable(UserSession session) {
        if (isEmbedSession(session)) {
            return;
        }
        String quality = effectiveQuality(session);
        if (remux.requiresProcessing(session.streamUrl, quality)) {
            try (var stream = remux.open(openRelayForRemux(session), quality)) {
                return;
            } catch (ApiException exception) {
                if (!remux.requiresRemux(session.streamUrl)
                        && remux.requiresTranscode(quality)
                        && likelyMpegTs(session.streamUrl)) {
                    verifyDirectReadable(session);
                    return;
                }
                throw exception;
            }
        }
        verifyDirectReadable(session);
    }

    private void verifyDirectReadable(UserSession session) {
        if (looksLikeHlsPlaylistUrl(session.streamUrl)) {
            verifyHlsReadable(session, session.streamUrl, 0);
            return;
        }
        verifyMediaUrlReadable(session, session.streamUrl);
    }

    private void verifyHlsReadable(UserSession session, String streamUrl, int depth) {
        var remote = openRelay(session, streamUrl, null);
        try (var ignored = remote.body()) {
            if (!isHlsPlaylist(streamUrl, remote.contentType())) {
                return;
            }
            String playlist = new String(ignored.readNBytes(HLS_PREFLIGHT_MAX_BYTES), StandardCharsets.UTF_8);
            String firstResource = firstHlsResource(playlist);
            if (firstResource == null || depth >= 2) {
                return;
            }
            String resolved = URI.create(streamUrl).resolve(firstResource).toString();
            requireAllowedHlsUrl(session, resolved);
            if (looksLikeHlsPlaylistUrl(resolved)) {
                verifyHlsReadable(session, resolved, depth + 1);
            } else {
                verifyMediaUrlReadable(session, resolved);
            }
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("Le fournisseur a interrompu la verification du flux");
        }
    }

    private void verifyMediaUrlReadable(UserSession session, String streamUrl) {
        var remote = openRelay(session, streamUrl, null);
        try (var ignored = remote.body()) {
            // relay.open already validates that the upstream sends at least one byte.
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("Le fournisseur a interrompu la verification du flux");
        }
    }

    private String firstHlsResource(String playlist) {
        for (String line : playlist.split("\\R")) {
            String value = line.strip();
            if (!value.isBlank() && !value.startsWith("#")) {
                return value;
            }
        }
        return null;
    }

    private ResponseEntity<StreamingResponseBody> proxyResponse(
            UserSession session,
            HttpServletRequest request
    ) {
        String quality = effectiveQuality(session);
        if (remux.requiresProcessing(session.streamUrl, quality)) {
            try {
                return remuxedResponse(remux.open(
                        openRelayForRemux(session),
                        quality
                ));
            } catch (ApiException exception) {
                if (!remux.requiresRemux(session.streamUrl)
                        && remux.requiresTranscode(quality)
                        && likelyMpegTs(session.streamUrl)) {
                    return directProxyResponse(session, request);
                }
                throw exception;
            }
        }

        return directProxyResponse(session, request);
    }

    private ResponseEntity<StreamingResponseBody> directProxyResponse(
            UserSession session,
            HttpServletRequest request
    ) {
        return directProxyResponse(session, session.streamUrl, request);
    }

    private ResponseEntity<StreamingResponseBody> directProxyResponse(
            UserSession session,
            String streamUrl,
            HttpServletRequest request
    ) {
        String requestedRange = looksLikeHlsPlaylistUrl(streamUrl) ? null : request.getHeader(HttpHeaders.RANGE);
        var remote = openRelay(session, streamUrl, requestedRange);
        if (isHlsPlaylist(streamUrl, remote.contentType())) {
            return rewrittenHlsResponse(session, streamUrl, remote);
        }

        StreamingResponseBody body = output -> {
            try (var input = remote.body()) {
                copyMedia(input, output);
            } catch (ClientAbortException exception) {
                // Seeking or closing the browser player aborts the current range request.
            } catch (IOException exception) {
                // The upstream or browser may close a media range before the proxy finishes writing.
            } catch (RuntimeException exception) {
                if (!isClientDisconnect(exception)) {
                    throw exception;
                }
            }
        };

        ResponseEntity.BodyBuilder response = ResponseEntity.status(remote.status())
                .contentType(mediaType(remote.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no");
        if (shouldExposeContentLength(remote)) {
            response.contentLength(remote.contentLength());
        }
        if (remote.contentRange() != null) {
            response.header(HttpHeaders.CONTENT_RANGE, remote.contentRange());
        }
        if (remote.acceptRanges() != null) {
            response.header(HttpHeaders.ACCEPT_RANGES, remote.acceptRanges());
        }
        return response.body(body);
    }

    private ResponseEntity<StreamingResponseBody> rewrittenHlsResponse(
            UserSession session,
            String playlistUrl,
            StreamRelayService.RelayResponse remote
    ) {
        StreamingResponseBody body = output -> {
            try (var input = remote.body()) {
                String playlist = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                output.write(rewriteHlsPlaylist(session, playlistUrl, playlist).getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (ClientAbortException exception) {
                // The browser replaced the HLS request before the playlist finished writing.
            } catch (IOException exception) {
                // The browser moved on while this HLS response was being written.
            } catch (RuntimeException exception) {
                if (!isClientDisconnect(exception)) {
                    throw exception;
                }
            }
        };
        return ResponseEntity.status(remote.status())
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> remuxedResponse(VlcRemuxService.RemuxStream stream) {
        StreamingResponseBody body = output -> {
            try (stream) {
                copyMedia(stream.body(), output);
            } catch (ClientAbortException exception) {
                // Closing or replacing the browser player stops the VLC remux process.
            } catch (IOException exception) {
                // The browser may abort while VLC is still writing the transport stream.
            } catch (RuntimeException exception) {
                if (!isClientDisconnect(exception)) {
                    throw exception;
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp2t"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private void copyMedia(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[131_072];
        int pendingFlush = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            pendingFlush += read;
            if (pendingFlush >= 524_288) {
                output.flush();
                pendingFlush = 0;
            }
        }
        output.flush();
    }

    private boolean isClientDisconnect(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ClientAbortException) {
                return true;
            }
            String className = current.getClass().getName();
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(Locale.ROOT);
            if (className.contains("AsyncRequestNotUsableException")
                    || message.contains("broken pipe")
                    || message.contains("connection reset")
                    || message.contains("connexion") && message.contains("abandonn")
                    || message.contains("connection") && message.contains("aborted")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean shouldExposeContentLength(StreamRelayService.RelayResponse remote) {
        return remote.contentLength() != null && remote.status() != 206;
    }

    private MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean isHlsPlaylist(String streamUrl, String contentType) {
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedType.contains("mpegurl") || normalizedType.contains("application/vnd.apple")) {
            return true;
        }
        return looksLikeHlsPlaylistUrl(streamUrl);
    }

    private boolean looksLikeHlsPlaylistUrl(String streamUrl) {
        try {
            return URI.create(streamUrl).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String rewriteHlsPlaylist(UserSession session, String playlistUrl, String playlist) {
        URI base = URI.create(playlistUrl);
        StringBuilder rewritten = new StringBuilder(playlist.length() + 256);
        for (String line : playlist.split("\\R", -1)) {
            if (line.isBlank()) {
                rewritten.append(line).append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                rewritten.append(rewriteHlsAttributeUris(session, base, line)).append('\n');
                continue;
            }
            rewritten.append(hlsProxyUrl(session, base.resolve(line.strip()).toString())).append('\n');
        }
        return rewritten.toString();
    }

    private String rewriteHlsAttributeUris(UserSession session, URI base, String line) {
        String marker = "URI=\"";
        int start = line.indexOf(marker);
        if (start < 0) {
            return line;
        }
        int valueStart = start + marker.length();
        int valueEnd = line.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return line;
        }
        String value = line.substring(valueStart, valueEnd);
        if (value.isBlank() || value.startsWith("data:")) {
            return line;
        }
        String replacement = hlsProxyUrl(session, base.resolve(value).toString());
        return line.substring(0, valueStart) + replacement + line.substring(valueEnd);
    }

    private String hlsProxyUrl(UserSession session, String targetUrl) {
        requireAllowedHlsUrl(session, targetUrl);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(targetUrl.getBytes(StandardCharsets.UTF_8));
        return absoluteApiUrl("/api/stream/hls/" + session.sessionToken + "?u=" + encoded);
    }

    private String decodeHlsUrl(String encodedUrl) {
        try {
            return new String(Base64.getUrlDecoder().decode(encodedUrl), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw ApiException.validation("URL HLS invalide");
        }
    }

    private void requireAllowedHlsUrl(UserSession session, String targetUrl) {
        String rejectedHost = null;
        try {
            URI sessionUri = URI.create(session.streamUrl);
            URI targetUri = URI.create(targetUrl);
            String sessionHost = sessionUri.getHost();
            String targetHost = targetUri.getHost();
            rejectedHost = targetHost;
            String targetScheme = targetUri.getScheme() == null
                    ? ""
                    : targetUri.getScheme().toLowerCase(Locale.ROOT);
            if (sessionHost == null
                    || targetHost == null
                    || !Set.of("http", "https").contains(targetScheme)) {
                throw new IllegalArgumentException();
            }
            if (!isAllowedHlsHost(sessionHost, targetHost)
                    && !addons.isAllowedStreamHostForPlayback(session.itemId, session.user, targetHost)
                    && !isAllowedConsumetStreamHost(session.itemId, targetHost)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw ApiException.forbidden(rejectedHost == null || rejectedHost.isBlank()
                    ? "URL HLS non autorisee pour cette session"
                    : "URL HLS non autorisee pour cette session: " + rejectedHost.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isAllowedHlsHost(String sessionHost, String targetHost) {
        String normalizedSessionHost = sessionHost.toLowerCase(Locale.ROOT);
        String normalizedTargetHost = targetHost.toLowerCase(Locale.ROOT);
        if (normalizedSessionHost.equals(normalizedTargetHost)
                || normalizedTargetHost.endsWith("." + normalizedSessionHost)
                || normalizedSessionHost.endsWith("." + normalizedTargetHost)) {
            return true;
        }
        String sessionDomain = parentDomain(normalizedSessionHost);
        String targetDomain = parentDomain(normalizedTargetHost);
        return !sessionDomain.isBlank() && sessionDomain.equals(targetDomain);
    }

    private StreamRelayService.RelayResponse openRelay(UserSession session, String streamUrl, String range) {
        Map<String, String> headers = relayHeaders(session);
        return headers.isEmpty()
                ? relay.open(streamUrl, range)
                : relay.open(streamUrl, range, headers);
    }

    private InputStream openRelayForRemux(UserSession session) {
        Map<String, String> headers = relayHeaders(session);
        return headers.isEmpty()
                ? relay.openForRemux(session.streamUrl)
                : relay.openForRemux(session.streamUrl, headers);
    }

    private Map<String, String> relayHeaders(UserSession session) {
        return StreamRequestHeaders.decode(session.streamHeaders);
    }

    private boolean isAllowedConsumetStreamHost(String itemId, String targetHost) {
        return consumet != null && consumet.isAllowedStreamHostForPlayback(itemId, targetHost);
    }

    private String parentDomain(String host) {
        String[] labels = host.split("\\.");
        if (labels.length < 2) {
            return "";
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private void logStreamFailure(String phase, UserSession session, ApiException failure) {
        Long accountId = session.iptvAccount == null ? null : session.iptvAccount.id;
        LOGGER.warn(
                "Lecture IPTV {} en echec: session={}, compte={}, item={}, code={}, message={}",
                phase,
                session.sessionToken,
                accountId,
                session.itemId,
                failure.code(),
                failure.getMessage()
        );
    }

    private Map<String, Object> sessionPayload(com.iptv.saas.domain.UserSession session) {
        String quality = effectiveQuality(session);
        boolean embed = isEmbedSession(session);
        var body = Responses.map();
        body.put("session", ApiMappers.session(session));
        body.put("token", session.sessionToken);
        body.put("proxyUrl", embed ? session.streamUrl : absoluteApiUrl("/api/stream/proxy/" + session.sessionToken));
        body.put("quality", quality);
        body.put("playbackMode", playbackMode(session, quality, embed));
        body.put("canFailover", canFailover(session));
        return body;
    }

    private boolean canFailover(UserSession session) {
        return session != null
                && session.iptvAccount != null
                && session.iptvAccount.assignedUser == null;
    }

    private String playbackMode(UserSession session, String quality, boolean embed) {
        if (embed) {
            return "embed";
        }
        if (looksLikeHlsPlaylistUrl(session.streamUrl)) {
            return "hls";
        }
        if ("live".equals(session.contentType)
                || remux.requiresProcessing(session.streamUrl, quality)
                || likelyMpegTs(session.streamUrl)) {
            return "mpegts";
        }
        return "native";
    }

    private String absoluteApiUrl(String path) {
        String normalizedPath = path == null || path.isBlank()
                ? "/"
                : path.startsWith("/") ? path : "/" + path;
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(normalizedPath)
                    .build()
                    .toUriString();
        } catch (IllegalStateException exception) {
            return normalizedPath;
        }
    }

    private boolean isEmbedSession(UserSession session) {
        return session != null
                && (consumet != null && consumet.isEmbedPlayback(session.itemId, session.streamUrl)
                || eporner != null && eporner.isEmbedPlayback(session.itemId, session.streamUrl));
    }

    private String effectiveQuality(com.iptv.saas.domain.UserSession session) {
        String quality = VlcRemuxService.normalizeQuality(session.playbackQuality);
        return remux.requiresTranscode(quality) && !remux.isAvailable()
                ? "auto"
                : quality;
    }

    private boolean likelyMpegTs(String streamUrl) {
        try {
            String path = java.net.URI.create(streamUrl).getPath().toLowerCase(java.util.Locale.ROOT);
            return path.endsWith(".ts")
                    || path.endsWith(".m2ts")
                    || path.endsWith(".mpegts")
                    || path.contains("/live/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record OpenRequest(String type, @NotBlank @Size(max = 8192) String itemId, String quality) {
    }

    public record QualityRequest(String quality) {
    }
}
