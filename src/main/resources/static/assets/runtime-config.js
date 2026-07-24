(function () {
    const publicApiBase = "https://api.nexoragabon.com";
    // L'API Content passe par l'hote API principal, dont le certificat couvre
    // deja api.nexoragabon.com. Le lecteur garde son sous-domaine dedie.
    const publicContentNexoraApiBase = "https://api.nexoragabon.com/content-api";
    const publicContentNexoraPlayerBase = "https://content.nexoragabon.com";
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
        return host === apiHost ? "" : publicApiBase;
    }

    function configuredContentNexoraBase() {
        const explicit = trimSlash(
            window.NEXORA_CONTENT_NEXORA_API_BASE_URL
            || window.NEXORA_CONTENT_NEXORA_BASE_URL
            || ""
        );
        return explicit || publicContentNexoraApiBase;
    }

    function configuredDramaBase() {
        return trimSlash(window.NEXORA_DRAMA_API_BASE_URL || "") || publicDramaApiBase;
    }

    function configuredAnimeNexoraBase() {
        const explicit = trimSlash(window.NEXORA_ANIME_API_BASE_URL || "");
        if (explicit) return explicit;
        return publicAnimeNexoraApiBase;
    }

    function configuredContentNexoraPlayerBase() {
        return trimSlash(window.NEXORA_CONTENT_NEXORA_PLAYER_BASE_URL || "")
            || publicContentNexoraPlayerBase;
    }

    const apiBaseUrl = configuredBase();
    const apiRoot = `${apiBaseUrl}/api`;
    const contentNexoraApiBaseUrl = configuredContentNexoraBase();
    const contentNexoraApiRoot = `${contentNexoraApiBaseUrl}/api`;
    const contentNexoraPlayerBaseUrl = configuredContentNexoraPlayerBase();
    const dramaApiBaseUrl = configuredDramaBase();
    const dramaApiRoot = `${dramaApiBaseUrl}/api/v1/reelshort`;
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

    function contentNexoraApiUrl(path) {
        const value = String(path || "");
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith("/api/")) return `${contentNexoraApiBaseUrl}${value}`;
        if (value.startsWith("api/")) return `${contentNexoraApiBaseUrl}/${value}`;
        return `${contentNexoraApiRoot}${value.startsWith("/") ? value : `/${value}`}`;
    }

    function contentNexoraPlayerUrl(parameters = {}) {
        const url = new URL(`${contentNexoraPlayerBaseUrl}/`);
        Object.entries(parameters || {}).forEach(([key, value]) => {
            if (value !== undefined && value !== null && String(value).trim() !== "") {
                url.searchParams.set(key, String(value));
            }
        });
        return url.href;
    }

    function dramaApiUrl(path) {
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

    window.NEXORA_CONFIG = {
        apiBaseUrl,
        apiRoot,
        contentNexoraApiBaseUrl,
        contentNexoraApiRoot,
        contentNexoraPlayerBaseUrl,
        dramaApiBaseUrl,
        dramaApiRoot,
        animeNexoraApiBaseUrl,
        animeNexoraApiRoot,
        publicSiteUrl
    };

    window.NexoraApi = {
        baseUrl: apiBaseUrl,
        root: function () { return apiRoot; },
        url: apiUrl,
        resolve: resolveUrl
    };
    window.NexoraContentNexoraApi = {
        baseUrl: contentNexoraApiBaseUrl,
        root: function () { return contentNexoraApiRoot; },
        enabled: function () { return Boolean(contentNexoraApiBaseUrl); },
        url: contentNexoraApiUrl,
        player: contentNexoraPlayerUrl
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
