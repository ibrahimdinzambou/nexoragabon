package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ConsumetContentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumetContentService.class);
    private static final String ITEM_PREFIX = "consumet";
    private static final String FAMILY_MOVIES = "movies";
    private static final String FAMILY_ANIME = "anime";
    private static final String FAMILY_META = "meta";
    private static final String PROVIDER_ANILIST = "anilist";
    private static final String PROVIDER_ANIME_NEXORA = "anime-nexora";
    private static final String TYPE_MOVIE = "movie";
    private static final String TYPE_SERIES = "series";
    private static final int DEFAULT_PAGE_SIZE = 24;

    private final ObjectMapper mapper;
    private final CatalogImageService images;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String movieProvider;
    private final String animeProvider;
    private final String movieServer;
    private final String animeServer;
    private final String animeCategory;
    private final String sourceMode;
    private final String metaProvider;
    private final String embedSource;
    private final String embedPlayerUrl;
    private final String otherEmbedPlayerUrl;
    private final int maxResponseBytes;
    private final Set<String> allowedStreamHosts;

    public ConsumetContentService(
            ObjectMapper mapper,
            CatalogImageService images,
            @Value("${app.consumet.enabled:false}") boolean enabled,
            @Value("${app.consumet.base-url:}") String baseUrl,
            @Value("${app.consumet.movie-provider:flixhq}") String movieProvider,
            @Value("${app.consumet.anime-provider:hianime}") String animeProvider,
            @Value("${app.consumet.movie-server:vidcloud}") String movieServer,
            @Value("${app.consumet.anime-server:vidstreaming}") String animeServer,
            @Value("${app.consumet.anime-category:sub}") String animeCategory,
            @Value("${app.consumet.source-mode:auto}") String sourceMode,
            @Value("${app.consumet.meta-provider:zoro}") String metaProvider,
            @Value("${app.consumet.embed-source:hd-1}") String embedSource,
            @Value("${app.consumet.embed-player-url:}") String embedPlayerUrl,
            @Value("${app.consumet.other-embed-player-url:}") String otherEmbedPlayerUrl,
            @Value("${app.consumet.timeout-seconds:20}") long timeoutSeconds,
            @Value("${app.consumet.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${app.consumet.allowed-stream-hosts:}") String allowedStreamHosts
    ) {
        this.mapper = mapper;
        this.images = images;
        this.enabled = enabled;
        this.baseUrl = trimSlash(baseUrl);
        this.movieProvider = cleanProvider(movieProvider, "flixhq");
        this.animeProvider = cleanProvider(animeProvider, "hianime");
        this.movieServer = clean(movieServer);
        this.animeServer = clean(animeServer);
        this.animeCategory = clean(animeCategory);
        this.sourceMode = clean(sourceMode) == null ? "auto" : clean(sourceMode).toLowerCase(Locale.ROOT);
        this.metaProvider = cleanProvider(metaProvider, "zoro");
        this.embedSource = clean(embedSource) == null ? "hd-2" : clean(embedSource).toLowerCase(Locale.ROOT);
        this.embedPlayerUrl = trimSlash(embedPlayerUrl);
        this.otherEmbedPlayerUrl = trimSlash(otherEmbedPlayerUrl);
        this.maxResponseBytes = Math.max(65_536, maxResponseBytes);
        this.allowedStreamHosts = parseHosts(allowedStreamHosts);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isEnabled() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }

    public boolean isConsumetItem(String itemId) {
        return itemId != null && itemId.startsWith(ITEM_PREFIX + "~");
    }

    public List<Map<String, Object>> categories(String type) {
        if (!isEnabled()) {
            return List.of();
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        addCategory(categories, type, FAMILY_MOVIES, TYPE_MOVIE);
        addCategory(categories, type, FAMILY_MOVIES, TYPE_SERIES);
        addCategory(categories, type, FAMILY_ANIME, TYPE_MOVIE);
        addCategory(categories, type, FAMILY_ANIME, TYPE_SERIES);
        return List.copyOf(categories);
    }

    public List<Map<String, Object>> languages(String type) {
        if (!isEnabled()) {
            return List.of();
        }
        String normalizedType = catalogType(type);
        List<String> types = normalizedType == null
                ? List.of(TYPE_MOVIE, TYPE_SERIES)
                : List.of(normalizedType);
        return List.of(Map.of(
                "id", "fr",
                "name", "Francais / VF",
                "types", types,
                "source", animeNexoraMode() ? "Anime Nexora" : "Consumet"
        ));
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit
    ) {
        if (!isEnabled()) {
            return List.of();
        }
        String normalizedLanguage = normalizeLanguageFilter(language);
        if (normalizedLanguage != null && !"fr".equals(normalizedLanguage)) {
            return List.of();
        }
        String normalizedType = catalogType(type);
        if (normalizedType == null) {
            return List.of();
        }
        if (animeNexoraMode()
                && matchesAnimeNexoraCategory(categoryId, normalizedType)) {
            return animeNexoraItems(normalizedType, query, categoryId, requestedLimit, normalizedLanguage, sort);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            search(result, normalizedType, query, categoryId, requestedLimit, normalizedLanguage);
        } else {
            browse(result, normalizedType, categoryId, requestedLimit, normalizedLanguage);
        }
        result = deduplicate(result, "id");
        Comparator<Map<String, Object>> byName = Comparator.comparing(
                item -> String.valueOf(item.get("name")),
                String.CASE_INSENSITIVE_ORDER
        );
        if ("title-desc".equals(sort)) {
            byName = byName.reversed();
        }
        result.sort(byName);
        int limit = requestedLimit <= 0 ? result.size() : Math.min(requestedLimit, result.size());
        return List.copyOf(result.subList(0, limit));
    }

    public Map<String, Object> itemInfo(String publicItemId) {
        ConsumetItemId itemId = parseItemId(publicItemId);
        if (isAnimeNexoraItem(itemId)) {
            return animeNexoraInfo(itemId, publicItemId);
        }
        if (TYPE_SERIES.equals(itemId.type()) && itemId.episodeId() == null) {
            return seriesInfo(publicItemId, null);
        }
        JsonNode info = info(itemId);
        Map<String, Object> payload = baseInfoPayload(itemId, info);
        payload.put("id", publicItemId);
        payload.put("type", itemId.type());
        payload.put("isEpisode", itemId.episodeId() != null);
        payload.put("streamAvailable", hasPlayableEpisode(itemId, info));
        if (!Boolean.TRUE.equals(payload.get("streamAvailable"))) {
            payload.put("streamUnavailableReason", "Consumet ne renvoie pas encore de source video pour ce contenu.");
        }
        images.rewrite(payload);
        return payload;
    }

    public Map<String, Object> seriesInfo(String publicItemId, String titleHint) {
        ConsumetItemId itemId = parseItemId(publicItemId);
        if (isAnimeNexoraItem(itemId)) {
            return animeNexoraSeriesInfo(itemId, publicItemId, titleHint);
        }
        if (!TYPE_SERIES.equals(itemId.type())) {
            throw ApiException.validation("Cet element Consumet n'est pas une serie");
        }
        JsonNode info = info(itemId);
        List<Map<String, Object>> episodes = episodes(info).stream()
                .map(episode -> episodePayload(itemId, episode, info))
                .toList();
        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> episode : episodes) {
            int season = numberValue(episode.get("season"), 1);
            grouped.computeIfAbsent(season, ignored -> new ArrayList<>()).add(episode);
        }
        List<Map<String, Object>> seasons = grouped.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "season", entry.getKey(),
                        "name", "Saison " + entry.getKey(),
                        "episodeCount", entry.getValue().size(),
                        "episodes", entry.getValue()
                ))
                .toList();

        Map<String, Object> payload = baseInfoPayload(itemId, info);
        payload.put("id", publicItemId);
        payload.put("name", textOrDefault(
                info,
                titleHint == null || titleHint.isBlank() ? itemId.mediaId() : titleHint,
                "title",
                "name",
                "otherName",
                "romaji",
                "english",
                "native"
        ));
        payload.put("type", TYPE_SERIES);
        payload.put("isSeries", true);
        payload.put("seasonCount", seasons.size());
        payload.put("episodeCount", episodes.size());
        payload.put("seasons", seasons);
        payload.put("streamAvailable", !episodes.isEmpty());
        if (episodes.isEmpty()) {
            payload.put("streamUnavailableReason", "Aucun episode Consumet disponible pour cette serie.");
        }
        images.rewrite(payload);
        return payload;
    }

    public CatalogAccessDescriptor accessForItem(String publicItemId) {
        ConsumetItemId itemId = parseItemId(publicItemId);
        Category category = category(itemId.family(), itemId.type());
        return new CatalogAccessDescriptor(category.id(), category.name(), itemId.type(), false);
    }

    public StreamResolution selectStream(String publicItemId) {
        ConsumetItemId itemId = parseItemId(publicItemId);
        if (isAnimeNexoraItem(itemId)) {
            return animeNexoraStream(itemId);
        }
        if (isAnilistItem(itemId)) {
            String episodeId = itemId.episodeId();
            if (episodeId == null || episodeId.isBlank()) {
                episodeId = "1";
            }
            // Les instances Consumet recentes peuvent encore resoudre le flux
            // directement. On tente donc le provider anime configure avant de
            // retomber sur le lecteur embed, utile lorsque l'instance est
            // partiellement indisponible.
            try {
                return fetchAnimeStream(animeProvider, episodeId);
            } catch (RuntimeException ignored) {
                return new StreamResolution(embedUrl(itemId.mediaId(), episodeId), Map.of());
            }
        }
        String episodeId = itemId.episodeId();
        if (episodeId == null || episodeId.isBlank()) {
            JsonNode info = info(itemId);
            episodeId = firstEpisodeId(info)
                    .orElseThrow(() -> ApiException.streamUnavailable(
                            "Aucun episode Consumet ne peut etre lu pour ce contenu"
                    ));
        }

        URI watchUri = FAMILY_ANIME.equals(itemId.family())
                ? animeWatchEndpoint(itemId.provider(), episodeId)
                : endpoint(
                        Map.of(
                                "episodeId", episodeId,
                                "mediaId", itemId.mediaId(),
                                "server", movieServer == null ? "" : movieServer
                        ),
                        FAMILY_MOVIES,
                        itemId.provider(),
                        "watch"
                );
        try {
            JsonNode response = fetchJson(watchUri);
            JsonNode source = bestSource(response.path("sources"));
            if (source == null) {
                throw ApiException.streamUnavailable("Consumet ne renvoie aucune source video lisible");
            }
            String streamUrl = text(source, "url");
            validateStreamUrl(streamUrl);
            return new StreamResolution(streamUrl, headers(response.path("headers")));
        } catch (RuntimeException exception) {
            if (FAMILY_ANIME.equals(itemId.family())) {
                return new StreamResolution(embedUrl(itemId.mediaId(), episodeNumber(episodeId)), Map.of());
            }
            throw exception;
        }
    }

    private StreamResolution fetchAnimeStream(String provider, String episodeId) {
        JsonNode response = fetchJson(animeWatchEndpoint(provider, episodeId));
        JsonNode source = bestSource(response.path("sources"));
        if (source == null) {
            throw ApiException.streamUnavailable("Consumet ne renvoie aucune source anime lisible");
        }
        String streamUrl = text(source, "url");
        validateStreamUrl(streamUrl);
        return new StreamResolution(streamUrl, headers(response.path("headers")));
    }

    public boolean isAllowedStreamHostForPlayback(String publicItemId, String host) {
        return isConsumetItem(publicItemId) && hostAllowed(host, allowedStreamHosts);
    }

    public boolean isEmbedPlayback(String publicItemId, String streamUrl) {
        if (!isConsumetItem(publicItemId) || streamUrl == null || streamUrl.isBlank()) {
            return false;
        }
        if (publicItemId.contains("~anime-nexora~")) {
            // Anime Nexora expose les lecteurs Anime-Sama (pages player), pas
            // une playlist HLS directement exploitable par le relais serveur.
            validateStreamUrl(streamUrl);
            return true;
        }
        return hostMatches(streamUrl, embedPlayerUrl) || hostMatches(streamUrl, otherEmbedPlayerUrl);
    }

    private void addCategory(List<Map<String, Object>> categories, String requestedType, String family, String type) {
        if (requestedType != null && !requestedType.isBlank() && !type.equals(requestedType)) {
            return;
        }
        Category category = category(family, type);
        categories.add(Map.of(
                "id", category.id(),
                "name", category.name(),
                "type", type,
                "source", animeNexoraMode() && FAMILY_ANIME.equals(family) ? "Anime Nexora" : "Consumet",
                "metadataAvailable", true,
                "streamAvailable", true,
                "adult", false
        ));
    }

    private void search(
            List<Map<String, Object>> result,
            String type,
            String query,
            String categoryId,
            int requestedLimit,
            String language
    ) {
        if (animeNexoraMode() && matchesAnimeNexoraCategory(categoryId, type)) {
            result.addAll(animeNexoraItems(type, query, categoryId, requestedLimit, language, null));
            return;
        }
        if (matchesCategory(categoryId, FAMILY_MOVIES, type)) {
            addItems(result, fetchItems(endpoint(
                    Map.of("page", "1"),
                    FAMILY_MOVIES,
                    movieProvider,
                    query
            )), FAMILY_MOVIES, movieProvider, null, type, categoryId, language);
        }
        if (TYPE_SERIES.equals(type) && matchesCategory(categoryId, FAMILY_ANIME, TYPE_SERIES)) {
            addItems(result, fetchItems(endpoint(
                    Map.of("page", "1"),
                    FAMILY_ANIME,
                    animeProvider,
                    query
            )), FAMILY_ANIME, animeProvider, TYPE_SERIES, type, categoryId, language);
        }
        if (preferAnilistMode() || result.isEmpty()) {
            searchAnilist(result, type, query, categoryId, requestedLimit, language);
        }
    }

    private void browse(
            List<Map<String, Object>> result,
            String type,
            String categoryId,
            int requestedLimit,
            String language
    ) {
        if (animeNexoraMode() && matchesAnimeNexoraCategory(categoryId, type)) {
            result.addAll(animeNexoraItems(type, null, categoryId, requestedLimit, language, null));
            return;
        }
        if (TYPE_MOVIE.equals(type)) {
            if (matchesCategory(categoryId, FAMILY_MOVIES, TYPE_MOVIE)) {
                addItems(result, fetchItems(endpoint(FAMILY_MOVIES, movieProvider, "recent-movies")),
                        FAMILY_MOVIES, movieProvider, TYPE_MOVIE, type, categoryId, language);
            }
            if (!preferAnilistMode() && matchesCategory(categoryId, FAMILY_ANIME, TYPE_MOVIE)) {
                addItems(result, fetchItems(endpoint(Map.of("page", "1"), FAMILY_ANIME, animeProvider, "movie")),
                        FAMILY_ANIME, animeProvider, TYPE_MOVIE, type, categoryId, language);
            }
            if (preferAnilistMode() || result.isEmpty()) {
                browseAnilist(result, type, categoryId, requestedLimit, language);
            }
            return;
        }
        if (matchesCategory(categoryId, FAMILY_MOVIES, TYPE_SERIES)) {
            addItems(result, fetchItems(endpoint(FAMILY_MOVIES, movieProvider, "recent-shows")),
                    FAMILY_MOVIES, movieProvider, TYPE_SERIES, type, categoryId, language);
        }
        if (!preferAnilistMode() && matchesCategory(categoryId, FAMILY_ANIME, TYPE_SERIES)) {
            addItems(result, fetchItems(endpoint(Map.of("page", "1"), FAMILY_ANIME, animeProvider, "top-airing")),
                    FAMILY_ANIME, animeProvider, TYPE_SERIES, type, categoryId, language);
        }
        if (preferAnilistMode() || result.isEmpty()) {
            browseAnilist(result, type, categoryId, requestedLimit, language);
        }
    }

    private void searchAnilist(
            List<Map<String, Object>> result,
            String type,
            String query,
            String categoryId,
            int requestedLimit,
            String language
    ) {
        if (!matchesCategory(categoryId, FAMILY_ANIME, type)) {
            return;
        }
        addItems(result, fetchPagedAnilist(
                "advanced-search",
                Map.of("query", query.strip()),
                requestedLimit
        ), FAMILY_ANIME, PROVIDER_ANILIST, null, type, categoryId, language);
        if (result.isEmpty()) {
            addItems(result, fetchPagedAnilist(query, Map.of(), requestedLimit),
                    FAMILY_ANIME, PROVIDER_ANILIST, null, type, categoryId, language);
        }
    }

    private void browseAnilist(
            List<Map<String, Object>> result,
            String type,
            String categoryId,
            int requestedLimit,
            String language
    ) {
        if (!matchesCategory(categoryId, FAMILY_ANIME, type)) {
            return;
        }
        if (TYPE_MOVIE.equals(type)) {
            addItems(result, fetchPagedAnilist(
                    "advanced-search",
                    Map.of("format", "MOVIE"),
                    requestedLimit
            ), FAMILY_ANIME, PROVIDER_ANILIST, null, type, categoryId, language);
            return;
        }
        addItems(result, fetchPagedAnilist(
                "advanced-search",
                Map.of("format", "TV"),
                requestedLimit
        ), FAMILY_ANIME, PROVIDER_ANILIST, null, type, categoryId, language);
    }

    private void addItems(
            List<Map<String, Object>> result,
            List<JsonNode> items,
            String family,
            String provider,
            String forcedType,
            String requestedType,
            String categoryId,
            String language
    ) {
        for (JsonNode item : items) {
            String type = forcedType == null ? inferType(item) : forcedType;
            if (!requestedType.equals(type) || !matchesCategory(categoryId, family, type)) {
                continue;
            }
            String remoteId = text(item, "id");
            String title = titleText(item, null);
            if (remoteId == null || remoteId.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            Category category = category(family, type);
            payload.put("id", publicItemId(family, provider, type, remoteId, null));
            payload.put("name", title);
            payload.put("type", type);
            payload.put("categoryId", category.id());
            payload.put("categoryName", category.name());
            payload.put("poster", text(item, "image"));
            payload.put("logo", text(item, "image"));
            payload.put("releaseDate", text(item, "releaseDate"));
            payload.put("releaseYear", releaseYear(text(item, "releaseDate", "season", "latestEpisode")));
            payload.put("duration", text(item, "duration"));
            payload.put("source", "Consumet");
            payload.put("provider", provider);
            if ("fr".equals(language)) {
                payload.put("language", "fr");
                payload.put("languageName", "Francais / VF");
                payload.put("audioLanguage", "fr");
            }
            payload.put("metadataAvailable", true);
            payload.put("streamAvailable", true);
            if (TYPE_SERIES.equals(type)) {
                payload.put("isSeries", true);
            }
            images.rewrite(payload);
            result.add(payload);
        }
    }

    private List<JsonNode> fetchItems(URI uri) {
        try {
            JsonNode response = fetchJson(uri);
            JsonNode values = response.isArray() ? response : response.path("results");
            if (!values.isArray()) {
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>();
            values.forEach(result::add);
            return result;
        } catch (ApiException exception) {
            LOGGER.warn("Catalogue Consumet ignore pour {}: {}", uri, exception.getMessage());
            return List.of();
        }
    }

    private boolean animeNexoraMode() {
        if (!isEnabled()) {
            return false;
        }
        String configuredBase = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        return "anime-nexora".equals(sourceMode)
                || "animesama".equals(sourceMode)
                || configuredBase.contains("anime-api")
                || configuredBase.contains("anime-nexora");
    }

    private boolean isAnimeNexoraItem(ConsumetItemId itemId) {
        return itemId != null && FAMILY_ANIME.equals(itemId.family())
                && PROVIDER_ANIME_NEXORA.equals(itemId.provider());
    }

    private List<Map<String, Object>> animeNexoraItems(
            String type, String query, String categoryId, int requestedLimit, String language, String sort) {
        JsonNode response = fetchJson(animeNexoraEndpoint(
                query == null || query.isBlank() ? "api/v1/catalogues" : "api/v1/search",
                query == null || query.isBlank()
                        ? Map.of("limit", String.valueOf(Math.max(1, Math.min(100, requestedLimit <= 0 ? DEFAULT_PAGE_SIZE : requestedLimit))))
                        : Map.of("q", query.strip())));
        JsonNode values = response.path("data");
        if (!values.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Category category = category(FAMILY_ANIME, type);
        for (JsonNode item : values) {
            String slug = animeSlug(text(item, "url"));
            String title = text(item, "name");
            if (slug == null || title == null || title.isBlank()) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            String remoteType = animeFilm(item) ? TYPE_MOVIE : TYPE_SERIES;
            if (!type.equals(remoteType)) {
                continue;
            }
            payload.put("id", publicItemId(FAMILY_ANIME, PROVIDER_ANIME_NEXORA, remoteType, slug, null));
            payload.put("name", title);
            payload.put("type", remoteType);
            Category itemCategory = category(FAMILY_ANIME, remoteType);
            payload.put("categoryId", itemCategory.id());
            payload.put("categoryName", itemCategory.name());
            payload.put("poster", text(item, "image_url"));
            payload.put("logo", text(item, "image_url"));
            payload.put("genres", stringList(item.path("genres")));
            payload.put("source", "Anime Nexora");
            payload.put("provider", PROVIDER_ANIME_NEXORA);
            payload.put("metadataAvailable", true);
            payload.put("streamAvailable", true);
            if (TYPE_SERIES.equals(remoteType)) {
                payload.put("isSeries", true);
            }
            if ("fr".equals(language)) {
                payload.put("language", "fr");
                payload.put("languageName", "Francais / VF");
                payload.put("audioLanguage", "fr");
            }
            images.rewrite(payload);
            result.add(payload);
        }
        Comparator<Map<String, Object>> byName = Comparator.comparing(
                item -> String.valueOf(item.get("name")), String.CASE_INSENSITIVE_ORDER);
        if ("title-desc".equals(sort)) {
            byName = byName.reversed();
        }
        result.sort(byName);
        return result.size() > requestedLimit && requestedLimit > 0
                ? List.copyOf(result.subList(0, requestedLimit)) : List.copyOf(result);
    }

    private Map<String, Object> animeNexoraInfo(ConsumetItemId itemId, String publicId) {
        JsonNode info = animeNexoraDetail(itemId.mediaId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", publicId);
        payload.put("name", textOrDefault(info, itemId.mediaId(), "name"));
        payload.put("type", itemId.type());
        payload.put("categoryId", category(FAMILY_ANIME, itemId.type()).id());
        payload.put("categoryName", category(FAMILY_ANIME, itemId.type()).name());
        payload.put("poster", text(info, "image_url"));
        payload.put("summary", text(info, "synopsis"));
        payload.put("genres", stringList(info.path("genres")));
        payload.put("source", "Anime Nexora");
        payload.put("provider", PROVIDER_ANIME_NEXORA);
        payload.put("metadataAvailable", true);
        payload.put("streamAvailable", true);
        if (TYPE_SERIES.equals(itemId.type())) {
            payload.put("isSeries", true);
        }
        return payload;
    }

    private Map<String, Object> animeNexoraSeriesInfo(ConsumetItemId itemId, String publicId, String titleHint) {
        JsonNode info = animeNexoraDetail(itemId.mediaId());
        JsonNode seasons = fetchJson(animeNexoraEndpoint("api/v1/catalogue/" + encodePath(itemId.mediaId()) + "/seasons", Map.of())).path("data");
        List<Map<String, Object>> seasonPayloads = new ArrayList<>();
        int episodeCount = 0;
        if (seasons.isArray()) {
            for (JsonNode season : seasons) {
                String seasonUrl = text(season, "url");
                if (seasonUrl == null) continue;
                String seasonSlug = animeSlug(seasonUrl);
                JsonNode episodes = fetchJson(animeNexoraEndpoint(
                        "api/v1/catalogue/" + encodePath(itemId.mediaId()) + "/seasons/" + encodePath(seasonSlug) + "/episodes", Map.of())).path("data");
                List<Map<String, Object>> values = new ArrayList<>();
                if (episodes.isArray()) {
                    int index = 0;
                    for (JsonNode episode : episodes) {
                        index++;
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("id", publicItemId(FAMILY_ANIME, PROVIDER_ANIME_NEXORA, TYPE_SERIES,
                                itemId.mediaId(), seasonUrl + "|" + index));
                        value.put("name", textOrDefault(episode, "Episode " + index, "name", "short_name", "long_name"));
                        value.put("type", TYPE_SERIES);
                        value.put("season", seasonNumber(text(season, "name")));
                        value.put("episode", index);
                        value.put("isEpisode", true);
                        value.put("poster", text(info, "image_url"));
                        value.put("streamAvailable", !episode.path("languages").isEmpty());
                        values.add(value);
                    }
                }
                episodeCount += values.size();
                seasonPayloads.add(Map.of("season", seasonNumber(text(season, "name")), "name", textOrDefault(season, "Saison", "name"), "episodeCount", values.size(), "episodes", values));
            }
        }
        Map<String, Object> payload = animeNexoraInfo(itemId, publicId);
        payload.put("name", titleHint == null || titleHint.isBlank() ? payload.get("name") : titleHint);
        payload.put("seasonCount", seasonPayloads.size());
        payload.put("episodeCount", episodeCount);
        payload.put("seasons", seasonPayloads);
        return payload;
    }

    private StreamResolution animeNexoraStream(ConsumetItemId itemId) {
        String episodeToken = itemId.episodeId();
        if (episodeToken == null || episodeToken.isBlank()) {
            JsonNode seasons = fetchJson(animeNexoraEndpoint(
                    "api/v1/catalogue/" + encodePath(itemId.mediaId()) + "/seasons", Map.of())).path("data");
            if (!seasons.isArray() || seasons.isEmpty()) {
                throw ApiException.streamUnavailable("Aucun lecteur Anime Nexora disponible pour ce film");
            }
            JsonNode season = seasons.get(0);
            String seasonUrl = text(season, "url");
            String seasonSlug = animeSlug(seasonUrl);
            JsonNode episodes = fetchJson(animeNexoraEndpoint(
                    "api/v1/catalogue/" + encodePath(itemId.mediaId()) + "/seasons/" + encodePath(seasonSlug) + "/episodes", Map.of())).path("data");
            if (!episodes.isArray() || episodes.isEmpty()) {
                throw ApiException.streamUnavailable("Aucun lecteur Anime Nexora disponible pour ce film");
            }
            episodeToken = seasonUrl + "|1";
        }
        String[] token = episodeToken.split("\\|", 2);
        if (token.length != 2) throw ApiException.streamUnavailable("Episode Anime Nexora invalide");
        String seasonSlug = animeSlug(token[0]);
        int index = Integer.parseInt(token[1]);
        JsonNode episodes = fetchJson(animeNexoraEndpoint(
                "api/v1/catalogue/" + encodePath(itemId.mediaId()) + "/seasons/" + encodePath(seasonSlug) + "/episodes", Map.of())).path("data");
        JsonNode episode = episodes.isArray() && index > 0 && index <= episodes.size() ? episodes.get(index - 1) : null;
        if (episode == null) throw ApiException.streamUnavailable("Episode Anime Nexora introuvable");
        JsonNode languages = episode.path("languages");
        String stream = firstPlayer(languages, "vf", "fr", "vostfr", "vo");
        validateStreamUrl(stream);
        return new StreamResolution(stream, Map.of());
    }

    private String firstPlayer(JsonNode languages, String... names) {
        for (String name : names) {
            JsonNode values = languages.path(name);
            if (values.isArray() && !values.isEmpty() && !values.get(0).asText().isBlank()) return values.get(0).asText();
        }
        if (languages.isObject()) {
            var fields = languages.fields();
            while (fields.hasNext()) {
                JsonNode values = fields.next().getValue();
                if (values.isArray() && !values.isEmpty()) return values.get(0).asText();
            }
        }
        throw ApiException.streamUnavailable("Aucun lecteur Anime Nexora disponible");
    }

    private boolean animeFilm(JsonNode item) {
        JsonNode categories = item == null ? null : item.path("categories");
        if (categories == null || !categories.isArray()) {
            return false;
        }
        for (JsonNode value : categories) {
            String category = value.asText("").toLowerCase(Locale.ROOT);
            if (category.equals("film") || category.equals("films")) {
                return true;
            }
        }
        return false;
    }

    private JsonNode animeNexoraDetail(String slug) {
        return fetchJson(animeNexoraEndpoint("api/v1/catalogue/" + encodePath(slug), Map.of())).path("data");
    }

    private URI animeNexoraEndpoint(String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(baseUrl).append('/').append(path);
        appendQuery(url, query);
        return URI.create(url.toString());
    }

    private String animeSlug(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("/+$", "");
        int index = clean.lastIndexOf('/');
        return index < 0 ? clean : clean.substring(index + 1);
    }

    private int seasonNumber(String value) {
        if (value == null) return 1;
        var matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 1;
    }

    private List<JsonNode> fetchPagedAnilist(String endpointName, Map<String, String> query, int requestedLimit) {
        int perPage = 50;
        int pageCount = pageCount(requestedLimit, perPage, 5);
        List<JsonNode> result = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            if (query != null) {
                params.putAll(query);
            }
            params.put("page", String.valueOf(page));
            params.put("perPage", String.valueOf(perPage));
            List<JsonNode> pageItems = fetchItems(endpoint(
                    params,
                    FAMILY_META,
                    PROVIDER_ANILIST,
                    endpointName
            ));
            if (pageItems.isEmpty()) {
                break;
            }
            result.addAll(pageItems);
            if (requestedLimit > 0 && result.size() >= requestedLimit) {
                break;
            }
        }
        return result;
    }

    private int pageCount(int requestedLimit, int perPage, int maxPages) {
        int target = requestedLimit <= 0 ? DEFAULT_PAGE_SIZE : requestedLimit;
        int pages = (int) Math.ceil((double) Math.max(1, target) / Math.max(1, perPage));
        return Math.max(1, Math.min(maxPages, pages));
    }

    private JsonNode info(ConsumetItemId itemId) {
        if (isAnilistItem(itemId)) {
            return fetchJson(endpoint(
                    Map.of("provider", metaProvider),
                    FAMILY_META,
                    PROVIDER_ANILIST,
                    "info",
                    itemId.mediaId()
            ));
        }
        return fetchJson(endpoint(
                Map.of("id", itemId.mediaId()),
                itemId.family(),
                itemId.provider(),
                "info"
        ));
    }

    private Map<String, Object> baseInfoPayload(ConsumetItemId itemId, JsonNode info) {
        Category category = category(itemId.family(), itemId.type());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", titleText(info, itemId.mediaId()));
        payload.put("type", itemId.type());
        payload.put("categoryId", category.id());
        payload.put("categoryName", category.name());
        payload.put("poster", text(info, "image"));
        payload.put("backdrop", text(info, "cover", "image"));
        payload.put("summary", text(info, "description", "overview"));
        payload.put("releaseDate", text(info, "releaseDate", "aired"));
        payload.put("releaseYear", releaseYear(text(info, "releaseDate", "aired")));
        payload.put("duration", text(info, "duration"));
        payload.put("rating", text(info, "rating", "imdbRating"));
        payload.put("genres", stringList(firstArray(info, "genres", "geners", "tags")));
        payload.put("cast", stringList(firstArray(info, "cast", "casts")));
        payload.put("status", text(info, "status"));
        payload.put("source", "Consumet");
        payload.put("provider", itemId.provider());
        payload.put("metadataAvailable", true);
        return payload;
    }

    private Map<String, Object> episodePayload(ConsumetItemId parent, JsonNode episode, JsonNode info) {
        int episodeNumber = intValue(episode, "number", 1);
        int seasonNumber = intValue(episode, "season", 1);
        String remoteEpisodeId = text(episode, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", publicItemId(parent.family(), parent.provider(), TYPE_SERIES, parent.mediaId(), remoteEpisodeId));
        payload.put("name", textOrDefault(episode, "Episode " + episodeNumber, "title", "name"));
        payload.put("type", TYPE_SERIES);
        payload.put("season", seasonNumber);
        payload.put("episode", episodeNumber);
        payload.put("isEpisode", true);
        payload.put("poster", textOrDefault(episode, text(info, "image"), "image"));
        payload.put("summary", text(episode, "description", "overview"));
        payload.put("streamAvailable", remoteEpisodeId != null && !remoteEpisodeId.isBlank());
        return payload;
    }

    private List<JsonNode> episodes(JsonNode info) {
        JsonNode episodes = info.path("episodes");
        if (episodes.isArray() && !episodes.isEmpty()) {
            List<JsonNode> result = new ArrayList<>();
            episodes.forEach(result::add);
            return result;
        }
        int count = intValue(info, "currentEpisode", 0);
        if (count <= 0) {
            count = intValue(info, "currentEpisodeCount", 0);
        }
        if (count <= 0) {
            count = intValue(info, "totalEpisodes", 0);
        }
        if (count <= 0) {
            return List.of();
        }
        int cappedCount = Math.min(count, 500);
        List<JsonNode> result = new ArrayList<>();
        for (int episode = 1; episode <= cappedCount; episode++) {
            var generated = mapper.createObjectNode();
            generated.put("id", String.valueOf(episode));
            generated.put("title", "Episode " + episode);
            generated.put("number", episode);
            generated.put("season", 1);
            result.add(generated);
        }
        return result;
    }

    private java.util.Optional<String> firstEpisodeId(JsonNode info) {
        for (JsonNode episode : episodes(info)) {
            String id = text(episode, "id");
            if (id != null && !id.isBlank()) {
                return java.util.Optional.of(id);
            }
        }
        String id = text(info, "episodeId");
        if (id != null && !id.isBlank()) {
            return java.util.Optional.of(id);
        }
        return episodes(info).isEmpty() ? java.util.Optional.empty() : java.util.Optional.of("1");
    }

    private boolean hasPlayableEpisode(ConsumetItemId itemId, JsonNode info) {
        if (itemId.episodeId() != null && !itemId.episodeId().isBlank()) {
            return true;
        }
        return firstEpisodeId(info).isPresent();
    }

    private JsonNode bestSource(JsonNode sources) {
        if (!sources.isArray() || sources.isEmpty()) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode source : sources) {
            String url = text(source, "url");
            if (url == null || url.isBlank()) {
                continue;
            }
            if (fallback == null) {
                fallback = source;
            }
            if (source.path("isM3U8").asBoolean(false)) {
                return source;
            }
        }
        return fallback;
    }

    private Map<String, String> headers(JsonNode headers) {
        Map<String, String> values = new LinkedHashMap<>();
        if (headers == null || !headers.isObject()) {
            return values;
        }
        headers.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return StreamRequestHeaders.sanitize(values);
    }

    private JsonNode fetchJson(URI uri) {
        if (!isEnabled()) {
            throw ApiException.serviceUnavailable("Consumet n'est pas configure");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "Nexora-Consumet/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input);
            }
            JsonNode json = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
            if (response.statusCode() == 404) {
                throw ApiException.notFound(message(json, "Contenu Consumet introuvable"));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.providerUnavailable(message(json, "Consumet ne repond pas correctement"));
            }
            if (json.has("message") && !json.has("results") && !json.has("sources") && !json.isArray()) {
                throw ApiException.providerUnavailable(message(json, "Consumet a refuse la requete"));
            }
            return json;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.providerUnavailable("Connexion Consumet interrompue");
        } catch (IOException exception) {
            throw ApiException.providerUnavailable("Impossible de joindre Consumet");
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxResponseBytes) {
                throw new IOException("Reponse Consumet trop volumineuse");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private URI endpoint(String... segments) {
        return endpoint(Map.of(), segments);
    }

    private URI endpoint(Map<String, String> query, String... segments) {
        StringBuilder url = new StringBuilder(baseUrl);
        for (String segment : segments) {
            url.append('/').append(encodePath(segment));
        }
        appendQuery(url, query);
        return URI.create(url.toString());
    }

    private URI animeWatchEndpoint(String provider, String episodeId) {
        String raw = episodeId == null ? "" : episodeId.strip();
        int queryStart = raw.indexOf('?');
        String path = queryStart < 0 ? raw : raw.substring(0, queryStart);
        String existingQuery = queryStart < 0 ? "" : raw.substring(queryStart + 1);
        StringBuilder url = new StringBuilder(baseUrl)
                .append('/')
                .append(encodePath(FAMILY_ANIME))
                .append('/')
                .append(encodePath(provider))
                .append("/watch/")
                .append(encodePath(path));
        if (!existingQuery.isBlank()) {
            url.append('?').append(existingQuery);
        }
        appendQuery(url, Map.of(
                "server", animeServer == null ? "" : animeServer,
                "category", animeCategory == null ? "" : animeCategory
        ));
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

    private String embedUrl(String mediaId, String episodeId) {
        String episode = episodeNumber(episodeId);
        String category = animeCategory == null || animeCategory.isBlank() ? "sub" : animeCategory;
        String base = "hd-2".equals(embedSource) || "hd-3".equals(embedSource)
                ? embedPlayerUrl
                : otherEmbedPlayerUrl;
        if (base == null || base.isBlank()) {
            throw ApiException.streamUnavailable("Aucun lecteur embed Consumet n'est configure");
        }
        StringBuilder url = new StringBuilder(base);
        if ("hd-2".equals(embedSource)) {
            url.append("/anime");
        } else if ("hd-3".equals(embedSource)) {
            url.append("/animepahe");
        }
        url.append('/')
                .append(encodePath(mediaId))
                .append('/')
                .append(encodePath(episode))
                .append('/')
                .append(encodePath(category));
        validateStreamUrl(url.toString());
        return url.toString();
    }

    private String episodeNumber(String episodeId) {
        String value = episodeId == null || episodeId.isBlank() ? "1" : episodeId.strip();
        int separator = value.indexOf('?');
        if (separator >= 0) {
            value = value.substring(0, separator);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        return matcher.find() ? matcher.group() : "1";
    }

    private void validateStreamUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw ApiException.streamUnavailable("Consumet a renvoye une URL de flux invalide");
        }
    }

    private ConsumetItemId parseItemId(String publicItemId) {
        try {
            String[] parts = String.valueOf(publicItemId).split("~", 6);
            if (parts.length < 5 || !ITEM_PREFIX.equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            String family = normalizeFamily(parts[1]);
            String provider = cleanProvider(parts[2], FAMILY_ANIME.equals(family) ? animeProvider : movieProvider);
            String type = normalizeType(parts[3]);
            String mediaId = decodeToken(parts[4]);
            String episodeId = parts.length >= 6 ? decodeToken(parts[5]) : null;
            return new ConsumetItemId(family, provider, type, mediaId, episodeId);
        } catch (RuntimeException exception) {
            throw ApiException.validation("Identifiant Consumet invalide");
        }
    }

    private String publicItemId(String family, String provider, String type, String mediaId, String episodeId) {
        String id = ITEM_PREFIX + "~" + normalizeFamily(family) + "~" + cleanProvider(provider, movieProvider)
                + "~" + normalizeType(type) + "~" + encodeToken(mediaId);
        return episodeId == null || episodeId.isBlank() ? id : id + "~" + encodeToken(episodeId);
    }

    private boolean isAnilistItem(ConsumetItemId itemId) {
        return FAMILY_ANIME.equals(itemId.family()) && PROVIDER_ANILIST.equals(itemId.provider());
    }

    private boolean preferAnilistMode() {
        if ("anilist".equals(sourceMode) || "meta".equals(sourceMode)) {
            return true;
        }
        if ("standard".equals(sourceMode) || "classic".equals(sourceMode)) {
            return false;
        }
        return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("kuro-neko");
    }

    private boolean matchesCategory(String requestedCategoryId, String family, String type) {
        return requestedCategoryId == null
                || requestedCategoryId.isBlank()
                || requestedCategoryId.equals(category(family, type).id());
    }

    private boolean matchesAnimeNexoraCategory(String requestedCategoryId, String type) {
        if (requestedCategoryId == null || requestedCategoryId.isBlank()) {
            return true;
        }
        String normalized = requestedCategoryId.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("anime-nexora")
                || normalized.equals("anime-nexora-" + type)
                || normalized.startsWith("consumet-anime-");
    }

    private Category category(String family, String type) {
        String normalizedFamily = normalizeFamily(family);
        String normalizedType = normalizeType(type);
        if (FAMILY_ANIME.equals(normalizedFamily)) {
            if (animeNexoraMode()) {
                return TYPE_MOVIE.equals(normalizedType)
                        ? new Category("anime-nexora-movie", "Films anime")
                        : new Category("anime-nexora", "Anime");
            }
            return TYPE_MOVIE.equals(normalizedType)
                    ? new Category("consumet-anime-movie-" + animeProvider, "Films anime")
                    : new Category("consumet-anime-" + animeProvider, "Anime");
        }
        return TYPE_MOVIE.equals(normalizedType)
                ? new Category("consumet-movie-" + movieProvider, "Films Consumet")
                : new Category("consumet-series-" + movieProvider, "Series Consumet");
    }

    private String inferType(JsonNode item) {
        String type = text(item, "type");
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return normalized.contains("movie") ? TYPE_MOVIE : TYPE_SERIES;
    }

    private String normalizeFamily(String family) {
        String normalized = family == null ? FAMILY_MOVIES : family.strip().toLowerCase(Locale.ROOT);
        if (!FAMILY_MOVIES.equals(normalized) && !FAMILY_ANIME.equals(normalized)) {
            throw ApiException.validation("Famille Consumet non prise en charge");
        }
        return normalized;
    }

    private String normalizeType(String type) {
        String normalized = type == null || type.isBlank() ? TYPE_MOVIE : type.strip().toLowerCase(Locale.ROOT);
        if (TYPE_MOVIE.equals(normalized) || TYPE_SERIES.equals(normalized)) {
            return normalized;
        }
        throw ApiException.validation("Type Consumet non pris en charge");
    }

    private String catalogType(String type) {
        String normalized = type == null || type.isBlank() ? TYPE_MOVIE : type.strip().toLowerCase(Locale.ROOT);
        return TYPE_MOVIE.equals(normalized) || TYPE_SERIES.equals(normalized) ? normalized : null;
    }

    private String normalizeLanguageFilter(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.equals("fr")
                || normalized.equals("fr-fr")
                || normalized.equals("fra")
                || normalized.equals("fre")
                || normalized.equals("vf")
                || normalized.equals("french")
                || normalized.equals("francais")
                || normalized.equals("français")) {
            return "fr";
        }
        return normalized;
    }

    private String text(JsonNode node, String... fieldsOrFallbacks) {
        if (node != null) {
            for (String field : fieldsOrFallbacks) {
                JsonNode candidate = node.path(field);
                if (candidate.isValueNode() && !candidate.asText("").isBlank()) {
                    return candidate.asText().strip();
                }
                if (candidate.isObject()) {
                    String title = titleText(candidate, null);
                    if (title != null && !title.isBlank()) {
                        return title;
                    }
                }
            }
        }
        return null;
    }

    private String textOrDefault(JsonNode node, String fallback, String... fields) {
        String value = text(node, fields);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String titleText(JsonNode node, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode title = node.path("title");
        if (title.isObject()) {
            String value = text(title, "english", "romaji", "userPreferred", "native");
            return value == null || value.isBlank() ? fallback : value;
        }
        String value = text(node, "title", "name", "english", "romaji", "userPreferred", "native", "otherName");
        return value == null || value.isBlank() ? fallback : value;
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray()) {
                return value;
            }
        }
        return mapper.createArrayNode();
    }

    private List<String> stringList(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        value.forEach(entry -> {
            if (entry.isValueNode() && !entry.asText("").isBlank()) {
                result.add(entry.asText().strip());
            }
        });
        return result;
    }

    private int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.canConvertToInt() ? value.asInt() : fallback;
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String releaseYear(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> values, String key) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            unique.putIfAbsent(String.valueOf(value.get(key)), value);
        }
        return new ArrayList<>(unique.values());
    }

    private String message(JsonNode json, String fallback) {
        String message = text(json, "message");
        return message == null || message.isBlank() ? fallback : message;
    }

    private String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String decodeToken(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String cleanProvider(String value, String fallback) {
        String cleaned = clean(value);
        if (cleaned == null || !cleaned.matches("[a-zA-Z0-9_-]+")) {
            return fallback;
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String trimSlash(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.replaceAll("/+$", "");
    }

    private Set<String> parseHosts(String value) {
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
        return hosts;
    }

    private boolean hostAllowed(String host, Set<String> allowed) {
        if (host == null || allowed.isEmpty()) {
            return false;
        }
        if (allowed.contains("*")) {
            return true;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(candidate -> candidate.startsWith(".")
                ? normalized.endsWith(candidate) && normalized.length() > candidate.length()
                : normalized.equals(candidate));
    }

    private boolean hostMatches(String value, String base) {
        if (value == null || base == null || base.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            URI baseUri = URI.create(base);
            return uri.getHost() != null
                    && baseUri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(baseUri.getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record CatalogAccessDescriptor(String categoryId, String categoryName, String contentType, boolean adult) {
    }

    public record StreamResolution(String streamUrl, Map<String, String> headers) {
    }

    private record ConsumetItemId(String family, String provider, String type, String mediaId, String episodeId) {
    }

    private record Category(String id, String name) {
    }
}
