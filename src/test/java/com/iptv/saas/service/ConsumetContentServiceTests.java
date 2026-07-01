package com.iptv.saas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumetContentServiceTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsMovieCatalogInfoAndStreamingHeaders() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        ConsumetContentService service = new ConsumetContentService(
                new ObjectMapper(),
                new CatalogImageService(1_048_576, 16),
                true,
                root,
                "flixhq",
                "hianime",
                "vidcloud",
                "vidstreaming",
                "sub",
                "standard",
                "zoro",
                "hd-1",
                "https://vidnest.test",
                "https://embed.test/ani",
                5,
                1_048_576,
                ".cdn.example"
        );

        List<Map<String, Object>> items = service.items("movie", null, null, null, "title-asc", 10);

        assertEquals(1, items.size());
        String itemId = String.valueOf(items.get(0).get("id"));
        assertTrue(itemId.startsWith("consumet~movies~flixhq~movie~"));
        assertEquals("movie", items.get(0).get("type"));
        assertEquals("consumet-movie-flixhq", items.get(0).get("categoryId"));
        assertTrue(service.items("live", null, null, null, "title-asc", 10).isEmpty());

        List<Map<String, Object>> frenchItems = service.items("movie", null, null, "vf", "title-asc", 10);
        assertEquals(1, frenchItems.size());
        assertEquals("fr", frenchItems.get(0).get("language"));
        assertEquals("Francais / VF", frenchItems.get(0).get("languageName"));
        assertTrue(service.items("movie", null, null, "en", "title-asc", 10).isEmpty());
        assertEquals("fr", service.languages("movie").get(0).get("id"));

        Map<String, Object> info = service.itemInfo(itemId);
        assertEquals("Demo Movie", info.get("name"));
        assertEquals(true, info.get("streamAvailable"));

        ConsumetContentService.StreamResolution stream = service.selectStream(itemId);
        assertEquals("https://video.cdn.example/master.m3u8", stream.streamUrl());
        assertEquals("https://flix.example/", stream.headers().get("Referer"));
        assertTrue(service.isAllowedStreamHostForPlayback(itemId, "video.cdn.example"));
        assertNotNull(info.get("genres"));
    }

    @Test
    void mapsAnilistCatalogAndEmbedPlayback() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handleAnilistRequest);
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        ConsumetContentService service = new ConsumetContentService(
                new ObjectMapper(),
                new CatalogImageService(1_048_576, 16),
                true,
                root,
                "flixhq",
                "hianime",
                "vidcloud",
                "vidstreaming",
                "sub",
                "anilist",
                "zoro",
                "hd-1",
                "https://vidnest.test",
                "https://embed.test/ani",
                5,
                1_048_576,
                ""
        );

        List<Map<String, Object>> items = service.items("series", null, null, null, "title-asc", 10);

        assertEquals(1, items.size());
        String itemId = String.valueOf(items.get(0).get("id"));
        assertTrue(itemId.startsWith("consumet~anime~anilist~series~"));
        assertEquals("Naruto", items.get(0).get("name"));

        Map<String, Object> series = service.seriesInfo(itemId, null);
        assertEquals(3, series.get("episodeCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) series.get("seasons");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes = (List<Map<String, Object>>) seasons.get(0).get("episodes");
        String episodeId = String.valueOf(episodes.get(1).get("id"));

        ConsumetContentService.StreamResolution stream = service.selectStream(episodeId);
        assertEquals("https://embed.test/ani/20/2/sub", stream.streamUrl());
        assertTrue(service.isEmbedPlayback(episodeId, stream.streamUrl()));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/movies/flixhq/recent-movies".equals(path)) {
            json(exchange, """
                    [
                      {
                        "id": "movie-1",
                        "title": "Demo Movie",
                        "image": "https://img.example/poster.jpg",
                        "releaseDate": "2026",
                        "duration": "90m",
                        "type": "Movie"
                      }
                    ]
                    """);
            return;
        }
        if ("/movies/flixhq/info".equals(path)) {
            json(exchange, """
                    {
                      "id": "movie-1",
                      "title": "Demo Movie",
                      "image": "https://img.example/poster.jpg",
                      "description": "A mapped movie.",
                      "geners": ["Action"],
                      "type": "Movie",
                      "duration": "90m",
                      "episodes": [
                        { "id": "episode-1", "title": "Demo Movie", "number": 1, "season": 1 }
                      ]
                    }
                    """);
            return;
        }
        if ("/movies/flixhq/watch".equals(path)) {
            json(exchange, """
                    {
                      "headers": {
                        "Referer": "https://flix.example/",
                        "User-Agent": "Consumet Test"
                      },
                      "sources": [
                        {
                          "url": "https://video.cdn.example/master.m3u8",
                          "quality": "auto",
                          "isM3U8": true
                        }
                      ]
                    }
                    """);
            return;
        }
        if ("/anime/hianime/movie".equals(path)) {
            json(exchange, """
                    { "currentPage": 1, "hasNextPage": false, "results": [] }
                    """);
            return;
        }
        exchange.sendResponseHeaders(404, 0);
        exchange.close();
    }

    private void handleAnilistRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/meta/anilist/advanced-search".equals(path)) {
            json(exchange, """
                    {
                      "currentPage": 1,
                      "hasNextPage": false,
                      "results": [
                        {
                          "id": "20",
                          "title": { "english": "Naruto", "romaji": "NARUTO" },
                          "image": "https://img.example/naruto.jpg",
                          "cover": "https://img.example/naruto-cover.jpg",
                          "releaseDate": 2002,
                          "type": "TV",
                          "totalEpisodes": 3
                        }
                      ]
                    }
                    """);
            return;
        }
        if ("/meta/anilist/trending".equals(path)) {
            json(exchange, """
                    {
                      "currentPage": 1,
                      "hasNextPage": false,
                      "results": [
                        {
                          "id": "20",
                          "title": { "english": "Naruto", "romaji": "NARUTO" },
                          "image": "https://img.example/naruto.jpg",
                          "cover": "https://img.example/naruto-cover.jpg",
                          "releaseDate": 2002,
                          "type": "TV",
                          "totalEpisodes": 3
                        }
                      ]
                    }
                    """);
            return;
        }
        if ("/meta/anilist/popular".equals(path)) {
            json(exchange, """
                    { "currentPage": 1, "hasNextPage": false, "results": [] }
                    """);
            return;
        }
        if ("/meta/anilist/info/20".equals(path)) {
            json(exchange, """
                    {
                      "id": "20",
                      "title": { "english": "Naruto", "romaji": "NARUTO" },
                      "image": "https://img.example/naruto.jpg",
                      "cover": "https://img.example/naruto-cover.jpg",
                      "description": "A ninja story.",
                      "releaseDate": 2002,
                      "type": "TV",
                      "currentEpisode": 3,
                      "totalEpisodes": 3,
                      "genres": ["Action"]
                    }
                    """);
            return;
        }
        exchange.sendResponseHeaders(404, 0);
        exchange.close();
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
