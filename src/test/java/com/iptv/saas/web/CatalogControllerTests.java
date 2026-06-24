package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.EpornerContentService;
import com.iptv.saas.service.IptvCatalogService;
import com.iptv.saas.service.OrganizationService;
import com.iptv.saas.service.SubscriptionAccessService;
import com.iptv.saas.service.TmdbCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogControllerTests {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void selectedUserCanSeeAnAdultPrivateAddonCategory() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = new SubscriptionAccessService(
                mock(SubscriptionRepository.class),
                mock(OrganizationService.class)
        );
        CatalogController controller = new CatalogController(catalog, addons, access);
        UserEntity user = new UserEntity();
        user.id = 55L;
        user.role = Enums.UserRole.USER;

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of())
        );
        when(catalog.hasActiveSources()).thenReturn(false);
        when(addons.hasApprovedAddons(user)).thenReturn(true);
        when(addons.categories(null, user)).thenReturn(List.of(Map.of(
                "id", "addon-1-movie-asa",
                "name", "ASA",
                "adult", true,
                "privateUse", true,
                "privateAccess", true
        )));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.categories(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, values.size());
        assertEquals("addon-1-movie-asa", values.get(0).get("id"));
    }

    @Test
    void fallsBackToApprovedAddonsWhenActiveIptvSourcesReturnNoItems() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = new SubscriptionAccessService(
                mock(SubscriptionRepository.class),
                mock(OrganizationService.class)
        );
        CatalogController controller = new CatalogController(catalog, addons, access);
        List<Map<String, Object>> addonItems = List.of(Map.of(
                "id", "addon~36~movie~anime~film-1",
                "type", "movie",
                "name", "Addon movie",
                "categoryId", "addon-36-movie-anime"
        ));

        when(catalog.hasActiveSources()).thenReturn(true);
        when(addons.hasApprovedAddons(null)).thenReturn(true);
        when(catalog.items("movie", null, null, null, "default", 10)).thenReturn(List.of());
        when(addons.items("movie", null, null, "default", 10, null, 1, null)).thenReturn(addonItems);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.items(
                "movie",
                null,
                null,
                null,
                "default",
                10,
                null,
                1
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, values.size());
        assertEquals("addon~36~movie~anime~film-1", values.get(0).get("id"));
    }

    @Test
    void adminCanSeeCatalogItemsWithoutSubscriptionFilter() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        CatalogController controller = new CatalogController(catalog, addons, access);
        UserEntity admin = new UserEntity();
        admin.id = 1L;
        admin.role = Enums.UserRole.ADMIN;
        List<Map<String, Object>> catalogItems = List.of(Map.of(
                "id", "consumet~movies~flixhq~movie~abc",
                "type", "movie",
                "name", "Consumet movie",
                "categoryId", "consumet-movie-flixhq"
        ));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of())
        );
        when(catalog.hasActiveSources()).thenReturn(true);
        when(catalog.items("movie", null, null, null, "default", 10)).thenReturn(catalogItems);
        when(access.filter(admin, catalogItems)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.items(
                "movie",
                null,
                null,
                null,
                "default",
                10,
                null,
                1
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, values.size());
        assertEquals("consumet~movies~flixhq~movie~abc", values.get(0).get("id"));
    }

    @Test
    void exposesTmdbCategoriesWithoutFallingBackToDemoCatalog() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        TmdbCatalogService tmdb = mock(TmdbCatalogService.class);
        SubscriptionAccessService access = new SubscriptionAccessService(
                mock(SubscriptionRepository.class),
                mock(OrganizationService.class)
        );
        CatalogController controller = new CatalogController(catalog, addons, null, tmdb, access);
        List<Map<String, Object>> tmdbCategories = List.of(Map.of(
                "id", "tmdb-movie-popular",
                "name", "TMDB - Films populaires",
                "type", "movie",
                "source", "TMDB",
                "sourceCode", "tmdb"
        ));

        when(catalog.hasActiveSources()).thenReturn(false);
        when(addons.hasApprovedAddons(null)).thenReturn(false);
        when(tmdb.isEnabled()).thenReturn(true);
        when(tmdb.categories(null)).thenReturn(tmdbCategories);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.categories(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, values.size());
        assertEquals("tmdb-movie-popular", values.get(0).get("id"));
    }

    @Test
    void superAdminCanSeeAdultsCategoryEvenWhenProviderIsDisabled() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        EpornerContentService eporner = mock(EpornerContentService.class);
        SubscriptionAccessService access = new SubscriptionAccessService(
                mock(SubscriptionRepository.class),
                mock(OrganizationService.class)
        );
        CatalogController controller = new CatalogController(catalog, addons, null, null, eporner, access);
        UserEntity admin = new UserEntity();
        admin.id = 1L;
        admin.role = Enums.UserRole.SUPER_ADMIN;

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of())
        );
        when(catalog.hasActiveSources()).thenReturn(false);
        when(addons.hasApprovedAddons(admin)).thenReturn(false);
        when(eporner.isEnabled()).thenReturn(false);
        when(eporner.hasAccess(admin)).thenReturn(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.categories("movie");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, values.size());
        assertEquals("adults-eporner", values.get(0).get("id"));
        assertEquals(true, values.get(0).get("adult"));
    }

    @Test
    void searchMergesNativeAndTmdbResults() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        TmdbCatalogService tmdb = mock(TmdbCatalogService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        CatalogController controller = new CatalogController(catalog, addons, null, tmdb, access);
        UserEntity admin = new UserEntity();
        admin.id = 1L;
        admin.role = Enums.UserRole.ADMIN;
        List<Map<String, Object>> nativeItems = List.of(Map.of(
                "id", "xtream-1-movie-603",
                "type", "movie",
                "name", "Matrix IPTV",
                "categoryId", "movie-action"
        ));
        List<Map<String, Object>> tmdbItems = List.of(Map.of(
                "id", "tmdb~movie~603",
                "type", "movie",
                "name", "The Matrix",
                "categoryId", "tmdb-movie-search",
                "source", "TMDB"
        ));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of())
        );
        when(catalog.hasActiveSources()).thenReturn(true);
        when(catalog.items("movie", "matrix", null, null, "default", 10)).thenReturn(nativeItems);
        when(tmdb.isEnabled()).thenReturn(true);
        when(tmdb.items("movie", "matrix", null, null, "default", 10)).thenReturn(tmdbItems);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.items(
                "movie",
                "matrix",
                null,
                null,
                "default",
                10,
                null,
                1
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(2, values.size());
        assertEquals("xtream-1-movie-603", values.get(0).get("id"));
        assertEquals("tmdb~movie~603", values.get(1).get("id"));
    }

    @Test
    void interleavesProviderResultsBeforeApplyingLimit() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        TmdbCatalogService tmdb = mock(TmdbCatalogService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        CatalogController controller = new CatalogController(catalog, addons, null, tmdb, access);
        UserEntity admin = new UserEntity();
        admin.id = 1L;
        admin.role = Enums.UserRole.ADMIN;
        List<Map<String, Object>> nativeItems = List.of(
                Map.of("id", "xtream-1-movie-1", "type", "movie", "name", "Native 1", "categoryId", "movie-action"),
                Map.of("id", "xtream-1-movie-2", "type", "movie", "name", "Native 2", "categoryId", "movie-action"),
                Map.of("id", "xtream-1-movie-3", "type", "movie", "name", "Native 3", "categoryId", "movie-action")
        );
        List<Map<String, Object>> tmdbItems = List.of(Map.of(
                "id", "tmdb~movie~603",
                "type", "movie",
                "name", "The Matrix",
                "categoryId", "tmdb-movie-search",
                "source", "TMDB"
        ));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of())
        );
        when(catalog.hasActiveSources()).thenReturn(true);
        when(catalog.items("movie", "matrix", null, null, "default", 3)).thenReturn(nativeItems);
        when(tmdb.isEnabled()).thenReturn(true);
        when(tmdb.items("movie", "matrix", null, null, "default", 3)).thenReturn(tmdbItems);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.items(
                "movie",
                "matrix",
                null,
                null,
                "default",
                3,
                null,
                1
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(3, values.size());
        assertEquals("xtream-1-movie-1", values.get(0).get("id"));
        assertEquals("tmdb~movie~603", values.get(1).get("id"));
        assertEquals("xtream-1-movie-2", values.get(2).get("id"));
    }
}
