package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.iptv.saas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TmdbCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TmdbCatalogService.class);
    private static final String ITEM_PREFIX = "tmdb";
    private static final String TYPE_MOVIE = "movie";
    private static final String TYPE_SERIES = "series";
    private static final String SOURCE = "TMDB";
    private static final String PLAYBACK_PROVIDER = "videasy";
    private static final String PLAYBACK_PROVIDER_NAME = "Videasy";
    private static final int TMDB_PAGE_SIZE = 20;
    private static final List<Category> CATEGORIES = List.of(
            new Category("tmdb-movie-trending", "TMDB - Films tendances", TYPE_MOVIE, Kind.TRENDING, "trending"),
            new Category("tmdb-movie-popular", "TMDB - Films populaires", TYPE_MOVIE, Kind.MOVIE_LIST, "popular"),
            new Category("tmdb-movie-now-playing", "TMDB - Au cinema", TYPE_MOVIE, Kind.MOVIE_LIST, "now_playing"),
            new Category("tmdb-movie-top-rated", "TMDB - Films les mieux notes", TYPE_MOVIE, Kind.MOVIE_LIST, "top_rated"),
            new Category("tmdb-movie-upcoming", "TMDB - Prochainement", TYPE_MOVIE, Kind.MOVIE_LIST, "upcoming"),
            new Category("tmdb-series-trending", "TMDB - Series tendances", TYPE_SERIES, Kind.TRENDING, "trending"),
            new Category("tmdb-series-popular", "TMDB - Series populaires", TYPE_SERIES, Kind.TV_LIST, "popular"),
            new Category("tmdb-series-on-the-air", "TMDB - En diffusion", TYPE_SERIES, Kind.TV_LIST, "on_the_air"),
            new Category("tmdb-series-top-rated", "TMDB - Series les mieux notees", TYPE_SERIES, Kind.TV_LIST, "top_rated")
    );

    private final TmdbMetadataService tmdb;
    private final CatalogImageService images;

    public TmdbCatalogService(TmdbMetadataService tmdb, CatalogImageService images) {
        this.tmdb = tmdb;
        this.images = images;
    }

    public boolean isEnabled() {
        return tmdb.configured();
    }

    public boolean isTmdbItem(String itemId) {
        return itemId != null && itemId.startsWith(ITEM_PREFIX + "~");
    }

    public List<Map<String, Object>> categories(String type) {
        if (!isEnabled()) {
            return List.of();
        }
        return CATEGORIES.stream()
                .filter(category -> type == null || type.isBlank() || category.type().equals(type))
                .map(this::categoryPayload)
                .toList();
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
        String normalizedType = catalogType(type);
        if (normalizedType == null) {
            return List.of();
        }
        List<Map<String, Object>> result = query != null && !query.isBlank()
                ? searchItems(normalizedType, query, requestedLimit)
                : browseItems(normalizedType, categoryId, requestedLimit);
        result = deduplicate(result);
        sort(result, sort);
        return limit(result, requestedLimit);
    }

    public Map<String, Object> itemInfo(String publicItemId) {
        TmdbItemId itemId = parseItemId(publicItemId);
        if (TYPE_SERIES.equals(itemId.type())) {
            return seriesInfo(publicItemId, null);
        }
        JsonNode details = tmdb.details(TYPE_MOVIE, itemId.tmdbId(), "credits,release_dates");
        Map<String, Object> payload = detailPayload(details, TYPE_MOVIE, movieCategory("tmdb-movie-popular"));
        images.rewrite(payload);
        return payload;
    }

    public Map<String, Object> seriesInfo(String publicItemId, String titleHint) {
        TmdbItemId itemId = parseItemId(publicItemId);
        if (!TYPE_SERIES.equals(itemId.type())) {
            throw ApiException.validation("Cet element TMDB n'est pas une serie");
        }
        JsonNode details = tmdb.details("tv", itemId.tmdbId(), "credits,content_ratings");
        Map<String, Object> payload = detailPayload(details, TYPE_SERIES, seriesCategory("tmdb-series-popular"));
        String title = text(details, "name");
        if ((title == null || title.isBlank()) && titleHint != null && !titleHint.isBlank()) {
            payload.put("name", titleHint);
        }
        List<Map<String, Object>> seasons = seasons(itemId.tmdbId(), details);
        int episodeCount = seasons.stream()
                .mapToInt(season -> numberValue(season.get("episodeCount"), 0))
                .sum();
        payload.put("isSeries", true);
        payload.put("seasonCount", seasons.size());
        payload.put("episodeCount", episodeCount);
        payload.put("seasons", seasons);
        payload.put("streamAvailable", true);
        payload.put("externalPlayback", true);
        payload.put("playbackProvider", PLAYBACK_PROVIDER);
        payload.put("playbackProviderName", PLAYBACK_PROVIDER_NAME);
        images.rewrite(payload);
        return payload;
    }

    public CatalogAccessDescriptor accessForItem(String publicItemId) {
        TmdbItemId itemId = parseItemId(publicItemId);
        Category category = TYPE_SERIES.equals(itemId.type())
                ? seriesCategory("tmdb-series-popular")
                : movieCategory("tmdb-movie-popular");
        return new CatalogAccessDescriptor(category.id(), category.name(), itemId.type(), false);
    }

    private List<Map<String, Object>> searchItems(String type, String query, int requestedLimit) {
        int pages = pageCount(requestedLimit, 5);
        List<Map<String, Object>> result = new ArrayList<>();
        Category category = searchCategory(type);
        for (int page = 1; page <= pages; page++) {
            try {
                addResults(result, tmdb.search(type, query.strip(), page), type, category);
            } catch (ApiException exception) {
                LOGGER.warn("Recherche TMDB ignoree pour {}: {}", type, exception.getMessage());
                break;
            }
        }
        return result;
    }

    private List<Map<String, Object>> browseItems(String type, String categoryId, int requestedLimit) {
        List<Category> categories = matchingCategories(type, categoryId);
        if (categories.isEmpty()) {
            return List.of();
        }
        int pages = categoryId == null || categoryId.isBlank() ? 1 : pageCount(requestedLimit, 5);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Category category : categories) {
            for (int page = 1; page <= pages; page++) {
                try {
                    addResults(result, fetchCategory(category, page), type, category);
                } catch (ApiException exception) {
                    LOGGER.warn("Catalogue TMDB ignore pour {}: {}", category.id(), exception.getMessage());
                    break;
                }
            }
        }
        return result;
    }

    private JsonNode fetchCategory(Category category, int page) {
        return switch (category.kind()) {
            case TRENDING -> tmdb.trending(category.type(), page);
            case MOVIE_LIST -> tmdb.movieList(category.endpoint(), page);
            case TV_LIST -> tmdb.tvList(category.endpoint(), page);
        };
    }

    private void addResults(List<Map<String, Object>> result, JsonNode response, String type, Category category) {
        JsonNode results = response.path("results");
        if (!results.isArray()) {
            return;
        }
        for (JsonNode item : results) {
            Map<String, Object> payload = itemPayload(item, type, category);
            if (!payload.isEmpty()) {
                result.add(payload);
            }
        }
    }

    private Map<String, Object> itemPayload(JsonNode item, String type, Category category) {
        long id = item.path("id").asLong(0);
        if (id <= 0) {
            return Map.of();
        }
        String title = title(item, type);
        if (title == null || title.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", publicItemId(type, id));
        payload.put("tmdbId", id);
        payload.put("name", title);
        payload.put("type", type);
        payload.put("categoryId", category.id());
        payload.put("categoryName", category.name());
        payload.put("poster", imageUrl(text(item, "poster_path"), "w500"));
        payload.put("logo", imageUrl(text(item, "poster_path"), "w500"));
        payload.put("backdrop", imageUrl(text(item, "backdrop_path"), "w1280"));
        payload.put("summary", text(item, "overview"));
        payload.put("releaseDate", releaseDate(item, type));
        payload.put("releaseYear", releaseYear(releaseDate(item, type)));
        payload.put("rating", rating(item.path("vote_average").asDouble(0)));
        payload.put("source", SOURCE);
        payload.put("sourceCode", "tmdb");
        payload.put("provider", SOURCE);
        payload.put("metadataAvailable", true);
        payload.put("streamAvailable", true);
        payload.put("externalPlayback", true);
        payload.put("playbackProvider", PLAYBACK_PROVIDER);
        payload.put("playbackProviderName", PLAYBACK_PROVIDER_NAME);
        payload.put("adult", item.path("adult").asBoolean(false));
        if (TYPE_SERIES.equals(type)) {
            payload.put("isSeries", true);
            payload.put("seasonCount", 0);
            payload.put("episodeCount", 0);
        }
        images.rewrite(payload);
        return payload;
    }

    private Map<String, Object> detailPayload(JsonNode details, String type, Category category) {
        long id = details.path("id").asLong(0);
        if (id <= 0) {
            throw ApiException.notFound("Contenu TMDB introuvable");
        }
        Map<String, Object> payload = itemPayload(details, type, category);
        payload.put("duration", duration(details, type));
        payload.put("genres", stringArray(details.path("genres"), "name", 8));
        payload.put("cast", stringArray(details.path("credits").path("cast"), "name", 12));
        payload.put("directors", directors(details));
        payload.put("country", countries(details));
        payload.put("ageRating", TYPE_MOVIE.equals(type) ? movieCertification(details) : tvCertification(details));
        payload.put("streamAvailable", true);
        payload.put("externalPlayback", true);
        payload.put("playbackProvider", PLAYBACK_PROVIDER);
        payload.put("playbackProviderName", PLAYBACK_PROVIDER_NAME);
        return payload;
    }

    private List<Map<String, Object>> seasons(long seriesId, JsonNode details) {
        JsonNode values = details.path("seasons");
        if (!values.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> seasons = new ArrayList<>();
        for (JsonNode season : values) {
            int seasonNumber = season.path("season_number").asInt(-1);
            int episodeCount = season.path("episode_count").asInt(0);
            if (seasonNumber <= 0 || episodeCount <= 0) {
                continue;
            }
            List<Map<String, Object>> episodes = seasonEpisodes(seriesId, season, seasonNumber, episodeCount);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("season", seasonNumber);
            payload.put("name", textOrDefault(season, "Saison " + seasonNumber, "name"));
            payload.put("episodeCount", episodeCount);
            payload.put("poster", imageUrl(text(season, "poster_path"), "w500"));
            payload.put("episodes", episodes);
            seasons.add(payload);
        }
        return seasons;
    }

    private List<Map<String, Object>> seasonEpisodes(long seriesId, JsonNode season, int seasonNumber, int episodeCount) {
        try {
            JsonNode details = tmdb.season(seriesId, seasonNumber);
            List<Map<String, Object>> episodes = detailedEpisodes(seriesId, details, season, seasonNumber, episodeCount);
            if (!episodes.isEmpty()) {
                return episodes;
            }
        } catch (ApiException exception) {
            LOGGER.warn("Episodes TMDB detailles ignores pour {} saison {}: {}", seriesId, seasonNumber, exception.getMessage());
        }
        return generatedEpisodes(seriesId, season, seasonNumber, episodeCount);
    }

    private List<Map<String, Object>> detailedEpisodes(
            long seriesId,
            JsonNode seasonDetails,
            JsonNode season,
            int seasonNumber,
            int episodeCount
    ) {
        JsonNode values = seasonDetails.path("episodes");
        if (!values.isArray()) {
            return List.of();
        }
        int cappedCount = Math.min(episodeCount, 80);
        String seasonPoster = imageUrl(text(season, "poster_path"), "w300");
        List<Map<String, Object>> episodes = new ArrayList<>();
        for (JsonNode episodeNode : values) {
            int episodeNumber = episodeNode.path("episode_number").asInt(0);
            if (episodeNumber <= 0 || episodeNumber > cappedCount) {
                continue;
            }
            Map<String, Object> payload = baseEpisodePayload(seriesId, seasonNumber, episodeNumber);
            payload.put("name", textOrDefault(episodeNode, "Episode " + episodeNumber, "name"));
            payload.put("summary", text(episodeNode, "overview"));
            payload.put("releaseDate", text(episodeNode, "air_date"));
            int runtime = episodeNode.path("runtime").asInt(0);
            if (runtime > 0) {
                payload.put("duration", runtime + " min");
            }
            payload.put("rating", rating(episodeNode.path("vote_average").asDouble(0)));
            payload.put("poster", imageUrl(text(episodeNode, "still_path"), "w300"));
            if (String.valueOf(payload.get("poster")).isBlank()) {
                payload.put("poster", seasonPoster);
            }
            episodes.add(payload);
        }
        return episodes;
    }

    private List<Map<String, Object>> generatedEpisodes(long seriesId, JsonNode season, int seasonNumber, int episodeCount) {
        int cappedCount = Math.min(episodeCount, 80);
        List<Map<String, Object>> episodes = new ArrayList<>();
        String poster = imageUrl(text(season, "poster_path"), "w300");
        for (int episode = 1; episode <= cappedCount; episode++) {
            Map<String, Object> payload = baseEpisodePayload(seriesId, seasonNumber, episode);
            payload.put("name", "Episode " + episode);
            payload.put("poster", poster);
            episodes.add(payload);
        }
        return episodes;
    }

    private Map<String, Object> baseEpisodePayload(long seriesId, int seasonNumber, int episode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", publicEpisodeId(seriesId, seasonNumber, episode));
        payload.put("tmdbId", seriesId);
        payload.put("type", TYPE_SERIES);
        payload.put("season", seasonNumber);
        payload.put("episode", episode);
        payload.put("isEpisode", true);
        payload.put("source", SOURCE);
        payload.put("sourceCode", "tmdb");
        payload.put("provider", SOURCE);
        payload.put("streamAvailable", true);
        payload.put("externalPlayback", true);
        payload.put("playbackProvider", PLAYBACK_PROVIDER);
        payload.put("playbackProviderName", PLAYBACK_PROVIDER_NAME);
        return payload;
    }

    private Map<String, Object> categoryPayload(Category category) {
        return Map.of(
                "id", category.id(),
                "name", category.name(),
                "type", category.type(),
                "source", SOURCE,
                "sourceCode", "tmdb",
                "metadataAvailable", true,
                "streamAvailable", true,
                "adult", false
        );
    }

    private List<Category> matchingCategories(String type, String categoryId) {
        return CATEGORIES.stream()
                .filter(category -> category.type().equals(type))
                .filter(category -> categoryId == null || categoryId.isBlank() || category.id().equals(categoryId))
                .toList();
    }

    private Category searchCategory(String type) {
        return TYPE_SERIES.equals(type)
                ? new Category("tmdb-series-search", "TMDB - Recherche series", TYPE_SERIES, Kind.TV_LIST, "popular")
                : new Category("tmdb-movie-search", "TMDB - Recherche films", TYPE_MOVIE, Kind.MOVIE_LIST, "popular");
    }

    private Category movieCategory(String id) {
        return CATEGORIES.stream()
                .filter(category -> category.id().equals(id))
                .findFirst()
                .orElse(CATEGORIES.get(1));
    }

    private Category seriesCategory(String id) {
        return CATEGORIES.stream()
                .filter(category -> category.id().equals(id))
                .findFirst()
                .orElse(CATEGORIES.get(6));
    }

    private void sort(List<Map<String, Object>> values, String sort) {
        Comparator<Map<String, Object>> byTitle = Comparator.comparing(
                value -> String.valueOf(value.get("name")),
                String.CASE_INSENSITIVE_ORDER
        );
        switch (sort == null ? "default" : sort.strip().toLowerCase(Locale.ROOT)) {
            case "title-asc" -> values.sort(byTitle);
            case "title-desc" -> values.sort(byTitle.reversed());
            case "recent" -> values.sort(Comparator
                    .comparingInt((Map<String, Object> value) -> numberValue(value.get("releaseYear"), 0))
                    .reversed()
                    .thenComparing(byTitle));
            case "category" -> values.sort(Comparator
                    .comparing((Map<String, Object> value) -> String.valueOf(value.get("categoryName")),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(byTitle));
            default -> {
            }
        }
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> values, int requestedLimit) {
        if (requestedLimit <= 0 || values.size() <= requestedLimit) {
            return List.copyOf(values);
        }
        return List.copyOf(values.subList(0, requestedLimit));
    }

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> values) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            unique.putIfAbsent(String.valueOf(value.get("id")), value);
        }
        return new ArrayList<>(unique.values());
    }

    private int pageCount(int requestedLimit, int maxPages) {
        if (requestedLimit <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(maxPages, (int) Math.ceil((double) requestedLimit / TMDB_PAGE_SIZE)));
    }

    private String catalogType(String type) {
        String normalized = type == null || type.isBlank() ? TYPE_MOVIE : type.strip().toLowerCase(Locale.ROOT);
        return TYPE_MOVIE.equals(normalized) || TYPE_SERIES.equals(normalized) ? normalized : null;
    }

    private String publicItemId(String type, long tmdbId) {
        return ITEM_PREFIX + "~" + type + "~" + tmdbId;
    }

    private String publicEpisodeId(long seriesId, int season, int episode) {
        return ITEM_PREFIX + "~" + TYPE_SERIES + "~" + seriesId + "~s~" + season + "~e~" + episode;
    }

    private TmdbItemId parseItemId(String publicItemId) {
        String[] parts = String.valueOf(publicItemId).split("~");
        if (parts.length < 3 || !ITEM_PREFIX.equals(parts[0])) {
            throw ApiException.validation("Identifiant TMDB invalide");
        }
        String type = catalogType(parts[1]);
        if (type == null) {
            throw ApiException.validation("Type TMDB non pris en charge");
        }
        try {
            return new TmdbItemId(type, Long.parseLong(parts[2]));
        } catch (NumberFormatException exception) {
            throw ApiException.validation("Identifiant TMDB invalide");
        }
    }

    private String title(JsonNode item, String type) {
        return TYPE_SERIES.equals(type)
                ? text(item, "name", "original_name")
                : text(item, "title", "original_title");
    }

    private String releaseDate(JsonNode item, String type) {
        return TYPE_SERIES.equals(type) ? text(item, "first_air_date") : text(item, "release_date");
    }

    private String imageUrl(String path, String size) {
        return path == null || path.isBlank() ? "" : tmdb.imageUrl(path, size);
    }

    private String duration(JsonNode details, String type) {
        if (TYPE_SERIES.equals(type)) {
            JsonNode runtimes = details.path("episode_run_time");
            if (runtimes.isArray() && !runtimes.isEmpty()) {
                int minutes = runtimes.path(0).asInt(0);
                return minutes > 0 ? minutes + " min / episode" : null;
            }
            return null;
        }
        int minutes = details.path("runtime").asInt(0);
        return minutes > 0 ? minutes + " min" : null;
    }

    private List<String> directors(JsonNode details) {
        List<String> values = new ArrayList<>();
        JsonNode crew = details.path("credits").path("crew");
        if (!crew.isArray()) {
            return values;
        }
        for (JsonNode person : crew) {
            String job = text(person, "job");
            String department = text(person, "department");
            if ("Director".equalsIgnoreCase(job) || "Creator".equalsIgnoreCase(job)
                    || "Writing".equalsIgnoreCase(department) && TYPE_SERIES.equals(details.path("type").asText(""))) {
                String name = text(person, "name");
                if (name != null && !name.isBlank() && !values.contains(name)) {
                    values.add(name);
                }
            }
            if (values.size() >= 8) {
                break;
            }
        }
        if (values.isEmpty()) {
            return stringArray(details.path("created_by"), "name", 8);
        }
        return values;
    }

    private String countries(JsonNode details) {
        List<String> values = stringArray(details.path("production_countries"), "name", 4);
        if (values.isEmpty()) {
            values = stringArray(details.path("origin_country"), null, 4);
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private String movieCertification(JsonNode details) {
        JsonNode countries = details.path("release_dates").path("results");
        if (!countries.isArray()) {
            return null;
        }
        for (JsonNode country : countries) {
            if (!"FR".equalsIgnoreCase(text(country, "iso_3166_1"))) {
                continue;
            }
            String certification = firstCertification(country.path("release_dates"));
            if (certification != null) {
                return certification;
            }
        }
        return null;
    }

    private String tvCertification(JsonNode details) {
        JsonNode countries = details.path("content_ratings").path("results");
        if (!countries.isArray()) {
            return null;
        }
        for (JsonNode country : countries) {
            if ("FR".equalsIgnoreCase(text(country, "iso_3166_1"))) {
                return text(country, "rating");
            }
        }
        return null;
    }

    private String firstCertification(JsonNode values) {
        if (!values.isArray()) {
            return null;
        }
        for (JsonNode value : values) {
            String certification = text(value, "certification");
            if (certification != null && !certification.isBlank()) {
                return certification;
            }
        }
        return null;
    }

    private List<String> stringArray(JsonNode values, String field, int limit) {
        if (!values.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String text = field == null && value.isValueNode() ? value.asText("") : text(value, field);
            if (text != null && !text.isBlank() && !result.contains(text)) {
                result.add(text);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
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

    private String rating(double value) {
        return value <= 0 ? null : String.format(Locale.US, "%.1f", value);
    }

    private String releaseYear(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private String textOrDefault(JsonNode node, String fallback, String... fields) {
        String value = text(node, fields);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText().strip();
            }
        }
        return null;
    }

    public record CatalogAccessDescriptor(String categoryId, String categoryName, String contentType, boolean adult) {
    }

    private record TmdbItemId(String type, long tmdbId) {
    }

    private record Category(String id, String name, String type, Kind kind, String endpoint) {
    }

    private enum Kind {
        TRENDING,
        MOVIE_LIST,
        TV_LIST
    }
}
