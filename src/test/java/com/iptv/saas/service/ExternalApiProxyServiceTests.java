package com.iptv.saas.service;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalApiProxyServiceTests {
    private final ExternalApiProxyService proxy = new ExternalApiProxyService(
            "http://127.0.0.1:8787/",
            "http://127.0.0.1:5001/",
            Duration.ofSeconds(30),
            1024 * 1024,
            HttpClient.newHttpClient()
    );

    @Test
    void mapsContentPathAndPreservesEncodedQuery() {
        assertEquals(
                "http://127.0.0.1:8787/api/content?provider=french-stream&q=Star%20Trek",
                proxy.upstreamUri(
                        ExternalApiProxyService.Target.CONTENT,
                        "/api/external/content/api/content",
                        "provider=french-stream&q=Star%20Trek"
                ).toString()
        );
    }

    @Test
    void mapsAnimePathToDedicatedLoopbackService() {
        assertEquals(
                "http://127.0.0.1:5001/api/v1/catalogues?limit=48",
                proxy.upstreamUri(
                        ExternalApiProxyService.Target.ANIME,
                        "/api/external/anime/api/v1/catalogues",
                        "limit=48"
                ).toString()
        );
    }

    @Test
    void keepsConfiguredFallbackBaseUrlsForUnavailableLoopbackServices() {
        ExternalApiProxyService fallbackProxy = new ExternalApiProxyService(
                "http://127.0.0.1:8787/",
                "http://127.0.0.1:5001/",
                "https://content.nexoragabon.com/",
                "https://api.nexoragabon.com/anime-api/",
                Duration.ofSeconds(30),
                1024 * 1024,
                HttpClient.newHttpClient()
        );

        assertEquals(
                List.of(
                        "http://127.0.0.1:8787/api/content?provider=french-stream&q=Matrix",
                        "https://content.nexoragabon.com/api/content?provider=french-stream&q=Matrix"
                ),
                fallbackProxy.upstreamUris(
                                ExternalApiProxyService.Target.CONTENT,
                                "/api/external/content/api/content",
                                "provider=french-stream&q=Matrix"
                        )
                        .stream()
                        .map(Object::toString)
                        .toList()
        );
        assertEquals(
                List.of(
                        "http://127.0.0.1:5001/api/v1/search?q=the&limit=12",
                        "https://api.nexoragabon.com/anime-api/api/v1/search?q=the&limit=12"
                ),
                fallbackProxy.upstreamUris(
                                ExternalApiProxyService.Target.ANIME,
                                "/api/external/anime/api/v1/search",
                                "q=the&limit=12"
                        )
                        .stream()
                        .map(Object::toString)
                        .toList()
        );
    }

    @Test
    void rejectsPathFromAnotherProxyTarget() {
        assertThrows(IllegalArgumentException.class, () -> proxy.upstreamUri(
                ExternalApiProxyService.Target.CONTENT,
                "/api/external/anime/health",
                null
        ));
    }
}
