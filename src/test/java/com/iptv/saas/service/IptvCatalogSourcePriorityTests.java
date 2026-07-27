package com.iptv.saas.service;

import com.iptv.saas.domain.IptvAccount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IptvCatalogSourcePriorityTests {

    @Test
    void identifiesFrenchNexoraFromTheConfiguredAccountIdentity() {
        IptvAccount account = new IptvAccount();
        account.name = "French-Nexora principal";
        account.baseUrl = "https://stream.example";

        assertEquals("french-nexora", IptvCatalogService.catalogSourceCode(account));
    }

    @Test
    void identifiesApiNodeFromItsEndpoint() {
        IptvAccount account = new IptvAccount();
        account.name = "Catalogue secondaire";
        account.playlistUrl = "https://api-node.example/catalog.m3u";

        assertEquals("api-node", IptvCatalogService.catalogSourceCode(account));
    }

    @Test
    void keepsUnknownIptvAccountsAsNeutralSources() {
        IptvAccount account = new IptvAccount();
        account.name = "Autre fournisseur";

        assertEquals("iptv", IptvCatalogService.catalogSourceCode(account));
    }

    @Test
    void comparesSeriesYearsOnlyWhenBothProvidersDeclareOne() {
        assertEquals(false, IptvCatalogService.seriesIdentityMatches("Shogun 1980", "Shogun 2024"));
        assertEquals(true, IptvCatalogService.seriesIdentityMatches("Shogun 2024 HD", "Shogun VF"));
        assertEquals(true, IptvCatalogService.seriesIdentityMatches("Shogun 2024 HD", "Shogun 2024 VF"));
    }
}
