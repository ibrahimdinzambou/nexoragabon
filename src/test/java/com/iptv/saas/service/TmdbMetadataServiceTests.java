package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbMetadataServiceTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchesWithBearerAuthenticationWhenTokenIsConfigured() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/search/multi", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            json(exchange, """
                    {"page":2,"results":[{"id":603,"media_type":"movie","title":"The Matrix"}]}
                    """);
        });
        server.start();

        TmdbMetadataService service = service(root(), "legacy-key", "read-token");

        JsonNode response = service.searchMulti("matrix", 2);

        assertEquals("Bearer read-token", authorization.get());
        assertFalse(query.get().contains("api_key="));
        assertTrue(query.get().contains("query=matrix"));
        assertTrue(query.get().contains("language=fr-FR"));
        assertTrue(query.get().contains("include_adult=false"));
        assertEquals(603, response.path("results").path(0).path("id").asInt());
    }

    @Test
    void fallsBackToApiKeyWhenBearerTokenIsMissing() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/movie/11", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            json(exchange, """
                    {"id":11,"title":"Star Wars"}
                    """);
        });
        server.start();

        TmdbMetadataService service = service(root(), "api-key", "");

        JsonNode response = service.details("movie", 11, "images,videos");

        assertEquals(null, authorization.get());
        assertTrue(query.get().contains("api_key=api-key"));
        assertTrue(query.get().contains("append_to_response=images%2Cvideos")
                || query.get().contains("append_to_response=images,videos"));
        assertEquals("Star Wars", response.path("title").asText());
    }

    @Test
    void exposesStatusWithoutSecrets() {
        TmdbMetadataService service = service("https://api.themoviedb.org/3", "api-key", "read-token");

        Map<String, Object> status = service.status();

        assertEquals(true, status.get("configured"));
        assertEquals("TMDB", status.get("provider"));
        assertEquals("bearer", status.get("authentication"));
        assertFalse(status.toString().contains("api-key"));
        assertFalse(status.toString().contains("read-token"));
    }

    @Test
    void rejectsUnsupportedMediaTypes() {
        TmdbMetadataService service = service("https://api.themoviedb.org/3", "api-key", "");

        ApiException exception = assertThrows(ApiException.class, () -> service.details("episode", 1));

        assertEquals(422, exception.status().value());
    }

    @Test
    void buildsImageUrlsFromTmdbPaths() {
        TmdbMetadataService service = service("https://api.themoviedb.org/3", "api-key", "");

        assertEquals(
                "https://image.tmdb.org/t/p/w500/poster.jpg",
                service.imageUrl("/poster.jpg", "w500")
        );
    }

    private TmdbMetadataService service(String baseUrl, String apiKey, String readAccessToken) {
        return new TmdbMetadataService(
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                baseUrl,
                "https://image.tmdb.org/t/p",
                apiKey,
                readAccessToken,
                "fr-FR",
                "FR",
                false,
                2,
                1_048_576
        );
    }

    private String root() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
