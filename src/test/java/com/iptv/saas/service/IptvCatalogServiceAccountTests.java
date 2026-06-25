package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

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
}
