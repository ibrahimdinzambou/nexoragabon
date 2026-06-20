package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IptvAccountHealthAuditServiceTests {
    @Test
    void disablesUnresponsiveProvidersAndClosesTheirSessions() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        IptvAccountHealthAuditService audit = new IptvAccountHealthAuditService(
                true,
                accounts,
                sessions,
                catalog,
                playlists,
                xtream
        );

        IptvAccount account = new IptvAccount();
        account.id = 33L;
        account.name = "Dead provider";
        account.accountType = Enums.IptvAccountType.XTREAM;
        account.active = true;
        account.disabled = false;
        account.activeStreams = 2;

        UserSession session = new UserSession();
        session.id = 9L;
        session.status = Enums.SessionStatus.ACTIVE;
        session.iptvAccount = account;

        when(accounts.findAll()).thenReturn(List.of(account));
        when(catalog.health(account)).thenReturn("ok");
        when(sessions.findByIptvAccountAndStatus(account, Enums.SessionStatus.ACTIVE)).thenReturn(List.of(session));
        when(xtream.refreshNow(account)).thenThrow(ApiException.providerUnavailable("Impossible de charger le catalogue Xtream"));

        IptvAccountHealthAuditService.AuditReport report = audit.auditAndDisableUnresponsive("test");

        assertEquals(1, report.disabled());
        assertFalse(account.active);
        assertTrue(account.disabled);
        assertEquals(0, account.activeStreams);
        assertEquals(1, account.failureCount);
        assertEquals("unreachable", account.lastHealthStatus);
        assertEquals("health-audit:test", account.disabledReason);
        assertEquals(Enums.SessionStatus.CLOSED, session.status);
        verify(sessions).save(session);
        verify(accounts).save(account);
        verify(xtream).invalidate(account.id);
    }

    @Test
    void keepsDisabledAccountsOutOfCapacityCountersDuringAudit() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        IptvAccountHealthAuditService audit = new IptvAccountHealthAuditService(
                true,
                accounts,
                sessions,
                catalog,
                playlists,
                xtream
        );

        IptvAccount account = new IptvAccount();
        account.id = 44L;
        account.name = "Disabled";
        account.active = true;
        account.disabled = true;
        account.activeStreams = 3;
        account.lastHealthStatus = "stream-failed";

        when(accounts.findAll()).thenReturn(List.of(account));
        when(sessions.findByIptvAccountAndStatus(account, Enums.SessionStatus.ACTIVE)).thenReturn(List.of());

        IptvAccountHealthAuditService.AuditReport report = audit.auditAndDisableUnresponsive("test");

        assertEquals(1, report.skipped());
        assertFalse(account.active);
        assertTrue(account.disabled);
        assertEquals(0, account.activeStreams);
        assertEquals("disabled", account.lastHealthStatus);
        verify(accounts).save(account);
    }

    @Test
    void warnsOnEmptyCatalogWithoutDisablingReachableProvider() {
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        M3uPlaylistService playlists = mock(M3uPlaylistService.class);
        XtreamCatalogService xtream = mock(XtreamCatalogService.class);
        IptvAccountHealthAuditService audit = new IptvAccountHealthAuditService(
                true,
                accounts,
                sessions,
                catalog,
                playlists,
                xtream
        );

        IptvAccount account = new IptvAccount();
        account.id = 55L;
        account.name = "Empty provider";
        account.accountType = Enums.IptvAccountType.M3U;
        account.active = true;
        account.disabled = false;

        when(accounts.findAll()).thenReturn(List.of(account));
        when(catalog.health(account)).thenReturn("ok");
        when(playlists.refreshNow(account)).thenReturn(new M3uPlaylistService.Playlist(List.of(), List.of(), List.of()));

        IptvAccountHealthAuditService.AuditReport report = audit.auditAndDisableUnresponsive("test");

        assertEquals(1, report.warnings());
        assertEquals(0, report.disabled());
        assertTrue(account.active);
        assertFalse(account.disabled);
        assertEquals("empty-catalog", account.lastHealthStatus);
        verify(accounts).save(account);
    }
}
