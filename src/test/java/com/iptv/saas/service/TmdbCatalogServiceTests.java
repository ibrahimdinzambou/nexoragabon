package com.iptv.saas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbCatalogServiceTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exposesTmdbCategoriesWhenConfigured() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        TmdbCatalogService service = service();

        List<Map<String, Object>> categories = service.categories("movie");

        assertFalse(categories.isEmpty());
        assertEquals("Content-Nexora", categories.get(0).get("source"));
        assertEquals("content-nexora", categories.get(0).get("sourceCode"));
        assertEquals("tmdb", categories.get(0).get("metadataProvider"));
        assertEquals("movie", categories.get(0).get("type"));
    }

    @Test
    void exposesKoreanDramaSeriesCategory() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        TmdbCatalogService service = service();

        List<Map<String, Object>> categories = service.categories("series");

        assertTrue(categories.stream().anyMatch(category ->
                "tmdb-series-korean-drama".equals(category.get("id"))
                        && "Drama coreens".equals(category.get("name"))
        ));
    }

    @Test
    void loadsMovieCategoryItemsWithContentPrimaryAndVideasyFallback() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/movie/popular", exchange -> json(exchange, """
                {"page":1,"results":[
                  {
                    "id":603,
                    "title":"The Matrix",
                    "overview":"A hacker discovers reality.",
                    "poster_path":"/matrix.jpg",
                    "backdrop_path":"/matrix-backdrop.jpg",
                    "release_date":"1999-03-31",
                    "vote_average":8.2
                  }
                ]}
                """));
        server.start();
        TmdbCatalogService service = service();

        List<Map<String, Object>> items = service.items(
                "movie",
                null,
                "tmdb-movie-popular",
                null,
                "default",
                10
        );

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("tmdb~movie~603", item.get("id"));
        assertEquals("The Matrix", item.get("name"));
        assertEquals("Content-Nexora", item.get("source"));
        assertEquals("content-nexora", item.get("sourceCode"));
        assertEquals("tmdb", item.get("metadataProvider"));
        assertEquals(true, item.get("streamAvailable"));
        assertEquals(true, item.get("externalPlayback"));
        assertEquals("content-nexora", item.get("playbackProvider"));
        assertEquals("videasy", item.get("fallbackPlaybackProvider"));
        assertEquals(List.of("content-nexora", "videasy"), item.get("availablePlaybackProviders"));
        assertEquals("/matrix.jpg", item.get("tmdbPosterPath"));
        assertTrue(String.valueOf(item.get("categoryName")).contains("FR"));
    }

    @Test
    void loadsAdditionalTmdbPagesWhenTheCatalogLimitIncreases() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger highestPage = new AtomicInteger();
        server.createContext("/trending/movie/week", exchange -> pagedResults(exchange, 10_000, highestPage));
        server.createContext("/movie/popular", exchange -> pagedResults(exchange, 20_000, highestPage));
        server.createContext("/movie/now_playing", exchange -> pagedResults(exchange, 30_000, highestPage));
        server.createContext("/movie/top_rated", exchange -> pagedResults(exchange, 40_000, highestPage));
        server.createContext("/movie/upcoming", exchange -> pagedResults(exchange, 50_000, highestPage));
        server.start();
        TmdbCatalogService service = service();

        List<Map<String, Object>> items = service.items(
                "movie",
                null,
                null,
                null,
                "default",
                120
        );

        assertEquals(120, items.size());
        assertEquals(2, highestPage.get());
    }

    @Test
    void searchExtendsResultsWithoutApplyingBrowseCategoryOrLanguage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/search/movie", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getQuery());
            json(exchange, """
                    {"page":1,"results":[
                      {"id":603,"title":"The Matrix","release_date":"1999-03-31"}
                    ]}
                    """);
        });
        server.start();
        TmdbCatalogService service = service();

        List<Map<String, Object>> items = service.items(
                "movie",
                "matrix",
                "tmdb-movie-upcoming",
                "en",
                "default",
                10
        );

        assertEquals("/search/movie", path.get());
        assertTrue(query.get().contains("query=matrix"));
        assertEquals(1, items.size());
        assertEquals("tmdb~movie~603", items.get(0).get("id"));
    }

    @Test
    void loadsKoreanDramaDiscoveryWithTmdbPlayableSeries() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/discover/tv", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            json(exchange, """
                    {"page":1,"results":[
                      {
                        "id":96102,
                        "name":"Crash Landing on You",
                        "overview":"A Korean drama.",
                        "poster_path":"/cloy.jpg",
                        "first_air_date":"2019-12-14",
                        "vote_average":8.5
                      }
                    ]}
                    """);
        });
        server.start();
        TmdbCatalogService service = service();

        List<Map<String, Object>> items = service.items(
                "series",
                null,
                "tmdb-series-korean-drama",
                null,
                "default",
                10
        );

        assertTrue(query.get().contains("with_origin_country=KR"));
        assertTrue(query.get().contains("with_original_language=ko"));
        assertEquals(1, items.size());
        assertEquals("tmdb~series~96102", items.get(0).get("id"));
        assertEquals("content-nexora", items.get(0).get("playbackProvider"));
        assertEquals("videasy", items.get(0).get("fallbackPlaybackProvider"));
        assertEquals("Drama coreens", items.get(0).get("categoryName"));
    }

    @Test
    void mapsMovieDetailsFromTmdb() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/movie/603", exchange -> json(exchange, """
                {
                  "id":603,
                  "title":"The Matrix",
                  "overview":"A hacker discovers reality.",
                  "poster_path":"/matrix.jpg",
                  "backdrop_path":"/matrix-backdrop.jpg",
                  "release_date":"1999-03-31",
                  "runtime":136,
                  "vote_average":8.2,
                  "genres":[{"name":"Science-fiction"}],
                  "production_countries":[{"name":"United States of America"}],
                  "credits":{
                    "cast":[{"name":"Keanu Reeves"}],
                    "crew":[{"name":"Lana Wachowski","job":"Director"}]
                  },
                  "release_dates":{"results":[
                    {"iso_3166_1":"FR","release_dates":[{"certification":"12"}]}
                  ]}
                }
                """));
        server.start();
        TmdbCatalogService service = service();

        Map<String, Object> details = service.itemInfo("tmdb~movie~603");

        assertEquals("The Matrix", details.get("name"));
        assertEquals("136 min", details.get("duration"));
        assertEquals(List.of("Science-fiction"), details.get("genres"));
        assertEquals(List.of("Keanu Reeves"), details.get("cast"));
        assertEquals(true, details.get("streamAvailable"));
        assertEquals("content-nexora", details.get("playbackProvider"));
        assertEquals("videasy", details.get("fallbackPlaybackProvider"));
    }

    @Test
    void mapsSeriesDetailsWithContentPrimaryAndVideasyFallback() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/tv/1399", exchange -> json(exchange, """
                {
                  "id":1399,
                  "name":"Game of Thrones",
                  "original_name":"Game of Thrones",
                  "overview":"Noble families fight for power.",
                  "poster_path":"/got.jpg",
                  "backdrop_path":"/got-backdrop.jpg",
                  "first_air_date":"2011-04-17",
                  "vote_average":8.4,
                  "genres":[{"name":"Drama"}],
                  "created_by":[{"name":"David Benioff"}],
                  "seasons":[
                    {"season_number":1,"name":"Season 1","episode_count":2,"poster_path":"/s1.jpg"}
                  ],
                  "credits":{"cast":[{"name":"Emilia Clarke"}]},
                  "content_ratings":{"results":[{"iso_3166_1":"FR","rating":"16"}]},
                  "external_ids":{"imdb_id":"tt0944947"}
                }
                """));
        server.createContext("/tv/1399/season/1", exchange -> json(exchange, """
                {
                  "id":3624,
                  "season_number":1,
                  "episodes":[
                    {
                      "episode_number":1,
                      "name":"Winter Is Coming",
                      "overview":"The Stark family receives a royal visit.",
                      "air_date":"2011-04-17",
                      "still_path":"/winter.jpg",
                      "vote_average":8.0
                    },
                    {
                      "episode_number":2,
                      "name":"The Kingsroad",
                      "overview":"The royal party heads south.",
                      "air_date":"2011-04-24",
                      "still_path":"/kingsroad.jpg",
                      "vote_average":7.8
                    }
                  ]
                }
                """));
        server.start();
        TmdbCatalogService service = service();

        Map<String, Object> series = service.seriesInfo("tmdb~series~1399", null);

        assertEquals("Game of Thrones", series.get("name"));
        assertEquals("Game of Thrones", series.get("originalTitle"));
        assertEquals("tt0944947", series.get("imdbId"));
        assertEquals(1, series.get("seasonCount"));
        assertEquals(2, series.get("episodeCount"));
        assertEquals(true, series.get("streamAvailable"));
        assertEquals("content-nexora", series.get("playbackProvider"));
        assertEquals("videasy", series.get("fallbackPlaybackProvider"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) series.get("seasons");
        assertEquals("/s1.jpg", seasons.get(0).get("tmdbPosterPath"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes = (List<Map<String, Object>>) seasons.get(0).get("episodes");
        assertEquals("tmdb~series~1399~s~1~e~1", episodes.get(0).get("id"));
        assertEquals("Winter Is Coming", episodes.get(0).get("name"));
        assertEquals("The Stark family receives a royal visit.", episodes.get(0).get("summary"));
        assertEquals("2011-04-17", episodes.get(0).get("releaseDate"));
        assertEquals(1399L, episodes.get(0).get("tmdbId"));
        assertEquals(true, episodes.get(0).get("streamAvailable"));
        assertEquals("content-nexora", episodes.get(0).get("playbackProvider"));
        assertEquals("videasy", episodes.get(0).get("fallbackPlaybackProvider"));
    }

    private TmdbCatalogService service() {
        TmdbMetadataService metadata = new TmdbMetadataService(
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                root(),
                "https://image.tmdb.org/t/p",
                "api-key",
                "",
                "fr-FR",
                "FR",
                false,
                2,
                1_048_576
        );
        return new TmdbCatalogService(metadata, new CatalogImageService(1_048_576, 16));
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

    private void pagedResults(HttpExchange exchange, int seed, AtomicInteger highestPage) throws IOException {
        int page = 1;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("page=")) {
                    page = Integer.parseInt(parameter.substring("page=".length()));
                }
            }
        }
        highestPage.accumulateAndGet(page, Math::max);
        StringBuilder body = new StringBuilder("{\"page\":").append(page).append(",\"results\":[");
        for (int index = 0; index < 20; index++) {
            if (index > 0) {
                body.append(',');
            }
            int id = seed + page * 100 + index;
            body.append("{\"id\":").append(id)
                    .append(",\"title\":\"Film ").append(id)
                    .append("\",\"release_date\":\"2026-01-01\"}");
        }
        body.append("]}");
        json(exchange, body.toString());
    }
}
