(function () {
    const publicApiBase = "https://api.nexoragabon.com";
    // Ancienne agrégation FrenchNexora, utilisée en secours pour les sources /streams.
    const publicNodeApiBase = "https://api.nexoragabon.com/french-providers";
    // API Nexora Node dédiée aux films français (/api/sources/movie/:tmdbId).
    const publicLegacyNodeApiBase = "https://api.nexoragabon.com/node-fr";
    const publicAetherApiBase = "https://api.nexoragabon.com";
    // API compatible avec ibrahimdinzambou/frenchnexoraAPI.
    // Peut être remplacée sans rebuild via window.NEXORA_FRENCH_NEXORA_API_BASE_URL.
    const publicFrenchNexoraApiBase = "https://api.nexoragabon.com/french-providers";
    const publicDramaApiBase = "https://api.nexoragabon.com/drama-api";
    const publicAnimeNexoraApiBase = "https://api.nexoragabon.com/anime-api";
    const publicSiteUrl = "https://nexoragabon.com";
    const apiHost = "api.nexoragabon.com";

    function trimSlash(value) {
        return String(value || "").replace(/\/+$/, "");
    }

    function configuredBase() {
        const explicit = trimSlash(window.NEXORA_API_BASE_URL || "");
        if (explicit) return explicit;
        const host = String(window.location.hostname || "").toLowerCase();
        if (host === apiHost) {
            return "";
        }
        return publicApiBase;
    }

    function configuredNodeBase() {
        const explicit = trimSlash(window.NEXORA_FRENCH_NEXORA_API_BASE_URL || "");
        if (explicit) return explicit;
        return publicNodeApiBase;
    }

    function configuredFrenchNexoraBase() {
        const explicit = trimSlash(window.NEXORA_FRENCH_NEXORA_API_BASE_URL || "");
        return explicit || publicFrenchNexoraApiBase;
    }

    function configuredLegacyNodeBase() {
        const explicit = trimSlash(
            window.NEXORA_ORION_API_BASE_URL
            || window.NEXORA_LEGACY_NODE_API_BASE_URL
            || ""
        );
        return explicit || publicLegacyNodeApiBase;
    }

    function configuredDramaBase() {
        const explicit = trimSlash(window.NEXORA_DRAMA_API_BASE_URL || "");
        if (explicit) return explicit;
        return "";
    }

    function configuredAnimeNexoraBase() {
        const explicit = trimSlash(window.NEXORA_ANIME_API_BASE_URL || "");
        return explicit || publicAnimeNexoraApiBase;
    }

    const apiBaseUrl = configuredBase();
    const apiRoot = `${apiBaseUrl}/api`;
    const nodeApiBaseUrl = configuredNodeBase();
    const nodeApiRoot = nodeApiBaseUrl ? `${nodeApiBaseUrl}/api` : "";
    const frenchNexoraApiBaseUrl = configuredFrenchNexoraBase();
    const legacyNodeApiBaseUrl = configuredLegacyNodeBase();
    const legacyNodeApiRoot = legacyNodeApiBaseUrl ? `${legacyNodeApiBaseUrl}/api` : "";
    const aetherApiBaseUrl = publicAetherApiBase;
    const aetherApiRoot = `${aetherApiBaseUrl}/api`;
    const dramaApiBaseUrl = configuredDramaBase();
    const dramaApiRoot = dramaApiBaseUrl ? `${dramaApiBaseUrl}/api/v1/reelshort` : "";
    const animeNexoraApiBaseUrl = configuredAnimeNexoraBase();
    const animeNexoraApiRoot = `${animeNexoraApiBaseUrl}/api/v1`;

    function apiUrl(path) {
        const value = String(path || "");
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith("/api/")) return `${apiBaseUrl}${value}`;
        if (value.startsWith("api/")) return `${apiBaseUrl}/${value}`;
        return `${apiRoot}${value.startsWith("/") ? value : `/${value}`}`;
    }

    function resolveUrl(value) {
        const raw = String(value || "");
        if (!raw) return raw;
        if (/^https?:\/\//i.test(raw)) return raw;
        if (raw.startsWith("/api/") || raw.startsWith("api/")) return apiUrl(raw);
        return new URL(raw, window.location.origin).href;
    }

    function nodeApiUrl(path) {
        if (!nodeApiBaseUrl) return "";
        const value = String(path || "");
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith("/api/")) return `${nodeApiBaseUrl}${value}`;
        if (value.startsWith("api/")) return `${nodeApiBaseUrl}/${value}`;
        return `${nodeApiRoot}${value.startsWith("/") ? value : `/${value}`}`;
    }

    function resolveNodeUrl(value) {
        const raw = String(value || "");
        if (!raw) return raw;
        if (/^https?:\/\//i.test(raw)) return raw;
        if (raw.startsWith("/api/") || raw.startsWith("api/")) return nodeApiUrl(raw);
        return new URL(raw, nodeApiBaseUrl || window.location.origin).href;
    }

    function dramaApiUrl(path) {
        if (!dramaApiBaseUrl) return "";
        const value = String(path || "");
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith("/api/v1/reelshort/")) return `${dramaApiBaseUrl}${value}`;
        if (value.startsWith("api/v1/reelshort/")) return `${dramaApiBaseUrl}/${value}`;
        return `${dramaApiRoot}${value.startsWith("/") ? value : `/${value}`}`;
    }

    function animeNexoraApiUrl(path) {
        const value = String(path || "");
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith("/api/v1/")) return `${animeNexoraApiBaseUrl}${value}`;
        if (value.startsWith("api/v1/")) return `${animeNexoraApiBaseUrl}/${value}`;
        return `${animeNexoraApiRoot}${value.startsWith("/") ? value : `/${value}`}`;
    }

    function legacyNodeApiUrl(path) {
        if (!legacyNodeApiBaseUrl) return "";
        const value = String(path || "");
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith("/api/")) return `${legacyNodeApiBaseUrl}${value}`;
        if (value.startsWith("api/")) return `${legacyNodeApiBaseUrl}/${value}`;
        return `${legacyNodeApiRoot}${value.startsWith("/") ? value : `/${value}`}`;
    }

    function resolveLegacyNodeUrl(value) {
        const raw = String(value || "");
        if (!raw) return raw;
        if (/^https?:\/\//i.test(raw)) return raw;
        if (raw.startsWith("/api/") || raw.startsWith("api/")) return legacyNodeApiUrl(raw);
        return new URL(raw, legacyNodeApiBaseUrl || window.location.origin).href;
    }

    window.NEXORA_CONFIG = {
        apiBaseUrl,
        apiRoot,
        nodeApiBaseUrl,
        nodeApiRoot,
        frenchNexoraApiBaseUrl,
        legacyNodeApiBaseUrl,
        legacyNodeApiRoot,
        aetherApiBaseUrl,
        aetherApiRoot,
        dramaApiBaseUrl,
        dramaApiRoot,
        animeNexoraApiBaseUrl,
        animeNexoraApiRoot,
        orionApiBaseUrl: nodeApiBaseUrl,
        publicSiteUrl,
        railwayApiBase: publicApiBase,
        railwayNodeApiBase: publicNodeApiBase,
        railwayFrenchNexoraApiBase: publicFrenchNexoraApiBase,
        railwayLegacyNodeApiBase: publicLegacyNodeApiBase,
        railwayDramaApiBase: publicDramaApiBase
    };
    window.NexoraApi = {
        baseUrl: apiBaseUrl,
        root: function () { return apiRoot; },
        url: apiUrl,
        resolve: resolveUrl
    };
    window.NexoraNodeApi = {
        baseUrl: nodeApiBaseUrl,
        root: function () { return nodeApiRoot; },
        enabled: function () { return Boolean(nodeApiBaseUrl); },
        url: nodeApiUrl,
        resolve: resolveNodeUrl
    };
    window.NexoraLegacyNodeApi = {
        baseUrl: legacyNodeApiBaseUrl,
        root: function () { return legacyNodeApiRoot; },
        enabled: function () { return Boolean(legacyNodeApiBaseUrl); },
        url: legacyNodeApiUrl,
        resolve: resolveLegacyNodeUrl
    };
    window.NexoraAetherApi = {
        baseUrl: aetherApiBaseUrl,
        root: function () { return aetherApiRoot; },
        enabled: function () { return Boolean(aetherApiBaseUrl); },
        url: function (path) {
            const value = String(path || "");
            if (/^https?:\/\//i.test(value)) return value;
            if (value.startsWith("/api/")) return `${aetherApiBaseUrl}${value}`;
            if (value.startsWith("api/")) return `${aetherApiBaseUrl}/${value}`;
            return `${aetherApiRoot}${value.startsWith("/") ? value : `/${value}`}`;
        }
    };
    window.NexoraDramaApi = {
        baseUrl: dramaApiBaseUrl,
        root: function () { return dramaApiRoot; },
        enabled: function () { return Boolean(dramaApiBaseUrl); },
        url: dramaApiUrl
    };
    window.NexoraAnimeNexoraApi = {
        baseUrl: animeNexoraApiBaseUrl,
        root: function () { return animeNexoraApiRoot; },
        enabled: function () { return Boolean(animeNexoraApiBaseUrl); },
        url: animeNexoraApiUrl
    };
}());
