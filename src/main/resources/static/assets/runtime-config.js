(function () {
    const publicApiBase = "https://api.nexoragabon.com";
    const publicNodeApiBase = "https://api.nexoragabon.com/node-fr";
    const publicSiteUrl = "https://nexoragabon.com";
    const localHosts = new Set(["localhost", "127.0.0.1", "::1"]);
    const proxiedFrontHosts = new Set(["nexoragabon.com", "www.nexoragabon.com"]);
    const apiHost = "api.nexoragabon.com";

    function trimSlash(value) {
        return String(value || "").replace(/\/+$/, "");
    }

    function configuredBase() {
        const explicit = trimSlash(window.NEXORA_API_BASE_URL || "");
        if (explicit) return explicit;
        const host = String(window.location.hostname || "").toLowerCase();
        if (localHosts.has(host)) {
            return "";
        }
        if (proxiedFrontHosts.has(host) || host === apiHost) {
            return "";
        }
        return publicApiBase;
    }

    function configuredNodeBase() {
        const explicit = trimSlash(
            window.NEXORA_NODE_API_BASE_URL || window.NEXORA_ORION_API_BASE_URL || ""
        );
        if (explicit) return explicit;
        return publicNodeApiBase;
    }

    const apiBaseUrl = configuredBase();
    const apiRoot = `${apiBaseUrl}/api`;
    const nodeApiBaseUrl = configuredNodeBase();
    const nodeApiRoot = nodeApiBaseUrl ? `${nodeApiBaseUrl}/api` : "";

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

    window.NEXORA_CONFIG = {
        apiBaseUrl,
        apiRoot,
        nodeApiBaseUrl,
        nodeApiRoot,
        orionApiBaseUrl: nodeApiBaseUrl,
        publicSiteUrl,
        railwayApiBase: publicApiBase,
        railwayNodeApiBase: publicNodeApiBase
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
}());
