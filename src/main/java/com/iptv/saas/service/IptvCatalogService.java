package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import com.iptv.saas.web.Responses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IptvCatalogService {
    public static final String ARCHIVED_BY_ADMIN = "archived-by-admin";
    private static final Logger LOGGER = LoggerFactory.getLogger(IptvCatalogService.class);
    private static final Pattern M3U_ITEM_ID = Pattern.compile("^m3u-(\\d+)-[a-f0-9]+$");
    private static final Pattern M3U_SERIES_ID = Pattern.compile("^m3u-series-(\\d+)-[a-f0-9]+$");
    private static final Pattern XTREAM_ITEM_ID = Pattern.compile(
            "^xtream-(\\d+)-(live|movie|series)-([a-zA-Z0-9_]+)$"
    );
    private static final Pattern XTREAM_SERIES_ID = Pattern.compile(
            "^xtream-series-(\\d+)-([a-zA-Z0-9]+)$"
    );
    private static final int MAX_BACKGROUND_CATALOG_REFRESHES = 2;
    private static final int MAX_BOOTSTRAP_CATALOG_LOADS = 8;
    private static final String CACHED_ONLY_STREAM_UNAVAILABLE_REASON =
            "Ce contenu est visible dans le catalogue cache, mais aucun compte IPTV actif ne possede ce flux.";
    private static final Set<String> BLOCKING_HEALTH_STATUSES = Set.of(
            "disabled",
            "expired",
            "misconfigured",
            "stream-failed",
            "unreachable"
    );

    private final IptvAccountRepository accounts;
    private final UserRepository users;
    private final M3uPlaylistService playlists;
    private final XtreamCatalogService xtream;
    private final ProviderMetadataService metadata;
    private final CatalogImageService images;
    private final AtomicLong roundRobinSequence = new AtomicLong();
    private static final Map<String, String> LANGUAGE_NAMES = languageNames();

    public IptvCatalogService(
            IptvAccountRepository accounts,
            M3uPlaylistService playlists,
            XtreamCatalogService xtream,
            ProviderMetadataService metadata,
            CatalogImageService images
    ) {
        this(accounts, null, playlists, xtream, metadata, images);
    }

    @Autowired
    public IptvCatalogService(
            IptvAccountRepository accounts,
            UserRepository users,
            M3uPlaylistService playlists,
            XtreamCatalogService xtream,
            ProviderMetadataService metadata,
            CatalogImageService images
    ) {
        this.accounts = accounts;
        this.users = users;
        this.playlists = playlists;
        this.xtream = xtream;
        this.metadata = metadata;
        this.images = images;
    }

    public List<Map<String, Object>> categories(String type) {
        return categories(null, type);
    }

    public List<Map<String, Object>> categories(UserEntity user, String type) {
        List<IptvAccount> catalogAccounts = browsableCatalogAccounts(user);
        if (catalogAccounts.isEmpty()) {
            if (user != null) {
                return List.of();
            }
            return demoCategories().stream()
                    .filter(category -> isType(category, type))
                    .toList();
        }

        Map<String, CategorySource> unique = new LinkedHashMap<>();
        for (CatalogSource source : availableSources(catalogAccounts)) {
            for (M3uPlaylistService.Category category : source.playlist().categories()) {
                if (type == null || type.isBlank() || category.type().equals(type)) {
                    unique.putIfAbsent(
                            categoryKey(category.type(), category.name()),
                            new CategorySource(source.account(), category)
                    );
                }
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(source -> source.category().name(), String.CASE_INSENSITIVE_ORDER))
                .map(source -> categoryPayload(source.category(), source.account()))
                .toList();
    }

    public boolean hasActiveSources() {
        return !browsableCatalogAccounts().isEmpty();
    }

    public boolean hasActiveSources(UserEntity user) {
        return !browsableCatalogAccounts(user).isEmpty();
    }

    public List<Map<String, Object>> items(String type, String query, String categoryId) {
        return items(type, query, categoryId, "default", 0);
    }

    public List<Map<String, Object>> items(String type, String query, String categoryId, int requestedLimit) {
        return items(type, query, categoryId, "default", requestedLimit);
    }

    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String sort,
            int requestedLimit
    ) {
        return items(null, type, query, categoryId, null, sort, requestedLimit);
    }
    public List<Map<String, Object>> items(
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit
    ) {
        return items(null, type, query, categoryId, language, sort, requestedLimit);
    }

    public List<Map<String, Object>> items(
            UserEntity user,
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit
    ) {
        return items(user, type, query, categoryId, language, sort, requestedLimit, true);
    }

    private List<Map<String, Object>> items(
            UserEntity user,
            String type,
            String query,
            String categoryId,
            String language,
            String sort,
            int requestedLimit,
            boolean userScoped
    ) {
        String normalizedType = type == null || type.isBlank() ? "live" : type;
        String normalizedQuery = normalizeIdentity(query);
        String normalizedSort = normalizedSort(sort);
        long limit = requestedLimit <= 0 ? Long.MAX_VALUE : requestedLimit;
        List<IptvAccount> catalogAccounts = userScoped ? browsableCatalogAccounts(user) : browsableCatalogAccounts();
        if (catalogAccounts.isEmpty()) {
            if (user != null) {
                return List.of();
            }
            return demoItems().stream()
                    .filter(item -> item.get("type").equals(normalizedType))
                    .filter(item -> categoryId == null || categoryId.isBlank() || item.get("categoryId").equals(categoryId))
                    .filter(item -> matchesQuery(item.get("name"), normalizedQuery))
                    .sorted(demoItemComparator(sort))
                    .limit(limit)
                    .toList();
        }

        List<CatalogSource> sources = availableSources(catalogAccounts);
        if ("series".equals(normalizedType)) {
            return mergedSeries(sources).stream()
                    .filter(source -> categoryMatches(
                            "series",
                            source.series().categoryId(),
                            source.series().categoryName(),
                            categoryId
                    ))
                    .filter(source -> languageMatches(source.series().categoryName(), language))
                    .filter(source -> matchesSeriesQuery(source.series(), normalizedQuery))
                    .sorted(Comparator.comparing(
                            source -> source.series().title(),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .limit(limit)
                    .map(source -> seriesPayload(source.series(), source.account()))
                    .toList();
        }

        Comparator<EntrySource> sourceComparator = (left, right) -> itemComparator(sort).compare(left.entry(), right.entry());
        Map<String, EntrySource> unique = new LinkedHashMap<>();
        PriorityQueue<EntrySource> limited = requestedLimit > 0
                ? new PriorityQueue<>(sourceComparator.reversed())
                : null;
        boolean fastPreview = requestedLimit > 0
                && normalizedQuery.isBlank()
                && (categoryId == null || categoryId.isBlank())
                && (language == null || language.isBlank())
                && "default".equals(normalizedSort);
        for (CatalogSource source : sources) {
            for (M3uPlaylistService.Entry entry : source.playlist().entries()) {
                if (!entry.type().equals(normalizedType)
                        || !categoryMatches(entry.type(), entry.categoryId(), entry.categoryName(), categoryId)
                        || !languageMatches(entry.categoryName(), language)
                        || !matchesEntryQuery(entry, normalizedQuery)) {
                    continue;
                }
                String key = contentKey(entry);
                EntrySource candidate = new EntrySource(source.account(), entry);
                EntrySource existing = unique.get(key);
                if (existing != null) {
                    EntrySource preferred = preferredEntry(existing, candidate);
                    if (preferred != existing) {
                        unique.put(key, preferred);
                        if (limited != null) {
                            limited.remove(existing);
                            limited.offer(preferred);
                        }
                    }
                    continue;
                }
                if (requestedLimit <= 0 || unique.size() < requestedLimit) {
                    unique.put(key, candidate);
                    if (limited != null) {
                        limited.offer(candidate);
                    }
                    if (fastPreview && unique.size() >= requestedLimit) {
                        return unique.values().stream()
                                .map(entrySource -> entryPayload(entrySource.entry(), entrySource.account()))
                                .toList();
                    }
                    continue;
                }
                EntrySource worst = limited == null ? null : limited.peek();
                if (worst != null && sourceComparator.compare(candidate, worst) < 0) {
                    unique.remove(contentKey(worst.entry()));
                    limited.poll();
                    unique.put(key, candidate);
                    limited.offer(candidate);
                }
            }
        }
        return unique.values().stream()
                .sorted(sourceComparator)
                .limit(limit)
                .map(source -> entryPayload(source.entry(), source.account()))
                .toList();
    }

    public List<Map<String, Object>> languages(String type) {
        return languages(null, type);
    }

    public List<Map<String, Object>> languages(UserEntity user, String type) {
        List<IptvAccount> catalogAccounts = browsableCatalogAccounts(user);
        if (catalogAccounts.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (CatalogSource source : availableSources(catalogAccounts)) {
            for (M3uPlaylistService.Category category : source.playlist().categories()) {
                if (type != null && !type.isBlank() && !type.equals(category.type())) {
                    continue;
                }
                String language = languageCode(category.name());
                if (language != null) {
                    found.computeIfAbsent(language, ignored -> new TreeSet<>()).add(category.type());
                }
            }
        }
        return found.entrySet().stream()
                .sorted(Comparator.comparing(entry -> LANGUAGE_NAMES.get(entry.getKey())))
                .map(entry -> Map.<String, Object>of(
                        "id", entry.getKey(),
                        "name", LANGUAGE_NAMES.get(entry.getKey()),
                        "types", List.copyOf(entry.getValue())
                ))
                .toList();
    }

    private Comparator<M3uPlaylistService.Entry> itemComparator(String sort) {
        Comparator<M3uPlaylistService.Entry> byTitle = Comparator.comparing(
                item -> sortableTitle(item.name()),
                String.CASE_INSENSITIVE_ORDER
        );
        return switch (normalizedSort(sort)) {
            case "title-desc" -> byTitle.reversed();
            case "category" -> Comparator
                    .comparing(M3uPlaylistService.Entry::categoryName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(byTitle);
            case "recent" -> Comparator
                    .comparingInt((M3uPlaylistService.Entry item) -> M3uPlaylistService.releaseYear(item.name()))
                    .reversed()
                    .thenComparing(byTitle);
            case "title-asc" -> byTitle;
            default -> (left, right) -> 0;
        };
    }

    private Comparator<Map<String, Object>> demoItemComparator(String sort) {
        Comparator<Map<String, Object>> byTitle = Comparator.comparing(
                item -> sortableTitle(String.valueOf(item.get("name"))),
                String.CASE_INSENSITIVE_ORDER
        );
        return switch (normalizedSort(sort)) {
            case "title-desc" -> byTitle.reversed();
            case "category" -> Comparator.<Map<String, Object>, String>comparing(
                            item -> String.valueOf(item.get("categoryName")),
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(byTitle);
            case "recent" -> Comparator
                    .comparingInt((Map<String, Object> item) ->
                            M3uPlaylistService.releaseYear(String.valueOf(item.get("name"))))
                    .reversed()
                    .thenComparing(byTitle);
            case "title-asc" -> byTitle;
            default -> (left, right) -> 0;
        };
    }

    private String normalizedSort(String sort) {
        return sort == null ? "default" : sort.strip().toLowerCase(Locale.ROOT);
    }

    static String sortableTitle(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
        return normalized.replaceFirst("^(le|la|les|un|une|des|the|a|an)\\s+", "");
    }

    public Map<String, Object> seriesInfo(String seriesId) {
        return seriesInfo(seriesId, null);
    }

    public Map<String, Object> seriesInfo(String seriesId, String titleHint) {
        return seriesInfo(null, seriesId, titleHint);
    }

    public Map<String, Object> seriesInfo(UserEntity user, String seriesId, String titleHint) {
        Matcher xtreamMatcher = XTREAM_SERIES_ID.matcher(seriesId == null ? "" : seriesId);
        if (xtreamMatcher.matches()) {
            if (user != null) {
                throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
            }
            Long accountId = Long.parseLong(xtreamMatcher.group(1));
            IptvAccount account = findCatalogAccount(accountId, Enums.IptvAccountType.XTREAM);
            requireUserCatalogAccess(user, account);
            boolean usable = isCatalogAccountUsable(account, Enums.IptvAccountType.XTREAM);
            M3uPlaylistService.Series summary = titleHint == null || titleHint.isBlank()
                    ? seriesSummary(account, seriesId, usable)
                    : new M3uPlaylistService.Series(
                            seriesId,
                            titleHint,
                            "",
                            "Séries",
                            "",
                            List.of()
                    );
            return mergedSeriesDetails(
                    new SeriesSource(account, summary),
                    titleHint != null && !titleHint.isBlank() || !usable,
                    activeCatalogAccounts(user)
            );
        }

        Matcher matcher = M3U_SERIES_ID.matcher(seriesId == null ? "" : seriesId);
        if (matcher.matches()) {
            Long accountId = Long.parseLong(matcher.group(1));
            IptvAccount account = findCatalogAccount(accountId, Enums.IptvAccountType.M3U);
            requireUserCatalogAccess(user, account);
            boolean usable = isCatalogAccountUsable(account, Enums.IptvAccountType.M3U);
            M3uPlaylistService.Series requested = usable
                    ? playlists.load(account).findSeries(seriesId)
                    : cachedM3uPlaylist(account)
                    .map(playlist -> playlist.findSeries(seriesId))
                    .orElseThrow(() -> ApiException.streamUnavailable(
                            "Cette serie n'est plus disponible sur un compte IPTV actif"
                    ));
            return mergedSeriesDetails(new SeriesSource(account, requested), !usable, activeCatalogAccounts(user));
        }

        Matcher episodeMatcher = M3U_ITEM_ID.matcher(seriesId == null ? "" : seriesId);
        if (episodeMatcher.matches()) {
            Long accountId = Long.parseLong(episodeMatcher.group(1));
            IptvAccount account = findCatalogAccount(accountId, Enums.IptvAccountType.M3U);
            requireUserCatalogAccess(user, account);
            boolean usable = isCatalogAccountUsable(account, Enums.IptvAccountType.M3U);
            M3uPlaylistService.Playlist playlist = usable
                    ? playlists.load(account)
                    : cachedM3uPlaylist(account)
                    .orElseThrow(() -> ApiException.streamUnavailable(
                            "Cet episode n'est plus disponible sur un compte IPTV actif"
                    ));
            M3uPlaylistService.Entry episode = playlist.find(seriesId);
            if ("series".equals(episode.type()) && episode.seriesId() != null) {
                return mergedSeriesDetails(
                        new SeriesSource(account, playlist.findSeries(episode.seriesId())),
                        !usable,
                        activeCatalogAccounts(user)
                );
            }
        }

        Map<String, Object> body = Responses.map();
        body.put("id", seriesId);
        body.put("name", "Demo Series " + seriesId);
        body.put("type", "series");
        body.put("categoryId", "series-drama");
        body.put("categoryName", "Drama");
        body.put("poster", "");
        body.put("seasonCount", 1);
        body.put("episodeCount", 2);
        body.put("isSeries", true);
        body.put("seasons", List.of(
                Map.of("season", 1, "episodes", List.of(
                        Map.of("id", seriesId + "-s1e1", "name", "Épisode 1", "type", "series", "season", 1, "episode", 1, "isEpisode", true),
                        Map.of("id", seriesId + "-s1e2", "name", "Épisode 2", "type", "series", "season", 1, "episode", 2, "isEpisode", true)
                ))
        ));
        return body;
    }

    public Map<String, Object> itemInfo(String itemId) {
        return itemInfo(null, itemId);
    }

    public Map<String, Object> itemInfo(UserEntity user, String itemId) {
        if (user != null) {
            String normalizedItemId = itemId == null ? "" : itemId;
            if (XTREAM_ITEM_ID.matcher(normalizedItemId).matches()
                    || XTREAM_SERIES_ID.matcher(normalizedItemId).matches()) {
                throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
            }
            EntrySource requested = requestedEntry(itemId == null ? "" : itemId, true);
            if (requested == null) {
                if (isCatalogPublicItemId(itemId)) {
                    throw ApiException.streamUnavailable("Ce contenu n'est plus disponible sur votre compte IPTV assigne");
                }
                return demoItems().stream()
                        .filter(item -> String.valueOf(item.get("id")).equals(itemId))
                        .findFirst()
                        .map(LinkedHashMap::new)
                        .orElseThrow(() -> ApiException.notFound("Programme introuvable"));
            }
            requireUserCatalogAccess(user, requested.account());
            if (requested.account().accountType != Enums.IptvAccountType.M3U) {
                throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
            }
            return itemInfo(requested);
        }
        EntrySource source = resolvedItemInfoSource(itemId == null ? "" : itemId);
        if (source != null) {
            return itemInfo(source);
        }

        return demoItems().stream()
                .filter(item -> String.valueOf(item.get("id")).equals(itemId))
                .findFirst()
                .map(LinkedHashMap::new)
                .map(item -> {
                    item.put("summary", "Une sélection de démonstration du catalogue Nexora.");
                    item.put("genres", List.of(String.valueOf(item.get("categoryName"))));
                    item.put("metadataAvailable", true);
                    return (Map<String, Object>) item;
                })
                .orElseThrow(() -> ApiException.notFound("Programme introuvable"));
    }

    public String categoryIdForItem(String itemId) {
        return accessForItem(itemId).categoryId();
    }

    public CatalogAccessDescriptor accessForItem(String itemId) {
        return accessForItem(null, itemId);
    }

    public CatalogAccessDescriptor accessForItem(UserEntity user, String itemId) {
        String normalizedItemId = itemId == null ? "" : itemId;
        if (user != null && (XTREAM_ITEM_ID.matcher(normalizedItemId).matches()
                || XTREAM_SERIES_ID.matcher(normalizedItemId).matches())) {
            throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
        }
        EntrySource requested = requestedEntry(normalizedItemId, true);
        if (requested != null) {
            requireUserCatalogAccess(user, requested.account());
            if (user != null && requested.account().accountType != Enums.IptvAccountType.M3U) {
                throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
            }
            return new CatalogAccessDescriptor(
                    canonicalCategoryId(requested.entry().type(), requested.entry().categoryName()),
                    requested.entry().categoryName(),
                    requested.entry().type(),
                    false
            );
        }
        Matcher xtreamMatcher = XTREAM_ITEM_ID.matcher(normalizedItemId);
        if (xtreamMatcher.matches()) {
            if (user != null) {
                throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
            }
            return new CatalogAccessDescriptor(null, null, xtreamMatcher.group(2), false);
        }
        return demoItems().stream()
                .filter(item -> String.valueOf(item.get("id")).equals(itemId))
                .map(item -> new CatalogAccessDescriptor(
                        stringValue(item.get("categoryId")),
                        stringValue(item.get("categoryName")),
                        stringValue(item.get("type")),
                        Boolean.TRUE.equals(item.get("adult"))
                ))
                .findFirst()
                .orElse(new CatalogAccessDescriptor(null, null, "all", false));
    }

    public List<Map<String, Object>> liveGroups() {
        return categories("live");
    }

    public List<Map<String, Object>> liveGroups(UserEntity user) {
        return categories(user, "live");
    }

    public List<Map<String, Object>> liveChannels() {
        return items("live", null, null);
    }

    public List<Map<String, Object>> liveChannels(UserEntity user) {
        return items(user, "live", null, null, null, "default", 0);
    }

    @Transactional
    public IptvAccount saveAccount(Long id, String name, Enums.IptvAccountType type, String baseUrl, String username,
                                   String password, String playlistUrl, Integer maxStreams, Boolean active,
                                   Instant expiresAt) {
        return saveAccount(id, name, type, baseUrl, username, password, playlistUrl, maxStreams, active, expiresAt, null);
    }

    @Transactional
    public IptvAccount saveAccount(Long id, String name, Enums.IptvAccountType type, String baseUrl, String username,
                                   String password, String playlistUrl, Integer maxStreams, Boolean active,
                                   Instant expiresAt, Long assignedUserId) {
        boolean update = id != null;
        IptvAccount account = id == null ? new IptvAccount() : accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        if (name != null && !name.isBlank()) account.name = name;
        if (type != null) account.accountType = type;
        if (account.accountType == Enums.IptvAccountType.M3U
                && (playlistUrl == null || playlistUrl.isBlank())
                && baseUrl != null
                && isHttpUrl(baseUrl)) {
            playlistUrl = baseUrl;
        }
        if (baseUrl != null && (!update || !baseUrl.isBlank())) account.baseUrl = trimSlash(baseUrl);
        if (username != null && (!update || !username.isBlank())) account.username = username.strip();
        if (password != null && (!update || !password.isBlank())) account.password = password.strip();
        if (playlistUrl != null && (!update || !playlistUrl.isBlank())) account.playlistUrl = playlistUrl.strip();
        if (maxStreams != null) account.maxStreams = maxStreams;
        if (active != null) account.active = active;
        if (expiresAt != null) account.expiresAt = expiresAt;
        if (assignedUserId != null && assignedUserId <= 0) {
            account.assignedUser = null;
        } else if (assignedUserId != null) {
            if (users == null) {
                throw ApiException.validation("Assignation utilisateur indisponible");
            }
            account.assignedUser = users.findById(assignedUserId)
                    .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        } else if (!update) {
            account.assignedUser = null;
        }
        if (account.accountType == Enums.IptvAccountType.M3U
                && (account.playlistUrl == null || account.playlistUrl.isBlank())) {
            throw ApiException.validation("URL de playlist obligatoire pour un compte M3U");
        }
        if (account.accountType == Enums.IptvAccountType.XTREAM && isXtreamMisconfigured(account)) {
            throw ApiException.validation("URL de base, nom d'utilisateur et mot de passe obligatoires pour un compte Xtream");
        }
        account.lastHealthStatus = health(account);
        account = accounts.save(account);
        playlists.invalidate(account.id);
        xtream.invalidate(account.id);
        metadata.invalidate(account.id);
        return account;
    }

    @Transactional
    public void deleteAccount(Long id) {
        IptvAccount account = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        account.active = false;
        account.disabled = true;
        account.activeStreams = 0;
        account.lastHealthStatus = "archived";
        account.disabledReason = ARCHIVED_BY_ADMIN;
        account.baseUrl = null;
        account.username = null;
        account.password = null;
        account.playlistUrl = null;
        accounts.save(account);
        playlists.invalidate(id);
        xtream.invalidate(id);
        metadata.invalidate(id);
    }

    @Transactional
    public Map<String, Object> syncLimits(Long id) {
        IptvAccount account = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        boolean detected = false;
        if (account.accountType == Enums.IptvAccountType.XTREAM) {
            XtreamCatalogService.AccountLimits limits = xtream.accountLimits(account);
            if (limits.maxConnections() > 0) {
                account.maxStreams = limits.maxConnections();
                detected = true;
            } else if (account.maxStreams <= 0) {
                account.maxStreams = 1;
            }
            if (limits.activeConnections() >= 0) {
                account.activeStreams = limits.activeConnections();
            }
        }
        if (account.maxStreams <= 0) {
            account.maxStreams = 1;
        }
        account.lastHealthStatus = health(account);
        accounts.save(account);
        return Map.of(
                "maxStreams", account.maxStreams,
                "activeStreams", account.activeStreams,
                "health", account.lastHealthStatus,
                "detected", detected
        );
    }

    public Map<String, Object> refreshCache(Long id) {
        if (!accounts.existsById(id)) {
            throw ApiException.notFound("Compte IPTV introuvable");
        }
        playlists.invalidate(id);
        xtream.invalidate(id);
        metadata.invalidate(id);
        accounts.findById(id).ifPresent(account -> {
            if (account.active && !account.disabled) {
                account.lastHealthStatus = "refresh-pending";
                accounts.save(account);
            }
        });
        return Map.of("accountId", id, "cacheCleared", true);
    }

    public StreamSelection selectStream(String type, String itemId) {
        return selectStream(type, itemId, Set.of());
    }

    public StreamSelection selectStream(String type, String itemId, Long excludedAccountId) {
        return selectStream(
                type,
                itemId,
                excludedAccountId == null ? Set.of() : Set.of(excludedAccountId)
        );
    }

    public StreamSelection selectStream(String type, String itemId, Set<Long> excludedAccountIds) {
        Set<Long> excluded = excludedAccountIds == null ? Set.of() : excludedAccountIds;
        String normalizedItemId = itemId == null ? "" : itemId;
        if (M3U_SERIES_ID.matcher(normalizedItemId).matches()
                || XTREAM_SERIES_ID.matcher(normalizedItemId).matches()) {
            throw ApiException.validation("Sélectionnez un épisode avant de lancer la lecture");
        }
        EntrySource requestedSource = requestedEntry(normalizedItemId, true);
        if (requestedSource != null) {
            M3uPlaylistService.Entry requested = requestedSource.entry();
            String key = contentKey(requested);
            Map<Long, EntrySource> candidatesByAccount = new LinkedHashMap<>();
            if (!excluded.contains(requestedSource.account().id)
                    && canServeStream(requestedSource.account())) {
                if (excluded.isEmpty()
                        && requestedSource.account().activeStreams == 0
                        && !hasRecentStreamFailure(requestedSource.account())) {
                    return new StreamSelection(
                            requestedSource.account(),
                            requestedSource.entry().streamUrl(),
                            requestedSource.entry().id()
                    );
                }
                candidatesByAccount.put(requestedSource.account().id, requestedSource);
                if (requestedSource.account().activeStreams == 0
                        && !hasRecentStreamFailure(requestedSource.account())) {
                    return new StreamSelection(
                            requestedSource.account(),
                            requestedSource.entry().streamUrl(),
                            requestedSource.entry().id()
                    );
                }
            }
            List<IptvAccount> selectableAccounts = activeCatalogAccounts().stream()
                    .filter(account -> !excluded.contains(account.id))
                    .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                    .toList();
            for (CatalogSource source : availableSources(selectableAccounts)) {
                List<EntrySource> equivalents = equivalentEntries(source, requested);
                if (equivalents.isEmpty() && !Objects.equals(source.account().id, requestedSource.account().id)) {
                    LOGGER.info(
                            "Flux absent sur compte IPTV {} pour '{}': type={}, contenuCle={}",
                            source.account().id,
                            requested.name(),
                            requested.type(),
                            key
                    );
                }
                equivalents.stream()
                        .findFirst()
                        .ifPresent(entry -> candidatesByAccount.put(source.account().id, entry));
            }
            if (candidatesByAccount.isEmpty()
                    && !excluded.contains(requestedSource.account().id)
                    && isCatalogAccountUsable(requestedSource.account(), requestedSource.account().accountType)
                    && requestedSource.account().maxStreams > 0
                    && requestedSource.account().activeStreams >= requestedSource.account().maxStreams) {
                LOGGER.info(
                        "Source IPTV unique occupee, tentative optimiste: compte={}, contenu='{}', type={}, contenuCle={}",
                        requestedSource.account().id,
                        requested.name(),
                        requested.type(),
                        key
                );
                return new StreamSelection(
                        requestedSource.account(),
                        requestedSource.entry().streamUrl(),
                        requestedSource.entry().id()
                );
            }
            if (candidatesByAccount.isEmpty()) {
                throw ApiException.streamUnavailable(
                        "Ce contenu n'est disponible sur aucun autre compte IPTV actif"
                );
            }
            EntrySource selected = selectEntrySource(new ArrayList<>(candidatesByAccount.values()));
            return new StreamSelection(selected.account(), selected.entry().streamUrl(), selected.entry().id());
        }
        if (isCatalogPublicItemId(normalizedItemId)) {
            throw ApiException.streamUnavailable("Ce contenu n'est plus disponible sur un compte IPTV actif");
        }
        IptvAccount account = bestAvailableAccount(excluded);
        return new StreamSelection(account, buildXtreamStreamUrl(account, type, itemId));
    }

    public StreamSelection selectStream(UserEntity user, String type, String itemId) {
        return selectStream(user, type, itemId, Set.of());
    }

    public StreamSelection selectStream(UserEntity user, String type, String itemId, Set<Long> excludedAccountIds) {
        if (user == null || user.id == null) {
            throw ApiException.forbidden("Utilisateur requis pour lancer le compte IPTV assigne");
        }
        Set<Long> excluded = excludedAccountIds == null ? Set.of() : excludedAccountIds;
        String normalizedItemId = itemId == null ? "" : itemId;
        if (M3U_SERIES_ID.matcher(normalizedItemId).matches()) {
            throw ApiException.validation("Selectionnez un episode avant de lancer la lecture");
        }
        if (XTREAM_SERIES_ID.matcher(normalizedItemId).matches()
                || XTREAM_ITEM_ID.matcher(normalizedItemId).matches()) {
            throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
        }

        EntrySource requestedSource = requestedEntry(normalizedItemId, true);
        if (requestedSource != null) {
            IptvAccount requestedAccount = requestedSource.account();
            if (!isAssignedToUser(requestedAccount, user)) {
                throw ApiException.forbidden("Ce compte IPTV n'est pas attribue a votre utilisateur");
            }
            if (requestedAccount.accountType != Enums.IptvAccountType.M3U) {
                throw ApiException.streamUnavailable("La lecture client accepte uniquement les liens M3U assignes");
            }
            if (excluded.contains(requestedAccount.id) || !canServeStream(requestedAccount)) {
                throw ApiException.streamUnavailable("Votre compte IPTV assigne ne peut pas servir ce contenu");
            }
            return new StreamSelection(
                    requestedAccount,
                    requestedSource.entry().streamUrl(),
                    requestedSource.entry().id()
            );
        }

        throw ApiException.streamUnavailable("Selectionnez un contenu provenant de votre playlist M3U assignee");
    }

    private EntrySource requestedEntry(String itemId) {
        return requestedEntry(itemId, false);
    }

    private EntrySource requestedEntry(String itemId, boolean allowReusableCache) {
        Matcher m3uMatcher = M3U_ITEM_ID.matcher(itemId);
        if (m3uMatcher.matches()) {
            IptvAccount account = findCatalogAccount(Long.parseLong(m3uMatcher.group(1)), Enums.IptvAccountType.M3U);
            if (!isCatalogAccountUsable(account, Enums.IptvAccountType.M3U)) {
                if (allowReusableCache) {
                    EntrySource cached = cachedM3uPlaylist(account)
                            .flatMap(playlist -> cachedEntry(playlist, itemId))
                            .map(entry -> new EntrySource(account, entry))
                            .orElse(null);
                    if (cached != null) {
                        return cached;
                    }
                    if (canReadRequestedEntryForFailover(account, Enums.IptvAccountType.M3U)) {
                        return new EntrySource(account, playlists.load(account).find(itemId));
                    }
                    return null;
                }
                throw catalogAccountUnavailable(account, Enums.IptvAccountType.M3U);
            }
            return new EntrySource(account, playlists.load(account).find(itemId));
        }
        Matcher xtreamMatcher = XTREAM_ITEM_ID.matcher(itemId);
        if (xtreamMatcher.matches()) {
            IptvAccount account = findCatalogAccount(
                    Long.parseLong(xtreamMatcher.group(1)),
                    Enums.IptvAccountType.XTREAM
            );
            if ("series".equals(xtreamMatcher.group(2))) {
                String compositeId = xtreamMatcher.group(3);
                int separator = compositeId.indexOf('_');
                if (separator < 1) {
                    throw ApiException.validation("Identifiant d'episode Xtream invalide");
                }
                String seriesPublicId = "xtream-series-" + account.id
                        + "-" + compositeId.substring(0, separator);
                if (!isCatalogAccountUsable(account, Enums.IptvAccountType.XTREAM)) {
                    if (allowReusableCache) {
                        EntrySource cached = cachedXtreamSeries(account, seriesPublicId)
                                .flatMap(series -> cachedEntry(series.episodes(), itemId))
                                .map(entry -> new EntrySource(account, entry))
                                .orElse(null);
                        if (cached != null) {
                            return cached;
                        }
                        if (canReadRequestedEntryForFailover(account, Enums.IptvAccountType.XTREAM)) {
                            M3uPlaylistService.Series series = xtream.loadSeries(account, seriesPublicId);
                            return new EntrySource(account, series.episodes().stream()
                                    .filter(entry -> entry.id().equals(itemId))
                                    .findFirst()
                                    .orElseThrow(() -> ApiException.notFound("Episode Xtream introuvable")));
                        }
                        return null;
                    }
                    throw catalogAccountUnavailable(account, Enums.IptvAccountType.XTREAM);
                }
                M3uPlaylistService.Series series = xtream.loadSeries(account, seriesPublicId);
                return new EntrySource(account, series.episodes().stream()
                        .filter(entry -> entry.id().equals(itemId))
                        .findFirst()
                        .orElseThrow(() -> ApiException.notFound("Episode Xtream introuvable")));
            }
            if (!isCatalogAccountUsable(account, Enums.IptvAccountType.XTREAM)) {
                if (allowReusableCache) {
                    EntrySource cached = cachedXtreamPlaylist(account)
                            .flatMap(playlist -> cachedEntry(playlist, itemId))
                            .map(entry -> new EntrySource(account, entry))
                            .orElse(null);
                    if (cached != null) {
                        return cached;
                    }
                    if (canReadRequestedEntryForFailover(account, Enums.IptvAccountType.XTREAM)) {
                        return new EntrySource(account, xtream.load(account).find(itemId));
                    }
                    return null;
                }
                throw catalogAccountUnavailable(account, Enums.IptvAccountType.XTREAM);
            }
            return new EntrySource(account, xtream.load(account).find(itemId));
        }
        return null;
    }

    private boolean canReadRequestedEntryForFailover(IptvAccount account, Enums.IptvAccountType type) {
        return account != null
                && account.accountType == type
                && account.active
                && !account.disabled
                && (account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                && !isMisconfigured(account)
                && "stream-failed".equals(normalizedHealthStatus(account));
    }

    private EntrySource resolvedItemInfoSource(String itemId) {
        EntrySource requested = requestedEntry(itemId, true);
        if (requested == null) {
            if (isCatalogPublicItemId(itemId)) {
                throw ApiException.streamUnavailable("Ce contenu n'est plus disponible sur un compte IPTV actif");
            }
            return null;
        }
        if (isCatalogAccountUsable(requested.account(), requested.account().accountType)) {
            return requested;
        }
        Optional<EntrySource> alternative = activeEquivalentEntry(requested, false);
        if (alternative.isEmpty()) {
            LOGGER.info(
                    "Fiche catalogue cachee sans source active: compte={}, item={}, type={}, contenu='{}'",
                    requested.account().id,
                    itemId,
                    requested.entry().type(),
                    requested.entry().name()
            );
            return requested;
        }
        LOGGER.info(
                "Fiche catalogue resolue via une source active: ancienCompte={}, nouveauCompte={}, item={}",
                requested.account().id,
                alternative.get().account().id,
                itemId
        );
        return alternative.get();
    }

    private Map<String, Object> itemInfo(EntrySource source) {
        IptvAccount account = source.account();
        M3uPlaylistService.Entry entry = source.entry();
        if (!isCatalogAccountUsable(account, account.accountType)) {
            Map<String, Object> payload = entryPayload(entry, account);
            payload.put("metadataAvailable", false);
            payload.put("streamAvailable", false);
            payload.put("streamUnavailableReason", CACHED_ONLY_STREAM_UNAVAILABLE_REASON);
            payload.putIfAbsent("summary", CACHED_ONLY_STREAM_UNAVAILABLE_REASON);
            return payload;
        }
        if ("series".equals(entry.type())
                && entry.seriesId() != null
                && account.accountType == Enums.IptvAccountType.M3U) {
            return mergedSeriesDetails(
                    new SeriesSource(account, playlists.load(account).findSeries(entry.seriesId())),
                    false
            );
        }
        if ("movie".equals(entry.type())) {
            Map<String, Object> details = new LinkedHashMap<>(metadata.movieDetails(account, entry));
            details.put("categoryId", canonicalCategoryId(entry.type(), entry.categoryName()));
            applyLanguage(details, entry.categoryName());
            applyPrivateAccess(details, account);
            images.rewrite(details);
            return details;
        }
        return entryPayload(entry, account);
    }

    private Optional<EntrySource> activeEquivalentEntry(EntrySource requested, boolean requireCapacity) {
        String key = contentKey(requested.entry());
        Map<Long, EntrySource> candidatesByAccount = new LinkedHashMap<>();
        List<IptvAccount> candidateAccounts = activeCatalogAccounts().stream()
                .filter(account -> !requireCapacity
                        || account.maxStreams <= 0
                        || account.activeStreams < account.maxStreams)
                .toList();
        for (CatalogSource source : availableSources(candidateAccounts)) {
            List<EntrySource> equivalents = equivalentEntries(source, requested.entry());
            if (equivalents.isEmpty() && !Objects.equals(source.account().id, requested.account().id)) {
                LOGGER.info(
                        "Flux absent sur compte IPTV {} pour '{}': type={}, contenuCle={}",
                        source.account().id,
                        requested.entry().name(),
                        requested.entry().type(),
                        key
                );
            }
            equivalents.stream()
                    .findFirst()
                    .ifPresent(entry -> candidatesByAccount.put(source.account().id, entry));
        }
        if (candidatesByAccount.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(selectEntrySource(new ArrayList<>(candidatesByAccount.values())));
    }

    private M3uPlaylistService.Series seriesSummary(
            IptvAccount account,
            String seriesId,
            boolean accountUsable
    ) {
        if (accountUsable) {
            return xtream.load(account).findSeries(seriesId);
        }
        return cachedXtreamPlaylist(account)
                .map(playlist -> playlist.findSeries(seriesId))
                .orElseThrow(() -> ApiException.streamUnavailable(
                        "Cette serie n'est plus disponible sur un compte IPTV actif"
                ));
    }

    private Optional<M3uPlaylistService.Playlist> cachedM3uPlaylist(IptvAccount account) {
        Optional<M3uPlaylistService.Playlist> cached = playlists.loadReusableCache(account);
        return cached == null ? Optional.empty() : cached;
    }

    private Optional<M3uPlaylistService.Playlist> cachedXtreamPlaylist(IptvAccount account) {
        Optional<M3uPlaylistService.Playlist> cached = xtream.loadReusableCache(account);
        return cached == null ? Optional.empty() : cached;
    }

    private Optional<M3uPlaylistService.Series> cachedXtreamSeries(
            IptvAccount account,
            String seriesPublicId
    ) {
        Optional<M3uPlaylistService.Series> cached = xtream.loadReusableSeriesCache(account, seriesPublicId);
        return cached == null ? Optional.empty() : cached;
    }

    private Optional<M3uPlaylistService.Entry> cachedEntry(
            M3uPlaylistService.Playlist playlist,
            String itemId
    ) {
        return cachedEntry(playlist.entries(), itemId);
    }

    private Optional<M3uPlaylistService.Entry> cachedEntry(
            List<M3uPlaylistService.Entry> entries,
            String itemId
    ) {
        return entries.stream()
                .filter(entry -> entry.id().equals(itemId))
                .findFirst();
    }

    private boolean isCatalogPublicItemId(String itemId) {
        String normalized = itemId == null ? "" : itemId;
        return M3U_ITEM_ID.matcher(normalized).matches()
                || XTREAM_ITEM_ID.matcher(normalized).matches();
    }

    private IptvAccount requireM3uAccount(Long accountId) {
        return requireCatalogAccount(accountId, Enums.IptvAccountType.M3U);
    }

    private IptvAccount requireCatalogAccount(Long accountId, Enums.IptvAccountType type) {
        IptvAccount account = findCatalogAccount(accountId, type);
        if (!isCatalogAccountUsable(account, type)) {
            throw catalogAccountUnavailable(account, type);
        }
        return account;
    }

    private IptvAccount findCatalogAccount(Long accountId, Enums.IptvAccountType type) {
        IptvAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Compte IPTV introuvable"));
        if (account.accountType != type) {
            throw ApiException.serviceUnavailable("Compte IPTV indisponible");
        }
        return account;
    }

    private ApiException catalogAccountUnavailable(IptvAccount account, Enums.IptvAccountType type) {
        if (account == null || account.accountType != type || !account.active || account.disabled) {
            return ApiException.serviceUnavailable("Compte IPTV indisponible");
        }
        if (account.expiresAt != null && account.expiresAt.isBefore(Instant.now())) {
            return ApiException.serviceUnavailable("Compte IPTV expire");
        }
        if (isMisconfigured(account)) {
            return ApiException.serviceUnavailable("Compte IPTV mal configure");
        }
        return ApiException.serviceUnavailable("Compte IPTV indisponible");
    }

    private boolean isCatalogAccountUsable(IptvAccount account, Enums.IptvAccountType type) {
        return account != null
                && account.accountType == type
                && account.active
                && !account.disabled
                && (account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                && !isMisconfigured(account)
                && !hasBlockingHealthStatus(account);
    }

    private boolean canServeStream(IptvAccount account) {
        return isCatalogAccountUsable(account, account == null ? null : account.accountType)
                && (account.maxStreams <= 0 || account.activeStreams < account.maxStreams);
    }

    private String buildXtreamStreamUrl(IptvAccount account, String type, String itemId) {
        if (isXtreamMisconfigured(account)) {
            throw ApiException.serviceUnavailable("Compte Xtream incomplet");
        }
        String base = trimSlash(account.baseUrl);
        String user = account.username.strip();
        String pass = account.password.strip();
        return switch (type == null ? "live" : type) {
            case "movie", "vod" -> base + "/movie/" + user + "/" + pass + "/" + itemId + ".mp4";
            case "series" -> base + "/series/" + user + "/" + pass + "/" + itemId + ".mp4";
            default -> base + "/live/" + user + "/" + pass + "/" + itemId + ".ts";
        };
    }

    public String health(IptvAccount account) {
        if (!account.active || account.disabled) {
            return "disabled";
        }
        if (account.expiresAt != null && account.expiresAt.isBefore(Instant.now())) {
            return "expired";
        }
        if (isMisconfigured(account)) {
            return "misconfigured";
        }
        if (hasBlockingHealthStatus(account)) {
            return normalizedHealthStatus(account);
        }
        if (account.maxStreams > 0 && account.activeStreams >= account.maxStreams) {
            return "saturated";
        }
        return "ok";
    }

    @Transactional(readOnly = true)
    public IptvAccount bestAvailableAccount() {
        return bestAvailableAccount(Set.of());
    }

    private IptvAccount bestAvailableAccount(Set<Long> excludedAccountIds) {
        Set<Long> excluded = excludedAccountIds == null ? Set.of() : excludedAccountIds;
        List<IptvAccount> candidates = accounts.findByActiveTrueAndDisabledFalse().stream()
                .filter(account -> !excluded.contains(account.id))
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> !isMisconfigured(account))
                .filter(account -> !hasBlockingHealthStatus(account))
                .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                .toList();
        if (candidates.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV disponible");
        }
        List<IptvAccount> preferredCandidates = accountsWithoutRecentStreamFailures(candidates);
        double minimumLoad = preferredCandidates.stream().mapToDouble(this::loadRatio).min().orElse(0);
        List<IptvAccount> tied = preferredCandidates.stream()
                .filter(account -> Double.compare(loadRatio(account), minimumLoad) == 0)
                .sorted(Comparator.comparing(account -> account.id))
                .toList();
        return tied.get(roundRobinIndex(tied.size()));
    }

    private IptvAccount bestAssignedAccount(UserEntity user, Set<Long> excludedAccountIds) {
        Set<Long> excluded = excludedAccountIds == null ? Set.of() : excludedAccountIds;
        List<IptvAccount> assignedAccounts = accounts.findByAssignedUser_IdAndActiveTrueAndDisabledFalse(user.id);
        if (assignedAccounts == null) {
            assignedAccounts = List.of();
        }
        List<IptvAccount> candidates = assignedAccounts.stream()
                .filter(account -> !excluded.contains(account.id))
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> !isMisconfigured(account))
                .filter(account -> !hasBlockingHealthStatus(account))
                .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                .toList();
        if (candidates.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV actif n'est attribue a votre utilisateur");
        }
        List<IptvAccount> preferredCandidates = accountsWithoutRecentStreamFailures(candidates);
        double minimumLoad = preferredCandidates.stream().mapToDouble(this::loadRatio).min().orElse(0);
        List<IptvAccount> tied = preferredCandidates.stream()
                .filter(account -> Double.compare(loadRatio(account), minimumLoad) == 0)
                .sorted(Comparator.comparing(account -> account.id))
                .toList();
        return tied.get(roundRobinIndex(tied.size()));
    }

    private boolean isAssignedToUser(IptvAccount account, UserEntity user) {
        return account != null
                && account.assignedUser != null
                && account.assignedUser.id != null
                && user != null
                && Objects.equals(account.assignedUser.id, user.id);
    }

    private List<IptvAccount> activeCatalogAccounts() {
        return accounts.findByActiveTrueAndDisabledFalse().stream()
                .filter(account -> account.accountType == Enums.IptvAccountType.M3U
                        || account.accountType == Enums.IptvAccountType.XTREAM)
                .filter(account -> account.assignedUser == null)
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> !isMisconfigured(account))
                .filter(account -> !hasBlockingHealthStatus(account))
                .toList();
    }

    private List<IptvAccount> activeCatalogAccounts(UserEntity user) {
        if (user == null) {
            return activeCatalogAccounts();
        }
        List<IptvAccount> assignedAccounts = accounts.findByAssignedUser_IdAndActiveTrueAndDisabledFalse(user.id);
        if (assignedAccounts == null) {
            return List.of();
        }
        return assignedAccounts.stream()
                .filter(account -> account.accountType == Enums.IptvAccountType.M3U)
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> !isMisconfigured(account))
                .filter(account -> !hasBlockingHealthStatus(account))
                .toList();
    }

    private List<IptvAccount> browsableCatalogAccounts() {
        return browsableCatalogAccounts(null);
    }

    private List<IptvAccount> browsableCatalogAccounts(UserEntity user) {
        if (user != null) {
            Map<Long, IptvAccount> browsable = new LinkedHashMap<>();
            activeCatalogAccounts(user).forEach(account -> browsable.put(account.id, account));
            List<IptvAccount> assignedAccounts = accounts.findByAssignedUser_IdAndActiveTrueAndDisabledFalse(user.id);
            if (assignedAccounts == null) {
                return List.copyOf(browsable.values());
            }
            assignedAccounts.stream()
                    .filter(account -> account.accountType == Enums.IptvAccountType.M3U)
                    .filter(account -> !ARCHIVED_BY_ADMIN.equals(account.disabledReason))
                    .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                    .filter(account -> !isMisconfigured(account))
                    .filter(account -> account.active && !account.disabled || hasReusableCatalog(account))
                    .forEach(account -> browsable.putIfAbsent(account.id, account));
            return List.copyOf(browsable.values());
        }
        Map<Long, IptvAccount> browsable = new LinkedHashMap<>();
        activeCatalogAccounts().forEach(account -> browsable.put(account.id, account));
        List<IptvAccount> allAccounts = accounts.findAll();
        if (allAccounts == null) {
            return List.copyOf(browsable.values());
        }
        allAccounts.stream()
                .filter(account -> account.accountType == Enums.IptvAccountType.M3U
                        || account.accountType == Enums.IptvAccountType.XTREAM)
                .filter(account -> account.assignedUser == null)
                .filter(account -> !ARCHIVED_BY_ADMIN.equals(account.disabledReason))
                .filter(account -> account.expiresAt == null || account.expiresAt.isAfter(Instant.now()))
                .filter(account -> !isMisconfigured(account))
                .filter(account -> account.active && !account.disabled || hasReusableCatalog(account))
                .forEach(account -> browsable.putIfAbsent(account.id, account));
        return List.copyOf(browsable.values());
    }

    private void requireUserCatalogAccess(UserEntity user, IptvAccount account) {
        if (user != null && !isAssignedToUser(account, user)) {
            throw ApiException.forbidden("Ce compte IPTV n'est pas attribue a votre utilisateur");
        }
    }

    private boolean isMisconfigured(IptvAccount account) {
        if (account.accountType == Enums.IptvAccountType.M3U) {
            return account.playlistUrl == null || account.playlistUrl.isBlank();
        }
        if (account.accountType == Enums.IptvAccountType.XTREAM) {
            return isXtreamMisconfigured(account);
        }
        return false;
    }

    private boolean isXtreamMisconfigured(IptvAccount account) {
        return account.baseUrl == null || account.baseUrl.isBlank()
                || account.username == null || account.username.isBlank()
                || account.password == null || account.password.isBlank();
    }

    private List<CatalogSource> availableSources(List<IptvAccount> catalogAccounts) {
        List<CatalogSource> sources = new ArrayList<>();
        List<IptvAccount> orderedAccounts = prioritizeCatalogAccounts(catalogAccounts);
        List<IptvAccount> skippedWithoutCache = new ArrayList<>();
        Set<String> unavailableProviders = new HashSet<>();
        boolean hasReusableSource = orderedAccounts.stream().anyMatch(this::hasReusableCatalog);
        int backgroundRefreshes = 0;

        for (IptvAccount account : orderedAccounts) {
            boolean hasReusableCatalog = hasReusableCatalog(account);
            if (hasReusableSource && !hasReusableCatalog) {
                skippedWithoutCache.add(account);
                if (backgroundRefreshes < MAX_BACKGROUND_CATALOG_REFRESHES
                        && refreshCatalogInBackground(account)) {
                    backgroundRefreshes++;
                }
                LOGGER.debug(
                        "Chargement synchrone ignore pour le compte IPTV {} sans cache reutilisable",
                        account.id
                );
                continue;
            }
            Optional<CatalogSource> source = loadCatalogSource(account);
            source.ifPresent(sources::add);
            if (source.isEmpty()) {
                String providerKey = providerKey(account);
                if (providerKey != null) {
                    unavailableProviders.add(providerKey);
                }
            }
        }
        if (sources.isEmpty() && !skippedWithoutCache.isEmpty()) {
            LOGGER.warn(
                    "Caches IPTV reutilisables vides, chargement de secours sur {} comptes actifs",
                    Math.min(MAX_BOOTSTRAP_CATALOG_LOADS, skippedWithoutCache.size())
            );
            Set<String> failedProviders = new HashSet<>(unavailableProviders);
            int attempts = 0;
            for (IptvAccount account : skippedWithoutCache) {
                if (attempts >= MAX_BOOTSTRAP_CATALOG_LOADS) {
                    break;
                }
                String providerKey = providerKey(account);
                if (providerKey != null && failedProviders.contains(providerKey)) {
                    continue;
                }
                attempts++;
                Optional<CatalogSource> source = loadCatalogSource(account);
                source.ifPresent(sources::add);
                if (!sources.isEmpty()) {
                    break;
                }
                if (providerKey != null) {
                    failedProviders.add(providerKey);
                }
            }
        }
        if (sources.isEmpty() && !catalogAccounts.isEmpty()) {
            LOGGER.warn(
                    "Aucun catalogue IPTV exploitable apres scan: comptes={}",
                    catalogAccounts.size()
            );
        }
        return sources;
    }

    private Optional<CatalogSource> loadCatalogSource(IptvAccount account) {
        try {
            M3uPlaylistService.Playlist playlist = account.accountType == Enums.IptvAccountType.M3U
                    ? playlists.load(account)
                    : xtream.load(account);
            if (isEmptyCatalog(playlist)) {
                LOGGER.warn("Catalogue IPTV vide pour le compte {} ({})", account.id, account.name);
                markCatalogStatus(account, "empty-catalog");
                refreshCatalogInBackground(account);
                return Optional.empty();
            }
            markCatalogStatus(account, "ok");
            return Optional.of(new CatalogSource(account, playlist));
        } catch (ApiException exception) {
            if ("provider_unavailable".equals(exception.code())) {
                disableCatalogAccount(account, "unreachable", exception.getMessage());
            } else {
                markCatalogStatus(account, "catalog-unavailable");
            }
            LOGGER.warn(
                    "Catalogue IPTV indisponible pour le compte {} ({}): {}",
                    account.id,
                    account.name,
                    exception.getMessage()
            );
            return Optional.empty();
        } catch (RuntimeException exception) {
            markCatalogStatus(account, "catalog-unavailable");
            LOGGER.warn(
                    "Catalogue IPTV indisponible pour le compte {} ({}): {}",
                    account.id,
                    account.name,
                    exception.getMessage()
            );
            return Optional.empty();
        }
    }

    private boolean isEmptyCatalog(M3uPlaylistService.Playlist playlist) {
        return playlist == null
                || playlist.entries().isEmpty()
                && playlist.categories().isEmpty()
                && playlist.series().isEmpty();
    }

    private void markCatalogStatus(IptvAccount account, String status) {
        if (account == null || ARCHIVED_BY_ADMIN.equals(account.disabledReason)) {
            return;
        }
        if (!Objects.equals(account.lastHealthStatus, status)) {
            account.lastHealthStatus = status;
            if ("ok".equals(status)) {
                account.failureCount = 0;
            }
            accounts.save(account);
        }
    }

    private void disableCatalogAccount(IptvAccount account, String status, String reason) {
        if (account == null || ARCHIVED_BY_ADMIN.equals(account.disabledReason)) {
            return;
        }
        account.active = false;
        account.disabled = true;
        account.activeStreams = 0;
        account.failureCount += 1;
        account.lastHealthStatus = status;
        account.disabledReason = "catalog-load:" + (reason == null || reason.isBlank()
                ? status
                : reason.strip().substring(0, Math.min(160, reason.strip().length())));
        accounts.save(account);
        playlists.invalidate(account.id);
        xtream.invalidate(account.id);
        LOGGER.warn(
                "Compte IPTV desactive automatiquement pendant le chargement catalogue: id={}, nom={}, status={}, raison={}",
                account.id,
                account.name,
                status,
                reason
        );
    }

    private boolean hasBlockingHealthStatus(IptvAccount account) {
        return BLOCKING_HEALTH_STATUSES.contains(normalizedHealthStatus(account));
    }

    private String normalizedHealthStatus(IptvAccount account) {
        return account == null || account.lastHealthStatus == null
                ? ""
                : account.lastHealthStatus.toLowerCase(Locale.ROOT);
    }

    private String providerKey(IptvAccount account) {
        if (account == null || account.accountType != Enums.IptvAccountType.XTREAM || account.baseUrl == null) {
            return null;
        }
        String normalized = account.baseUrl.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            return host == null || host.isBlank() ? normalized : host;
        } catch (IllegalArgumentException exception) {
            return normalized;
        }
    }

    private List<IptvAccount> prioritizeCatalogAccounts(List<IptvAccount> catalogAccounts) {
        return catalogAccounts.stream()
                .sorted(Comparator
                        .comparing((IptvAccount account) -> !hasReusableCatalog(account))
                        .thenComparingDouble(this::loadRatio)
                        .thenComparing(account -> account.id))
                .toList();
    }

    private boolean hasReusableCatalog(IptvAccount account) {
        if (account.accountType == Enums.IptvAccountType.M3U) {
            return playlists.hasReusableCache(account.id);
        }
        if (account.accountType == Enums.IptvAccountType.XTREAM) {
            return xtream.hasReusableCache(account.id);
        }
        return false;
    }

    private boolean refreshCatalogInBackground(IptvAccount account) {
        try {
            if (account.accountType == Enums.IptvAccountType.M3U) {
                return playlists.refreshInBackground(account);
            }
            if (account.accountType == Enums.IptvAccountType.XTREAM) {
                return xtream.refreshInBackground(account);
            }
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "Refresh catalogue en arriere-plan ignore pour le compte {}: {}",
                    account.id,
                    exception.getMessage()
            );
        }
        return false;
    }

    private List<SeriesSource> mergedSeries(List<CatalogSource> sources) {
        Map<String, List<SeriesSource>> grouped = new LinkedHashMap<>();
        for (CatalogSource source : sources) {
            for (M3uPlaylistService.Series series : source.playlist().series()) {
                grouped.computeIfAbsent(seriesKey(series), ignored -> new ArrayList<>())
                        .add(new SeriesSource(source.account(), series));
            }
        }
        return grouped.values().stream().map(this::mergeSeriesGroup).toList();
    }

    private SeriesSource mergeSeriesGroup(List<SeriesSource> group) {
        SeriesSource representative = group.stream()
                .min(Comparator
                        .comparing((SeriesSource source) -> source.series().poster() == null
                                || source.series().poster().isBlank())
                        .thenComparing(source -> source.account().id))
                .orElseThrow();

        Map<String, EntrySource> episodes = new LinkedHashMap<>();
        for (SeriesSource source : group) {
            for (M3uPlaylistService.Entry episode : source.series().episodes()) {
                episodes.merge(
                        contentKey(episode),
                        new EntrySource(source.account(), episode),
                        this::preferredEntry
                );
            }
        }
        List<M3uPlaylistService.Entry> mergedEpisodes = episodes.values().stream()
                .map(EntrySource::entry)
                .sorted(Comparator
                        .comparing(M3uPlaylistService.Entry::seasonNumber)
                        .thenComparing(M3uPlaylistService.Entry::episodeNumber)
                        .thenComparing(M3uPlaylistService.Entry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        M3uPlaylistService.Series merged = new M3uPlaylistService.Series(
                representative.series().id(),
                representative.series().title(),
                canonicalCategoryId("series", representative.series().categoryName()),
                representative.series().categoryName(),
                representative.series().poster(),
                mergedEpisodes
        );
        return new SeriesSource(representative.account(), merged);
    }

    private Map<String, Object> mergedSeriesDetails(
            SeriesSource requested,
            boolean preferAlternativeAccounts
    ) {
        return mergedSeriesDetails(requested, preferAlternativeAccounts, activeCatalogAccounts());
    }

    private Map<String, Object> mergedSeriesDetails(
            SeriesSource requested,
            boolean preferAlternativeAccounts,
            List<IptvAccount> candidateCatalogAccounts
    ) {
        String key = seriesKey(requested.series());
        Map<Long, SeriesSource> matching = new LinkedHashMap<>();
        if (!preferAlternativeAccounts) {
            matching.put(requested.account().id, requested);
        }
        List<IptvAccount> availableAccounts = candidateCatalogAccounts.stream()
                .filter(account -> !preferAlternativeAccounts
                        || !Objects.equals(account.id, requested.account().id))
                .filter(account -> account.maxStreams <= 0 || account.activeStreams < account.maxStreams)
                .sorted(Comparator
                        .comparingDouble(this::loadRatio)
                        .thenComparing(account -> account.id))
                .toList();
        for (CatalogSource source : availableSources(availableAccounts)) {
            source.playlist().series().stream()
                    .filter(series -> seriesKey(series).equals(key))
                    .findFirst()
                    .ifPresent(series -> matching.put(source.account().id, new SeriesSource(source.account(), series)));
        }
        if (matching.isEmpty()
                && isCatalogAccountUsable(requested.account(), requested.account().accountType)) {
            matching.put(requested.account().id, requested);
        }

        List<SeriesSource> detailed = new ArrayList<>();
        List<SeriesSource> ordered = matching.values().stream()
                .sorted(Comparator
                        .comparing((SeriesSource source) ->
                                source.account().maxStreams > 0
                                        && source.account().activeStreams >= source.account().maxStreams)
                        .thenComparingDouble(source -> loadRatio(source.account()))
                        .thenComparing(source -> source.account().id))
                .toList();
        for (SeriesSource source : ordered) {
            boolean saturated = source.account().maxStreams > 0
                    && source.account().activeStreams >= source.account().maxStreams;
            if (saturated && !detailed.isEmpty()) {
                continue;
            }
            try {
                detailed.add(loadSeriesDetails(source));
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Fiche serie indisponible pour le compte {} ({}): {}",
                        source.account().id,
                        source.account().name,
                        exception.getMessage()
                );
            }
        }
        if (detailed.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV ne peut charger cette série");
        }

        detailed.sort(Comparator
                .comparingDouble((SeriesSource source) -> loadRatio(source.account()))
                .thenComparing(source -> source.account().id));
        SeriesSource primary = detailed.get(0);
        SeriesSource merged = mergeSeriesGroup(detailed);
        Map<String, Object> details = new LinkedHashMap<>(primary.series().detailPayload());
        if (primary.account().accountType == Enums.IptvAccountType.M3U) {
            try {
                details.putAll(metadata.seriesDetails(primary.account(), primary.series()));
            } catch (RuntimeException exception) {
                LOGGER.warn("Metadonnees serie indisponibles pour le compte {}", primary.account().id);
            }
        }
        details.putAll(merged.series().detailPayload());
        applyLanguage(details, merged.series().categoryName());
        applyPrivateAccess(details, primary.account());
        images.rewrite(details);
        return details;
    }

    private SeriesSource loadSeriesDetails(SeriesSource source) {
        if (source.account().accountType == Enums.IptvAccountType.XTREAM) {
            return new SeriesSource(
                    source.account(),
                    xtream.loadSeries(source.account(), source.series().id())
            );
        }
        return source;
    }

    private List<EntrySource> equivalentEntries(
            CatalogSource source,
            M3uPlaylistService.Entry requested
    ) {
        String key = contentKey(requested);
        List<EntrySource> matches = new ArrayList<>();
        source.playlist().entries().stream()
                .filter(entry -> contentKey(entry).equals(key))
                .findFirst()
                .ifPresent(entry -> matches.add(new EntrySource(source.account(), entry)));
        if (!"series".equals(requested.type()) || requested.seriesTitle() == null) {
            return matches;
        }

        source.playlist().series().stream()
                .filter(series -> normalizeSeriesIdentity(series.title())
                        .equals(normalizeSeriesIdentity(requested.seriesTitle())))
                .findFirst()
                .ifPresent(series -> {
                    try {
                        SeriesSource detailed = loadSeriesDetails(new SeriesSource(source.account(), series));
                        detailed.series().episodes().stream()
                                .filter(entry -> contentKey(entry).equals(key))
                                .findFirst()
                                .ifPresent(entry -> matches.add(new EntrySource(source.account(), entry)));
                    } catch (RuntimeException exception) {
                        LOGGER.warn(
                                "Episodes equivalents indisponibles pour le compte {}: {}",
                                source.account().id,
                                exception.getMessage()
                        );
                    }
                });
        return matches;
    }

    private EntrySource preferredEntry(EntrySource left, EntrySource right) {
        boolean leftFailed = hasRecentStreamFailure(left.account());
        boolean rightFailed = hasRecentStreamFailure(right.account());
        if (leftFailed != rightFailed) {
            return leftFailed ? right : left;
        }
        boolean leftAvailable = left.account().maxStreams <= 0
                || left.account().activeStreams < left.account().maxStreams;
        boolean rightAvailable = right.account().maxStreams <= 0
                || right.account().activeStreams < right.account().maxStreams;
        if (leftAvailable != rightAvailable) {
            return leftAvailable ? left : right;
        }
        int loadComparison = Double.compare(loadRatio(left.account()), loadRatio(right.account()));
        if (loadComparison != 0) {
            return loadComparison < 0 ? left : right;
        }
        boolean leftHasArtwork = left.entry().logo() != null && !left.entry().logo().isBlank();
        boolean rightHasArtwork = right.entry().logo() != null && !right.entry().logo().isBlank();
        if (leftHasArtwork != rightHasArtwork) {
            return leftHasArtwork ? left : right;
        }
        return left.account().id <= right.account().id ? left : right;
    }

    private EntrySource selectEntrySource(List<EntrySource> candidates) {
        if (candidates.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucun compte IPTV ne peut servir ce contenu");
        }
        List<EntrySource> preferredCandidates = entriesWithoutRecentStreamFailures(candidates);
        double minimumLoad = preferredCandidates.stream()
                .map(EntrySource::account)
                .mapToDouble(this::loadRatio)
                .min()
                .orElse(0);
        List<EntrySource> tied = preferredCandidates.stream()
                .filter(source -> Double.compare(loadRatio(source.account()), minimumLoad) == 0)
                .sorted(Comparator.comparing(source -> source.account().id))
                .toList();
        return tied.get(roundRobinIndex(tied.size()));
    }

    private List<IptvAccount> accountsWithoutRecentStreamFailures(List<IptvAccount> candidates) {
        List<IptvAccount> available = candidates.stream()
                .filter(account -> !hasRecentStreamFailure(account))
                .toList();
        return available.isEmpty() ? candidates : available;
    }

    private List<EntrySource> entriesWithoutRecentStreamFailures(List<EntrySource> candidates) {
        List<EntrySource> available = candidates.stream()
                .filter(source -> !hasRecentStreamFailure(source.account()))
                .toList();
        return available.isEmpty() ? candidates : available;
    }

    private boolean hasRecentStreamFailure(IptvAccount account) {
        return account != null && "stream-failed".equalsIgnoreCase(account.lastHealthStatus);
    }

    private double loadRatio(IptvAccount account) {
        return account.maxStreams <= 0 ? 0 : (double) account.activeStreams / account.maxStreams;
    }

    private int roundRobinIndex(int size) {
        return (int) Math.floorMod(roundRobinSequence.getAndIncrement(), (long) size);
    }

    private Map<String, Object> categoryPayload(M3uPlaylistService.Category category) {
        return categoryPayload(category, null);
    }

    private Map<String, Object> categoryPayload(M3uPlaylistService.Category category, IptvAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>(category.apiPayload());
        payload.put("id", canonicalCategoryId(category.type(), category.name()));
        applyPrivateAccess(payload, account);
        return payload;
    }

    private Map<String, Object> entryPayload(M3uPlaylistService.Entry entry) {
        return entryPayload(entry, null);
    }

    private Map<String, Object> entryPayload(M3uPlaylistService.Entry entry, IptvAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>(entry.apiPayload());
        payload.put("categoryId", canonicalCategoryId(entry.type(), entry.categoryName()));
        applyLanguage(payload, entry.categoryName());
        applyPrivateAccess(payload, account);
        images.rewrite(payload);
        return payload;
    }

    private Map<String, Object> seriesPayload(M3uPlaylistService.Series series) {
        return seriesPayload(series, null);
    }

    private Map<String, Object> seriesPayload(M3uPlaylistService.Series series, IptvAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>(series.apiPayload());
        applyLanguage(payload, series.categoryName());
        applyPrivateAccess(payload, account);
        images.rewrite(payload);
        return payload;
    }

    private void applyPrivateAccess(Map<String, Object> payload, IptvAccount account) {
        if (account == null || account.assignedUser == null) {
            return;
        }
        payload.put("privateUse", true);
        payload.put("privateAccess", true);
        payload.put("assignedIptvAccountId", account.id);
    }

    private void applyLanguage(Map<String, Object> payload, String categoryName) {
        String language = languageCode(categoryName);
        if (language != null) {
            payload.put("language", language);
            payload.put("languageName", LANGUAGE_NAMES.get(language));
        }
    }

    private boolean categoryMatches(String type, String sourceId, String name, String requestedId) {
        return requestedId == null
                || requestedId.isBlank()
                || requestedId.equals(sourceId)
                || requestedId.equals(canonicalCategoryId(type, name));
    }

    private String canonicalCategoryId(String type, String name) {
        return "catalog-cat-" + type + "-" + digest(categoryKey(type, name));
    }

    private String categoryKey(String type, String name) {
        return type + "|" + normalizeIdentity(name);
    }

    private String seriesKey(M3uPlaylistService.Series series) {
        return "series|" + normalizeSeriesIdentity(series.title());
    }

    private String contentKey(M3uPlaylistService.Entry entry) {
        if ("series".equals(entry.type())) {
            String title = entry.seriesTitle() == null ? entry.name() : entry.seriesTitle();
            return "series|" + normalizeSeriesIdentity(title)
                    + "|s" + entry.seasonNumber()
                    + "|e" + entry.episodeNumber();
        }
        return entry.type() + "|" + normalizeIdentity(entry.name());
    }

    private String normalizeSeriesIdentity(String value) {
        String normalized = normalizeIdentity(value);
        String previous;
        do {
            previous = normalized;
            normalized = normalized
                    .replaceFirst("\\s+(19|20)\\d{2}$", "")
                    .replaceFirst("\\s+(4k|uhd|fhd|hd|sd|multi|vostfr|vf|vff)$", "")
                    .strip();
        } while (!normalized.equals(previous));
        return normalized;
    }

    private String normalizeIdentity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceFirst("^#\\s*\\d+\\s*", "")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                hex.append(String.format("%02x", hash[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible", exception);
        }
    }

    private boolean isType(Map<String, Object> item, String type) {
        return type == null || type.isBlank() || item.get("type").equals(type);
    }

    private boolean matchesQuery(Object value, String normalizedQuery) {
        return normalizedQuery == null || normalizedQuery.isBlank()
                || normalizeIdentity(String.valueOf(value)).contains(normalizedQuery);
    }

    private boolean matchesEntryQuery(M3uPlaylistService.Entry entry, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        String language = languageCode(entry.categoryName());
        return normalizedContains(entry.name(), normalizedQuery)
                || normalizedContains(entry.categoryName(), normalizedQuery)
                || normalizedContains(language, normalizedQuery)
                || normalizedContains(language == null ? null : LANGUAGE_NAMES.get(language), normalizedQuery);
    }

    private boolean matchesSeriesQuery(M3uPlaylistService.Series series, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        String language = languageCode(series.categoryName());
        return normalizedContains(series.title(), normalizedQuery)
                || normalizedContains(series.categoryName(), normalizedQuery)
                || normalizedContains(language, normalizedQuery)
                || normalizedContains(language == null ? null : LANGUAGE_NAMES.get(language), normalizedQuery)
                || (normalizedQuery.length() >= 3 && series.matchesNormalized(normalizedQuery));
    }

    private boolean normalizedContains(String value, String normalizedQuery) {
        return normalizeIdentity(value).contains(normalizedQuery);
    }

    private boolean languageMatches(String categoryName, String requestedLanguage) {
        return requestedLanguage == null
                || requestedLanguage.isBlank()
                || requestedLanguage.equalsIgnoreCase(languageCode(categoryName));
    }

    static String languageCode(String categoryName) {
        String normalized = normalizeLanguageText(categoryName);
        if (normalized.isBlank()) {
            return null;
        }

        String prefix = normalized.split("\\s+", 2)[0];
        if (normalized.contains("movies in english") || normalized.contains("english")) return "en";
        if (normalized.contains("vostfr") || normalized.matches(".*\\b(vf|vff|french|france)\\b.*")) return "fr";
        if (normalized.contains("kurd")) return "ku";
        if (normalized.contains("arab")) return "ar";
        if (normalized.contains("latino") || normalized.contains("latin america")) return "es";
        if (normalized.contains("brazil")) return "pt";
        if (normalized.contains("russian")) return "ru";
        if (normalized.contains("ukraine")) return "uk";
        if (normalized.contains("turk")) return "tr";
        if (normalized.contains("multi")) return "multi";

        return switch (prefix) {
            case "fr" -> "fr";
            case "en", "uk", "usa", "canada", "ca" -> "en";
            case "tr", "kktc" -> "tr";
            case "de", "at" -> "de";
            case "es", "lame" -> "es";
            case "pt", "br" -> "pt";
            case "it" -> "it";
            case "nl" -> "nl";
            case "pl" -> "pl";
            case "ar", "arab" -> "ar";
            case "ru" -> "ru";
            case "ukr", "ua" -> "uk";
            case "gr" -> "el";
            case "alb", "al" -> "sq";
            case "srb", "rs" -> "sr";
            case "cr", "cro", "hr" -> "hr";
            case "bih", "ba" -> "bs";
            case "mk" -> "mk";
            case "hu" -> "hu";
            case "slo", "si" -> "sl";
            case "cz" -> "cs";
            case "swe", "se" -> "sv";
            case "dk" -> "da";
            case "no" -> "no";
            case "fi" -> "fi";
            case "bg" -> "bg";
            case "ro" -> "ro";
            case "az" -> "az";
            case "kr" -> "ko";
            case "jp" -> "ja";
            case "ir" -> "fa";
            case "il" -> "he";
            case "in" -> "hi";
            case "pk" -> "ur";
            default -> null;
        };
    }

    private static String normalizeLanguageText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    private static Map<String, String> languageNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("fr", "Français");
        names.put("en", "Anglais");
        names.put("tr", "Turc");
        names.put("de", "Allemand");
        names.put("es", "Espagnol");
        names.put("pt", "Portugais");
        names.put("it", "Italien");
        names.put("nl", "Néerlandais");
        names.put("pl", "Polonais");
        names.put("ar", "Arabe");
        names.put("ru", "Russe");
        names.put("uk", "Ukrainien");
        names.put("el", "Grec");
        names.put("sq", "Albanais");
        names.put("sr", "Serbe");
        names.put("hr", "Croate");
        names.put("bs", "Bosnien");
        names.put("mk", "Macédonien");
        names.put("hu", "Hongrois");
        names.put("sl", "Slovène");
        names.put("cs", "Tchèque");
        names.put("sv", "Suédois");
        names.put("da", "Danois");
        names.put("no", "Norvégien");
        names.put("fi", "Finnois");
        names.put("bg", "Bulgare");
        names.put("ro", "Roumain");
        names.put("az", "Azéri");
        names.put("ku", "Kurde");
        names.put("ko", "Coréen");
        names.put("ja", "Japonais");
        names.put("fa", "Persan");
        names.put("he", "Hébreu");
        names.put("hi", "Hindi");
        names.put("ur", "Ourdou");
        names.put("multi", "Multilingue");
        return Map.copyOf(names);
    }

    private List<Map<String, Object>> demoCategories() {
        return List.of(
                Map.of("id", "news", "name", "News", "type", "live"),
                Map.of("id", "sports", "name", "Sports", "type", "live"),
                Map.of("id", "movies-action", "name", "Action", "type", "movie"),
                Map.of("id", "series-drama", "name", "Drama", "type", "series")
        );
    }

    private List<Map<String, Object>> demoItems() {
        return List.of(
                Map.of("id", "1001", "name", "Africa News", "type", "live", "categoryId", "news", "logo", ""),
                Map.of("id", "1002", "name", "World Sports", "type", "live", "categoryId", "sports", "logo", ""),
                Map.of("id", "2001", "name", "Action Demo Movie", "type", "movie", "categoryId", "movies-action", "poster", ""),
                Map.of("id", "3001", "name", "Demo Series", "type", "series", "categoryId", "series-drama",
                        "poster", "", "seasonCount", 1, "episodeCount", 2, "isSeries", true)
        );
    }

    private String trimSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("/+$", "");
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(String.valueOf(value).strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return Set.of("http", "https").contains(scheme) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record CatalogSource(IptvAccount account, M3uPlaylistService.Playlist playlist) {
    }

    private record CategorySource(IptvAccount account, M3uPlaylistService.Category category) {
    }

    private record EntrySource(IptvAccount account, M3uPlaylistService.Entry entry) {
    }

    private record SeriesSource(IptvAccount account, M3uPlaylistService.Series series) {
    }

    public record StreamSelection(IptvAccount account, String streamUrl, String itemId) {
        public StreamSelection(IptvAccount account, String streamUrl) {
            this(account, streamUrl, null);
        }
    }

    public record CatalogAccessDescriptor(String categoryId, String categoryName, String contentType, boolean adult) {
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
