package com.iptv.saas.web;

import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.ConsumetContentService;
import com.iptv.saas.service.EpornerContentService;
import com.iptv.saas.service.IptvCatalogService;
import com.iptv.saas.service.SubscriptionAccessService;
import com.iptv.saas.service.TmdbCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CatalogController {
    private final IptvCatalogService catalog;
    private final CommunityAddonService addons;
    private final ConsumetContentService consumet;
    private final TmdbCatalogService tmdb;
    private final EpornerContentService eporner;
    private final SubscriptionAccessService access;

    public CatalogController(IptvCatalogService catalog, CommunityAddonService addons, SubscriptionAccessService access) {
        this(catalog, addons, null, null, null, access);
    }

    public CatalogController(
            IptvCatalogService catalog,
            CommunityAddonService addons,
            ConsumetContentService consumet,
            TmdbCatalogService tmdb,
            SubscriptionAccessService access
    ) {
        this(catalog, addons, consumet, tmdb, null, access);
    }

    @Autowired
    public CatalogController(
            IptvCatalogService catalog,
            CommunityAddonService addons,
            ConsumetContentService consumet,
            TmdbCatalogService tmdb,
            EpornerContentService eporner,
            SubscriptionAccessService access
    ) {
        this.catalog = catalog;
        this.addons = addons;
        this.consumet = consumet;
        this.tmdb = tmdb;
        this.eporner = eporner;
        this.access = access;
    }

    @GetMapping({"/api/catalog/categories", "/api/v1/catalog/categories"})
    public Object categories(@RequestParam(required = false) String type) {
        type = normalizeCatalogType(type);
        UserEntity user = currentUser();
        List<Map<String, Object>> values = new ArrayList<>();
        if (shouldIncludeNativeCatalog(user)) {
            values.addAll(nativeCategories(user, type));
        }
        if (hasConsumet()) {
            values.addAll(consumet.categories(type));
        }
        if (hasTmdb()) {
            values.addAll(tmdb.categories(type));
        }
        if (shouldExposeEpornerCategory(user, type)) {
            values.addAll(hasEporner() ? eporner.categories(type) : List.of(epornerCategory()));
        }
        values.addAll(addons.categories(type, user));
        List<Map<String, Object>> uniqueValues = unique(values, "id");
        return Responses.ok(filterForUser(user, uniqueValues));
    }

    @GetMapping("/api/catalog/languages")
    public Object languages(@RequestParam(required = false) String type) {
        type = normalizeCatalogType(type);
        return Responses.ok(nativeLanguages(currentUser(), type));
    }

    @GetMapping("/api/catalog/items")
    public Object items(
            @RequestParam(required = false, defaultValue = "live") String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "default") String sort,
            @RequestParam(required = false, defaultValue = "0") int limit,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) String addonFilter,
            @RequestParam(required = false, defaultValue = "1") int addonPages
    ) {
        type = normalizeCatalogType(type);
        UserEntity user = currentUser();
        List<List<Map<String, Object>>> resultSets = new ArrayList<>();
        if (shouldIncludeNativeCatalog(user) && shouldQueryNativeCatalog(categoryId)) {
            addResultSet(resultSets, nativeItems(user, type, q, categoryId, language, sort, limit));
        }
        if (hasConsumet()) {
            addResultSet(resultSets, consumet.items(type, q, categoryId, language, sort, limit));
        }
        if (hasTmdb()) {
            addResultSet(resultSets, tmdb.items(type, q, categoryId, language, sort, limit));
        }
        if (hasEporner() && eporner.hasAccess(user)) {
            addResultSet(resultSets, eporner.items(type, q, categoryId, language, sort, limit, page));
        }
        if (resultSets.isEmpty() || shouldLoadAddons(q, categoryId, addonFilter)) {
            addResultSet(resultSets, addons.items(type, q, categoryId, sort, limit, addonFilter, addonPages, user));
        }
        List<Map<String, Object>> values = mergeResultSets(
                resultSets,
                shouldInterleaveProviders(q, categoryId)
        );
        values = filterForUser(user, values);
        if (limit > 0 && values.size() > limit) {
            values = new ArrayList<>(values.subList(0, limit));
        }
        return Responses.ok(values);
    }

    public Object items(
            String type,
            String q,
            String categoryId,
            String language,
            String sort,
            int limit,
            String addonFilter,
            int addonPages
    ) {
        type = normalizeCatalogType(type);
        return items(type, q, categoryId, language, sort, limit, 1, addonFilter, addonPages);
    }

    private String normalizeCatalogType(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.strip().toLowerCase(java.util.Locale.ROOT);
        return "drama".equals(normalized) || "kdrama".equals(normalized) ? "series" : type;
    }

    @GetMapping("/api/catalog/series/{seriesId}")
    public Object series(
            @PathVariable String seriesId,
            @RequestParam(required = false) String title
    ) {
        UserEntity user = currentUser();
        var series = addons.isAddonItem(seriesId)
                ? addons.seriesInfo(seriesId, user)
                : hasConsumet() && consumet.isConsumetItem(seriesId)
                ? consumet.seriesInfo(seriesId, title)
                : hasTmdb() && tmdb.isTmdbItem(seriesId)
                ? tmdb.seriesInfo(seriesId, title)
                : nativeSeriesInfo(user, seriesId, title);
        if (!permits(user, series)) {
            throw ApiException.forbidden("Cette catégorie n'est pas autorisée pour votre compte");
        }
        return Responses.ok(series);
    }

    @GetMapping("/api/catalog/items/{itemId}")
    public Object item(@PathVariable String itemId) {
        UserEntity user = currentUser();
        var item = addons.isAddonItem(itemId)
                ? addons.itemInfo(itemId, user)
                : hasConsumet() && consumet.isConsumetItem(itemId)
                ? consumet.itemInfo(itemId)
                : hasTmdb() && tmdb.isTmdbItem(itemId)
                ? tmdb.itemInfo(itemId)
                : hasEporner() && eporner.isEpornerItem(itemId)
                ? epornerItemInfo(user, itemId)
                : nativeItemInfo(user, itemId);
        if (!permits(user, item)) {
            throw ApiException.forbidden("Cette catégorie n'est pas autorisée pour votre compte");
        }
        return Responses.ok(item);
    }

    @GetMapping("/api/stream/groups")
    public Object groups() {
        UserEntity user = currentUser();
        return Responses.ok(filterForUser(user, nativeLiveGroups(user)));
    }

    @GetMapping("/api/stream/channels")
    public Object channels() {
        UserEntity user = currentUser();
        return Responses.ok(filterForUser(user, nativeLiveChannels(user)));
    }

    private UserEntity currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserEntity user ? user : null;
    }

    private boolean permits(UserEntity user, Map<String, Object> value) {
        return SecurityUtils.isAdminLike(user) || access.permits(user, value);
    }

    private List<Map<String, Object>> filterForUser(UserEntity user, List<Map<String, Object>> values) {
        return SecurityUtils.isAdminLike(user) ? values : access.filter(user, values);
    }

    private boolean shouldLoadAddons(String q, String categoryId, String addonFilter) {
        return (q != null && !q.isBlank())
                || (categoryId != null && !categoryId.isBlank())
                || (addonFilter != null && !addonFilter.isBlank());
    }

    private void addResultSet(List<List<Map<String, Object>>> resultSets, List<Map<String, Object>> values) {
        if (values != null && !values.isEmpty()) {
            resultSets.add(values);
        }
    }

    private List<Map<String, Object>> mergeResultSets(
            List<List<Map<String, Object>>> resultSets,
            boolean interleave
    ) {
        if (!interleave || resultSets.size() <= 1) {
            return resultSets.stream()
                    .flatMap(List::stream)
                    .toList();
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        int maxSize = resultSets.stream().mapToInt(List::size).max().orElse(0);
        for (int index = 0; index < maxSize; index++) {
            for (List<Map<String, Object>> resultSet : resultSets) {
                if (index < resultSet.size()) {
                    merged.add(resultSet.get(index));
                }
            }
        }
        return merged;
    }

    private boolean shouldInterleaveProviders(String q, String categoryId) {
        return (q != null && !q.isBlank()) || categoryId == null || categoryId.isBlank();
    }

    private boolean shouldIncludeNativeCatalog(UserEntity user) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return nativeHasActiveSources(user)
                || (catalogUser == null && !addons.hasApprovedAddons(user) && !hasConsumet() && !hasTmdb() && !hasEporner());
    }

    private boolean nativeHasActiveSources(UserEntity user) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.hasActiveSources() : catalog.hasActiveSources(catalogUser);
    }

    private List<Map<String, Object>> nativeCategories(UserEntity user, String type) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.categories(type) : catalog.categories(catalogUser, type);
    }

    private List<Map<String, Object>> nativeLanguages(UserEntity user, String type) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.languages(type) : catalog.languages(catalogUser, type);
    }

    private List<Map<String, Object>> nativeItems(
            UserEntity user,
            String type,
            String q,
            String categoryId,
            String language,
            String sort,
            int limit
    ) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null
                ? catalog.items(type, q, categoryId, language, sort, limit)
                : catalog.items(catalogUser, type, q, categoryId, language, sort, limit);
    }

    private Map<String, Object> nativeSeriesInfo(UserEntity user, String seriesId, String title) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.seriesInfo(seriesId, title) : catalog.seriesInfo(catalogUser, seriesId, title);
    }

    private Map<String, Object> nativeItemInfo(UserEntity user, String itemId) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.itemInfo(itemId) : catalog.itemInfo(catalogUser, itemId);
    }

    private List<Map<String, Object>> nativeLiveGroups(UserEntity user) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.liveGroups() : catalog.liveGroups(catalogUser);
    }

    private List<Map<String, Object>> nativeLiveChannels(UserEntity user) {
        UserEntity catalogUser = nativeCatalogUser(user);
        return catalogUser == null ? catalog.liveChannels() : catalog.liveChannels(catalogUser);
    }
    private UserEntity nativeCatalogUser(UserEntity user) {
        return SecurityUtils.isAdminLike(user) ? null : user;
    }

    private boolean shouldQueryNativeCatalog(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return true;
        }
        String normalized = categoryId.toLowerCase(java.util.Locale.ROOT);
        return !(normalized.startsWith("adults-eporner") || normalized.startsWith("addon-"));
    }

    private boolean hasConsumet() {
        return consumet != null && consumet.isEnabled();
    }

    private boolean hasTmdb() {
        return tmdb != null && tmdb.isEnabled();
    }

    private boolean hasEporner() {
        return eporner != null && eporner.isEnabled();
    }

    private boolean shouldExposeEpornerCategory(UserEntity user, String type) {
        return matchesEpornerType(type)
                && eporner != null
                && eporner.hasAccess(user);
    }

    private boolean matchesEpornerType(String type) {
        return type == null || type.isBlank() || "movie".equalsIgnoreCase(type);
    }

    private Map<String, Object> epornerCategory() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", EpornerContentService.CATEGORY_ADULTS);
        payload.put("name", "Reserve");
        payload.put("type", "movie");
        payload.put("source", "Eporner");
        payload.put("sourceCode", "eporner");
        payload.put("metadataAvailable", true);
        payload.put("streamAvailable", true);
        payload.put("privateUse", true);
        payload.put("privateAccess", true);
        payload.put("adult", true);
        return payload;
    }

    private Map<String, Object> epornerItemInfo(UserEntity user, String itemId) {
        if (!eporner.hasAccess(user)) {
            throw ApiException.forbidden("Cette categorie adults n'est pas autorisee pour votre compte");
        }
        return eporner.itemInfo(itemId);
    }

    private List<Map<String, Object>> unique(List<Map<String, Object>> values, String key) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        values.forEach(value -> unique.putIfAbsent(String.valueOf(value.get(key)), value));
        return List.copyOf(unique.values());
    }
}
