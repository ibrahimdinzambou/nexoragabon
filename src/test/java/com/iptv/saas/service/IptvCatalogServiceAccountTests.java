package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IptvCatalogServiceAccountTests {
    @Test
    void keepsExistingXtreamCredentialsWhenEditLeavesCredentialFieldsBlank() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount account = new IptvAccount();
        account.id = 7L;
        account.accountType = Enums.IptvAccountType.XTREAM;
        account.name = "Old";
        account.baseUrl = "https://old-provider.test";
        account.username = "real-user";
        account.password = "real-secret";
        account.active = true;
        account.maxStreams = 1;
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(accounts.save(account)).thenReturn(account);

        IptvAccount saved = catalog.saveAccount(
                7L,
                "Renamed",
                Enums.IptvAccountType.XTREAM,
                "https://new-provider.test/",
                "",
                "",
                null,
                2,
                true,
                null
        );

        assertEquals("Renamed", saved.name);
        assertEquals("https://new-provider.test", saved.baseUrl);
        assertEquals("real-user", saved.username);
        assertEquals("real-secret", saved.password);
        assertEquals("ok", saved.lastHealthStatus);
        verify(accounts).save(account);
    }

    @Test
    void rejectsXtreamAccountsWithoutRealCredentials() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        assertThrows(ApiException.class, () -> catalog.saveAccount(
                null,
                "Bad Xtream",
                Enums.IptvAccountType.XTREAM,
                "https://provider.test",
                "",
                "",
                null,
                1,
                true,
                null
        ));
        verify(accounts, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsM3uUrlPastedInBaseUrlField() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);
        when(accounts.save(org.mockito.ArgumentMatchers.any(IptvAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IptvAccount saved = catalog.saveAccount(
                null,
                "MISSOU M3U",
                Enums.IptvAccountType.M3U,
                "https://provider.test/get.php?username=user&password=pass&type=m3u_plus&output=ts",
                "",
                "",
                "",
                1,
                true,
                null
        );

        assertEquals(
                "https://provider.test/get.php?username=user&password=pass&type=m3u_plus&output=ts",
                saved.playlistUrl
        );
        assertEquals("ok", saved.lastHealthStatus);
    }

    @Test
    void archivesAccountInsteadOfDeletingSessionHistory() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        IptvAccount account = new IptvAccount();
        account.id = 1L;
        account.active = true;
        account.activeStreams = 1;
        account.baseUrl = "https://provider.test";
        account.username = "user";
        account.password = "secret";
        account.playlistUrl = "https://provider.test/list.m3u";
        when(accounts.findById(1L)).thenReturn(Optional.of(account));

        catalog.deleteAccount(1L);

        assertFalse(account.active);
        assertTrue(account.disabled);
        assertEquals(0, account.activeStreams);
        assertEquals("archived", account.lastHealthStatus);
        assertEquals(IptvCatalogService.ARCHIVED_BY_ADMIN, account.disabledReason);
        assertNull(account.baseUrl);
        assertNull(account.username);
        assertNull(account.password);
        assertNull(account.playlistUrl);
        verify(accounts).save(account);
        verify(accounts, never()).delete(account);
        verify(playlists).invalidate(1L);
        verify(xtream).invalidate(1L);
        verify(metadata).invalidate(1L);
    }

    @Test
    void userPlaybackRejectsXtreamItemIds() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        UserEntity user = new UserEntity();
        user.id = 42L;

        assertThrows(ApiException.class, () -> catalog.selectStream(user, "live", "xtream-1-live-99"));
        verify(xtream, never()).load(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void assignedM3uCatalogRetriesAfterCatalogErrorAndMarksItemsPrivate() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        ProviderMetadataService metadata = mock(ProviderMetadataService.class);
        CatalogImageService images = mock(CatalogImageService.class);
        IptvCatalogService catalog = new IptvCatalogService(accounts, playlists, xtream, metadata, images);

        UserEntity user = new UserEntity();
        user.id = 42L;
        IptvAccount account = new IptvAccount();
        account.id = 5L;
        account.name = "MISSOU M3U";
        account.accountType = Enums.IptvAccountType.M3U;
        account.playlistUrl = "https://provider.test/list.m3u";
        account.active = true;
        account.disabled = false;
        account.lastHealthStatus = "catalog-unavailable";
        account.assignedUser = user;

        M3uPlaylistService.Entry entry = new M3uPlaylistService.Entry(
                "m3u-5-channel",
                "",
                "Test Channel",
                "live",
                "m3u-cat-5-news",
                "News",
                "",
                "https://provider.test/live/1.ts",
                null,
                null,
                null,
                null,
                null
        );
        M3uPlaylistService.Playlist playlist = new M3uPlaylistService.Playlist(
                List.of(entry),
                List.of(new M3uPlaylistService.Category("m3u-cat-5-news", "News", "live")),
                List.of()
        );

        when(accounts.findByAssignedUser_IdAndActiveTrueAndDisabledFalse(42L)).thenReturn(List.of(account));
        when(playlists.load(account)).thenReturn(playlist);
        when(accounts.save(account)).thenReturn(account);

        assertTrue(catalog.hasActiveSources(user));
        List<Map<String, Object>> items = catalog.items(user, "live", null, null, null, "default", 0);

        assertEquals(1, items.size());
        assertEquals("Test Channel", items.get(0).get("name"));
        assertEquals(true, items.get(0).get("privateUse"));
        assertEquals(true, items.get(0).get("privateAccess"));
        assertEquals(5L, items.get(0).get("assignedIptvAccountId"));
        assertEquals("ok", account.lastHealthStatus);
    }
}
