package com.iptv.saas.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ExternalApiProxyService {
    public enum Target {
        CONTENT("/api/external/content"),
        ANIME("/api/external/anime");

        private final String publicPrefix;

        Target(String publicPrefix) {
            this.publicPrefix = publicPrefix;
        }
    }

    public record ProxyResponse(int status, String contentType, String cacheControl, byte[] body) {
    }

    private final List<URI> contentBaseUrls;
    private final List<URI> animeBaseUrls;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final HttpClient httpClient;

    @Autowired
    public ExternalApiProxyService(
            @Value("${app.external-api-proxy.content-base-url:http://127.0.0.1:8787}") String contentBaseUrl,
            @Value("${app.external-api-proxy.anime-base-url:http://127.0.0.1:5001}") String animeBaseUrl,
            @Value("${app.external-api-proxy.content-fallback-base-url:${CONTENT_NEXORA_API_BASE_URL:}}") String contentFallbackBaseUrl,
            @Value("${app.external-api-proxy.anime-fallback-base-url:${ANIME_NEXORA_BASE_URL:${CONSUMET_BASE_URL:}}}") String animeFallbackBaseUrl,
            @Value("${app.external-api-proxy.timeout-seconds:45}") long timeoutSeconds,
            @Value("${app.external-api-proxy.max-response-bytes:8388608}") int maxResponseBytes
    ) {
        this(
                contentBaseUrl,
                animeBaseUrl,
                contentFallbackBaseUrl,
                animeFallbackBaseUrl,
                Duration.ofSeconds(Math.max(5, timeoutSeconds)),
                Math.max(65_536, maxResponseBytes),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.min(10, Math.max(3, timeoutSeconds))))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    ExternalApiProxyService(
            String contentBaseUrl,
            String animeBaseUrl,
            Duration requestTimeout,
            int maxResponseBytes,
            HttpClient httpClient
    ) {
        this(contentBaseUrl, animeBaseUrl, "", "", requestTimeout, maxResponseBytes, httpClient);
    }

    ExternalApiProxyService(
            String contentBaseUrl,
            String animeBaseUrl,
            String contentFallbackBaseUrl,
            String animeFallbackBaseUrl,
            Duration requestTimeout,
            int maxResponseBytes,
            HttpClient httpClient
    ) {
        this.contentBaseUrls = baseUris(contentBaseUrl, contentFallbackBaseUrl);
        this.animeBaseUrls = baseUris(animeBaseUrl, animeFallbackBaseUrl);
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.httpClient = httpClient;
    }

    public ProxyResponse forward(
            Target target,
            String requestPath,
            String queryString,
            String method,
            String accept,
            String contentType,
            byte[] body
    ) throws IOException, InterruptedException {
        String normalizedMethod = String.valueOf(method).toUpperCase(Locale.ROOT);
        if (!normalizedMethod.equals("GET")
                && !normalizedMethod.equals("HEAD")
                && !normalizedMethod.equals("POST")) {
            throw new IllegalArgumentException("Methode de relais non autorisee");
        }

        IOException lastException = null;
        List<URI> baseUrls = target == Target.CONTENT ? contentBaseUrls : animeBaseUrls;
        for (URI baseUrl : baseUrls) {
            HttpRequest.BodyPublisher publisher = normalizedMethod.equals("POST")
                    ? HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body)
                    : HttpRequest.BodyPublishers.noBody();
            HttpRequest.Builder request = HttpRequest.newBuilder(upstreamUri(target, baseUrl, requestPath, queryString))
                    .timeout(requestTimeout)
                    .method(normalizedMethod, publisher)
                    .header("Accept", accept == null || accept.isBlank() ? "application/json" : accept)
                    .header("User-Agent", "Nexora-Internal-Proxy/1.0");
            if (contentType != null && !contentType.isBlank()) {
                request.header("Content-Type", contentType);
            }

            try {
                HttpResponse<byte[]> response = httpClient.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                );
                byte[] responseBody = response.body() == null ? new byte[0] : response.body();
                if (responseBody.length > maxResponseBytes) {
                    throw new IOException("Reponse du service interne trop volumineuse");
                }
                return new ProxyResponse(
                        response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse("application/json"),
                        response.headers().firstValue("Cache-Control").orElse("no-store"),
                        responseBody
                );
            } catch (IOException exception) {
                lastException = exception;
            }
        }
        throw lastException == null ? new IOException("Service interne indisponible") : lastException;
    }

    URI upstreamUri(Target target, String requestPath, String queryString) {
        List<URI> baseUrls = target == Target.CONTENT ? contentBaseUrls : animeBaseUrls;
        return upstreamUri(target, baseUrls.get(0), requestPath, queryString);
    }

    URI upstreamUri(Target target, URI baseUrl, String requestPath, String queryString) {
        String path = requestPath == null ? "" : requestPath;
        if (!path.startsWith(target.publicPrefix)) {
            throw new IllegalArgumentException("Chemin de relais invalide");
        }
        String suffix = path.substring(target.publicPrefix.length());
        if (suffix.isBlank()) {
            suffix = "/";
        } else if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        String query = queryString == null || queryString.isBlank() ? "" : "?" + queryString;
        return URI.create(baseUrl.toString() + suffix + query);
    }

    List<URI> upstreamUris(Target target, String requestPath, String queryString) {
        List<URI> baseUrls = target == Target.CONTENT ? contentBaseUrls : animeBaseUrls;
        return baseUrls.stream()
                .map(baseUrl -> upstreamUri(target, baseUrl, requestPath, queryString))
                .toList();
    }

    private static List<URI> baseUris(String primary, String fallback) {
        List<URI> values = new ArrayList<>();
        addBaseUri(values, primary);
        addBaseUri(values, fallback);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("URL de service interne invalide");
        }
        return List.copyOf(values);
    }

    private static void addBaseUri(List<URI> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        URI uri = baseUri(value);
        if (!values.contains(uri)) {
            values.add(uri);
        }
    }

    private static URI baseUri(String value) {
        String normalized = String.valueOf(value).strip().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        if (uri.getScheme() == null || !uri.getScheme().matches("https?")) {
            throw new IllegalArgumentException("URL de service interne invalide");
        }
        return uri;
    }
}
