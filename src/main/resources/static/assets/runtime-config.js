(function () {
    const railwayApiBase = "https://nexora-api-production.up.railway.app";
    const publicSiteUrl = "https://nexoragabon.com";
    const localHosts = new Set(["localhost", "127.0.0.1", "::1"]);
    const proxiedFrontHosts = new Set(["nexoragabon.com", "www.nexoragabon.com"]);
    const railwayHost = "nexora-api-production.up.railway.app";

    function trimSlash(value) {
        return String(value || "").replace(/\/+$/, "");
    }

    function configuredBase() {
        const explicit = trimSlash(window.NEXORA_API_BASE_URL || "");
        if (explicit) return explicit;
        const host = String(window.location.hostname || "").toLowerCase();
        if (
            localHosts.has(host)
            || proxiedFrontHosts.has(host)
            || host === railwayHost
            || host.endsWith(".up.railway.app")
            || host.endsWith(".netlify.app")
            || host.endsWith(".vercel.app")
        ) {
            return "";
        }
        return railwayApiBase;
    }

    const apiBaseUrl = configuredBase();
    const apiRoot = `${apiBaseUrl}/api`;

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

    window.NEXORA_CONFIG = {
        apiBaseUrl,
        apiRoot,
        publicSiteUrl,
        railwayApiBase
    };
    window.NexoraApi = {
        baseUrl: apiBaseUrl,
        root: function () { return apiRoot; },
        url: apiUrl,
        resolve: resolveUrl
    };
}());
