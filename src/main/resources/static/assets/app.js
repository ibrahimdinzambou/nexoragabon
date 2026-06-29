const API_ROOT = window.NexoraApi?.root?.() || "/api";
const NODE_API_ROOT = window.NexoraNodeApi?.root?.() || "";
const TOKEN_KEY = "nexora_access_token";
const MOVIE_SORT_KEY = "nexora_movie_sort";
const SETTINGS_KEY = "nexora_profile_settings";
const RECENTLY_WATCHED_KEY = "nexora_recently_watched";
const ACTIVE_PLAYBACK_KEY = "nexora_active_playback";
const NETWORK_RETRY_DELAYS = [700, 1800, 4000];
const STREAM_OPEN_RETRY_DELAYS = [500, 1200];
const IMAGE_RETRY_LIMIT = 2;
const PLAYER_RECOVERY_DELAY_MS = 6000;
const PLAYER_STARTUP_TIMEOUT_MS = 10000;
const STREAM_PREFLIGHT_TIMEOUT_MS = 3500;
const PLAYER_VISUAL_WATCHDOG_MS = 4500;
const PLAYER_VISUAL_WATCHDOG_MAX_MS = 14000;
const PLAYER_MAX_RECOVERY_ATTEMPTS = 4;
const PLAYER_MANUAL_PAUSE_WINDOW_MS = 1400;
const PLAYER_PAUSE_RESUME_DELAY_MS = 900;
const DASH_MANIFEST_TIMEOUT_MS = 10000;
const DASH_PLAY_PROMISE_TIMEOUT_MS = 3500;
const SEARCH_DEBOUNCE_MS = 550;
const MIN_REMOTE_SEARCH_LENGTH = 2;
const HERO_AUTO_ADVANCE_MS = 6500;
const HERO_MAX_SLIDES = 6;
const HOME_PREVIEW_LIMIT = 30;
const HOME_SHOWCASE_LIMIT = 20;
const LOAD_MORE_INCREMENT = 300;
const DRAMA_VISIBLE_INCREMENT = 50;
const DRAMA_SEARCH_PAGE_SIZE = 11;
const RECENTLY_WATCHED_LIMIT = 24;
const DEFAULT_VISIBLE_CATALOG = { live: 300, movie: 900, series: 900 };
const SEARCH_VISIBLE_CATALOG = { live: 120, movie: 240, series: 240 };
const VIDEASY_PLAYER_BASE_URL = "https://player.videasy.to";
const VIDEASY_ACCENT_COLOR = "e7c36d";
const KOREAN_DRAMA_CATEGORY_ID = "tmdb-series-korean-drama";
const EMBED_PLAYER_ALLOW = "autoplay; fullscreen; picture-in-picture; encrypted-media";
const MOBILE_EMBED_QUERY = "(max-width: 760px), (pointer: coarse)";
const NODE_MOVIE_PROVIDER = "auto";
const imageRepairCache = new Map();
const imageRepairInFlight = new Set();
const launchParams = new URLSearchParams(window.location.search);
const WATCH_REQUIRES_AUTH = launchParams.get("demo") !== "1";
const titleCollator = new Intl.Collator("fr", { sensitivity: "base", numeric: true });
const defaultSettings = {
    avatarTone: "gold",
    language: "fr",
    quality: "auto",
    subtitles: "off",
    autoplay: true,
    compactCards: false
};

const fallbackCatalog = [
    { id: "1001", name: "Africa News", type: "live", categoryId: "news", categoryName: "Actualités", image: "/assets/images/landscape-1.jpg" },
    { id: "1002", name: "World Sports", type: "live", categoryId: "sports", categoryName: "Sports", image: "/assets/images/landscape-2.jpg" },
    { id: "2001", name: "Action Demo Movie", type: "movie", categoryId: "movies-action", categoryName: "Films", image: "/assets/images/landscape-5.jpg" },
    { id: "3001", name: "Demo Series", type: "series", categoryId: "series-drama", categoryName: "Séries", image: "/assets/images/poster-2.jpg" }
];

const rowDefinitions = [
    { type: "live", title: "En direct maintenant", label: "Chaînes disponibles" },
    { type: "movie", title: "Films à ne pas manquer", label: "Sélection éditoriale" },
    { type: "series", title: "Séries du moment", label: "À regarder ensuite" }
];

const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: null,
    organization: null,
    subscription: null,
    iptv: null,
    catalog: [...fallbackCatalog],
    browseCatalog: [...fallbackCatalog],
    categories: [],
    languages: [],
    activeType: "all",
    dramaLoaded: false,
    dramaLang: "fr",
    dramaMode: "shelves",
    dramaSearchPage: 1,
    dramaSearchQuery: "love",
    dramaSearchHasMore: false,
    dramaVisibleLimit: DRAMA_VISIBLE_INCREMENT,
    dramaShelves: [],
    dramaShelfVisible: {},
    dramaShelfPaging: {},
    dramaHydratingShelves: false,
    dramaBooks: [],
    dramaCurrentTitle: "Dramas",
    dramaCurrentShelfIndex: null,
    activeDramaBook: null,
    activeDramaEpisodes: [],
    activeCategory: "",
    activeAddonFilter: "",
    addonPages: 1,
    addonHasMore: false,
    activeLanguage: "",
    visibleCatalog: { ...DEFAULT_VISIBLE_CATALOG },
    recentlyWatched: loadRecentlyWatched(),
    hiddenCatalogCards: new Set(),
    hiddenContinueCards: new Set(),
    movieSort: localStorage.getItem(MOVIE_SORT_KEY) || "title-asc",
    homeShuffleSalt: Math.floor(Math.random() * 1000000),
    query: "",
    authMode: "login",
    authRequired: WATCH_REQUIRES_AUTH,
    pendingAuthAction: null,
    profileSettings: loadProfileSettings(),
    twoFactorEmail: null,
    emailVerificationEmail: null,
    passwordResetEmail: null,
    passwordResetCode: null,
    activeSessionToken: null,
    playerOpening: false,
    heartbeatId: null,
    mpegtsPlayer: null,
    dashPlayer: null,
    hlsPlayer: null,
    playerHasStarted: false,
    playerErrorShown: false,
    playerRecoveryTimer: null,
    playerStartupTimer: null,
    playerRecoveryAttempts: 0,
    playerRecovering: false,
    playerRecoveryShouldFailover: false,
    playerLastProgressAt: 0,
    playerVisualWatchTimer: null,
    playerPauseResumeTimer: null,
    playerLastUserIntentAt: 0,
    playerDetaching: false,
    playerVisualWatchStartedAt: 0,
    playerVisualFallbackPending: false,
    activePlayerItem: null,
    activeProxyUrl: null,
    activeEmbedUrl: null,
    activeEmbedFallbackUrl: null,
    activeEmbedFallbackLabel: "",
    activeVisualWatchdogEnabled: false,
    activeCanFailover: false,
    embedRequiresUserLaunch: false,
    embedShieldTimer: null,
    embedShieldUnlockedUntil: 0,
    embedAssistTimer: null,
    embedAssistShown: false,
    embedManualRetryUsed: false,
    embedLoadCount: 0,
    embedReloading: false,
    nativePlaybackRequiresUserLaunch: false,
    pendingNonFrenchConfirmation: null,
    activePlaybackMode: null,
    activePlaybackQuality: null,
    activePreferredAudioLanguage: null,
    activePreferredSubtitleLanguage: null,
    playerGeneration: 0,
    catalogLoading: false,
    catalogIsFallback: true,
    catalogRequestId: 0,
    catalogAbortController: null,
    searchResults: [],
    searchResultQuery: "",
    searchSuggestions: [],
    searchActiveIndex: -1,
    heroSlides: [],
    heroIndex: 0,
    heroTimer: null,
    heroPaused: false,
    activeSeries: null,
    activeDetail: null,
    requestedPlan: launchParams.get("plan")
};

const elements = {
    topbar: document.querySelector("#topbar"),
    navLinks: [...document.querySelectorAll(".nav-link")],
    searchShell: document.querySelector("#searchShell"),
    searchInput: document.querySelector("#searchInput"),
    searchClear: document.querySelector("#searchClear"),
    searchPanel: document.querySelector("#searchSuggestions"),
    accountButton: document.querySelector("#accountButton"),
    accountLabel: document.querySelector("#accountLabel"),
    accountAvatar: document.querySelector("#accountAvatar"),
    hero: document.querySelector("#accueil"),
    heroContent: document.querySelector("#heroContent"),
    heroBackdrop: document.querySelector(".hero-backdrop"),
    heroEyebrow: document.querySelector("#heroEyebrow"),
    heroTitle: document.querySelector("#heroTitle"),
    heroCopy: document.querySelector("#heroCopy"),
    heroMatch: document.querySelector("#heroMatch"),
    heroQuality: document.querySelector("#heroQuality"),
    heroType: document.querySelector("#heroType"),
    heroAge: document.querySelector("#heroAge"),
    heroPlay: document.querySelector("#heroPlay"),
    heroPrev: document.querySelector("#heroPrev"),
    heroNext: document.querySelector("#heroNext"),
    heroCurrent: document.querySelector("#heroCurrent"),
    heroTotal: document.querySelector("#heroTotal"),
    heroProgress: document.querySelector("#heroProgress"),
    heroDots: document.querySelector("#heroDots"),
    homeForYouPanel: document.querySelector("#homeForYouPanel"),
    homeFeaturedCard: document.querySelector("#homeFeaturedCard"),
    homeForYouList: document.querySelector("#homeForYouList"),
    discoverButton: document.querySelector("#discoverButton"),
    catalogRows: document.querySelector("#catalogRows"),
    dramaSection: document.querySelector("#dramaSection"),
    dramaStatus: document.querySelector("#dramaStatus"),
    dramaSearchForm: document.querySelector("#dramaSearchForm"),
    dramaSearchInput: document.querySelector("#dramaSearchInput"),
    dramaLang: document.querySelector("#dramaLang"),
    dramaShelves: document.querySelector("#dramaShelves"),
    dramaShelfRows: document.querySelector("#dramaShelfRows"),
    dramaGrid: document.querySelector("#dramaGrid"),
    dramaLoadMore: document.querySelector("#dramaLoadMore"),
    dramaEpisodes: document.querySelector("#dramaEpisodes"),
    dramaEpisodeTitle: document.querySelector("#dramaEpisodeTitle"),
    dramaEpisodeCount: document.querySelector("#dramaEpisodeCount"),
    dramaEpisodeList: document.querySelector("#dramaEpisodeList"),
    catalogStatus: document.querySelector("#catalogStatus"),
    catalogKicker: document.querySelector("#catalogKicker"),
    catalogTitle: document.querySelector("#catalogTitle"),
    genreList: document.querySelector("#genreList"),
    addonFilterControl: document.querySelector("#addonFilterControl"),
    addonFilterLabel: document.querySelector("#addonFilterLabel"),
    addonFilterSelect: document.querySelector("#addonFilterSelect"),
    languageFilter: document.querySelector("#languageFilter"),
    languageSelect: document.querySelector("#languageSelect"),
    movieSortControl: document.querySelector("#movieSortControl"),
    movieSort: document.querySelector("#movieSort"),
    emptyState: document.querySelector("#emptyState"),
    resetFilters: document.querySelector("#resetFilters"),
    authModal: document.querySelector("#authModal"),
    authForm: document.querySelector("#authForm"),
    authTabs: document.querySelector("#authTabs"),
    authTitle: document.querySelector("#authTitle"),
    authSubtitle: document.querySelector("#authSubtitle"),
    authSubmit: document.querySelector("#authSubmit"),
    authError: document.querySelector("#authError"),
    authEmailField: document.querySelector("#authEmailField"),
    authPasswordField: document.querySelector("#authPasswordField"),
    twoFactorField: document.querySelector("#twoFactorField"),
    resetPasswordField: document.querySelector("#resetPasswordField"),
    forgotPasswordButton: document.querySelector("#forgotPasswordButton"),
    profileModal: document.querySelector("#profileModal"),
    profileName: document.querySelector("#profileName"),
    profileEmail: document.querySelector("#profileEmail"),
    profileAvatar: document.querySelector("#profileAvatar"),
    profileOrganization: document.querySelector("#profileOrganization"),
    profilePlan: document.querySelector("#profilePlan"),
    profileStatus: document.querySelector("#profileStatus"),
    settingsNav: document.querySelector(".settings-nav"),
    profileSettingsForm: document.querySelector("#profileSettingsForm"),
    playbackSettingsForm: document.querySelector("#playbackSettingsForm"),
    passwordSettingsForm: document.querySelector("#passwordSettingsForm"),
    settingsName: document.querySelector("#settingsName"),
    settingsEmail: document.querySelector("#settingsEmail"),
    settingsLanguage: document.querySelector("#settingsLanguage"),
    settingsQuality: document.querySelector("#settingsQuality"),
    settingsSubtitles: document.querySelector("#settingsSubtitles"),
    settingsAutoplay: document.querySelector("#settingsAutoplay"),
    settingsCompactCards: document.querySelector("#settingsCompactCards"),
    settingsTwoFactor: document.querySelector("#settingsTwoFactor"),
    settingsPlan: document.querySelector("#settingsPlan"),
    settingsStatus: document.querySelector("#settingsStatus"),
    settingsPeriodEnd: document.querySelector("#settingsPeriodEnd"),
    settingsStreams: document.querySelector("#settingsStreams"),
    adminConsoleLink: document.querySelector("#adminConsoleLink"),
    logoutAllButton: document.querySelector("#logoutAllButton"),
    logoutButton: document.querySelector("#logoutButton"),
    footerAccountButton: document.querySelector("#footerAccountButton"),
    detailModal: document.querySelector("#detailModal"),
    detailBackdrop: document.querySelector("#detailBackdrop"),
    detailPoster: document.querySelector("#detailPoster"),
    detailCategory: document.querySelector("#detailCategory"),
    detailTitle: document.querySelector("#detailTitle"),
    detailMeta: document.querySelector("#detailMeta"),
    detailSummary: document.querySelector("#detailSummary"),
    detailFacts: document.querySelector("#detailFacts"),
    detailPlay: document.querySelector("#detailPlay"),
    detailLoading: document.querySelector("#detailLoading"),
    seriesModal: document.querySelector("#seriesModal"),
    seriesBackdrop: document.querySelector("#seriesBackdrop"),
    seriesPoster: document.querySelector("#seriesPoster"),
    seriesCategory: document.querySelector("#seriesCategory"),
    seriesTitle: document.querySelector("#seriesTitle"),
    seriesSummary: document.querySelector("#seriesSummary"),
    seriesSeasonCount: document.querySelector("#seriesSeasonCount"),
    seriesEpisodeCount: document.querySelector("#seriesEpisodeCount"),
    seriesRating: document.querySelector("#seriesRating"),
    seriesRelease: document.querySelector("#seriesRelease"),
    seriesCredits: document.querySelector("#seriesCredits"),
    seriesLoading: document.querySelector("#seriesLoading"),
    seriesContent: document.querySelector("#seriesContent"),
    seriesSeasonSelect: document.querySelector("#seriesSeasonSelect"),
    seasonTitle: document.querySelector("#seasonTitle"),
    episodeList: document.querySelector("#episodeList"),
    seriesError: document.querySelector("#seriesError"),
    playerModal: document.querySelector("#playerModal"),
    streamPlayer: document.querySelector("#streamPlayer"),
    embedPlayer: document.querySelector("#embedPlayer"),
    embedClickShield: document.querySelector("#embedClickShield"),
    embedUnlockButton: document.querySelector("#embedUnlockButton"),
    embedLaunchPanel: document.querySelector("#embedLaunchPanel"),
    embedLaunchInlineButton: document.querySelector("#embedLaunchInlineButton"),
    embedOpenExternalLink: document.querySelector("#embedOpenExternalLink"),
    playerPlaceholder: document.querySelector("#playerPlaceholder"),
    playerTitle: document.querySelector("#playerTitle"),
    playerEpisodeSubtitle: document.querySelector("#playerEpisodeSubtitle"),
    playerInfoTitle: document.querySelector("#playerInfoTitle"),
    playerInfoSubtitle: document.querySelector("#playerInfoSubtitle"),
    playerInfoPills: document.querySelector("#playerInfoPills"),
    playerSynopsis: document.querySelector("#playerSynopsis"),
    playerEpisodePanel: document.querySelector("#playerEpisodePanel"),
    playerEpisodePanelTitle: document.querySelector("#playerEpisodePanelTitle"),
    playerEpisodePanelCount: document.querySelector("#playerEpisodePanelCount"),
    playerEpisodePanelList: document.querySelector("#playerEpisodePanelList"),
    playerAllEpisodesButton: document.querySelector("#playerAllEpisodesButton"),
    playerPrevEpisodeButton: document.querySelector("#playerPrevEpisodeButton"),
    playerNextEpisodeButton: document.querySelector("#playerNextEpisodeButton"),
    playerResumeButton: document.querySelector("#playerResumeButton"),
    playerVfButton: document.querySelector("#playerVfButton"),
    playerVostfrButton: document.querySelector("#playerVostfrButton"),
    playerFavoriteButton: document.querySelector("#playerFavoriteButton"),
    playerSearchForm: document.querySelector("#playerSearchForm"),
    playerSearchInput: document.querySelector("#playerSearchInput"),
    playerSearchClear: document.querySelector("#playerSearchClear"),
    playerRoomAvatar: document.querySelector("#playerRoomAvatar"),
    playerRoomUserName: document.querySelector("#playerRoomUserName"),
    playerBadge: document.querySelector("#playerBadge"),
    playerQuality: document.querySelector("#playerQuality"),
    playerFullscreenButton: document.querySelector("#playerFullscreenButton"),
    playerEmbedRetryButton: document.querySelector("#playerEmbedRetryButton"),
    playerEmbedOpenLink: document.querySelector("#playerEmbedOpenLink"),
    playerMessage: document.querySelector("#playerMessage"),
    toast: document.querySelector("#toast")
};

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");

    if (options.body && !(options.body instanceof FormData)) {
        headers.set("Content-Type", "application/json");
    }
    if (state.token) {
        headers.set("Authorization", `Bearer ${state.token}`);
    }

    const response = await fetchWithRetry(apiUrl(path), { ...options, headers });
    const body = await response.json().catch(() => ({}));

    if (!response.ok || body.success === false) {
        const error = new Error(body.message || "Une erreur est survenue.");
        error.code = body.code;
        error.status = response.status;
        throw error;
    }

    return body.data;
}

function apiUrl(path) {
    return window.NexoraApi?.url ? window.NexoraApi.url(path) : `${API_ROOT}${path}`;
}

function nodeApiUrl(path) {
    return window.NexoraNodeApi?.url ? window.NexoraNodeApi.url(path) : "";
}

function resolveApiResourceUrl(value) {
    return window.NexoraApi?.resolve
        ? window.NexoraApi.resolve(value)
        : new URL(value, window.location.origin).href;
}

function resolveNodeApiResourceUrl(value) {
    return window.NexoraNodeApi?.resolve
        ? window.NexoraNodeApi.resolve(value)
        : resolveApiResourceUrl(value);
}

function nodeApiEnabled() {
    return Boolean(NODE_API_ROOT || window.NexoraNodeApi?.enabled?.());
}

async function nodeApi(path, options = {}) {
    const url = nodeApiUrl(path);
    if (!url) {
        throw new Error("API Node FR non configurée.");
    }

    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");
    const response = await fetchWithRetry(url, { ...options, headers });
    const body = await response.json().catch(() => ({}));

    if (!response.ok || body.ok === false) {
        const error = new Error(body.erreur || body.message || "Source FR indisponible.");
        error.status = response.status;
        throw error;
    }
    return body;
}

async function nodeCatalogApi(path, options = {}) {
    const body = await nodeApi(path, options);
    return Array.isArray(body) ? body : (body.data || body.items || []);
}

async function fetchWithRetry(url, options) {
    const method = String(options.method || "GET").toUpperCase();
    const retryDelays = options.retryTransient === true
        ? STREAM_OPEN_RETRY_DELAYS
        : NETWORK_RETRY_DELAYS;
    const canRetry = (method === "GET" && !options.body) || options.retryTransient === true;
    const { retryTransient, ...fetchOptions } = options;

    for (let attempt = 0; ; attempt += 1) {
        try {
            const response = await fetch(url, fetchOptions);
            const transientStatus = [502, 503, 504].includes(response.status);
            if (!canRetry || !transientStatus || attempt >= retryDelays.length) {
                return response;
            }
            if (method === "GET" && await isFinalApiError(response)) {
                return response;
            }
        } catch (error) {
            const aborted = error?.name === "AbortError" || fetchOptions.signal?.aborted;
            if (!canRetry || aborted || attempt >= retryDelays.length) {
                throw error;
            }
        }
        await delay(retryDelays[attempt]);
    }
}

async function isFinalApiError(response) {
    const body = await response.clone().json().catch(() => null);
    return ["forbidden", "not_found", "service_unavailable", "stream_unavailable", "validation_error"]
        .includes(body?.code);
}

function delay(milliseconds) {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function retryCatalogImage(image, fallback) {
    const originalSource = image.dataset.originalSource || image.getAttribute("src") || "";
    image.dataset.originalSource = originalSource;
    const sourceUrl = new URL(originalSource, window.location.origin);
    const isCatalogProxy = sourceUrl.pathname.startsWith("/api/catalog/images/");
    const retryCount = Number(image.dataset.retryCount || 0);
    const directSource = image.dataset.directSource || sourceUrl.searchParams.get("url") || "";

    if (isCatalogProxy && retryCount < IMAGE_RETRY_LIMIT) {
        const nextRetry = retryCount + 1;
        image.dataset.retryCount = String(nextRetry);
        window.setTimeout(() => {
            if (!image.isConnected) return;
            const retryUrl = new URL(originalSource, window.location.origin);
            retryUrl.searchParams.set("_retry", `${Date.now()}-${nextRetry}`);
            image.src = retryUrl.href;
        }, NETWORK_RETRY_DELAYS[Math.min(retryCount, NETWORK_RETRY_DELAYS.length - 1)]);
        return;
    }

    if (directSource && image.dataset.directFallbackTried !== "1") {
        image.dataset.directFallbackTried = "1";
        image.onerror = () => retryCatalogImage(image, fallback);
        image.src = directSource;
        return;
    }

    image.onerror = null;
    image.src = fallback;
}

async function repairCatalogImage(image) {
    if (!image?.isConnected || image.dataset.placeholderRepairChecked === "1") return;
    const source = image.currentSrc || image.src || "";
    let sourceUrl;
    try {
        sourceUrl = new URL(source, window.location.origin);
    } catch {
        return;
    }
    if (!sourceUrl.pathname.startsWith("/api/catalog/images/")) return;

    const repairKey = sourceUrl.href.split("_retry=", 1)[0];
    if (imageRepairInFlight.has(repairKey)) return;
    image.dataset.placeholderRepairChecked = "1";
    imageRepairInFlight.add(repairKey);
    let response;
    try {
        response = await fetch(sourceUrl.href, {
            cache: "no-store",
            credentials: "same-origin"
        });
    } catch {
        imageRepairInFlight.delete(repairKey);
        return;
    }
    imageRepairInFlight.delete(repairKey);
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.includes("image/svg+xml")) return;

    const title = String(image.dataset.imageTitle || "").trim();
    const type = String(image.dataset.imageType || "").trim() || "movie";
    if (!title) return;

    const repaired = await findReplacementPoster(title, type, source);
    if (!repaired || !image.isConnected) return;
    image.dataset.originalSource = repaired;
    image.dataset.placeholderRepairChecked = "0";
    image.onerror = () => retryCatalogImage(image, "/assets/images/poster-1.jpg");
    image.src = repaired;
}

async function findReplacementPoster(title, type, currentSource) {
    const catalogType = type === "drama" || type === "kdrama" ? "series" : type;
    if (!["live", "movie", "series"].includes(catalogType)) return "";
    const key = `${catalogType}:${normalizeSearchText(title)}`;
    if (imageRepairCache.has(key)) {
        return imageRepairCache.get(key);
    }
    if (!state.token && state.authRequired) {
        imageRepairCache.set(key, "");
        return "";
    }
    try {
        const params = new URLSearchParams({
            type: catalogType,
            q: title,
            limit: "12"
        });
        const candidates = await api(`/catalog/items?${params}`);
        const match = (candidates || []).find((candidate) => {
            const candidateImage = candidate.poster || candidate.image || candidate.backdrop || "";
            if (!candidateImage || candidateImage === currentSource) return false;
            return isTmdbSource(candidate)
                || isTmdbPlayable(candidate)
                || normalizeSearchText(candidate.name || "").includes(normalizeSearchText(title).slice(0, 16));
        });
        const replacement = match
            ? normalizeImageSource(match.poster || match.image || match.backdrop, "/assets/images/poster-1.jpg")
            : "";
        imageRepairCache.set(key, replacement);
        return replacement;
    } catch {
        imageRepairCache.set(key, "");
        return "";
    }
}

function setResilientImage(image, source, fallback) {
    const safeSource = normalizeImageSource(source, fallback);
    const directSource = tmdbDirectImageUrl(source);
    image.dataset.originalSource = safeSource;
    if (directSource) image.dataset.directSource = directSource;
    image.dataset.retryCount = "0";
    image.onerror = () => retryCatalogImage(image, fallback);
    image.src = safeSource;
}

window.repairCatalogImage = repairCatalogImage;

function catalogImageProxyUrl(source) {
    return resolveApiResourceUrl(`/api/catalog/images/proxy?url=${encodeURIComponent(source)}`);
}

function tmdbImagePathUrl(path, size = "w500") {
    const normalized = String(path || "").trim().replace(/^\/+/, "");
    return `https://image.tmdb.org/t/p/${size}/${normalized}`;
}

function tmdbDirectImageUrl(source) {
    const value = String(source || "").trim();
    if (/^[A-Za-z0-9_-]+\.(?:jpe?g|png|webp|avif)$/i.test(value)) {
        return tmdbImagePathUrl(value);
    }
    try {
        const parsed = new URL(value, window.location.origin);
        return isTmdbImageUrl(parsed) ? parsed.href : "";
    } catch {
        return "";
    }
}

function isTmdbImageUrl(url) {
    return /(^|\.)image\.tmdb\.org$/i.test(url.hostname);
}

function normalizeImageSource(source, fallback = "/assets/images/landscape-1.jpg") {
    const value = String(source || "").trim();
    if (!value) return fallback;
    if (/^(?:file:|[a-z]:[\\/]|\\\\)/i.test(value)) return fallback;
    if (/^\/[A-Za-z0-9_/-]+\.(?:jpe?g|png|webp|avif)$/i.test(value)) {
        return tmdbImagePathUrl(value);
    }
    if (value.startsWith("/api/") || value.startsWith("api/")) {
        return resolveApiResourceUrl(value);
    }
    if (/^[A-Za-z0-9_-]+\.(?:jpe?g|png|webp|avif)$/i.test(value)) {
        return tmdbImagePathUrl(value);
    }

    try {
        const parsed = new URL(value, window.location.origin);
        if (!["http:", "https:"].includes(parsed.protocol)) return fallback;
        if (isTmdbImageUrl(parsed)) {
            return parsed.href;
        }
        return parsed.href;
    } catch {
        return fallback;
    }
}

function normalizeNodeImageSource(source, fallback = "/assets/images/landscape-1.jpg") {
    const value = String(source || "").trim();
    if (!value) return fallback;
    if (/^(?:file:|[a-z]:[\\/]|\\\\)/i.test(value)) return fallback;
    if (/^\/[A-Za-z0-9_/-]+\.(?:jpe?g|png|webp|avif)$/i.test(value)) {
        return tmdbImagePathUrl(value);
    }
    if (value.startsWith("/api/") || value.startsWith("api/")) {
        return resolveNodeApiResourceUrl(value);
    }
    try {
        const parsed = new URL(value, window.location.origin);
        if (!["http:", "https:"].includes(parsed.protocol)) return fallback;
        return catalogImageProxyUrl(parsed.href);
    } catch {
        return normalizeImageSource(value, fallback);
    }
}

function loadProfileSettings() {
    try {
        return { ...defaultSettings, ...JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}") };
    } catch {
        return { ...defaultSettings };
    }
}

function saveProfileSettings(nextSettings) {
    state.profileSettings = { ...state.profileSettings, ...nextSettings };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(state.profileSettings));
    applyProfileSettings();
}

function recentlyWatchedKey(user = null) {
    const owner = user?.id || user?.email || "anonymous";
    return `${RECENTLY_WATCHED_KEY}:${owner}`;
}

function loadRecentlyWatched(user = null) {
    try {
        const values = JSON.parse(localStorage.getItem(recentlyWatchedKey(user)) || "[]");
        if (!Array.isArray(values)) return [];
        return values
            .map((item, index) => normalizeRecentItem(item, index))
            .filter(Boolean)
            .slice(0, RECENTLY_WATCHED_LIMIT);
    } catch {
        return [];
    }
}

function normalizeRecentItem(item, index = 0) {
    if (!item || !item.id || !item.type || !item.name) return null;
    const type = ["live", "movie", "series"].includes(item.type) ? item.type : "movie";
    return normalizeItem({
        ...item,
        watchedAt: Number(item.watchedAt || 0),
        isEpisode: Boolean(item.isEpisode)
    }, type, index);
}

function saveRecentlyWatched() {
    localStorage.setItem(
        recentlyWatchedKey(state.user),
        JSON.stringify(state.recentlyWatched.slice(0, RECENTLY_WATCHED_LIMIT))
    );
}

function activePlaybackKey(user = null) {
    const owner = user?.id || user?.email || "anonymous";
    return `${ACTIVE_PLAYBACK_KEY}:${owner}`;
}

function saveActivePlayback(item) {
    if (!item?.id || !item?.type || !item?.name) return;
    localStorage.setItem(activePlaybackKey(state.user), JSON.stringify(recentItemPayload(item)));
}

function clearActivePlayback() {
    localStorage.removeItem(activePlaybackKey(state.user));
}

function loadActivePlayback(user = null) {
    try {
        const item = JSON.parse(localStorage.getItem(activePlaybackKey(user)) || "null");
        return normalizeRecentItem(item, 0);
    } catch {
        return null;
    }
}

function restoreActivePlayback() {
    if (!state.token || state.playerOpening || state.activePlayerItem) return;
    const item = loadActivePlayback(state.user);
    if (!item) return;
    window.setTimeout(() => {
        if (!state.token || state.playerOpening || state.activePlayerItem) return;
        if (String(item.id || "").startsWith("drama-") && item.dramaBookId && item.chapterId) {
            state.activeDramaBook = {
                id: item.dramaBookId,
                title: item.dramaBookTitle || item.name || "Drama",
                slug: item.dramaBookSlug || "",
                image: item.image || item.poster || "/assets/images/poster-2.jpg"
            };
            playDramaEpisode(Number(item.episode || 1), item.chapterId);
            return;
        }
        playItem(item, { restored: true });
    }, 250);
}

function catalogCardKey(type, id) {
    return `${type}:${id}`;
}

function isRemovableCatalogItem(item) {
    return ["movie", "series"].includes(item?.type);
}

function isCatalogCardHidden(item) {
    return isRemovableCatalogItem(item)
        && state.hiddenCatalogCards.has(catalogCardKey(item.type, item.id));
}

function hideCatalogCard(type, id) {
    if (!["movie", "series"].includes(type) || !id) return;
    const key = catalogCardKey(type, id);
    state.hiddenCatalogCards.add(key);
    state.recentlyWatched = state.recentlyWatched.filter((item) => catalogCardKey(item.type, item.id) !== key);
    saveRecentlyWatched();
    renderCatalog();
    renderSearchSuggestions();
    renderHeroCarousel(filteredCatalog());
    showToast("Carte masquée temporairement.");
}

function dismissContinueCard(type, id) {
    if (!type || !id) return;
    const key = catalogCardKey(type, id);
    state.hiddenContinueCards.add(key);
    state.recentlyWatched = state.recentlyWatched.filter((item) => catalogCardKey(item.type, item.id) !== key);
    saveRecentlyWatched();
    renderCatalog();
    showToast("Retire de Continuer la lecture.");
}

function clearHiddenCatalogCards() {
    state.hiddenCatalogCards.clear();
}

function recentItemPayload(item) {
    return {
        id: String(item.id),
        type: item.type,
        name: item.name,
        isEpisode: Boolean(item.isEpisode),
        tmdbId: item.tmdbId || undefined,
        season: item.season || undefined,
        episode: item.episode || undefined,
        chapterId: item.chapterId || undefined,
        dramaBookId: item.dramaBookId || undefined,
        dramaBookSlug: item.dramaBookSlug || undefined,
        dramaBookTitle: item.dramaBookTitle || undefined,
        categoryId: item.categoryId || "",
        categoryName: item.categoryName || typeLabel(item.type),
        image: item.image || item.poster || item.logo || "",
        poster: item.poster || item.image || "",
        backdrop: item.backdrop || "",
        releaseYear: item.releaseYear || item.year || "",
        year: item.year || item.releaseYear || "",
        source: item.source || "",
        sourceCode: item.sourceCode || "",
        provider: item.provider || "",
        playbackProvider: item.playbackProvider || "",
        playbackProviderName: item.playbackProviderName || "",
        externalPlayback: Boolean(item.externalPlayback),
        streamAvailable: item.streamAvailable,
        streamUnavailableReason: item.streamUnavailableReason || "",
        language: item.language || "",
        languageName: item.languageName || "",
        watchedAt: Date.now()
    };
}

function trackRecentlyWatched(item) {
    if (!item?.id || !item?.type || !item?.name) return;
    const payload = recentItemPayload(item);
    state.recentlyWatched = [
        payload,
        ...state.recentlyWatched.filter((entry) => (
            `${entry.type}:${entry.id}` !== `${payload.type}:${payload.id}`
        ))
    ].slice(0, RECENTLY_WATCHED_LIMIT);
    saveRecentlyWatched();
    if (!state.query.trim()) renderCatalog();
}

function avatarColor(tone) {
    return {
        gold: "linear-gradient(135deg, #f7d77f, #c18b2f)",
        red: "linear-gradient(135deg, #ff7777, #b31217)",
        green: "linear-gradient(135deg, #59d68f, #0a6f4b)",
        blue: "linear-gradient(135deg, #6eb6ff, #145c99)",
        violet: "linear-gradient(135deg, #b987ff, #4b1d8f)",
        mono: "linear-gradient(135deg, #f4f4f4, #171717)"
    }[tone] || "linear-gradient(135deg, #f7d77f, #c18b2f)";
}

function applyProfileSettings() {
    const tone = state.profileSettings.avatarTone;
    const color = avatarColor(tone);
    [elements.accountAvatar, elements.profileAvatar].forEach((avatar) => {
        if (!avatar) return;
        avatar.style.background = color;
        avatar.dataset.avatarTone = tone;
    });
    document.body.classList.toggle("compact-catalog", Boolean(state.profileSettings.compactCards));
}

function fillSettingsForms() {
    if (!state.user) return;
    elements.settingsName.value = state.user.name || "";
    elements.settingsEmail.value = state.user.email || "";
    elements.settingsLanguage.value = state.profileSettings.language;
    elements.settingsQuality.value = state.profileSettings.quality;
    elements.settingsSubtitles.value = state.profileSettings.subtitles;
    elements.settingsAutoplay.checked = Boolean(state.profileSettings.autoplay);
    elements.settingsCompactCards.checked = Boolean(state.profileSettings.compactCards);
    elements.settingsTwoFactor.checked = Boolean(state.user.twoFactorEnabled);

    const tone = state.profileSettings.avatarTone;
    const toneInput = elements.profileSettingsForm.querySelector(`[name="avatarTone"][value="${tone}"]`);
    if (toneInput) toneInput.checked = true;
}

function formatAccountDate(value) {
    if (!value) return "—";
    return new Intl.DateTimeFormat("fr", { dateStyle: "medium" }).format(new Date(value));
}

function renderBillingSettings() {
    const plan = state.subscription?.plan;
    const iptv = state.iptv || {};
    elements.settingsPlan.textContent = plan?.name || "Aucune formule";
    elements.settingsStatus.textContent = statusLabel(state.subscription?.status);
    elements.settingsPeriodEnd.textContent = formatAccountDate(state.subscription?.currentPeriodEnd);
    elements.settingsStreams.textContent = iptv.active
        ? `IPTV actif - ${iptv.accountName || `${iptv.assignedCount || 1} compte`}`
        : plan?.maxConcurrentStreams
        ? `${plan.maxConcurrentStreams} simultané${plan.maxConcurrentStreams > 1 ? "s" : ""} par utilisateur`
        : "IPTV non assigne";
}

function normalizeItem(item, type, index) {
    const fallback = fallbackCatalog.filter((entry) => entry.type === type);
    const matched = fallback.find((entry) => entry.id === String(item.id)) || fallback[index % fallback.length];
    const itemType = ["live", "movie", "series"].includes(item.type) ? item.type : type;
    const sourceCode = String(item.sourceCode || "").toLowerCase();
    const playbackProvider = String(item.playbackProvider || "").toLowerCase();
    const provider = String(item.provider || item.source || "").toLowerCase();
    const isNodeSource = sourceCode === "node-fr"
        || sourceCode === "orion"
        || playbackProvider.startsWith("node-fr")
        || playbackProvider.startsWith("orion")
        || provider.includes("orion")
        || provider.includes("source fr");
    const fallbackImage = matched?.image || "/assets/images/landscape-1.jpg";
    const normalizeSource = isNodeSource ? normalizeNodeImageSource : normalizeImageSource;
    const rawPoster = item.poster || item.image || item.cover || item.thumbnail || item.logo;
    const rawBackdrop = item.backdrop || item.background || item.banner || item.cover || item.image || rawPoster;
    const image = normalizeSource(rawPoster, fallbackImage);
    const backdrop = normalizeSource(rawBackdrop, image);

    return {
        ...matched,
        ...item,
        id: String(item.id),
        type: itemType,
        categoryName: item.categoryName || matched?.categoryName || typeLabel(itemType),
        image,
        poster: image,
        backdrop
    };
}

function mergeCatalogCategories(items) {
    const known = new Set(state.categories.map((category) => String(category.id)));
    const generated = [];
    items.forEach((item) => {
        if (!["movie", "series", "live"].includes(item.type)) return;
        const id = String(item.categoryId || "");
        if (!id || known.has(id)) return;
        known.add(id);
        generated.push({
            id,
            name: item.categoryName || typeLabel(item.type),
            type: item.type,
            source: item.source || item.provider || "",
            sourceCode: item.sourceCode || "",
            playbackProvider: item.playbackProvider || "",
            streamAvailable: item.streamAvailable
        });
    });
    if (generated.length) {
        state.categories = [...state.categories, ...generated];
    }
}

function defaultCatalogLimit(type) {
    return DEFAULT_VISIBLE_CATALOG[type] || 160;
}

function searchCatalogLimit(type) {
    return SEARCH_VISIBLE_CATALOG[type] || 240;
}

async function loadCatalog() {
    if (state.activeType === "drama") {
        state.catalogLoading = false;
        updateCatalogStatus();
        elements.dramaSection.hidden = false;
        elements.catalogRows.hidden = true;
        elements.emptyState.hidden = true;
        return;
    }

    const requestId = ++state.catalogRequestId;
    if (state.catalogAbortController) {
        state.catalogAbortController.abort();
        state.catalogAbortController = null;
    }

    if (!state.token) {
        state.catalogLoading = false;
        state.catalogIsFallback = true;
        state.categories = [];
        state.languages = [];
        state.browseCatalog = [...fallbackCatalog];
        state.catalog = [...fallbackCatalog];
        state.searchResults = [];
        state.searchResultQuery = "";
        updateCatalogStatus();
        renderCategories();
        renderAddonFilter();
        renderLanguageFilter();
        renderCatalog();
        renderSearchSuggestions();
        return;
    }

    const query = state.query.trim();
    const abortController = new AbortController();
    state.catalogAbortController = abortController;
    state.catalogLoading = true;
    updateCatalogStatus();
    renderCatalog();
    renderSearchSuggestions();

    if (!query) {
        try {
            const [categories, languages] = await Promise.all([
                api("/v1/catalog/categories", { signal: abortController.signal }),
                api("/catalog/languages", { signal: abortController.signal })
            ]);
            if (requestId !== state.catalogRequestId) return;
            state.categories = categories || [];
            state.languages = languages || [];
            renderCategories();
            renderAddonFilter();
            renderLanguageFilter();
        } catch {
            if (requestId !== state.catalogRequestId) return;
            state.categories = [];
            state.languages = [];
            renderAddonFilter();
            renderLanguageFilter();
        }
    }

    try {
        if (!query && state.activeType === "drama") {
            await loadDramaHome();
            return;
        }
        const types = query || state.activeType === "all"
            ? ["live", "movie", "series"]
            : [state.activeType];
        const resultSets = await Promise.all(
            types.map(async (type) => {
                const movieLibrary = !query && state.activeType === "movie" && type === "movie";
                const requestedLimit = query
                    ? searchCatalogLimit(type)
                    : Math.max(
                        state.visibleCatalog[type] || defaultCatalogLimit(type),
                        movieLibrary ? defaultCatalogLimit("movie") : defaultCatalogLimit(type)
                    );
                const params = new URLSearchParams({
                    type,
                    limit: String(requestedLimit)
                });
                if (query) params.set("q", query);
                if (!query && state.activeCategory) params.set("categoryId", state.activeCategory);
                if (!query && state.activeAddonFilter) params.set("addonFilter", state.activeAddonFilter);
                params.set("addonPages", query ? "3" : String(state.addonPages));
                if (!query && state.activeLanguage) params.set("language", state.activeLanguage);
                if (movieLibrary) params.set("sort", state.movieSort);
                const springItemsPromise = api(`/catalog/items?${params}`, { signal: abortController.signal })
                    .catch(() => []);
                const nodeItemsPromise = nodeApiEnabled() && ["movie", "series"].includes(type)
                    ? nodeCatalogApi(`/catalog/items?${params}`, { signal: abortController.signal })
                        .catch(() => [])
                    : Promise.resolve([]);
                const [springItems, nodeItems] = await Promise.all([springItemsPromise, nodeItemsPromise]);
                return ["movie", "series"].includes(type)
                    ? [...(nodeItems || []), ...(springItems || [])]
                    : [...(springItems || []), ...(nodeItems || [])];
            })
        );
        if (requestId !== state.catalogRequestId) return;

        const seenCatalogItems = new Map();
        const mergedItems = resultSets.flatMap((items, typeIndex) => {
            const type = types[typeIndex];
            return (items || []).map((item, index) => normalizeItem(item, type, index));
        }).filter((item) => types.includes(item.type));
        const apiItems = [];
        mergedItems.forEach((item) => {
            if (!types.includes(item.type)) return false;
            const sourceKey = item.sourceCode || item.playbackProvider || item.provider || item.source || "catalog";
            const dedupeKey = `${item.type}:${sourceKey}:${item.id}`;
            const existingIndex = seenCatalogItems.get(dedupeKey);
            if (existingIndex === undefined) {
                seenCatalogItems.set(dedupeKey, apiItems.length);
                apiItems.push(item);
                return true;
            }
            if (isFrenchSource(item) && !isFrenchSource(apiItems[existingIndex])) {
                apiItems[existingIndex] = item;
            }
            return true;
        });

        state.catalog = apiItems;
        if (!query) {
            mergeCatalogCategories(apiItems);
            renderCategories();
            renderAddonFilter();
        }
        const activeCategory = state.categories.find((category) => category.id === state.activeCategory);
        const activeAddonItems = apiItems.filter((item) => item.categoryId === state.activeCategory);
        state.addonHasMore = Boolean(
            !query
            && isAddonSource(activeCategory)
            && activeAddonItems.length >= state.addonPages * 24
        );
        state.catalogIsFallback = false;
        if (query) {
            state.searchResults = apiItems;
            state.searchResultQuery = normalizeSearchText(query);
        } else {
            state.browseCatalog = apiItems;
            state.searchResults = [];
            state.searchResultQuery = "";
        }
        renderCatalog();
        renderSearchSuggestions();
    } catch (error) {
        if (requestId !== state.catalogRequestId) return;
        if (error?.name === "AbortError") return;
        if (error.status === 401) {
            clearSession();
        }
        state.catalogIsFallback = true;
        state.browseCatalog = [...fallbackCatalog];
        state.catalog = [...fallbackCatalog];
        state.searchResults = [];
        state.searchResultQuery = "";
        renderCatalog();
        renderSearchSuggestions();
        showToast("Le catalogue de démonstration reste disponible.", true);
    } finally {
        if (requestId !== state.catalogRequestId) return;
        if (state.catalogAbortController === abortController) {
            state.catalogAbortController = null;
        }
        state.catalogLoading = false;
        updateCatalogStatus();
        renderCatalog();
        renderSearchSuggestions();
    }
}

function renderCategories() {
    const categories = state.categories.length
        ? state.categories
        : [
            { id: "news", name: "Actualités", type: "live" },
            { id: "sports", name: "Sports", type: "live" },
            { id: "movies-action", name: "Action", type: "movie" },
            { id: "series-drama", name: "Drame", type: "series" }
        ];

    const visible = categories.filter((category) => (
        !category.searchOnly
        && (state.activeType === "all" || category.type === state.activeType)
    ));

    elements.genreList.innerHTML = [
        `<button class="genre-chip ${state.activeCategory ? "" : "active"}" type="button" data-category="">Tout</button>`,
        ...visible.map((category) => (
            `<button class="genre-chip ${state.activeCategory === category.id ? "active" : ""} ${sourceClass(category)}" type="button" data-category="${escapeHtml(category.id)}">
                ${sourceBadge(category, "inline")}
                <span>${escapeHtml(category.name)}</span>
            </button>`
        ))
    ].join("");
}

function renderAddonFilter() {
    const category = state.categories.find((item) => item.id === state.activeCategory);
    const options = Array.isArray(category?.filterOptions) ? category.filterOptions : [];
    const visible = isAddonSource(category) && options.length > 0;
    elements.addonFilterControl.hidden = !visible;
    if (!visible) {
        elements.addonFilterSelect.innerHTML = "";
        return;
    }
    if (!options.includes(state.activeAddonFilter)) {
        state.activeAddonFilter = options[0] || "";
    }
    elements.addonFilterLabel.textContent = {
        year: "Année",
        studio: "Studio",
        performer: "Interprète",
        tag: "Tag",
        quality: "Qualité"
    }[category.filterName] || "Filtre add-on";
    elements.addonFilterSelect.innerHTML = options.map((option) => (
        `<option value="${escapeHtml(option)}">${escapeHtml(option)}</option>`
    )).join("");
    elements.addonFilterSelect.value = state.activeAddonFilter;
    elements.addonFilterControl.classList.toggle("active", Boolean(state.activeAddonFilter));
}

function renderLanguageFilter() {
    const visible = state.languages.filter((language) => (
        state.activeType === "all"
        || !Array.isArray(language.types)
        || language.types.includes(state.activeType)
    ));
    if (state.activeLanguage && !visible.some((language) => language.id === state.activeLanguage)) {
        state.activeLanguage = "";
    }
    elements.languageSelect.innerHTML = [
        `<option value="">Toutes les langues</option>`,
        ...visible.map((language) => (
            `<option value="${escapeHtml(language.id)}">${escapeHtml(language.name)}</option>`
        ))
    ].join("");
    elements.languageSelect.value = state.activeLanguage;
    elements.languageFilter.classList.toggle("active", Boolean(state.activeLanguage));
}

function filteredCatalog() {
    const query = normalizeSearchText(state.query);
    const searching = Boolean(query);
    const usingRemoteSearchResults = searching && state.searchResultQuery === query;

    const filtered = state.catalog.filter((item) => {
        if (isCatalogCardHidden(item)) return false;
        const matchesType = searching || state.activeType === "all" || item.type === state.activeType;
        const matchesCategory = searching || !state.activeCategory || item.categoryId === state.activeCategory;
        const matchesLanguage = searching || !state.activeLanguage || item.language === state.activeLanguage;
        const matchesQuery = !query || usingRemoteSearchResults || normalizeSearchText(
            `${item.name} ${item.categoryName} ${item.languageName || ""} ${typeLabel(item.type)}`
        ).includes(query);
        return matchesType && matchesCategory && matchesLanguage && matchesQuery;
    });
    return state.activeType === "movie" && !searching
        ? [...filtered].sort(movieComparator(state.movieSort))
        : filtered;
}

function renderCatalog() {
    syncCatalogMode();
    if (state.activeType === "drama") {
        elements.catalogRows.hidden = true;
        elements.emptyState.hidden = true;
        return;
    }
    elements.catalogRows.hidden = false;
    const items = filteredCatalog();
    const searching = Boolean(state.query.trim());
    const loading = state.catalogLoading;
    renderMovieSort(searching);
    const definitions = rowDefinitions.filter((row) => (
        searching || state.activeType === "all" || row.type === state.activeType
    ));

    const rows = definitions.map((row) => {
        const rowItems = items.filter((item) => item.type === row.type);
        if (!rowItems.length) {
            return "";
        }
        const rowDefinition = searching
            ? {
                ...row,
                title: row.type === "movie"
                    ? `Films pour « ${state.query.trim()} »`
                    : row.type === "series"
                        ? `Series pour « ${state.query.trim()} »`
                        : `Direct pour « ${state.query.trim()} »`,
                label: `${rowItems.length} resultat${rowItems.length > 1 ? "s" : ""} ${typeLabel(row.type).toLowerCase()}`
            }
            : row;

        const homePreview = !searching && state.activeType === "all";
        const visibleLimit = homePreview
            ? HOME_PREVIEW_LIMIT
            : searching
                ? Math.max(searchCatalogLimit(row.type), state.visibleCatalog[row.type])
                : state.visibleCatalog[row.type];
        const visibleItems = (homePreview ? balancedHomeItems(rowItems, visibleLimit, ["anime", row.type]) : rowItems.slice(0, visibleLimit));
        const shelves = buildCatalogShelves(rowDefinition, rowItems, visibleItems, searching)
            .map((shelf) => ({ ...shelf, homePreview }));
        const remoteMore = state.addonHasMore
            && row.type === state.activeType
            && Boolean(state.activeCategory);
        const likelyRemoteMore = !searching
            && state.activeType === row.type
            && visibleItems.length >= visibleLimit;
        let loadMoreLabel = `Afficher plus · ${Math.max(0, rowItems.length - visibleItems.length)} restants`;
        if (likelyRemoteMore && visibleItems.length >= rowItems.length) {
            loadMoreLabel = "Afficher plus";
        }
        let loadMore = visibleItems.length < rowItems.length || remoteMore || likelyRemoteMore
            ? `<button class="button button-outline catalog-more" type="button" data-load-more="${row.type}">
                    ${loadMoreLabel}
               </button>`
            : "";
        if (homePreview) {
            loadMore = "";
        }
        if (remoteMore) {
            loadMore = loadMore.replace(/Afficher plus[^<]+/, "Charger la suite de l'add-on");
        }
        return `${shelves.map(renderCatalogShelf).join("")}${loadMore}`;
    }).join("");

    const homeDashboard = !searching && state.activeType === "all"
        ? renderHomeDashboard(items)
        : "";
    const browseDashboard = !searching && ["movie", "series"].includes(state.activeType)
        ? renderBrowseDashboard(items)
        : "";
    const koreanDrama = renderKoreanDramaRail(items, searching);
    const recentlyWatched = state.activeType === "all" ? "" : renderRecentlyWatchedSection(items, searching);
    const loadingBanner = loading && rows ? renderCatalogLoadingBanner(searching) : "";
    elements.catalogRows.innerHTML = homeDashboard + browseDashboard + recentlyWatched + koreanDrama + loadingBanner + rows;
    elements.emptyState.hidden = Boolean(homeDashboard || browseDashboard || recentlyWatched || koreanDrama || rows);
    updateCatalogHeading(items.length, loading);
    renderHeroCarousel(items);
    renderHomeForYou(items);
}

function syncCatalogMode() {
    document.body.classList.toggle("catalog-mode-home", state.activeType === "all");
    document.body.classList.toggle("catalog-mode-movie", state.activeType === "movie");
    document.body.classList.toggle("catalog-mode-series", state.activeType === "series");
    document.body.classList.toggle("catalog-mode-live", state.activeType === "live");
    document.body.classList.toggle("catalog-mode-drama", state.activeType === "drama");
    document.body.classList.toggle("catalog-mode-browse", ["movie", "series"].includes(state.activeType));
}

function renderKoreanDramaRail(items, searching) {
    if (searching || !["all", "series"].includes(state.activeType)) {
        return "";
    }
    const dramas = uniqueCatalogItems(items)
        .filter((item) => item.type === "series" && item.categoryId === KOREAN_DRAMA_CATEGORY_ID)
        .slice(0, 50);
    if (!dramas.length) {
        return "";
    }
    return renderCatalogShelf({
        type: "series",
        title: "Drama coreens",
        label: "Series TMDB jouables avec le lecteur Videasy",
        items: dramas,
        posterLayout: true
    }, "kdrama");
}

function normalizeDramaBook(book) {
    return {
        id: String(book.book_id || book.id || ""),
        title: book.book_title || book.title || "Drama",
        slug: book.filtered_title || "",
        image: normalizeImageSource(book.book_pic || book.poster, "/assets/images/poster-2.jpg"),
        episodeCount: Number(book.chapter_count || 0),
        summary: book.special_desc || ""
    };
}

function setDramaStatus(message, isError = false) {
    if (!elements.dramaStatus) return;
    elements.dramaStatus.textContent = message || "";
    elements.dramaStatus.classList.toggle("error", Boolean(isError));
}

async function loadDramaHome() {
    if (!state.token && state.authRequired) {
        requestLogin("Connectez-vous pour ouvrir les dramas.");
        return;
    }
    elements.dramaSection.hidden = false;
    elements.catalogRows.hidden = true;
    elements.emptyState.hidden = true;
    renderCategories();
    renderAddonFilter();
    renderLanguageFilter();
    if (state.dramaLoaded) return;
    setDramaStatus("Chargement des rayons dramas...");
    try {
        const payload = await api(`/dramas/bookshelves?lang=${encodeURIComponent(state.dramaLang)}`);
        state.dramaShelves = payload.bookshelves || [];
        state.dramaShelfVisible = {};
        state.dramaLoaded = true;
        const first = state.dramaShelves.find((shelf) => shelf.books?.length);
        state.dramaMode = "shelf";
        state.dramaCurrentShelfIndex = first ? state.dramaShelves.indexOf(first) : null;
        renderDramaShelves();
        renderDramaShelfRows();
        setDramaStatus(`${state.dramaShelves.length} rayons disponibles`);
        hydrateDramaShelvesToMinimum().catch((error) => {
            setDramaStatus(error.message || "Certains rayons dramas n'ont pas pu etre completes.", true);
        });
    } catch (error) {
        setDramaStatus(error.message || "Impossible de charger les dramas.", true);
    }
}

function renderDramaShelves() {
    if (!elements.dramaShelves) return;
    const buttons = [
        `<button class="drama-chip ${state.dramaMode === "search" ? "active" : ""}" type="button" data-drama-shelf="search">Recherche</button>`,
        ...state.dramaShelves.map((shelf, index) => (
            `<button class="drama-chip ${state.dramaCurrentShelfIndex === index ? "active" : ""}" type="button" data-drama-shelf="${index}">${escapeHtml(shelf.bookshelf_name || "Rayon")}</button>`
        ))
    ];
    elements.dramaShelves.innerHTML = buttons.join("");
}

function renderDramaShelfRows() {
    if (!elements.dramaShelfRows) return;
    state.dramaMode = "shelf";
    elements.dramaShelfRows.hidden = false;
    elements.dramaGrid.hidden = true;
    elements.dramaLoadMore.hidden = true;
    elements.dramaEpisodes.hidden = true;
    elements.dramaEpisodeList.innerHTML = "";
    state.activeDramaBook = null;
    state.activeDramaEpisodes = [];

    const allBooks = [];
    const rows = state.dramaShelves.map((shelf, index) => {
        const books = (shelf.books || []).map(normalizeDramaBook).filter((book) => book.id && book.slug);
        allBooks.push(...books);
        const visibleLimit = state.dramaShelfVisible[index] || DRAMA_VISIBLE_INCREMENT;
        const visibleBooks = books.slice(0, visibleLimit);
        const paging = state.dramaShelfPaging[index] || {};
        const hasMore = visibleBooks.length < books.length || paging.hasMore !== false;
        return `
            <section class="drama-row" id="dramaShelfRow-${index}" data-drama-row="${index}">
                <div class="drama-row-heading">
                    <div>
                        <h3>${escapeHtml(shelf.bookshelf_name || "Rayon")}</h3>
                        <span>${visibleBooks.length}/${books.length} titres</span>
                    </div>
                    <button class="button button-outline drama-row-more" type="button" data-drama-row-more="${index}" ${hasMore ? "" : "hidden"}>
                        ${visibleBooks.length < books.length ? "Voir plus" : "Charger plus"}
                    </button>
                </div>
                <div class="drama-grid drama-row-grid">
                    ${visibleBooks.map((book) => dramaCardTemplate(book)).join("")}
                </div>
            </section>
        `;
    }).join("");

    state.dramaBooks = uniqueDramaBooks(allBooks);
    elements.dramaShelfRows.innerHTML = rows || `
        <div class="empty-state-inline">
            <strong>Aucun drama disponible</strong>
            <span>L'API Python n'a renvoye aucun rayon pour cette langue.</span>
        </div>
    `;
}

async function hydrateDramaShelvesToMinimum() {
    if (state.dramaHydratingShelves || !state.dramaShelves.length) return;
    state.dramaHydratingShelves = true;
    setDramaStatus("Completion des catalogues dramas jusqu'a 50 items...");
    try {
        for (let index = 0; index < state.dramaShelves.length; index += 1) {
            const shelf = state.dramaShelves[index];
            if (!shelf?.books || shelf.books.length >= DRAMA_VISIBLE_INCREMENT) continue;
            await loadMoreDramaShelf(index, DRAMA_VISIBLE_INCREMENT - shelf.books.length);
            renderDramaShelfRows();
        }
        setDramaStatus("Catalogues dramas prets");
    } finally {
        state.dramaHydratingShelves = false;
    }
}

async function loadMoreDramaShelf(index, targetCount = DRAMA_VISIBLE_INCREMENT) {
    const shelf = state.dramaShelves[index];
    if (!shelf) return;
    const paging = state.dramaShelfPaging[index] || { page: 1, hasMore: true };
    if (paging.hasMore === false) return;
    const query = dramaShelfSearchQuery(shelf.bookshelf_name || "");
    const batch = await fetchDramaSearchBatchForQuery(query, paging.page, targetCount);
    const existing = shelf.books || [];
    shelf.books = mergeDramaBookSources(existing, batch.books);
    state.dramaShelfPaging[index] = {
        page: batch.lastPage + 1,
        hasMore: batch.hasMore
    };
}

function mergeDramaBookSources(existing, incoming) {
    const seen = new Set();
    const merged = [];
    [...(existing || []), ...(incoming || [])].forEach((book) => {
        const id = String(book.book_id || book.id || "");
        const slug = book.filtered_title || book.slug || "";
        const key = `${id}:${slug}`;
        if (!id || !slug || seen.has(key)) return;
        seen.add(key);
        merged.push(book);
    });
    return merged;
}

function dramaShelfSearchQuery(name) {
    const normalized = String(name || "").toLowerCase();
    if (normalized.includes("asie") || normalized.includes("asia") || normalized.includes("korea") || normalized.includes("core")) return "asian love";
    if (normalized.includes("doubl") || normalized.includes("dub")) return "dubbed love";
    if (normalized.includes("identit") || normalized.includes("hidden")) return "secret identity";
    if (normalized.includes("tabou") || normalized.includes("forbidden")) return "forbidden love";
    if (normalized.includes("loup") || normalized.includes("werewolf")) return "alpha";
    if (normalized.includes("nouvelle") || normalized.includes("new")) return "new love";
    if (normalized.includes("classement") || normalized.includes("ranking")) return "top love";
    return "love";
}

function uniqueDramaBooks(books) {
    const seen = new Set();
    return books.filter((book) => {
        const key = `${book.id}:${book.slug}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
}

function dramaCardTemplate(book) {
    return `
        <button class="drama-card" type="button" data-drama-book="${escapeHtml(book.id)}">
            <img src="${escapeHtml(book.image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/poster-2.jpg')">
            <span>
                <strong>${escapeHtml(book.title)}</strong>
                <small>${book.episodeCount ? `${book.episodeCount} episodes` : "Episodes disponibles"}</small>
            </span>
        </button>
    `;
}

function renderDramaBooks(books, title = "Dramas", preserveVisibleLimit = false) {
    if (elements.dramaShelfRows) {
        elements.dramaShelfRows.hidden = true;
    }
    elements.dramaGrid.hidden = false;
    if (!preserveVisibleLimit) {
        state.dramaVisibleLimit = DRAMA_VISIBLE_INCREMENT;
    }
    state.dramaCurrentTitle = title;
    state.dramaBooks = (books || []).map(normalizeDramaBook).filter((book) => book.id && book.slug);
    const visibleBooks = state.dramaBooks.slice(0, state.dramaVisibleLimit);
    state.activeDramaBook = null;
    state.activeDramaEpisodes = [];
    elements.dramaEpisodes.hidden = true;
    elements.dramaEpisodeList.innerHTML = "";
    elements.dramaEpisodeTitle.textContent = title;
    elements.dramaEpisodeCount.textContent = `${visibleBooks.length}/${state.dramaBooks.length} titres`;
    elements.dramaGrid.innerHTML = visibleBooks.map(dramaCardTemplate).join("");
    elements.dramaLoadMore.hidden = visibleBooks.length >= state.dramaBooks.length && !state.dramaSearchHasMore;
    elements.dramaLoadMore.textContent = state.dramaSearchHasMore || visibleBooks.length < state.dramaBooks.length
        ? "Voir plus"
        : "Tout est affiche";
}

async function searchDramas(page = 1, append = false) {
    state.dramaMode = "search";
    state.dramaCurrentShelfIndex = null;
    state.dramaSearchQuery = elements.dramaSearchInput.value.trim() || "love";
    const startPage = append ? state.dramaSearchPage + 1 : page;
    setDramaStatus(append ? "Chargement de 50 dramas supplementaires..." : "Recherche dramas...");
    const batch = await fetchDramaSearchBatch(startPage, DRAMA_VISIBLE_INCREMENT);
    state.dramaSearchPage = batch.lastPage;
    state.dramaSearchHasMore = batch.hasMore;
    if (append) {
        state.dramaVisibleLimit += DRAMA_VISIBLE_INCREMENT;
        renderDramaBooks([...state.dramaBooks.map((book) => ({
            book_id: book.id,
            book_title: book.title,
            filtered_title: book.slug,
            book_pic: book.image,
            chapter_count: book.episodeCount,
            special_desc: book.summary
        })), ...batch.books], `Recherche: ${state.dramaSearchQuery}`, true);
    } else {
        state.dramaVisibleLimit = DRAMA_VISIBLE_INCREMENT;
        renderDramaBooks(batch.books, `Recherche: ${state.dramaSearchQuery}`, true);
    }
    renderDramaShelves();
    setDramaStatus(`${Math.min(state.dramaVisibleLimit, state.dramaBooks.length)} / ${state.dramaBooks.length} dramas affiches`);
}

async function fetchDramaSearchBatch(startPage, targetCount) {
    return fetchDramaSearchBatchForQuery(state.dramaSearchQuery, startPage, targetCount);
}

async function fetchDramaSearchBatchForQuery(query, startPage, targetCount) {
    const books = [];
    let page = Math.max(1, startPage);
    let hasMore = false;
    while (books.length < targetCount) {
        const payload = await api(
            `/dramas/search?q=${encodeURIComponent(query)}&page=${page}&lang=${encodeURIComponent(state.dramaLang)}`
        );
        const results = payload.results || [];
        books.push(...results);
        hasMore = results.length >= DRAMA_SEARCH_PAGE_SIZE;
        if (!hasMore) break;
        page += 1;
    }
    return {
        books,
        hasMore,
        lastPage: hasMore ? page - 1 : page
    };
}

async function selectDramaBook(bookId, options = {}) {
    const book = state.dramaBooks.find((item) => item.id === bookId);
    if (!book) return [];
    state.activeDramaBook = book;
    setDramaStatus(`Chargement des episodes de ${book.title}...`);
    const payload = await api(
        `/dramas/episodes/${encodeURIComponent(book.id)}?filtered_title=${encodeURIComponent(book.slug)}&lang=${encodeURIComponent(state.dramaLang)}`
    );
    state.activeDramaEpisodes = payload.episodes || [];
    elements.dramaEpisodes.hidden = false;
    elements.dramaEpisodeTitle.textContent = book.title;
    elements.dramaEpisodeCount.textContent = `${state.activeDramaEpisodes.length} episodes`;
    elements.dramaEpisodeList.innerHTML = state.activeDramaEpisodes.map((episode) => `
        <button class="drama-episode" type="button" data-drama-episode="${episode.episode}" data-drama-chapter="${escapeHtml(episode.chapter_id || "")}">
            EP ${episode.episode}
        </button>
    `).join("");
    setDramaStatus(state.activeDramaEpisodes.length ? "Episodes prets" : "Aucun episode recu", !state.activeDramaEpisodes.length);
    if (options.scroll !== false) {
        elements.dramaEpisodes.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
    return state.activeDramaEpisodes;
}

async function playDramaBook(bookId) {
    const episodes = await selectDramaBook(bookId, { scroll: false });
    const firstEpisode = episodes.find((episode) => episode.chapter_id) || episodes[0];
    if (!firstEpisode) {
        setDramaStatus("Aucun episode disponible pour ce drama.", true);
        return;
    }
    await playDramaEpisode(Number(firstEpisode.episode || 1), firstEpisode.chapter_id || "");
}

async function playDramaEpisode(episodeNumber, chapterId) {
    const book = state.activeDramaBook;
    if (!book || !chapterId) return;
    setDramaStatus(`Preparation episode ${episodeNumber}...`);
    const payload = await api(
        `/dramas/video/${encodeURIComponent(book.id)}/${episodeNumber}?filtered_title=${encodeURIComponent(book.slug)}&chapter_id=${encodeURIComponent(chapterId)}&lang=${encodeURIComponent(state.dramaLang)}`
    );
    const url = payload.proxy_video_url || payload.video_url;
    if (!url) {
        setDramaStatus("Aucune URL video pour cet episode", true);
        return;
    }
    await playDramaStream({
        id: `drama-${book.id}-${episodeNumber}`,
        type: "series",
        episode: Number(episodeNumber),
        chapterId: chapterId,
        dramaBookId: book.id,
        dramaBookSlug: book.slug,
        dramaBookTitle: book.title,
        name: `${book.title} · EP ${episodeNumber}`,
        image: book.image,
        categoryName: "Drama ReelShort"
    }, url);
}

async function playDramaStream(item, videoUrl) {
    if (!state.token) {
        requestLogin("Connectez-vous pour lancer un drama.", () => playDramaStream(item, videoUrl));
        return;
    }
    if (state.playerOpening) return;
    trackRecentlyWatched(item);
    saveActivePlayback(item);
    state.playerOpening = true;
    setPlayerControlsBusy(true);
    state.playerErrorShown = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    state.playerRecoveryShouldFailover = false;
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    state.activePlayerItem = item;
    elements.playerQuality.value = "auto";
    await ensurePlayerContext(item);
    openModal("playerModal");
    elements.playerTitle.textContent = item.name;
    elements.playerBadge.textContent = "DRAMA";
    refreshPlayerRoom(item, "DRAMA");
    setPlayerLoading("Preparation du drama...", "Connexion au flux ReelShort.");
    try {
        if (state.activeSessionToken) {
            await stopPlayer();
        }
        detachPlayerMedia();
        await startStreamPlayback(item, videoUrl, "hls", {
            preferredAudioLanguage: state.dramaLang,
            preferredSubtitleLanguage: state.dramaLang,
            enableSubtitles: true
        });
    } catch (error) {
        detachPlayerMedia();
        showPlayerError(error.message || "Impossible de lancer ce drama.");
    } finally {
        state.playerOpening = false;
        setPlayerControlsBusy(false);
    }
}

async function ensurePlayerContext(item) {
    await Promise.allSettled([
        ensurePlayerDramaContext(item),
        ensurePlayerSeriesContext(item)
    ]);
}

async function ensurePlayerDramaContext(item) {
    if (!item?.id?.startsWith?.("drama-")) return;
    if (state.activeDramaBook && state.activeDramaEpisodes.length) return;
    const bookId = item.dramaBookId;
    const slug = item.dramaBookSlug;
    if (!bookId || !slug) return;
    state.activeDramaBook = {
        id: String(bookId),
        title: item.dramaBookTitle || seriesTitleKey(item.name || "") || "Drama",
        slug,
        image: normalizeImageSource(item.image, "/assets/images/poster-2.jpg"),
        episodeCount: 0,
        summary: ""
    };
    const payload = await api(
        `/dramas/episodes/${encodeURIComponent(bookId)}?filtered_title=${encodeURIComponent(slug)}&lang=${encodeURIComponent(state.dramaLang)}`
    );
    state.activeDramaEpisodes = payload.episodes || [];
}

async function ensurePlayerSeriesContext(item) {
    if (item?.type !== "series" || !item?.isEpisode) return;
    if (state.activeSeries?.seasons?.length && seriesContainsEpisode(state.activeSeries, item.id)) return;

    const tmdbId = tmdbIdFromItem(item);
    const catalogSeries = findSeriesCatalogParent(item);
    try {
        const series = tmdbId && nodeApiEnabled()
            ? await nodeApi(`/catalog/series/${encodeURIComponent(tmdbId)}`)
            : await api(
                `/catalog/series/${encodeURIComponent(catalogSeries?.id || seriesCatalogIdFromEpisode(item))}?title=${encodeURIComponent(seriesTitleKey(item.name || "") || item.name || "")}`
            );
        state.activeSeries = normalizeSeriesContext(series, item, catalogSeries);
    } catch {
        if (catalogSeries && catalogSeries !== item) {
            state.activeSeries = {
                id: catalogSeries.id,
                name: catalogSeries.name || seriesTitleKey(item.name || "") || item.name,
                poster: catalogSeries.poster || catalogSeries.image || item.image,
                backdrop: catalogSeries.backdrop || item.backdrop,
                seasons: [[item.season || 1, item]].map(([season, episode]) => ({
                    season,
                    name: `Saison ${season}`,
                    episodes: [episode]
                }))
            };
        }
    }
}

function normalizeSeriesContext(series, item, fallbackSeries) {
    const poster = normalizeImageSource(
        series.poster || fallbackSeries?.poster || fallbackSeries?.image || item.image,
        "/assets/images/poster-2.jpg"
    );
    const seasons = (series.seasons || []).map((season) => ({
        ...season,
        episodes: (season.episodes || []).map((episode) => ({
            ...episode,
            type: "series",
            isEpisode: true,
            tmdbId: tmdbIdFromItem(episode) || tmdbIdFromItem(series) || tmdbIdFromItem(item) || undefined,
            source: episode.source || series.source || item.source,
            sourceCode: episode.sourceCode || series.sourceCode || item.sourceCode,
            playbackProvider: episode.playbackProvider || series.playbackProvider || item.playbackProvider,
            playbackProviderName: episode.playbackProviderName || series.playbackProviderName || item.playbackProviderName,
            externalPlayback: episode.externalPlayback || series.externalPlayback || item.externalPlayback,
            streamAvailable: episode.streamAvailable ?? series.streamAvailable ?? item.streamAvailable,
            categoryName: series.categoryName || fallbackSeries?.categoryName || item.categoryName,
            image: normalizeImageSource(episode.poster || poster, "/assets/images/landscape-1.jpg")
        }))
    }));
    return {
        ...series,
        poster,
        backdrop: normalizeImageSource(series.backdrop || fallbackSeries?.backdrop || poster, poster),
        seasons
    };
}

function seriesContainsEpisode(series, episodeId) {
    return (series.seasons || [])
        .flatMap((season) => season.episodes || [])
        .some((episode) => String(episode.id) === String(episodeId));
}

function seriesEpisodeKey(seasonNumber, episode) {
    const season = positiveInteger(seasonNumber) || positiveInteger(episode?.season) || 1;
    const episodeNumber = positiveInteger(episode?.episode) || 1;
    return `s${season}e${episodeNumber}:${String(episode?.id || "")}`;
}

function findSeriesEpisodeByKey(series, episodeKey) {
    const requested = String(episodeKey || "");
    for (const season of series?.seasons || []) {
        for (const episode of season.episodes || []) {
            const key = seriesEpisodeKey(season.season, episode);
            if (key === requested || String(episode.id) === requested) {
                return { season, episode, key };
            }
        }
    }
    return null;
}

function seriesEpisodeMatchesItem(season, episode, item) {
    if (!item) return false;
    const seasonNumber = positiveInteger(season?.season) || positiveInteger(episode?.season) || 1;
    const episodeNumber = positiveInteger(episode?.episode) || 1;
    if (positiveInteger(item.season) && positiveInteger(item.episode)) {
        return positiveInteger(item.season) === seasonNumber
            && positiveInteger(item.episode) === episodeNumber;
    }
    return String(episode.id) === String(item.id);
}

function seriesEpisodeDisplayName(episode, episodeNumber) {
    const name = String(episode?.name || "").trim();
    const genericMatch = name.match(/^(?:episode|épisode|ep\.?)\s*(\d+)/i);
    if (genericMatch && Number(genericMatch[1]) !== Number(episodeNumber)) {
        return `Episode ${episodeNumber}`;
    }
    return name || `Episode ${episodeNumber}`;
}

function seriesCatalogIdFromEpisode(item) {
    const tmdbId = tmdbIdFromItem(item);
    if (tmdbId) return `tmdb~series~${tmdbId}`;
    return String(item?.seriesId || item?.parentId || item?.id || "");
}

function findSeriesCatalogParent(item) {
    const tmdbId = String(tmdbIdFromItem(item) || "");
    const title = seriesTitleKey(item?.name || "");
    return state.catalog.find((candidate) => (
        candidate.type === "series"
        && !candidate.isEpisode
        && (
            (tmdbId && String(tmdbIdFromItem(candidate) || candidate.tmdbId || "") === tmdbId)
            || (title && seriesTitleKey(candidate.name || "") === title)
        )
    )) || null;
}

function refreshPlayerRoom(item, badgeLabel = "") {
    if (!item) return;
    const titleParts = String(item.name || "Programme").split(/\s+Â·\s+|\s+·\s+/);
    const mainTitle = titleParts[0] || item.name || "Programme";
    const subtitle = titleParts.slice(1).join(" · ") || playerSubtitleForItem(item);
    const badge = badgeLabel || elements.playerBadge?.textContent || typeLabel(item.type).toUpperCase();
    const pills = playerPillsForItem(item, badge);
    const synopsis = item.summary || item.description || item.overview || item.categoryName
        || "Selectionnez un episode, reprenez la lecture ou changez de version depuis cette interface.";

    if (elements.playerTitle) elements.playerTitle.textContent = mainTitle;
    if (elements.playerEpisodeSubtitle) elements.playerEpisodeSubtitle.textContent = subtitle;
    if (elements.playerInfoTitle) elements.playerInfoTitle.textContent = mainTitle;
    if (elements.playerInfoSubtitle) elements.playerInfoSubtitle.textContent = subtitle;
    if (elements.playerInfoPills) {
        elements.playerInfoPills.innerHTML = pills.map((pill) => `<span>${escapeHtml(pill)}</span>`).join("");
    }
    if (elements.playerSynopsis) elements.playerSynopsis.textContent = synopsis;
    if (elements.playerRoomUserName) elements.playerRoomUserName.textContent = state.user?.name || state.user?.email?.split("@")[0] || "Nexora";
    if (elements.playerRoomAvatar) {
        const label = state.user?.name || state.user?.email || "N";
        elements.playerRoomAvatar.textContent = label.trim().charAt(0).toUpperCase() || "N";
    }
    renderPlayerEpisodePanel(item);
}

function playerSubtitleForItem(item) {
    if (item.type === "live") return item.categoryName || "En direct";
    if (item.isEpisode) return "Episode en cours";
    if (item.episode) return `Episode ${item.episode}`;
    return item.categoryName || typeLabel(item.type);
}

function playerPillsForItem(item, badge) {
    return [
        item.season ? `Saison ${item.season}` : "",
        item.episode ? `Episode ${item.episode}` : "",
        item.duration || item.runtime || "",
        badge,
        item.type === "series" || item.type === "movie" ? "VF disponible" : ""
    ].filter(Boolean).slice(0, 5);
}

function renderPlayerEpisodePanel(item) {
    const entries = playerEpisodeEntries(item);
    const activeIndex = entries.findIndex((entry) => entry.active);
    const isMovie = item?.type === "movie";
    const hasEpisodes = !isMovie && (entries.length > 1 || item?.type === "series");
    if (elements.playerEpisodePanelTitle) {
        elements.playerEpisodePanelTitle.textContent = isMovie
            ? "Films similaires"
            : hasEpisodes ? "Autres episodes" : "A regarder";
    }
    if (elements.playerEpisodePanelCount) {
        elements.playerEpisodePanelCount.textContent = entries.length ? `${entries.length} items` : "";
    }
    if (elements.playerAllEpisodesButton) {
        elements.playerAllEpisodesButton.textContent = isMovie ? "Voir plus de films" : "Voir tous les episodes";
    }
    if (elements.playerEpisodePanelList) {
        elements.playerEpisodePanelList.innerHTML = entries.map(playerEpisodeCardTemplate).join("");
    }
    setPlayerEpisodeNav(entries, activeIndex);
    if (elements.playerEpisodePanel) {
        elements.playerEpisodePanel.hidden = false;
    }
}

function playerEpisodeEntries(item) {
    if (item?.id?.startsWith?.("drama-") && state.activeDramaBook && state.activeDramaEpisodes.length) {
        return state.activeDramaEpisodes.map((episode) => ({
            kind: "drama",
            id: String(episode.episode),
            chapterId: episode.chapter_id || "",
            title: `Episode ${episode.episode}`,
            subtitle: state.activeDramaBook.title,
            image: state.activeDramaBook.image,
            duration: "ReelShort",
            active: Number(episode.episode) === Number(item.episode)
        }));
    }

    if (item?.isEpisode && state.activeSeries?.seasons?.length) {
        return state.activeSeries.seasons.flatMap((season) => (season.episodes || []).map((episode) => ({
            kind: "series",
            id: seriesEpisodeKey(season.season, episode),
            title: seriesEpisodeDisplayName(episode, positiveInteger(episode.episode) || 1),
            subtitle: `Saison ${season.season} · Episode ${episode.episode || ""}`,
            image: normalizeImageSource(episode.poster || state.activeSeries.poster || item.image, "/assets/images/landscape-1.jpg"),
            duration: episode.duration || "",
            active: seriesEpisodeMatchesItem(season, episode, item)
        })));
    }

    const candidates = relatedPlayerCandidates(item);
    const related = candidates
        .slice(0, 18)
        .map((candidate) => ({
            kind: "catalog",
            id: candidate.id,
            type: candidate.type,
            title: candidate.name,
            subtitle: candidate.categoryName || typeLabel(candidate.type),
            image: normalizeImageSource(candidate.image, "/assets/images/landscape-1.jpg"),
            duration: candidate.type === "live" ? "Direct" : "",
            active: false
        }));
    return [{
        kind: "current",
        id: item?.id || "current",
        title: item?.name || "Programme",
        subtitle: item?.categoryName || typeLabel(item?.type),
        image: normalizeImageSource(item?.image, "/assets/images/landscape-1.jpg"),
        duration: item?.duration || "",
        active: true
    }, ...related];
}

function relatedPlayerCandidates(item) {
    const currentType = item?.type || "";
    const currentId = String(item?.id || "");
    const currentCategory = String(item?.categoryId || item?.categoryName || "").toLowerCase();
    const currentTmdbId = String(item?.tmdbId || tmdbIdFromItem(item) || "");
    const currentSeriesTitle = seriesTitleKey(item?.name || "");
    const pool = uniqueCatalogItems([...state.recentlyWatched, ...state.catalog])
        .filter((candidate) => candidate?.id && String(candidate.id) !== currentId)
        .filter((candidate) => !currentType || candidate.type === currentType);

    if (currentType === "movie") {
        return pool
            .filter((candidate) => candidate.type === "movie")
            .sort((a, b) => relatedScore(b, item, currentCategory, currentTmdbId) - relatedScore(a, item, currentCategory, currentTmdbId));
    }

    if (currentType === "series") {
        const sameSeries = pool.filter((candidate) => (
            isSameSeriesCandidate(candidate, currentTmdbId, currentSeriesTitle)
        ));
        const sameCategory = pool.filter((candidate) => (
            !sameSeries.includes(candidate)
            && String(candidate.categoryId || candidate.categoryName || "").toLowerCase() === currentCategory
        ));
        return [...sameSeries, ...sameCategory];
    }

    return pool;
}

function relatedScore(candidate, item, currentCategory, currentTmdbId) {
    let score = 0;
    if (currentTmdbId && String(candidate.tmdbId || tmdbIdFromItem(candidate) || "") === currentTmdbId) score += 12;
    if (currentCategory && String(candidate.categoryId || candidate.categoryName || "").toLowerCase() === currentCategory) score += 8;
    if (candidate.sourceCode && candidate.sourceCode === item?.sourceCode) score += 2;
    if (candidate.watchedAt) score += 1;
    return score;
}

function isSameSeriesCandidate(candidate, currentTmdbId, currentSeriesTitle) {
    const candidateTmdbId = String(candidate.tmdbId || tmdbIdFromItem(candidate) || "");
    if (currentTmdbId && candidateTmdbId && candidateTmdbId === currentTmdbId) return true;
    return currentSeriesTitle && seriesTitleKey(candidate.name || "") === currentSeriesTitle;
}

function seriesTitleKey(value) {
    return String(value || "")
        .replace(/\s+[·-]\s+(?:Episode|Épisode|Episodio|EP)\b.*$/i, "")
        .replace(/\s+S\d+\s*E\d+.*$/i, "")
        .trim()
        .toLowerCase();
}

function playerEpisodeCardTemplate(entry) {
    const stateIcon = entry.active ? "Ⅱ" : "✓";
    const attrs = entry.kind === "drama"
        ? `data-player-drama-episode="${escapeHtml(entry.id)}" data-player-drama-chapter="${escapeHtml(entry.chapterId)}"`
        : entry.kind === "series"
                ? `data-player-series-episode="${escapeHtml(entry.id)}"`
            : entry.kind === "catalog"
                ? `data-player-catalog-item="${escapeHtml(entry.id)}" data-player-catalog-type="${escapeHtml(entry.type || "")}"`
                : "";
    return `
        <button class="player-episode-card ${entry.active ? "active" : ""}" type="button" ${attrs} ${entry.active ? "aria-current=\"true\"" : ""}>
            <img src="${escapeHtml(entry.image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
            <span>
                <strong>${escapeHtml(entry.title)}</strong>
                <span>${escapeHtml([entry.subtitle, entry.duration].filter(Boolean).join(" · "))}</span>
            </span>
            <i class="player-episode-state" aria-hidden="true">${stateIcon}</i>
        </button>
    `;
}

function setPlayerEpisodeNav(entries, activeIndex) {
    const previous = activeIndex > 0 ? entries[activeIndex - 1] : null;
    const next = activeIndex >= 0 && activeIndex < entries.length - 1 ? entries[activeIndex + 1] : null;
    setPlayerNavButton(elements.playerPrevEpisodeButton, previous);
    setPlayerNavButton(elements.playerNextEpisodeButton, next);
}

function setPlayerNavButton(button, entry) {
    if (!button) return;
    button.disabled = !entry || !["series", "drama", "catalog"].includes(entry.kind);
    button.dataset.playerEpisodeKind = entry?.kind || "";
    button.dataset.playerEpisodeId = entry?.id || "";
    button.dataset.playerDramaChapter = entry?.chapterId || "";
    button.dataset.playerCatalogType = entry?.type || "";
}

function playPlayerPanelEntry(entryKind, id, chapterId = "", type = "") {
    if (entryKind === "drama") {
        return playDramaEpisode(Number(id), chapterId);
    }
    if (entryKind === "series") {
        return playSeriesEpisode(id);
    }
    if (entryKind === "catalog") {
        const item = [...state.catalog, ...state.recentlyWatched].find((candidate) => (
            String(candidate.id) === String(id) && (!type || candidate.type === type)
        ));
        if (item) return playItem(item);
    }
    return undefined;
}

function renderCatalogLoadingBanner(searching) {
    return `
        <div class="catalog-loading-strip" role="status" aria-live="polite">
            <span class="spinner" aria-hidden="true"></span>
            <strong>${searching ? "Recherche en cours" : "Synchronisation du catalogue"}</strong>
            <span>${searching
                ? "Nexora interroge les sources distantes, les resultats arrivent."
                : "Nexora met a jour les chaines, films et series disponibles."}</span>
        </div>
    `;
}

function homeImage(item, fallback = "/assets/images/landscape-1.jpg") {
    return escapeHtml(item?.backdrop || item?.image || item?.poster || item?.logo || fallback);
}

function homeMeta(item) {
    return [
        typeLabel(item?.type),
        item?.categoryName,
        item?.releaseYear || item?.year
    ].filter(Boolean).join(" · ");
}

function renderHomeForYou(items) {
    if (!elements.homeFeaturedCard || !elements.homeForYouList || !elements.homeForYouPanel) return;
    const unique = uniqueCatalogItems(items).filter((item) => ["movie", "series"].includes(item.type));
    const featured = unique.find((item) => item.type === "series") || unique[0];
    const miniItems = unique.filter((item) => item.id !== featured?.id || item.type !== featured?.type).slice(0, 3);

    elements.homeForYouPanel.hidden = state.activeType !== "all" || Boolean(state.query.trim());
    if (!featured) {
        elements.homeFeaturedCard.innerHTML = "";
        elements.homeForYouList.innerHTML = "";
        return;
    }

    elements.homeFeaturedCard.innerHTML = `
        <button class="home-featured-card" type="button" data-item-id="${escapeHtml(featured.id)}" data-item-type="${featured.type}">
            <img src="${homeImage(featured)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-2.jpg')">
            <span class="home-featured-play" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>
            </span>
            <span class="home-featured-copy">
                <strong>${escapeHtml(featured.name)}</strong>
                <small>${escapeHtml(homeMeta(featured))}</small>
            </span>
        </button>
    `;

    elements.homeForYouList.innerHTML = miniItems.map((item) => `
        <button class="home-mini-item" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}">
            <img src="${homeImage(item)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-3.jpg')">
            <span>
                <strong>${escapeHtml(item.name)}</strong>
                <small>${escapeHtml(homeMeta(item))}</small>
            </span>
        </button>
    `).join("");
}

function renderHomeDashboard(items) {
    const unique = uniqueCatalogItems(items).filter((item) => ["movie", "series", "live"].includes(item.type));
    const availableForContinue = unique.filter((item) => !state.hiddenContinueCards.has(catalogCardKey(item.type, item.id)));
    const recent = recentlyWatchedItems(availableForContinue).slice(0, 8);
    const fallbackContinue = balancedHomeItems(availableForContinue, 18, ["series", "movie", "live"]);
    const continueItems = uniqueCatalogItems([...recent, ...fallbackContinue]).slice(0, 18);
    const trending = balancedHomeItems(unique.filter((item) => item.type !== "live"), 18, ["series", "movie"]);
    const recommended = balancedHomeItems(unique.filter((item) => item.type === "series"), 18, ["series"]);
    const moodItems = [
        ["Action", "movie", "/assets/images/landscape-1.jpg"],
        ["Suspense", "series", "/assets/images/landscape-2.jpg"],
        ["Romance", "series", "/assets/images/landscape-3.jpg"],
        ["Drame", "series", "/assets/images/landscape-4.jpg"],
        ["Science-Fiction", "movie", "/assets/images/poster-2.jpg"],
        ["Animation", "movie", "/assets/images/poster-3.jpg"]
    ];

    return `
        <section class="home-dashboard" aria-label="Accueil Nexora">
            <div class="home-panel home-continue">
                <div class="home-panel-heading">
                    <h2>Continuer la lecture</h2>
                    ${renderHomeScrollControls("homeContinueTrack")}
                </div>
                <div class="home-continue-track" id="homeContinueTrack">
                    ${continueItems.map(renderHomeContinueCard).join("")}
                </div>
            </div>

            <div class="home-panel home-moods">
                <div class="home-panel-heading">
                    <h2>Raccourcis par humeur</h2>
                    <button class="circle-link" type="button" data-filter-shortcut="movie" aria-label="Explorer les humeurs">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                    </button>
                </div>
                <div class="home-mood-grid">
                    ${moodItems.map(([label, type, image]) => `
                        <button class="home-mood-card" type="button" data-filter-shortcut="${type}" style="--mood-image: url('${image}')">
                            <span>${escapeHtml(label)}</span>
                        </button>
                    `).join("")}
                </div>
            </div>

            <div class="home-panel home-trending">
                <div class="home-panel-heading">
                    <h2>Tendances du moment</h2>
                    ${renderHomeScrollControls("homeTrendingTrack")}
                </div>
                <div class="home-poster-track" id="homeTrendingTrack">
                    ${trending.map((item, index) => renderHomePosterCard(item, index)).join("")}
                </div>
            </div>

            <div class="home-panel home-recommended">
                <div class="home-panel-heading">
                    <h2>S\u00e9ries recommand\u00e9es pour vous</h2>
                    ${renderHomeScrollControls("homeRecommendedTrack")}
                </div>
                <div class="home-poster-track" id="homeRecommendedTrack">
                    ${recommended.map((item, index) => renderHomePosterCard(item, index + 6)).join("")}
                </div>
            </div>
        </section>
    `;
}

function renderHomeScrollControls(targetId) {
    return `
        <span class="home-panel-actions">
            <button class="circle-link" type="button" data-home-scroll="${targetId}" data-home-scroll-direction="-1" aria-label="Faire defiler vers la gauche">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"></path></svg>
            </button>
            <button class="circle-link" type="button" data-home-scroll="${targetId}" data-home-scroll-direction="1" aria-label="Faire defiler vers la droite">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
            </button>
        </span>
    `;
}

function scrollRail(track, direction) {
    if (!track) return;
    const amount = Math.max(track.clientWidth * 0.86, 320);
    const nextLeft = Math.max(0, Math.min(track.scrollWidth - track.clientWidth, track.scrollLeft + direction * amount));
    if (Math.abs(nextLeft - track.scrollLeft) < 2 && direction < 0) {
        track.scrollLeft = 0;
        return;
    }
    if (track.scrollTo) {
        track.scrollTo({ left: nextLeft, behavior: "smooth" });
    } else {
        track.scrollLeft = nextLeft;
    }
}

function renderHomeContinueCard(item) {
    if (!item) return "";
    const progress = Math.min(92, 24 + Math.abs(String(item.id).split("").reduce((sum, char) => sum + char.charCodeAt(0), 0)) % 55);
    return `
        <article class="home-continue-card" role="button" tabindex="0" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}">
            <button class="home-continue-remove" type="button" data-continue-remove="${escapeHtml(item.id)}" data-continue-type="${item.type}" aria-label="Retirer ${escapeHtml(item.name)} de Continuer la lecture">
                <span aria-hidden="true">&times;</span>
            </button>
            <img src="${homeImage(item)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
            <span>
                <strong>${escapeHtml(item.name)}</strong>
                <small>${escapeHtml(item.isEpisode ? `S${item.season || 1} E${item.episode || 1}` : homeMeta(item))}</small>
            </span>
            <i style="--progress: ${progress}%"></i>
        </article>
    `;
}

function renderHomePosterCard(item, index = 0) {
    if (!item) return "";
    const fallback = `/assets/images/poster-${(index % 4) + 1}.jpg`;
    return `
        <button class="home-poster-card" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}">
            <img src="${homeImage(item, fallback)}" alt="" decoding="async" onerror="retryCatalogImage(this, '${fallback}')">
            <span>
                <strong>${escapeHtml(item.name)}</strong>
                <small>${escapeHtml(homeMeta(item))}</small>
            </span>
        </button>
    `;
}

function renderBrowseDashboard(items) {
    const type = state.activeType;
    const unique = uniqueCatalogItems(items).filter((item) => item.type === type);
    if (!unique.length) return "";

    const originals = balancedHomeItems(unique, 8, ["tmdb", "movie", "series"]);
    const recommended = balancedHomeItems(unique.filter((item) => item.streamAvailable !== false), 12, [type, "tmdb"]);
    const recent = recentlyWatchedItems(unique).slice(0, 10);
    const continueItems = uniqueCatalogItems([...recent, ...balancedHomeItems(unique, 10, [type])]).slice(0, 10);
    const universes = browseUniverses(unique).slice(0, 8);
    const featured = originals[0] || recommended[0] || unique[0];
    const title = type === "movie" ? "Films originaux Nexora" : "Series originales Nexora";
    const subtitle = type === "movie" ? "Des histoires uniques. Des emotions vraies." : "Des saisons a enchainer, selectionnees pour vous.";

    return `
        <section class="browse-dashboard" aria-label="${escapeHtml(typeLabel(type))}">
            <article class="browse-originals" data-item-id="${escapeHtml(featured.id)}" data-item-type="${featured.type}">
                <img src="${homeImage(featured, "/assets/images/landscape-7.jpg")}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-7.jpg')">
                <div>
                    <p class="section-kicker">${escapeHtml(type === "movie" ? "FILMS ORIGINAUX" : "SERIES ORIGINALES")}</p>
                    <h3>${escapeHtml(title)}</h3>
                    <span>${escapeHtml(subtitle)}</span>
                    <button class="button button-outline" type="button" data-item-id="${escapeHtml(featured.id)}" data-item-type="${featured.type}">Decouvrir</button>
                </div>
            </article>
            ${renderBrowseRail("Recommandes pour vous", "browseRecommendedTrack", recommended, renderHomePosterCard)}
            ${renderBrowseRail("Continuer la lecture", "browseContinueTrack", continueItems, renderHomeContinueCard)}
            ${renderUniverseRail(universes)}
        </section>
    `;
}

function renderBrowseRail(title, id, items, renderer) {
    if (!items.length) return "";
    return `
        <section class="browse-rail">
            <div class="browse-rail-head">
                <h3>${escapeHtml(title)}</h3>
                ${renderHomeScrollControls(id)}
            </div>
            <div class="browse-track ${id === "browseContinueTrack" ? "browse-continue-track" : "browse-poster-track"}" id="${id}">
                ${items.map((item, index) => renderer(item, index)).join("")}
            </div>
        </section>
    `;
}

function browseUniverses(items) {
    const seen = new Set();
    return items.reduce((values, item) => {
        const label = item.categoryName || typeLabel(item.type);
        const key = normalizeSearchText(label);
        if (!key || seen.has(key)) return values;
        seen.add(key);
        values.push({
            label,
            type: item.type,
            categoryId: item.categoryId || "",
            image: homeImage(item, "/assets/images/landscape-4.jpg")
        });
        return values;
    }, []);
}

function renderUniverseRail(universes) {
    if (!universes.length) return "";
    return `
        <section class="browse-rail browse-universes">
            <div class="browse-rail-head">
                <h3>Explorer par univers</h3>
                ${renderHomeScrollControls("browseUniverseTrack")}
            </div>
            <div class="browse-universe-track" id="browseUniverseTrack">
                ${universes.map((universe) => `
                    <button class="browse-universe-card" type="button" data-browse-category="${escapeHtml(universe.categoryId)}" data-browse-type="${escapeHtml(universe.type)}" style="--universe-image: url('${escapeHtml(universe.image)}')">
                        <span>${escapeHtml(universe.label)}</span>
                    </button>
                `).join("")}
            </div>
        </section>
    `;
}

function renderHomeShowcase(items) {
    const unique = uniqueCatalogItems(items);
    const posterItems = balancedHomeItems(unique, HOME_SHOWCASE_LIMIT, ["anime", "series", "movie", "live"]);
    const orbitItems = balancedHomeItems(unique, HOME_SHOWCASE_LIMIT, ["movie", "anime", "series", "live"]);

    if (!posterItems.length && !orbitItems.length) return "";

    return `
        <section class="watch-showcase" aria-labelledby="watchShowcaseTitle">
            <div class="watch-showcase-heading">
                <div>
                    <p class="section-kicker">SELECTION NEXORA</p>
                    <h3 id="watchShowcaseTitle">Decouvertes du moment</h3>
                </div>
                <div class="watch-showcase-controls">
                    <button class="row-arrow" type="button" data-row-scroll="-1" data-row-target="watchPosterRail" aria-label="Faire défiler la sélection vers la gauche">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"></path></svg>
                    </button>
                    <button class="row-arrow" type="button" data-row-scroll="1" data-row-target="watchPosterRail" aria-label="Faire défiler la sélection vers la droite">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                    </button>
                </div>
                <button class="watch-showcase-more" type="button" data-filter-shortcut="series">
                    Voir plus
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                </button>
            </div>
            <div class="watch-poster-rail" id="watchPosterRail">
                ${posterItems.map(watchPosterCard).join("")}
            </div>
            <div class="watch-orbit-rail" id="watchOrbitRail">
                ${orbitItems.map(watchOrbitCard).join("")}
            </div>
        </section>
    `;
}

function uniqueCatalogItems(items) {
    return [...new Map(items.map((item) => [`${item.type}:${item.id}`, item])).values()];
}

function isAnimeItem(item) {
    const haystack = normalizeSearchText([
        item?.name,
        item?.categoryName,
        item?.categoryId,
        item?.source,
        item?.languageName
    ].filter(Boolean).join(" "));
    return /\b(anime|animation|manga|japanimation|shonen|seinen)\b/.test(haystack);
}

function homeBucket(item) {
    if (isAnimeItem(item)) return "anime";
    return item.type || "other";
}

function stableHomeScore(item, offset = 0) {
    const raw = `${state.homeShuffleSalt}:${offset}:${item.type}:${item.id}:${item.name}`;
    let hash = 0;
    for (let index = 0; index < raw.length; index += 1) {
        hash = ((hash << 5) - hash + raw.charCodeAt(index)) | 0;
    }
    return Math.abs(hash);
}

function shuffledForHome(items, offset = 0) {
    return [...items].sort((left, right) => stableHomeScore(left, offset) - stableHomeScore(right, offset));
}

function balancedHomeItems(items, limit, order) {
    const buckets = new Map();
    shuffledForHome(items).forEach((item) => {
        const bucket = homeBucket(item);
        if (!buckets.has(bucket)) buckets.set(bucket, []);
        buckets.get(bucket).push(item);
    });

    const selected = [];
    const seen = new Set();
    const rounds = Math.max(...order.map((bucket) => buckets.get(bucket)?.length || 0), 0);
    for (let round = 0; round < rounds && selected.length < limit; round += 1) {
        for (const bucket of order) {
            const item = buckets.get(bucket)?.[round];
            if (!item) continue;
            const key = `${item.type}:${item.id}`;
            if (seen.has(key)) continue;
            seen.add(key);
            selected.push(item);
            if (selected.length >= limit) break;
        }
    }

    shuffledForHome(items, 37).forEach((item) => {
        if (selected.length >= limit) return;
        const key = `${item.type}:${item.id}`;
        if (seen.has(key)) return;
        seen.add(key);
        selected.push(item);
    });
    return selected;
}

function findCatalogItem(id, type) {
    const key = `${type}:${id}`;
    const pool = [...state.catalog, ...state.browseCatalog, ...state.searchResults, ...state.recentlyWatched];
    return pool.find((entry) => `${entry.type}:${entry.id}` === key)
        || pool.find((entry) => String(entry.id) === String(id));
}

function recentlyWatchedItems(items = state.catalog) {
    const lookup = new Map(
        [...state.browseCatalog, ...state.searchResults, ...items]
            .filter((item) => !isCatalogCardHidden(item))
            .map((item) => [`${item.type}:${item.id}`, item])
    );
    return state.recentlyWatched
        .map((recent, index) => {
            const key = `${recent.type}:${recent.id}`;
            const matched = lookup.get(key);
            return normalizeRecentItem({
                ...recent,
                ...matched,
                watchedAt: recent.watchedAt,
                isEpisode: recent.isEpisode || Boolean(matched?.isEpisode)
            }, index);
        })
        .filter((item) => item && !isCatalogCardHidden(item) && (state.activeType === "all" || item.type === state.activeType));
}

function renderRecentlyWatchedSection(items, searching) {
    if (searching) return "";
    const recentItems = recentlyWatchedItems(items).slice(0, state.activeType === "all" ? 18 : RECENTLY_WATCHED_LIMIT);
    if (!recentItems.length) return "";
    return renderCatalogShelf({
        type: "recent",
        title: "Regard\u00e9 r\u00e9cemment",
        label: `${recentItems.length} titre${recentItems.length > 1 ? "s" : ""} repris depuis cet appareil`,
        items: recentItems,
        posterLayout: true
    }, 0);
}

function renderHeroCarousel(items = state.catalog) {
    if (!elements.hero) return;
    const slides = buildHeroSlides(items);
    const oldKey = state.heroSlides[state.heroIndex]?.key;
    const changed = slides.map((slide) => slide.key).join("|")
        !== state.heroSlides.map((slide) => slide.key).join("|");
    state.heroSlides = slides;

    const preservedIndex = oldKey
        ? slides.findIndex((slide) => slide.key === oldKey)
        : -1;
    const nextIndex = preservedIndex >= 0 ? preservedIndex : 0;
    setHeroSlide(nextIndex, false, changed);
    renderHeroDots();
    scheduleHeroCarousel();
}

function buildHeroSlides(items) {
    const unique = uniqueCatalogItems(items || [])
        .filter((item) => item?.name && item?.image);
    const selected = balancedHomeItems(unique, HERO_MAX_SLIDES, ["anime", "series", "movie", "live"]);
    if (!selected.length) {
        return [{
            key: "fallback-hero",
            item: null,
            image: "/assets/images/hero.jpg",
            eyebrow: "La sélection Nexora",
            titleMain: "Votre télévision.",
            titleAccent: "Sans frontières.",
            copy: "Retrouvez le direct, vos films et vos séries dans une expérience fluide, pensée pour tous vos écrans.",
            match: "98 % pour vous",
            quality: "4K UHD",
            type: "Multi-écrans",
            age: "12+",
            longTitle: false
        }];
    }
    return selected.map(heroSlideFromItem);
}

function heroTypeScore(item) {
    if (isAnimeItem(item)) return 4;
    return { series: 3, movie: 2, live: 1 }[item.type] || 0;
}

function heroSlideFromItem(item, index) {
    const category = item.categoryName || typeLabel(item.type);
    const [titleMain, titleAccent] = heroTitleParts(item.name, category);
    const year = item.releaseYear || releaseYearFromDate(item.releaseDate);
    const quality = item.type === "live"
        ? "Direct"
        : year || "HD";
    const age = item.type === "live" ? "LIVE" : "12+";
    const score = Math.max(82, 98 - index * 3);
    return {
        key: `${item.type}:${item.id}`,
        item,
        image: item.backdrop || item.poster || item.image || "/assets/images/hero.jpg",
        eyebrow: heroEyebrow(item),
        titleMain,
        titleAccent,
        copy: heroCopy(item, category),
        match: `${score} % pour vous`,
        quality,
        type: typeLabel(item.type),
        age,
        longTitle: item.name.length > 28 || titleMain.length > 24 || titleAccent.length > 28
    };
}

function heroEyebrow(item) {
    if (state.query.trim()) {
        return "Recherche Nexora";
    }
    if (item.type === "series") return "Série en sélection";
    if (item.type === "movie") return "Film en sélection";
    return "En direct";
}

function heroTitleParts(name, fallbackAccent) {
    const clean = String(name || "Votre télévision.").trim();
    const splitters = [": ", " - ", " | "];
    for (const splitter of splitters) {
        const index = clean.indexOf(splitter);
        if (index > 2 && index < clean.length - splitter.length - 2) {
            return [
                clean.slice(0, index).trim(),
                clean.slice(index + splitter.length).trim()
            ];
        }
    }
    if (clean.length > 32) {
        const words = clean.split(/\s+/);
        const first = [];
        const second = [];
        let length = 0;
        for (const word of words) {
            if (length < 24 || !second.length) {
                first.push(word);
                length += word.length + 1;
            } else {
                second.push(word);
            }
        }
        if (second.length) {
            return [first.join(" "), second.join(" ")];
        }
    }
    return [clean, fallbackAccent || "Nexora"];
}

function heroCopy(item, category) {
    if (item.summary) return item.summary;
    if (item.type === "live") {
        return `${item.name} est disponible en direct maintenant.`;
    }
    if (item.type === "series") {
        return `${item.name} est disponible dans votre catalogue.`;
    }
    return `${item.name} est prêt à regarder depuis votre espace.`;
}

function setHeroSlide(index, manual = false, force = false) {
    if (!state.heroSlides.length || !elements.hero) return;
    const total = state.heroSlides.length;
    const normalized = ((index % total) + total) % total;
    if (!force && normalized === state.heroIndex) return;
    state.heroIndex = normalized;
    const slide = state.heroSlides[state.heroIndex];

    elements.hero.classList.remove("is-switching");
    void elements.hero.offsetWidth;
    elements.hero.classList.add("is-switching");
    elements.hero.classList.toggle("long-title", Boolean(slide.longTitle));
    elements.hero.style.setProperty("--hero-image", `url("${escapeCssUrl(slide.image)}")`);
    elements.hero.style.setProperty("--hero-progress", String((state.heroIndex + 1) / total));

    elements.heroEyebrow.textContent = slide.eyebrow;
    elements.heroTitle.innerHTML = `${escapeHtml(slide.titleMain)}<br><em>${escapeHtml(slide.titleAccent)}</em>`;
    elements.heroCopy.textContent = slide.copy;
    elements.heroMatch.textContent = slide.match;
    elements.heroQuality.textContent = slide.quality;
    elements.heroType.textContent = slide.type;
    elements.heroAge.textContent = slide.age;
    elements.heroCurrent.textContent = padHeroNumber(state.heroIndex + 1);
    elements.heroTotal.textContent = padHeroNumber(total);

    window.setTimeout(() => elements.hero.classList.remove("is-switching"), 760);
    renderHeroDots();
    if (manual) scheduleHeroCarousel(true);
}

function renderHeroDots() {
    if (!elements.heroDots) return;
    elements.heroDots.innerHTML = state.heroSlides.map((slide, index) => (
        `<button class="hero-dot ${index === state.heroIndex ? "active" : ""}" type="button" data-hero-slide="${index}" aria-label="${escapeHtml(slide.titleMain)}"></button>`
    )).join("");
}

function changeHeroSlide(direction) {
    setHeroSlide(state.heroIndex + direction, true);
}

function scheduleHeroCarousel(reset = false) {
    if (reset) clearHeroCarouselTimer();
    if (state.heroTimer || state.heroPaused || state.heroSlides.length < 2) return;
    state.heroTimer = window.setInterval(() => {
        setHeroSlide(state.heroIndex + 1);
    }, HERO_AUTO_ADVANCE_MS);
}

function clearHeroCarouselTimer() {
    if (state.heroTimer) {
        window.clearInterval(state.heroTimer);
        state.heroTimer = null;
    }
}

function pauseHeroCarousel() {
    state.heroPaused = true;
    clearHeroCarouselTimer();
}

function resumeHeroCarousel() {
    state.heroPaused = false;
    scheduleHeroCarousel(true);
}

function currentHeroItem() {
    return state.heroSlides[state.heroIndex]?.item || null;
}

function playHeroSelection() {
    const item = currentHeroItem();
    if (!item) {
        document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth" });
        return;
    }
    if (item.type === "series" && !item.isEpisode) {
        openSeries(item);
        return;
    }
    playItem(item);
}

function discoverHeroSelection() {
    const item = currentHeroItem();
    if (item) {
        selectCatalogItem(item);
        return;
    }
    document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth" });
}

function padHeroNumber(value) {
    return String(value).padStart(2, "0");
}

function removeCardControl(item) {
    if (!isRemovableCatalogItem(item)) return "";
    return `
        <span class="card-remove-action" role="button" tabindex="0" data-remove-card data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(`Supprimer ${item.name}`)}">
            ×
        </span>
    `;
}

function watchPosterCard(item) {
    const year = item.releaseYear || item.year || (item.type === "live" ? "Live" : "2026");
    const genre = item.categoryName || typeLabel(item.type);
    const addonBadge = sourceBadge(item, "card");
    return `
        <button class="watch-poster-card" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(`Ouvrir ${item.name}`)}">
            ${addonBadge}
            ${removeCardControl(item)}
            <img src="${escapeHtml(item.image)}" alt="" loading="lazy" decoding="async" data-image-title="${escapeHtml(item.name)}" data-image-type="${escapeHtml(item.type)}" onload="repairCatalogImage(this)" onerror="retryCatalogImage(this, '/assets/images/poster-1.jpg')">
            <span class="watch-poster-year">${escapeHtml(year)}</span>
            <strong>${escapeHtml(item.name)}</strong>
            <small><span>${escapeHtml(genre)}</span><b>${escapeHtml(typeLabel(item.type))}</b></small>
        </button>
    `;
}

function watchOrbitCard(item) {
    return `
        <button class="watch-orbit-card" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(`Ouvrir ${item.name}`)}">
            <span><img src="${escapeHtml(item.image)}" alt="" loading="lazy" decoding="async" data-image-title="${escapeHtml(item.name)}" data-image-type="${escapeHtml(item.type)}" onload="repairCatalogImage(this)" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')"></span>
            <strong>${escapeHtml(item.name)}</strong>
            <small>${escapeHtml(typeLabel(item.type))}</small>
        </button>
    `;
}

function buildCatalogShelves(definition, allItems, visibleItems, searching) {
    const splitIntoShelves = !searching
        && state.activeType === definition.type
        && ["live", "movie", "series"].includes(definition.type);

    if (!splitIntoShelves) {
        return [{
            type: definition.type,
            title: definition.title,
            label: state.activeType === "all"
                ? `${definition.label} · ${visibleItems.length} à découvrir`
                : definition.label,
            items: visibleItems
        }];
    }

    const categoryOrder = new Map(
        state.categories
            .filter((category) => category.type === definition.type)
            .map((category, index) => [String(category.id), index])
    );
    const totals = new Map();
    allItems.forEach((item) => {
        const key = catalogGroupKey(item);
        totals.set(key, (totals.get(key) || 0) + 1);
    });

    const groups = new Map();
    visibleItems.forEach((item) => {
        const key = catalogGroupKey(item);
        if (!groups.has(key)) {
            groups.set(key, {
                key,
                title: item.categoryName || definition.title,
                items: []
            });
        }
        groups.get(key).items.push(item);
    });

    const orderedGroups = [...groups.values()].sort((left, right) => {
        const leftOrder = categoryOrder.get(left.key) ?? Number.MAX_SAFE_INTEGER;
        const rightOrder = categoryOrder.get(right.key) ?? Number.MAX_SAFE_INTEGER;
        return leftOrder - rightOrder || titleCollator.compare(left.title, right.title);
    });
    const onlyOneCategory = orderedGroups.length === 1;

    return orderedGroups.flatMap((group) => {
        const total = totals.get(group.key) || group.items.length;
        return chunkCatalogItems(group.items, 30).map((chunk, chunkIndex) => {
            const firstItem = chunkIndex * 30 + 1;
            const lastItem = firstItem + chunk.length - 1;
            const noun = definition.type === "live"
                ? "chaînes"
                : definition.type === "series"
                    ? "séries"
                    : "films";
            let title = group.title;
            if (definition.type === "live" && onlyOneCategory && chunkIndex === 0) {
                title = "En direct maintenant";
            } else if (chunkIndex > 0) {
                title = definition.type === "live" && onlyOneCategory
                    ? `La suite du direct · ${chunkIndex + 1}`
                    : definition.type === "live"
                        ? `Encore en direct · ${group.title} · ${chunkIndex + 1}`
                        : `Plus de ${group.title} · ${chunkIndex + 1}`;
            }
            return {
                type: definition.type,
                title,
                label: `${noun} ${firstItem}–${lastItem} sur ${total}`,
                items: chunk
            };
        });
    });
}

function catalogGroupKey(item) {
    return String(item.categoryId || normalizeSearchText(item.categoryName) || item.type);
}

function chunkCatalogItems(items, size) {
    const chunks = [];
    for (let index = 0; index < items.length; index += size) {
        chunks.push(items.slice(index, index + size));
    }
    return chunks;
}

function renderCatalogShelf(shelf, index) {
    const rowId = `catalog-row-${shelf.type}-${index}`;
    const posterLayout = shelf.posterLayout ?? ["movie", "series"].includes(shelf.type);
    const hasShortcut = Boolean(shelf.homePreview);
    const endCard = hasShortcut ? renderShelfMoreCard(shelf.type) : "";
    const trackItems = posterLayout
        ? shelf.items.map(posterMediaCard).join("")
        : shelf.items.map(mediaCard).join("");
    const controls = shelf.items.length > 4
        ? `
            <div class="row-navigation" aria-label="Navigation de la rangée">
                <button class="row-arrow" type="button" data-row-scroll="-1" data-row-target="${rowId}" aria-label="Faire défiler vers la gauche">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"></path></svg>
                </button>
                <button class="row-arrow" type="button" data-row-scroll="1" data-row-target="${rowId}" aria-label="Faire défiler vers la droite">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                </button>
            </div>
        `
        : "";

    return `
        <section class="content-row content-row-${shelf.type}" aria-labelledby="${rowId}-title">
            <div class="row-heading">
                <div class="row-heading-copy">
                    <h3 id="${rowId}-title">${escapeHtml(shelf.title)}</h3>
                    <span>${escapeHtml(shelf.label)}</span>
                </div>
                ${hasShortcut ? `<button class="row-see-more" type="button" data-filter-shortcut="${shelf.type}">Voir plus ${escapeHtml(typeLabel(shelf.type).toLowerCase())}</button>` : ""}
                ${controls}
            </div>
            <div class="card-track ${posterLayout ? "poster-card-track" : ""} card-track-${shelf.type}" id="${rowId}">
                ${trackItems}
                ${endCard}
            </div>
        </section>
    `;
}

function renderShelfMoreCard(type) {
    const labels = {
        live: ["Direct", "Toutes les chaînes"],
        movie: ["Films", "Voir la bibliothèque"],
        series: ["Séries", "Explorer les saisons"]
    };
    const [title, label] = labels[type] || labels.live;
    return `
        <button class="shelf-more-card" type="button" data-filter-shortcut="${type}" aria-label="Voir plus: ${escapeHtml(title)}">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(title)}</strong>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
        </button>
    `;
}

function posterMediaCard(item) {
    const year = item.releaseYear || item.year || "";
    const meta = [
        item.type === "series" && item.episodeCount
            ? `${item.seasonCount || 1} saison${(item.seasonCount || 1) > 1 ? "s" : ""}`
            : year,
        item.categoryName || typeLabel(item.type)
    ].filter(Boolean).join(" · ");
    const addonBadge = sourceBadge(item, "card");
    const actionLabel = item.type === "series" ? "Voir les épisodes" : `Ouvrir ${item.name}`;

    return `
        <button class="media-card poster-media-card ${item.type}" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(actionLabel)}">
            ${addonBadge}
            ${removeCardControl(item)}
            <img src="${escapeHtml(item.image)}" alt="" loading="lazy" decoding="async" data-image-title="${escapeHtml(item.name)}" data-image-type="${escapeHtml(item.type)}" onload="repairCatalogImage(this)" onerror="retryCatalogImage(this, '/assets/images/poster-1.jpg')">
            <span class="poster-card-copy">
                <strong>${escapeHtml(item.name)}</strong>
                <span>${escapeHtml(meta)}</span>
            </span>
        </button>
    `;
}

function mediaCard(item) {
    const label = item.type === "live"
        ? "En direct"
        : item.type === "series" && item.episodeCount
            ? `${item.seasonCount} saison${item.seasonCount > 1 ? "s" : ""} · ${item.episodeCount} épisodes`
            : item.type === "movie" && item.releaseYear
                ? `${item.categoryName} · ${item.releaseYear}`
            : item.categoryName;
    const live = item.type === "live" ? `<span class="live-label">LIVE</span>` : "";
    const addonBadge = sourceBadge(item, "card");
    const actionIcon = item.type === "series"
        ? `<svg viewBox="0 0 24 24"><path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"></path></svg>`
        : `<svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>`;
    const actionLabel = item.type === "series" ? "Voir les épisodes" : `Regarder ${item.name}`;

    return `
        <button class="media-card ${item.type === "series" ? "series" : ""}" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(actionLabel)}">
            ${live}
            ${addonBadge}
            ${removeCardControl(item)}
            <img src="${escapeHtml(item.image)}" alt="" loading="lazy" decoding="async" data-image-title="${escapeHtml(item.name)}" data-image-type="${escapeHtml(item.type)}" onload="repairCatalogImage(this)" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
            <span class="card-content">
                <span class="card-copy">
                    <strong>${escapeHtml(item.name)}</strong>
                    <span>${escapeHtml(label)}</span>
                </span>
                <span class="card-play" aria-hidden="true">
                    ${actionIcon}
                </span>
            </span>
        </button>
    `;
}

function isAsaAddon(value) {
    return value?.addonKey === "org.adult.stremio.addon"
        || value?.source === "Adult Stremio Addon";
}

function isAddonSource(value) {
    return Boolean(value?.addonKey) || isAsaAddon(value);
}

function isTmdbSource(value) {
    return value?.sourceCode === "tmdb" || value?.source === "TMDB";
}

function isFrenchSource(value) {
    const sourceCode = String(value?.sourceCode || "").toLowerCase();
    const playbackProvider = String(value?.playbackProvider || "").toLowerCase();
    const categoryId = String(value?.categoryId || value?.id || "").toLowerCase();
    const source = String(value?.source || value?.provider || "").toLowerCase();
    return sourceCode === "orion"
        || playbackProvider.startsWith("orion")
        || categoryId.startsWith("orion~")
        || categoryId.startsWith("orion-french-")
        || source.includes("orion");
}

function isEpornerSource(value) {
    const sourceCode = String(value?.sourceCode || "").toLowerCase();
    const playbackProvider = String(value?.playbackProvider || "").toLowerCase();
    const categoryId = String(value?.categoryId || value?.id || "").toLowerCase();
    const source = String(value?.source || value?.provider || "").toLowerCase();
    return sourceCode === "eporner"
        || playbackProvider === "eporner"
        || categoryId.startsWith("adults-eporner")
        || categoryId.startsWith("eporner~")
        || source.includes("eporner");
}

function sourceClass(value) {
    if (isAsaAddon(value)) return "asa-category";
    if (isEpornerSource(value)) return "adult-category";
    if (isFrenchSource(value)) return "french-category";
    if (isTmdbSource(value)) return "tmdb-category";
    return "";
}

function sourceBadge(value, placement = "inline") {
    const placementClass = placement === "card" ? "addon-badge-card" : "addon-badge-inline";
    if (isAsaAddon(value)) {
        return `<span class="addon-badge ${placementClass}" aria-label="Contenu provenant de l'add-on ASA">ASA</span>`;
    }
    if (isFrenchSource(value)) {
        return `<span class="addon-badge french-badge ${placementClass}" aria-label="Contenu français via l'API Node">FR</span>`;
    }
    if (isEpornerSource(value)) {
        return `<span class="addon-badge adult-badge ${placementClass}" aria-label="Provider Adults restreint">18+</span>`;
    }
    if (isTmdbSource(value)) {
        return `<span class="addon-badge tmdb-badge ${placementClass}" aria-label="Contenu provenant de TMDB">TMDB</span>`;
    }
    return "";
}

function movieComparator(sort) {
    const byTitle = (left, right) => titleCollator.compare(
        sortableMovieTitle(left.name),
        sortableMovieTitle(right.name)
    );
    if (sort === "title-desc") {
        return (left, right) => byTitle(right, left);
    }
    if (sort === "recent") {
        return (left, right) => (
            Number(right.releaseYear || 0) - Number(left.releaseYear || 0)
            || byTitle(left, right)
        );
    }
    if (sort === "category") {
        return (left, right) => (
            titleCollator.compare(left.categoryName || "", right.categoryName || "")
            || byTitle(left, right)
        );
    }
    return byTitle;
}

function sortableMovieTitle(value) {
    return normalizeSearchText(value)
        .replace(/[^a-z0-9]+/g, " ")
        .trim()
        .replace(/^(le|la|les|un|une|des|the|a|an)\s+/, "");
}

function movieSortLabel(sort) {
    return {
        "title-asc": "Titre A-Z",
        "title-desc": "Titre Z-A",
        recent: "Les plus récents",
        category: "Par genre"
    }[sort] || "Titre A-Z";
}

function renderMovieSort(searching = Boolean(state.query.trim())) {
    const visible = state.activeType === "movie" && !searching;
    elements.movieSortControl.hidden = !visible;
    elements.movieSort.value = state.movieSort;
}

function normalizeSearchText(value) {
    return String(value || "")
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .toLocaleLowerCase("fr")
        .trim();
}

function searchScore(item, query) {
    const title = normalizeSearchText(item.name);
    const category = normalizeSearchText(item.categoryName);
    const language = normalizeSearchText(item.languageName);
    const type = normalizeSearchText(typeLabel(item.type));
    const haystack = `${title} ${category} ${language} ${type}`;
    const terms = query.split(/\s+/).filter(Boolean);

    if (!terms.every((term) => haystack.includes(term))) return Number.POSITIVE_INFINITY;
    if (title === query) return 0;
    if (title.startsWith(query)) return 1;
    if (title.includes(query)) return 2;
    if (category.startsWith(query)) return 3;
    if (language.startsWith(query)) return 4;
    return 5;
}

function searchCandidateItems() {
    const query = normalizeSearchText(state.query);
    const hasRemoteResults = query && state.searchResultQuery === query;
    const source = hasRemoteResults
        ? state.searchResults
        : state.browseCatalog.length
            ? state.browseCatalog
            : state.catalog;
    const unique = [...new Map(source.map((item) => [`${item.type}:${item.id}`, item])).values()]
        .filter((item) => !isCatalogCardHidden(item));

    if (!query) return unique.slice(0, 6);

    return unique
        .map((item, index) => ({ item, index, score: searchScore(item, query) }))
        .filter((entry) => Number.isFinite(entry.score))
        .sort((a, b) => a.score - b.score || a.index - b.index)
        .slice(0, 7)
        .map((entry) => entry.item);
}

function renderSearchSuggestions(forceOpen = false) {
    const focused = elements.searchShell.contains(document.activeElement);
    if (!forceOpen && !focused) {
        closeSearchSuggestions();
        return;
    }

    const query = state.query.trim();
    const items = searchCandidateItems();
    state.searchSuggestions = items;
    state.searchActiveIndex = -1;

    const heading = query ? "Suggestions pour vous" : "Tendances du catalogue";
    const status = state.catalogLoading && query
        ? "Recherche..."
        : `${items.length} proposition${items.length > 1 ? "s" : ""}`;
    const loadingSearch = state.catalogLoading && query;
    const list = items.length
        ? `
            <ul class="search-result-list">
                ${items.map((item, index) => `
                    <li>
                        <button
                            class="search-result"
                            id="search-option-${index}"
                            type="button"
                            role="option"
                            aria-selected="false"
                            data-search-index="${index}"
                        >
                            <span class="search-result-image">
                                <img src="${escapeHtml(item.image)}" alt="" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
                            </span>
                            <span class="search-result-copy">
                                <strong>${escapeHtml(item.name)}</strong>
                                <span>${escapeHtml(typeLabel(item.type))} · ${escapeHtml(item.categoryName)}</span>
                            </span>
                            <span class="search-result-play" aria-hidden="true">
                                <svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>
                            </span>
                        </button>
                    </li>
                `).join("")}
            </ul>
        `
        : `
            <div class="search-empty ${loadingSearch ? "search-loading" : ""}">
                ${loadingSearch ? `<span class="spinner" aria-hidden="true"></span>` : ""}
                <strong>${loadingSearch ? "Recherche en cours" : "Aucun titre trouvé"}</strong>
                <span>${loadingSearch
                    ? "Le catalogue distant repond encore, gardez cette recherche ouverte."
                    : "Essayez un autre titre, une chaîne ou un genre."}</span>
            </div>
        `;
    const showAll = query
        ? `<button class="search-show-all" type="button" data-search-all>Voir tous les résultats pour « ${escapeHtml(query)} »</button>`
        : "";

    elements.searchPanel.innerHTML = `
        <div class="search-panel-head">
            <span>${heading}</span>
            <span>${status}</span>
        </div>
        ${list}
        ${showAll}
    `;
    elements.searchPanel.hidden = false;
    elements.searchShell.classList.add("open");
    elements.searchInput.setAttribute("aria-expanded", "true");
    elements.searchInput.removeAttribute("aria-activedescendant");
}

function closeSearchSuggestions() {
    elements.searchPanel.hidden = true;
    elements.searchShell.classList.remove("open");
    elements.searchInput.setAttribute("aria-expanded", "false");
    elements.searchInput.removeAttribute("aria-activedescendant");
    state.searchActiveIndex = -1;
}

function setSearchActiveIndex(index) {
    const options = [...elements.searchPanel.querySelectorAll("[data-search-index]")];
    if (!options.length) return;

    const nextIndex = (index + options.length) % options.length;
    state.searchActiveIndex = nextIndex;
    options.forEach((option, optionIndex) => {
        const active = optionIndex === nextIndex;
        option.classList.toggle("active", active);
        option.setAttribute("aria-selected", String(active));
    });
    const activeOption = options[nextIndex];
    elements.searchInput.setAttribute("aria-activedescendant", activeOption.id);
    activeOption.scrollIntoView({ block: "nearest" });
}

function updateSearchChrome() {
    const hasQuery = Boolean(state.query.trim());
    elements.searchShell.classList.toggle("has-query", hasQuery);
    elements.searchClear.hidden = !hasQuery;
}

function resetSearchQuery(focusInput = false) {
    window.clearTimeout(searchTimer);
    clearHiddenCatalogCards();
    state.query = "";
    state.searchResults = [];
    state.searchResultQuery = "";
    state.searchSuggestions = [];
    state.catalog = state.browseCatalog.length ? [...state.browseCatalog] : [...fallbackCatalog];
    elements.searchInput.value = "";
    updateSearchChrome();
    closeSearchSuggestions();
    renderCatalog();
    if (focusInput) elements.searchInput.focus();
}

async function commitSearch() {
    window.clearTimeout(searchTimer);
    closeSearchSuggestions();
    const normalizedQuery = normalizeSearchText(state.query);
    if (state.token && (!normalizedQuery || normalizedQuery.length >= MIN_REMOTE_SEARCH_LENGTH)) {
        await loadCatalog();
    } else {
        renderCatalog();
    }
    document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth", block: "start" });
}

function updateCatalogHeading(resultCount, loading = false) {
    const query = state.query.trim();
    elements.emptyState.classList.toggle("loading-state", loading);
    elements.resetFilters.hidden = loading;
    if (query) {
        elements.catalogKicker.textContent = loading
            ? "RECHERCHE EN COURS"
            : `RECHERCHE · ${resultCount} RÉSULTAT${resultCount > 1 ? "S" : ""}`;
        elements.catalogTitle.textContent = `Résultats pour « ${query} »`;
        elements.emptyState.querySelector("span").innerHTML = loading
            ? `<span class="spinner large" aria-hidden="true"></span> Recherche`
            : "0 résultat";
        elements.emptyState.querySelector("h3").textContent = loading
            ? "Recherche en cours..."
            : `Aucun résultat pour « ${query} »`;
        elements.emptyState.querySelector("p").textContent = loading
            ? "Nexora synchronise les sources distantes. Les resultats peuvent prendre quelques secondes a apparaitre."
            : "Essayez un autre titre, une chaîne, un genre ou une catégorie.";
        return;
    }

    elements.catalogKicker.textContent = loading ? "SYNCHRONISATION EN COURS" : "À VOIR MAINTENANT";
    elements.catalogTitle.textContent = state.activeType === "movie"
        ? "Tous vos films, enfin bien rangés"
        : state.activeType === "live"
            ? "Toutes vos chaînes, en un coup d’œil"
            : "Le meilleur du catalogue";
    elements.emptyState.querySelector("span").innerHTML = loading
        ? `<span class="spinner large" aria-hidden="true"></span> Catalogue`
        : "0 résultat";
    elements.emptyState.querySelector("h3").textContent = loading
        ? "Chargement du catalogue..."
        : "Aucun programme à l’horizon";
    elements.emptyState.querySelector("p").textContent = loading
        ? "Synchronisation des chaines, films et series en cours. Cette etape peut durer quelques secondes."
        : "Essayez une autre recherche ou revenez à l’ensemble du catalogue.";
}

async function setFilter(type) {
    if (!state.token && state.authRequired) {
        requestLogin("Connectez-vous pour acceder au catalogue.");
        return;
    }
    resetSearchQuery(false);
    clearHiddenCatalogCards();
    state.activeType = type;
    state.activeCategory = "";
    state.activeAddonFilter = "";
    state.addonPages = 1;
    state.addonHasMore = false;
    elements.navLinks.forEach((link) => link.classList.toggle("active", link.dataset.filter === type));
    if (type === "drama") {
        elements.catalogRows.hidden = true;
        elements.emptyState.hidden = true;
        await loadDramaHome();
        document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth", block: "start" });
        return;
    }
    elements.dramaSection.hidden = true;
    elements.catalogRows.hidden = false;
    if (type !== "all") state.visibleCatalog[type] = defaultCatalogLimit(type);
    renderLanguageFilter();
    renderCategories();
    renderAddonFilter();
    renderCatalog();
    await loadCatalog();
    document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth", block: "start" });
}

function openModal(id) {
    const modal = document.getElementById(id);
    if (!modal) return;
    modal.hidden = false;
    document.body.classList.add("modal-open");
    window.setTimeout(() => modal.querySelector("input, button")?.focus(), 20);
}

function closeModal(id) {
    const modal = document.getElementById(id);
    if (!modal) return;
    if (id === "authModal" && state.authRequired && !state.user) {
        state.pendingAuthAction = null;
        window.location.href = "/";
        return;
    }
    modal.hidden = true;

    if (id === "playerModal") {
        clearActivePlayback();
        state.activePlayerItem = null;
        stopPlayer();
    }

    if (![...document.querySelectorAll(".modal")].some((item) => !item.hidden)) {
        document.body.classList.remove("modal-open");
    }
}

function setAuthMode(mode) {
    if (mode === "register") {
        window.location.href = "/signup.html";
        return;
    }
    state.authMode = "login";
    state.twoFactorEmail = null;
    state.emailVerificationEmail = null;
    state.passwordResetEmail = null;
    state.passwordResetCode = null;
    elements.authError.hidden = true;
    if (elements.authTabs) elements.authTabs.hidden = true;
    setAuthField(elements.authEmailField, true, true);
    setAuthField(elements.authPasswordField, true, true);
    setAuthField(elements.twoFactorField, false, false);
    setAuthField(elements.resetPasswordField, false, false);
    setCodeFieldLabel("Code de verification");
    elements.forgotPasswordButton.hidden = false;
    elements.forgotPasswordButton.textContent = "Mot de passe oublie ?";

    document.querySelectorAll(".register-field").forEach((field) => {
        field.hidden = true;
        field.querySelector("input").required = false;
    });

    elements.authTabs?.querySelectorAll("button").forEach((button) => (
        button.classList.toggle("active", button.dataset.authMode === "login")
    ));
    elements.authTitle.textContent = "Connexion";
    elements.authSubtitle.textContent = "Accedez a votre catalogue avec votre e-mail et votre mot de passe.";
    elements.authSubmit.firstChild.textContent = "Se connecter ";
}

function setAuthField(field, visible, required) {
    if (!field) return;
    field.hidden = !visible;
    field.querySelectorAll("input").forEach((input) => {
        input.required = Boolean(required);
        if (!visible) input.value = "";
    });
}

function setCodeFieldLabel(label) {
    const labelElement = elements.twoFactorField?.querySelector("span");
    if (labelElement) labelElement.textContent = label;
}

function setAuthCodeStep(kind, email) {
    setAuthField(elements.authEmailField, false, false);
    setAuthField(elements.authPasswordField, false, false);
    setAuthField(elements.twoFactorField, true, true);
    setAuthField(elements.resetPasswordField, false, false);
    setCodeFieldLabel(kind === "email" ? "Code OTP" : "Code de verification");
    elements.forgotPasswordButton.hidden = true;
    elements.authTitle.textContent = kind === "email"
        ? "Verification de votre email"
        : "Verification en deux etapes";
    elements.authSubtitle.textContent = kind === "email"
        ? `Saisissez le code OTP envoye a ${email}.`
        : `Saisissez le code envoye a ${email}.`;
    elements.authSubmit.firstChild.textContent = "Verifier ";
}

function setForgotPasswordMode() {
    state.authMode = "forgot";
    state.twoFactorEmail = null;
    state.emailVerificationEmail = null;
    state.passwordResetEmail = null;
    state.passwordResetCode = null;
    elements.authError.hidden = true;
    if (elements.authTabs) elements.authTabs.hidden = true;
    setAuthField(elements.authEmailField, true, true);
    setAuthField(elements.authPasswordField, false, false);
    setAuthField(elements.twoFactorField, false, false);
    setAuthField(elements.resetPasswordField, false, false);
    elements.forgotPasswordButton.hidden = false;
    elements.forgotPasswordButton.textContent = "Retour a la connexion";
    elements.authTitle.textContent = "Mot de passe oublie";
    elements.authSubtitle.textContent = "Indiquez votre adresse e-mail. Nous envoyons un code pour choisir un nouveau mot de passe.";
    elements.authSubmit.firstChild.textContent = "Envoyer le code ";
}

function setResetCodeMode(email) {
    state.authMode = "reset-code";
    state.passwordResetEmail = email;
    state.passwordResetCode = null;
    setAuthField(elements.authEmailField, false, false);
    setAuthField(elements.authPasswordField, false, false);
    setAuthField(elements.twoFactorField, true, true);
    setAuthField(elements.resetPasswordField, false, false);
    setCodeFieldLabel("Code de reinitialisation");
    elements.forgotPasswordButton.hidden = false;
    elements.forgotPasswordButton.textContent = "Retour a la connexion";
    elements.authTitle.textContent = "Code OTP";
    elements.authSubtitle.textContent = `Saisissez le code envoye a ${email}.`;
    elements.authSubmit.firstChild.textContent = "Verifier le code ";
}

function setResetPasswordMode(email, code) {
    state.authMode = "reset-password";
    state.passwordResetEmail = email;
    state.passwordResetCode = code;
    setAuthField(elements.authEmailField, false, false);
    setAuthField(elements.authPasswordField, false, false);
    setAuthField(elements.twoFactorField, false, false);
    setAuthField(elements.resetPasswordField, true, true);
    elements.forgotPasswordButton.hidden = false;
    elements.forgotPasswordButton.textContent = "Retour a la connexion";
    elements.authTitle.textContent = "Nouveau mot de passe";
    elements.authSubtitle.textContent = "Choisissez un nouveau mot de passe pour votre compte.";
    elements.authSubmit.firstChild.textContent = "Modifier le mot de passe ";
}

function toggleForgotPasswordMode() {
    if (state.authMode === "forgot" || state.authMode === "reset-code" || state.authMode === "reset-password") {
        const email = state.passwordResetEmail || elements.authForm.elements.email.value;
        setAuthMode("login");
        elements.authForm.elements.email.value = email || "";
        return;
    }
    setForgotPasswordMode();
}

function requestLogin(message, afterLogin = null) {
    state.pendingAuthAction = afterLogin;
    if (message) {
        showToast(message);
    }
    setAuthMode("login");
    openModal("authModal");
}

async function submitAuth(event) {
    event.preventDefault();
    const formData = new FormData(elements.authForm);
    elements.authError.hidden = true;
    elements.authSubmit.classList.add("loading");

    try {
        let data;
        if (state.authMode === "reset-password") {
            const newPassword = String(formData.get("resetPassword") || "");
            if (newPassword.length < 8) {
                throw new Error("Le nouveau mot de passe doit contenir au moins 8 caracteres.");
            }
            await api("/auth/reset-password", {
                method: "POST",
                body: JSON.stringify({
                    email: state.passwordResetEmail,
                    code: state.passwordResetCode,
                    password: newPassword
                })
            });
            const email = state.passwordResetEmail;
            setAuthMode("login");
            elements.authForm.elements.email.value = email;
            elements.authError.textContent = "Mot de passe modifie. Vous pouvez vous connecter.";
            elements.authError.hidden = false;
            return;
        }
        if (state.authMode === "reset-code") {
            const code = String(formData.get("code") || "").trim();
            if (code.length !== 6) {
                throw new Error("Saisissez le code OTP a 6 chiffres.");
            }
            await api("/auth/reset-password/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: state.passwordResetEmail,
                    code
                })
            });
            setResetPasswordMode(state.passwordResetEmail, code);
            return;
        }
        if (state.authMode === "forgot") {
            const email = String(formData.get("email") || "").trim();
            await api("/auth/forgot-password", {
                method: "POST",
                body: JSON.stringify({ email })
            });
            setResetCodeMode(email);
            return;
        }
        if (state.twoFactorEmail) {
            data = await api("/auth/2fa/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: state.twoFactorEmail,
                    code: formData.get("code")
                })
            });
        } else if (state.emailVerificationEmail) {
            data = await api("/auth/email/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: state.emailVerificationEmail,
                    code: formData.get("code")
                })
            });
        } else {
            const payload = {
                email: formData.get("email"),
                password: formData.get("password")
            };
            data = await api("/auth/login", {
                method: "POST",
                body: JSON.stringify(payload)
            });
        }

        if (data.requiresEmailVerification) {
            state.emailVerificationEmail = data.email;
            setAuthCodeStep("email", data.email);
            return;
        }

        if (data.requiresTwoFactor) {
            state.twoFactorEmail = data.email;
            setAuthCodeStep("2fa", data.email);
            elements.authTitle.textContent = "Vérification en deux étapes";
            elements.authSubtitle.textContent = `Saisissez le code envoyé à ${data.email}.`;
            elements.authSubmit.firstChild.textContent = "Vérifier ";
            return;
        }

        await applySession(data);
        if (state.authMode === "register" && state.requestedPlan) {
            try {
                state.subscription = await api("/billing/change-plan", {
                    method: "POST",
                    body: JSON.stringify({ planCode: state.requestedPlan })
                });
                await refreshProfile();
                updateAccountUi();
            } catch (error) {
                showToast(error.message || "La formule pourra être choisie depuis votre espace.", true);
            }
        }
        const pendingAction = state.pendingAuthAction;
        state.pendingAuthAction = null;
        closeModal("authModal");
        elements.authForm.reset();
        setAuthMode("login");
        window.history.replaceState({}, "", "/watch.html");
        showToast(`Bienvenue ${state.user?.name || ""}.`);
        if (pendingAction) {
            try {
                await pendingAction();
            } catch (error) {
                showToast(error.message || "Action impossible apres connexion.", true);
            }
        }
    } catch (error) {
        elements.authError.textContent = error.message;
        elements.authError.hidden = false;
    } finally {
        elements.authSubmit.classList.remove("loading");
    }
}

async function applySession(data) {
    state.token = data.token;
    localStorage.setItem(TOKEN_KEY, state.token);
    state.user = data.user || null;
    state.organization = data.organization || null;
    state.subscription = data.subscription || null;
    state.iptv = data.iptv || null;
    state.recentlyWatched = loadRecentlyWatched(state.user);
    await refreshProfile();
    state.recentlyWatched = loadRecentlyWatched(state.user);
    updateAccountUi();
    await loadCatalog();
}

async function restoreSession() {
    if (!state.token) {
        updateAccountUi();
        if (state.authRequired) {
            requestLogin("Connectez-vous pour acceder a votre espace Nexora.");
            return;
        }
        await loadCatalog();
        return;
    }

    try {
        await refreshProfile();
        state.recentlyWatched = loadRecentlyWatched(state.user);
        updateAccountUi();
        await loadCatalog();
        restoreActivePlayback();
    } catch {
        clearSession();
        if (state.authRequired) {
            requestLogin("Votre session a expire. Reconnectez-vous.");
            return;
        }
        await loadCatalog();
    }
}

function applyLaunchIntent() {
    const mode = launchParams.get("mode");
    const requestedFilter = launchParams.get("filter");
    if (requestedFilter && ["all", "live", "movie", "series", "drama"].includes(requestedFilter)) {
        window.setTimeout(() => setFilter(requestedFilter), 80);
    }
    if (mode === "register" && !state.token) {
        const signupParams = new URLSearchParams();
        const email = launchParams.get("email");
        const plan = launchParams.get("plan");
        if (email) signupParams.set("email", email);
        if (plan) signupParams.set("plan", plan);
        const query = signupParams.toString();
        window.location.replace(`/signup.html${query ? `?${query}` : ""}`);
        return;
    }
    if (mode !== "login" || state.token) return;
    setAuthMode(mode);
    const email = launchParams.get("email");
    if (email) {
        const emailInput = elements.authForm.querySelector('input[name="email"]');
        emailInput.value = email;
    }
    requestLogin(null);
}

async function refreshProfile() {
    const profile = await api("/auth/me");
    state.user = profile.user;
    state.organization = profile.organization;
    state.subscription = profile.subscription;
    state.iptv = profile.iptv || null;
}

function updateAccountUi() {
    const connected = Boolean(state.user);
    const initial = state.user?.name?.trim().charAt(0).toUpperCase() || "N";
    elements.accountAvatar.textContent = initial;
    document.querySelectorAll(".avatar-choice").forEach((choice) => {
        choice.textContent = initial;
    });
    elements.accountLabel.textContent = connected ? state.user.name : "Connexion";
    elements.adminConsoleLink.hidden = !["SUPER_ADMIN", "ADMIN", "BILLING", "SUPPORT", "OPS"].includes(state.user?.role);
    applyProfileSettings();
    updateCatalogStatus();

    if (!connected) return;

    elements.profileAvatar.textContent = initial;
    elements.profileName.textContent = state.user.name;
    elements.profileEmail.textContent = state.user.email;
    elements.profileOrganization.textContent = state.organization?.name || "Espace personnel";
    elements.profilePlan.textContent = state.subscription?.plan?.name || "Aucune formule";
    elements.profileStatus.textContent = statusLabel(state.subscription?.status);
    fillSettingsForms();
    renderBillingSettings();
}

function updateCatalogStatus() {
    const connected = Boolean(state.user);
    elements.catalogStatus.classList.toggle("connected", connected);
    elements.catalogStatus.classList.toggle("loading", state.catalogLoading);
    elements.catalogStatus.lastChild.textContent = state.catalogLoading
        ? " Synchronisation du catalogue..."
        : connected
            ? " Catalogue synchronisé"
            : " Mode découverte";
}

function clearSession() {
    state.token = null;
    state.user = null;
    state.organization = null;
    state.subscription = null;
    state.iptv = null;
    state.languages = [];
    state.activeLanguage = "";
    state.pendingAuthAction = null;
    state.recentlyWatched = loadRecentlyWatched();
    localStorage.removeItem(TOKEN_KEY);
    updateAccountUi();
    renderLanguageFilter();
}

async function logout() {
    try {
        await api("/auth/logout", { method: "POST" });
    } catch {
        // The local session must still be cleared when the token has expired.
    }
    clearSession();
    closeModal("profileModal");
    state.catalogIsFallback = true;
    state.browseCatalog = [...fallbackCatalog];
    state.catalog = [...fallbackCatalog];
    resetSearchQuery(false);
    window.location.href = "/";
}

function switchSettingsTab(tab) {
    document.querySelectorAll("[data-settings-tab]").forEach((button) => {
        button.classList.toggle("active", button.dataset.settingsTab === tab);
    });
    document.querySelectorAll("[data-settings-panel]").forEach((panel) => {
        panel.classList.toggle("active", panel.dataset.settingsPanel === tab);
    });
}

async function submitProfileSettings(event) {
    event.preventDefault();
    const formData = new FormData(elements.profileSettingsForm);
    const localSettings = {
        avatarTone: formData.get("avatarTone") || "gold",
        language: formData.get("language") || "fr"
    };

    try {
        const user = await api("/auth/profile", {
            method: "PATCH",
            body: JSON.stringify({
                name: formData.get("name"),
                email: formData.get("email")
            })
        });
        state.user = user;
        saveProfileSettings(localSettings);
        updateAccountUi();
        showToast("Profil mis à jour.");
    } catch (error) {
        showToast(error.message, true);
    }
}

function submitPlaybackSettings(event) {
    event.preventDefault();
    const formData = new FormData(elements.playbackSettingsForm);
    saveProfileSettings({
        quality: formData.get("quality") || "auto",
        subtitles: formData.get("subtitles") || "off",
        autoplay: elements.settingsAutoplay.checked,
        compactCards: elements.settingsCompactCards.checked
    });
    showToast("Préférences de lecture enregistrées.");
}

async function submitPasswordSettings(event) {
    event.preventDefault();
    const formData = new FormData(elements.passwordSettingsForm);

    try {
        await api("/auth/password", {
            method: "POST",
            body: JSON.stringify({
                currentPassword: formData.get("currentPassword"),
                newPassword: formData.get("newPassword")
            })
        });
        elements.passwordSettingsForm.reset();
        showToast("Mot de passe modifié.");
    } catch (error) {
        showToast(error.message, true);
    }
}

async function toggleTwoFactor(event) {
    const enabled = event.target.checked;
    event.target.disabled = true;

    try {
        await api(`/auth/2fa/${enabled ? "enable" : "disable"}`, { method: "POST" });
        state.user.twoFactorEnabled = enabled;
        showToast(enabled ? "Validation en deux étapes activée." : "Validation en deux étapes désactivée.");
    } catch (error) {
        event.target.checked = !enabled;
        showToast(error.message, true);
    } finally {
        event.target.disabled = false;
    }
}

async function logoutAllDevices() {
    try {
        await api("/auth/logout-all", { method: "POST" });
    } catch (error) {
        showToast(error.message, true);
        return;
    }

    clearSession();
    closeModal("profileModal");
    state.catalogIsFallback = true;
    state.browseCatalog = [...fallbackCatalog];
    state.catalog = [...fallbackCatalog];
    resetSearchQuery(false);
    window.location.href = "/";
}

function handleAccountClick() {
    if (state.user) {
        updateAccountUi();
        openModal("profileModal");
    } else {
        requestLogin("Connectez-vous pour ouvrir votre espace client.", handleAccountClick);
    }
}

function selectCatalogItem(item) {
    if (item.type === "series" && !item.isEpisode) {
        openSeries(item);
        return;
    }
    if (item.type === "movie") {
        openMovieDetails(item);
        return;
    }
    playItem(item);
}

async function openMovieDetails(item) {
    if (!state.token) {
        requestLogin("Connectez-vous pour consulter la fiche du film.", () => openMovieDetails(item));
        return;
    }

    state.activeDetail = item;
    renderMovieDetails(item, true);
    openModal("detailModal");

    if (item.externalPlayback || isFrenchSource(item)) {
        state.activeDetail = {
            ...item,
            summary: item.summary || item.description || "Ce film est disponible via les sources FR connectees a Nexora.",
            streamAvailable: item.streamAvailable !== false
        };
        renderMovieDetails(state.activeDetail, false);
        return;
    }

    try {
        const details = await api(`/catalog/items/${encodeURIComponent(item.id)}`);
        state.activeDetail = {
            ...item,
            ...details,
            image: details.poster || item.image
        };
        renderMovieDetails(state.activeDetail, false);
    } catch (error) {
        const reason = error.message || "La fiche détaillée est indisponible.";
        state.activeDetail = {
            ...item,
            streamAvailable: false,
            streamUnavailableReason: reason,
            summary: reason
        };
        renderMovieDetails(state.activeDetail, false);
        showToast(reason, true);
    }
}

function renderMovieDetails(item, loading) {
    elements.detailTitle.textContent = item.name;
    elements.detailCategory.textContent = item.categoryName || "FILM";
    elements.detailSummary.textContent = item.summary
        || "Le fournisseur n’a pas encore renseigné de résumé pour ce film.";

    const metadata = [
        item.releaseYear || releaseYearFromDate(item.releaseDate),
        item.duration,
        item.rating ? `★ ${item.rating}/10` : "",
        item.ageRating
    ].filter(Boolean);
    elements.detailMeta.innerHTML = metadata.map((value) => (
        `<span>${escapeHtml(value)}</span>`
    )).join("");

    const facts = [
        ["Genres", joinedMetadata(item.genres)],
        ["Réalisation", joinedMetadata(item.directors)],
        ["Acteurs", joinedMetadata(item.cast, 10)],
        ["Pays", item.country]
    ].filter(([, value]) => value);
    elements.detailFacts.innerHTML = facts.map(([label, value]) => `
        <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(value)}</dd>
        </div>
    `).join("");
    elements.detailFacts.hidden = !facts.length;
    setDetailArtwork(item.backdrop || item.image, item.poster || item.image);
    elements.detailLoading.hidden = !loading;
    const playable = item.streamAvailable !== false || isTmdbPlayable(item);
    elements.detailPlay.disabled = loading || !playable;
    elements.detailPlay.innerHTML = playable
        ? `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7z"></path></svg> Regarder`
        : "Catalogue uniquement";
    if (!playable && item.streamUnavailableReason) {
        elements.detailSummary.textContent = item.streamUnavailableReason;
    }
}

function setDetailArtwork(backdropValue, posterValue) {
    const poster = posterValue || "/assets/images/poster-1.jpg";
    const backdrop = backdropValue || poster || "/assets/images/hero.jpg";
    setResilientImage(elements.detailPoster, poster, "/assets/images/poster-1.jpg");
    setResilientImage(elements.detailBackdrop, backdrop, "/assets/images/hero.jpg");
}

function joinedMetadata(value, limit = 6) {
    const values = Array.isArray(value)
        ? value
        : String(value || "").split(/\s*[,|]\s*/);
    return values.map((entry) => String(entry).trim()).filter(Boolean).slice(0, limit).join(", ");
}

function releaseYearFromDate(value) {
    return String(value || "").match(/(?:19|20)\d{2}/)?.[0] || "";
}

async function openSeries(item) {
    if (!state.token) {
        requestLogin("Connectez-vous pour consulter les episodes.", () => openSeries(item));
        return;
    }

    state.activeSeries = null;
    elements.seriesTitle.textContent = item.name;
    elements.seriesCategory.textContent = item.categoryName || "SÉRIE";
    elements.seriesSummary.textContent = "Organisation des saisons et épisodes.";
    elements.seriesSeasonCount.textContent = "— saison";
    elements.seriesEpisodeCount.textContent = "— épisode";
    elements.seriesRating.hidden = true;
    elements.seriesRelease.hidden = true;
    elements.seriesCredits.hidden = true;
    setSeriesArtwork(item.image);
    elements.seriesLoading.hidden = false;
    elements.seriesContent.hidden = true;
    elements.seriesError.hidden = true;
    openModal("seriesModal");

    try {
        const nodeSeries = (item.externalPlayback || isFrenchSource(item)) && tmdbIdFromItem(item);
        const series = nodeSeries
            ? await nodeApi(`/catalog/series/${encodeURIComponent(nodeSeries)}`)
            : await api(
                `/catalog/series/${encodeURIComponent(item.id)}?title=${encodeURIComponent(item.name || "")}`
            );
        state.activeSeries = series;
        renderSeriesDetails(series, item.image);
    } catch (error) {
        elements.seriesLoading.hidden = true;
        elements.seriesContent.hidden = true;
        elements.seriesError.textContent = error.message || "Impossible de charger les épisodes de cette série.";
        elements.seriesError.hidden = false;
    }
}

function setSeriesArtwork(posterValue, backdropValue) {
    const poster = posterValue || "/assets/images/poster-2.jpg";
    const backdrop = backdropValue || poster || "/assets/images/hero.jpg";
    setResilientImage(elements.seriesPoster, poster, "/assets/images/poster-2.jpg");
    setResilientImage(elements.seriesBackdrop, backdrop, "/assets/images/hero.jpg");
}

function renderSeriesDetails(series, fallbackImage) {
    const seasons = series.seasons || [];
    const seasonCount = Number(series.seasonCount ?? seasons.length);
    const episodeCount = Number(series.episodeCount ?? seasons.reduce((total, season) => total + (season.episodes?.length || 0), 0));

    elements.seriesTitle.textContent = series.name;
    elements.seriesCategory.textContent = series.categoryName || "SÉRIE";
    elements.seriesSummary.textContent = series.summary || (episodeCount
        ? "Choisissez une saison, puis lancez l’épisode que vous souhaitez regarder."
        : "Aucun épisode n’est disponible pour le moment.");
    elements.seriesSeasonCount.textContent = `${seasonCount} saison${seasonCount > 1 ? "s" : ""}`;
    elements.seriesEpisodeCount.textContent = `${episodeCount} épisode${episodeCount > 1 ? "s" : ""}`;
    setOptionalText(elements.seriesRating, series.rating ? `★ ${series.rating}/10` : "");
    setOptionalText(elements.seriesRelease, series.releaseYear || releaseYearFromDate(series.releaseDate));
    const credits = [
        ["Genres", joinedMetadata(series.genres)],
        ["Création", joinedMetadata(series.directors)],
        ["Acteurs", joinedMetadata(series.cast, 8)]
    ].filter(([, value]) => value);
    elements.seriesCredits.innerHTML = credits.map(([label, value]) => `
        <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(value)}</dd>
        </div>
    `).join("");
    elements.seriesCredits.hidden = !credits.length;
    setSeriesArtwork(series.poster || fallbackImage, series.backdrop);
    elements.seriesSeasonSelect.innerHTML = seasons.map((season) => (
        `<option value="${season.season}">${escapeHtml(season.name || `Saison ${season.season}`)} (${season.episodeCount ?? season.episodes?.length ?? 0})</option>`
    )).join("");
    elements.seriesLoading.hidden = true;
    elements.seriesError.hidden = true;
    elements.seriesContent.hidden = false;

    if (seasons.length) {
        renderSeriesSeason(seasons[0].season);
    } else {
        elements.seasonTitle.textContent = "Aucun épisode";
        elements.episodeList.innerHTML = "";
    }
}

function renderSeriesSeason(seasonNumber) {
    const series = state.activeSeries;
    if (!series) return;
    const season = (series.seasons || []).find((item) => String(item.season) === String(seasonNumber));
    if (!season) return;

    elements.seriesSeasonSelect.value = String(season.season);
    elements.seasonTitle.textContent = season.name || `Saison ${season.season}`;
    elements.episodeList.innerHTML = (season.episodes || []).map((episode) => {
        const image = normalizeImageSource(
            episode.poster || series.poster,
            "/assets/images/landscape-1.jpg"
        );
        const episodeNumber = Number(episode.episode || 1);
        const episodeName = seriesEpisodeDisplayName(episode, episodeNumber);
        const episodeMetadata = [
            `S${String(season.season).padStart(2, "0")} · E${String(episodeNumber).padStart(2, "0")}`,
            episode.duration,
            episode.rating ? `★ ${episode.rating}` : ""
        ].filter(Boolean).join(" · ");
        return `
            <button class="episode-row" type="button" data-episode-id="${escapeHtml(seriesEpisodeKey(season.season, episode))}" aria-label="Regarder ${escapeHtml(episodeName)}">
                <span class="episode-number">${episodeNumber}</span>
                <span class="episode-thumb">
                    <img src="${escapeHtml(image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
                </span>
                <span class="episode-copy">
                    <strong>${escapeHtml(episodeName)}</strong>
                    <span>${escapeHtml(episodeMetadata)}</span>
                    ${episode.summary ? `<p>${escapeHtml(episode.summary)}</p>` : ""}
                </span>
                <span class="episode-play" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>
                </span>
            </button>
        `;
    }).join("");
}

function setOptionalText(element, value) {
    element.textContent = value;
    element.hidden = !value;
}

function playSeriesEpisode(episodeId) {
    const series = state.activeSeries;
    if (!series) return;
    const resolved = findSeriesEpisodeByKey(series, episodeId);
    if (!resolved) return;
    const { season, episode } = resolved;
    const seasonNumber = positiveInteger(season.season) || positiveInteger(episode.season) || 1;
    const episodeNumber = positiveInteger(episode.episode) || 1;
    const episodeName = seriesEpisodeDisplayName(episode, episodeNumber);
    const tmdbEpisode = isTmdbPlayable(episode) || isTmdbPlayable(series);
    if ((episode.streamAvailable === false || series.streamAvailable === false) && !tmdbEpisode) {
        showToast("Cet add-on fournit les informations sans flux de lecture.", true);
        return;
    }

    closeModal("seriesModal");
    playItem({
        ...episode,
        type: "series",
        isEpisode: true,
        season: seasonNumber,
        episode: episodeNumber,
        tmdbId: tmdbIdFromItem(episode) || tmdbIdFromItem(series) || undefined,
        source: episode.source || series.source,
        sourceCode: episode.sourceCode || series.sourceCode,
        playbackProvider: episode.playbackProvider || series.playbackProvider,
        playbackProviderName: episode.playbackProviderName || series.playbackProviderName,
        externalPlayback: episode.externalPlayback || series.externalPlayback,
        streamAvailable: tmdbEpisode ? true : episode.streamAvailable,
        name: `${series.name} · ${episodeName}`,
        categoryName: series.categoryName,
        image: normalizeImageSource(
            episode.poster || series.poster,
            "/assets/images/landscape-1.jpg"
        )
    });
}

async function playItem(item, options = {}) {
    if (!state.token) {
        requestLogin("Connectez-vous pour lancer un programme.", () => playItem(item, options));
        return;
    }

    if (state.catalogLoading && state.catalogIsFallback) {
        showToast("Le catalogue est encore en cours de synchronisation.", true);
        return;
    }

    if (state.catalogIsFallback) {
        showToast("Ce programme est un aperçu sans flux vidéo. Rechargez le catalogue.", true);
        return;
    }
    if (isTmdbPlayable(item)) {
        await playTmdbItem(item);
        return;
    }
    if (item.streamAvailable === false && !isTmdbPlayable(item)) {
        showToast(item.streamUnavailableReason || "Ce contenu n'a pas de flux IPTV actif disponible.", true);
        return;
    }
    if (state.playerOpening) return;
    trackRecentlyWatched(item);
    saveActivePlayback(item);
    state.playerOpening = true;
    setPlayerControlsBusy(true);
    state.playerErrorShown = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    state.playerRecoveryShouldFailover = false;
    clearPlayerRecoveryTimer();
    state.activePlayerItem = item;
    const requestedQuality = requestedPlaybackQuality(item, options);
    elements.playerQuality.value = requestedQuality;
    await ensurePlayerContext(item);

    openModal("playerModal");
    elements.playerTitle.textContent = item.name;
    elements.playerBadge.textContent = isEpornerSource(item)
        ? "18+"
        : item.type === "live" ? "DIRECT" : typeLabel(item.type).toUpperCase();
    refreshPlayerRoom(item, elements.playerBadge.textContent);
    state.playerHasStarted = false;
    setPlayerLoading("Préparation de votre programme...", "Connexion au flux sécurisé Nexora.");

    try {
        if (state.activeSessionToken) {
            await stopPlayer();
        }
        if (shouldUseNodeFrenchPlayback(item)) {
            try {
                await playNodeFrenchItem(item);
                return;
            } catch (directError) {
                const opened = await switchToUniversalEmbedFallback(
                    directError.message || "Aucune source FR disponible; bascule vers Videasy."
                );
                if (!opened) {
                    detachPlayerMedia();
                    showPlayerError(directError.message || "Source FR directe indisponible.");
                }
                return;
            }
        }
        const stream = await api("/stream/open", {
            method: "POST",
            body: JSON.stringify({
                type: item.type,
                itemId: item.id,
                quality: requestedQuality
            })
        });
        await startStreamFromPayload(item, stream, requestedQuality);
    } catch (error) {
        if (["movie", "series"].includes(item.type) && videasyUrlForItem(item)) {
            const opened = await switchToUniversalEmbedFallback(
                playerOpenErrorMessage(error, item) || "Aucune source directe disponible; bascule vers Videasy."
            );
            if (opened) return;
        }
        detachPlayerMedia();
        showPlayerError(playerOpenErrorMessage(error, item));
        showPlayerError(error.message || "Ce flux est momentanément indisponible.");
    } finally {
        state.playerOpening = false;
        setPlayerControlsBusy(false);
    }
}

async function changePlayerQuality(event) {
    const requestedQuality = event.target.value || "auto";
    const item = state.activePlayerItem;
    const previousQuality = state.activePlaybackQuality || state.profileSettings.quality || "auto";
    if (!item || !state.activeSessionToken) {
        saveProfileSettings({ quality: requestedQuality });
        return;
    }
    if (state.playerOpening || state.playerRecovering) {
        event.target.value = previousQuality;
        showToast("Patientez, le lecteur change deja de flux.");
        return;
    }

    saveProfileSettings({ quality: requestedQuality });
    state.playerOpening = true;
    state.playerErrorShown = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    state.playerRecoveryShouldFailover = false;
    clearPlayerRecoveryTimer();
    setPlayerControlsBusy(true);

    setPlayerLoading(
        `Passage en ${qualityLabel(requestedQuality)}...`,
        "Le flux redémarre avec le nouveau débit."
    );
    try {
        detachPlayerMedia();
        await settleDetachedPlayer();
        await restartActiveSessionWithQuality(item, requestedQuality);
    } catch (error) {
        if (requestedQuality !== "auto") {
            try {
                const fallback = await fallbackActiveSessionToAuto(
                    `${qualityLabel(requestedQuality)} ne demarre pas, retour en Auto.`
                );
                if (fallback) return;
            } catch (fallbackError) {
                elements.playerQuality.value = previousQuality;
                saveProfileSettings({ quality: previousQuality });
                showPlayerError(fallbackError.message || error.message || "Impossible de changer la qualite.");
                return;
            }
        }
        elements.playerQuality.value = previousQuality;
        saveProfileSettings({ quality: previousQuality });
        showPlayerError(error.message || "Impossible de changer la qualite.");
    } finally {
        state.playerOpening = false;
        setPlayerControlsBusy(false);
    }
}

function setPlayerControlsBusy(isBusy) {
    elements.playerQuality.disabled = Boolean(isBusy) || state.activePlaybackMode === "embed";
}

function hasActivePlayback() {
    return Boolean(state.activeSessionToken || state.activePlaybackMode || state.activeProxyUrl || state.activeEmbedUrl);
}

function requestedPlaybackQuality(item, options = {}) {
    if (options.quality) return options.quality;
    const savedQuality = state.profileSettings.quality || "auto";
    return item?.type === "live" ? savedQuality : "auto";
}

function shouldUseNodeFrenchPlayback(item) {
    return isFrenchSource(item) && ["movie", "series"].includes(item?.type);
}

function isNodeFrenchPlayerItem(item = state.activePlayerItem) {
    return item?.playbackProvider === "node-fr" || item?.playbackProvider === "node-fr-embed";
}

function nodeFrenchEndpointForItem(item) {
    const tmdbId = tmdbIdFromItem(item);
    if (!tmdbId) return "";
    if (item.type === "movie") {
        return `/sources/movie/${tmdbId}?provider=${encodeURIComponent(NODE_MOVIE_PROVIDER)}`;
    }
    if (item.type === "series") {
        const season = positiveInteger(item.season);
        const episode = positiveInteger(item.episode);
        if (!season || !episode) return "";
        return `/sources/series/${tmdbId}/${season}/${episode}`;
    }
    return "";
}

function nodeTmdbEmbedFallbackEndpointForItem(item) {
    const tmdbId = tmdbIdFromItem(item);
    if (!tmdbId) return "";
    if (item.type === "movie") {
        return `/sources/movie/${tmdbId}?provider=tmdbembed&allowNonFrench=1`;
    }
    if (item.type === "series") {
        const season = positiveInteger(item.season);
        const episode = positiveInteger(item.episode);
        if (!season || !episode) return "";
        return `/sources/series/${tmdbId}/${season}/${episode}?provider=tmdbembed&allowNonFrench=1`;
    }
    return "";
}

function bestNodeFrenchHoster(hosters) {
    if (!Array.isArray(hosters)) return null;
    const usable = hosters.filter((hoster) => (
        hoster?.proxyM3U8 || hoster?.proxyM3u8 || hoster?.m3u8 || hoster?.directUrl || hoster?.embedUrl
    ));
    return usable.find((hoster) => (
        /truefrench|vf|fr/i.test(`${hoster.lang || ""} ${hoster.nom || ""}`)
    )) || usable[0] || null;
}

function nodeFrenchStreamFromSource(source) {
    const hoster = bestNodeFrenchHoster(source?.hosters);
    if (!hoster) {
        throw new Error(source?.erreur || "Aucune source FR exploitable.");
    }
    const embedUrl = hoster.embedUrl || "";
    const directUrl = hoster.directUrl || hoster.videoUrl || "";
    let hlsUrl = hoster.proxyM3U8 || hoster.proxyM3u8 || "";
    if (!hlsUrl && hoster.m3u8) {
        const params = new URLSearchParams({ url: hoster.m3u8 });
        if (embedUrl) params.set("referer", embedUrl);
        hlsUrl = `/api/proxy/m3u8?${params}`;
    }
    const selectedUrl = hlsUrl || directUrl || embedUrl;
    if (!selectedUrl) {
        throw new Error("La source FR ne fournit pas d'URL de lecture.");
    }
    return {
        proxyUrl: hlsUrl ? resolveNodeApiResourceUrl(hlsUrl) : directUrl || embedUrl,
        playbackMode: hlsUrl ? "hls" : directUrl ? "direct" : "embed",
        embedUrl: embedUrl ? resolveNodeApiResourceUrl(embedUrl) : "",
        preferredAudioLanguage: hoster.preferredAudioLanguage || (hoster.canSelectFrenchAudio ? "fr" : ""),
        preferredSubtitleLanguage: hoster.preferredSubtitleLanguage || (hoster.canSelectFrenchSubtitles ? "fr" : ""),
        hoster
    };
}

async function playNodeFrenchItem(item) {
    if (!nodeApiEnabled()) {
        throw new Error("API Node FR non configurée.");
    }
    const endpoint = nodeFrenchEndpointForItem(item);
    if (!endpoint) {
        throw new Error("Identifiant TMDB FR incomplet.");
    }

    state.activeSessionToken = null;
    state.activeCanFailover = false;
    state.activePlaybackQuality = "auto";
    elements.playerQuality.value = "auto";
    stopHeartbeat();
    elements.playerBadge.textContent = "FR";
    setPlayerLoading(
        "Recherche de la source FR...",
        "Connexion directe a l'API Node: Orion puis Aether."
    );

    let source;
    try {
        source = await nodeApi(endpoint);
    } catch (strictError) {
        const fallbackEndpoint = nodeTmdbEmbedFallbackEndpointForItem(item);
        if (!fallbackEndpoint) throw strictError;
        source = await nodeApi(fallbackEndpoint);
        await confirmNonFrenchPlayback(source);
    }
    elements.playerBadge.textContent = source.requiresLanguageConfirmation ? "VO" : "FR";
    const stream = nodeFrenchStreamFromSource(source);
    const sourceLabel = [
        stream.hoster.nom || "Source FR",
        stream.hoster.lang || ""
    ].filter(Boolean).join(" - ");

    setPlayerLoading(
        "Ouverture de la source FR...",
        sourceLabel || "Lecture directe depuis l'API Node."
    );
    await startStreamPlayback(
        { ...item, playbackProvider: "node-fr", playbackProviderName: sourceLabel },
        stream.proxyUrl,
        stream.playbackMode,
        {
            embedFallbackUrl: stream.playbackMode === "hls" ? stream.embedUrl : "",
            embedFallbackLabel: sourceLabel,
            preferredAudioLanguage: stream.preferredAudioLanguage,
            preferredSubtitleLanguage: stream.preferredSubtitleLanguage,
            visualWatch: true
        }
    );
    if (stream.playbackMode === "embed") {
        elements.playerMessage.textContent = "Lecteur FR ouvert depuis l'API Node.";
    } else {
        elements.playerMessage.textContent = "Lecture FR via l'API Node.";
    }
}

async function playTmdbItem(item) {
    if (!state.token) {
        requestLogin("Connectez-vous pour lancer un programme TMDB.", () => playTmdbItem(item));
        return;
    }

    const playerUrl = videasyUrlForItem(item);
    if (!playerUrl) {
        showToast("Choisissez un episode TMDB pour lancer cette serie.", true);
        return;
    }
    if (state.playerOpening) return;

    trackRecentlyWatched(item);
    saveActivePlayback(item);
    state.playerOpening = true;
    setPlayerControlsBusy(true);
    state.playerErrorShown = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    state.playerRecoveryShouldFailover = false;
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();

    try {
        await stopPlayer();
        state.activePlayerItem = item;
        state.activePlaybackQuality = "auto";
        elements.playerQuality.value = "auto";
        await ensurePlayerContext(item);

        openModal("playerModal");
        elements.playerTitle.textContent = item.name || "Programme TMDB";
        elements.playerBadge.textContent = "TMDB";
        refreshPlayerRoom(item, "TMDB");
        setPlayerLoading(
            "Ouverture du lecteur TMDB...",
            "Injection automatique du code TMDB dans Videasy."
        );
        await startStreamPlayback(item, playerUrl, "embed");
        if (state.embedRequiresUserLaunch) {
            elements.playerMessage.textContent = "Lecteur TMDB pret. Lancez-le depuis le bouton affiche.";
        } else {
            elements.playerMessage.textContent = "Lecture Videasy ouverte. Si le lecteur affiche une erreur, la source n'est pas disponible chez Videasy.";
        }
    } catch (error) {
        detachPlayerMedia();
        showPlayerError(error.message || "Impossible d'ouvrir le lecteur Videasy.");
    } finally {
        state.playerOpening = false;
        setPlayerControlsBusy(false);
    }
}

function videasyUrlForItem(item) {
    const tmdbId = tmdbIdFromItem(item);
    if (!tmdbId) return "";

    const parameters = new URLSearchParams({
        color: VIDEASY_ACCENT_COLOR
    });

    if (item?.type === "series") {
        const season = positiveInteger(item.season);
        const episode = positiveInteger(item.episode);
        if (!season || !episode) return "";
        parameters.set("nextEpisode", "true");
        parameters.set("episodeSelector", "true");
        if (!isMobileEmbedEnvironment()) {
            parameters.set("autoplayNextEpisode", "true");
        }
        return `${VIDEASY_PLAYER_BASE_URL}/tv/${tmdbId}/${season}/${episode}?${parameters}`;
    }

    return `${VIDEASY_PLAYER_BASE_URL}/movie/${tmdbId}?${parameters}`;
}

function isTmdbPlayable(value) {
    return value?.playbackProvider === "videasy"
        || isTmdbSource(value)
        || isTmdbPublicId(value?.id);
}

function tmdbIdFromItem(value) {
    const directId = positiveInteger(value?.tmdbId);
    if (directId) return directId;
    const match = String(value?.id || "").match(/^(?:tmdb|orion)~(?:movie|series)~(\d+)/);
    return match ? positiveInteger(match[1]) : 0;
}

function isTmdbPublicId(value) {
    return /^tmdb~(?:movie|series)~\d+/.test(String(value || ""));
}

function positiveInteger(value) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? Math.trunc(number) : 0;
}

async function restartActiveSessionWithQuality(item, requestedQuality) {
    const stream = await api(`/stream/quality/${state.activeSessionToken}`, {
        method: "POST",
        body: JSON.stringify({ quality: requestedQuality }),
        retryTransient: true
    });
    await startStreamFromPayload(item, stream, requestedQuality);
}

async function fallbackActiveSessionToAuto(message) {
    if (!state.activePlayerItem || !state.activeSessionToken) {
        return false;
    }
    const previousQuality = state.activePlaybackQuality;
    setPlayerLoading("Retour en mode automatique...", message || "La qualite forcee ne demarre pas.");
    elements.playerQuality.value = "auto";
    saveProfileSettings({ quality: "auto" });
    detachPlayerMedia();
    await settleDetachedPlayer();
    await restartActiveSessionWithQuality(state.activePlayerItem, "auto");
    showToast(message || `${qualityLabel(previousQuality)} instable, retour en Auto.`);
    return true;
}

async function startStreamFromPayload(item, stream, requestedQuality) {
    applyStreamPayload(stream, requestedQuality);
    setPlayerLoading("Verification du flux...", "Nexora choisit la source la plus stable.");
    const verifiedStream = await preflightActiveStream(requestedQuality);
    if (verifiedStream.preflightOk === false && !state.activeCanFailover) {
        const error = new Error(playerOpenErrorMessage({
            status: 503,
            code: verifiedStream.preflightCode,
            message: verifiedStream.preflightMessage
        }, item));
        error.status = 503;
        error.code = verifiedStream.preflightCode;
        throw error;
    }
    startHeartbeat();
    setPlayerLoading("Ouverture du flux...", "Connexion directe a la source video.");
    await startStreamPlayback(
        item,
        verifiedStream.proxyUrl,
        verifiedStream.playbackMode
    );
}

function applyStreamPayload(stream, requestedQuality) {
    state.activeSessionToken = stream.token || state.activeSessionToken;
    if (stream.proxyUrl) {
        state.activeProxyUrl = stream.proxyUrl;
    }
    if (stream.playbackMode) {
        state.activePlaybackMode = stream.playbackMode;
    }
    state.activeCanFailover = Boolean(stream.canFailover);
    state.activePlaybackQuality = stream.quality || requestedQuality || "auto";
    elements.playerQuality.value = state.activePlaybackQuality;
    if (requestedQuality && state.activePlaybackQuality !== requestedQuality) {
        showToast(`${qualityLabel(requestedQuality)} indisponible, lecture en ${qualityLabel(state.activePlaybackQuality)}.`);
    }
}

async function preflightActiveStream(requestedQuality) {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), STREAM_PREFLIGHT_TIMEOUT_MS);
    try {
        const stream = await api(`/stream/preflight/${state.activeSessionToken}`, {
            method: "POST",
            signal: controller.signal
        });
        applyStreamPayload(stream, requestedQuality || stream.quality || "auto");
        return stream;
    } catch (error) {
        const aborted = error?.name === "AbortError" || controller.signal.aborted;
        const transient = error?.status === 502 || error?.status === 503 || error?.status === 504;
        if ((transient || aborted) && !state.activeCanFailover) {
            const unavailable = new Error("Le fournisseur video ne repond pas pour le moment.");
            unavailable.status = error?.status || 503;
            unavailable.code = error?.code || "provider_unavailable";
            throw unavailable;
        }
        if ((!transient && !aborted) || !state.activeProxyUrl || !state.activePlaybackMode) {
            throw error;
        }
        elements.playerMessage.textContent = "Verification distante trop lente, ouverture directe du flux.";
        return {
            token: state.activeSessionToken,
            proxyUrl: state.activeProxyUrl,
            playbackMode: state.activePlaybackMode,
            quality: state.activePlaybackQuality || requestedQuality || "auto"
        };
    } finally {
        window.clearTimeout(timeoutId);
    }
}

function settleDetachedPlayer() {
    return delay(180);
}

function qualityLabel(quality) {
    return {
        data: "480p",
        hd: "720p",
        fullhd: "1080p",
        uhd: "qualité originale",
        auto: "mode automatique"
    }[quality] || "mode automatique";
}

function dashElementsByLocalName(root, localName) {
    return Array.from(root.getElementsByTagName("*"))
        .filter((element) => element.localName === localName || element.nodeName === localName);
}

function dashCodecLooksVideo(codec) {
    return /^(avc1|avc3|hev1|hvc1|vp09|av01)/i.test(codec || "");
}

function dashCodecLabel(codec) {
    if (/^(hev1|hvc1)/i.test(codec || "")) return "HEVC/H.265";
    if (/^(avc1|avc3)/i.test(codec || "")) return "H.264";
    if (/^vp09/i.test(codec || "")) return "VP9";
    if (/^av01/i.test(codec || "")) return "AV1";
    return codec || "codec inconnu";
}

function dashVideoTracks(manifest) {
    const adaptationSets = dashElementsByLocalName(manifest, "AdaptationSet");
    const tracks = [];
    adaptationSets.forEach((set) => {
        const setMime = set.getAttribute("mimeType") || "";
        const setCodecs = set.getAttribute("codecs") || "";
        const representations = dashElementsByLocalName(set, "Representation");
        const looksVideo = set.getAttribute("contentType") === "video"
            || setMime.startsWith("video/")
            || dashCodecLooksVideo(setCodecs)
            || representations.some((representation) => dashCodecLooksVideo(
                representation.getAttribute("codecs") || setCodecs
            ));
        if (!looksVideo) return;
        const values = representations.length ? representations : [set];
        values.forEach((representation) => {
            const mimeType = representation.getAttribute("mimeType") || setMime || "video/mp4";
            const codecs = representation.getAttribute("codecs") || setCodecs;
            if (mimeType && codecs) {
                tracks.push({ mimeType, codecs });
            }
        });
    });
    return tracks;
}

async function assertDashPlaybackSupported(streamUrl) {
    if (!window.MediaSource?.isTypeSupported) {
        throw new Error("Ce navigateur ne prend pas en charge le lecteur DASH requis pour ce flux.");
    }
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), DASH_MANIFEST_TIMEOUT_MS);
    let response;
    let manifestText;
    try {
        response = await fetch(streamUrl, {
            cache: "no-store",
            credentials: "same-origin",
            signal: controller.signal
        });
        if (!response.ok) {
            throw new Error(`Le manifeste DASH ne repond pas (HTTP ${response.status}).`);
        }
        manifestText = await response.text();
    } catch (error) {
        if (error?.name === "AbortError") {
            throw new Error("Le manifeste DASH met trop de temps a repondre.");
        }
        throw error;
    } finally {
        window.clearTimeout(timeout);
    }
    const manifest = new DOMParser().parseFromString(manifestText, "application/xml");
    if (manifest.querySelector("parsererror")) {
        throw new Error("Le manifeste DASH renvoye par le fournisseur est illisible.");
    }
    const tracks = dashVideoTracks(manifest);
    if (!tracks.length) return;
    if (tracks.some((track) => window.MediaSource.isTypeSupported(
        `${track.mimeType}; codecs="${track.codecs}"`
    ))) {
        return;
    }
    const codecNames = Array.from(new Set(tracks.map((track) => dashCodecLabel(track.codecs))));
    throw new Error(
        `Ce flux video utilise ${codecNames.join(", ")}, un format que ce navigateur ne sait pas lire ici.`
    );
}

async function waitForDashPlayAttempt(playPromise, generation) {
    if (!playPromise || typeof playPromise.then !== "function") return;
    let timedOut = false;
    playPromise.catch((error) => {
        if (!timedOut || generation !== state.playerGeneration || state.playerHasStarted || state.playerErrorShown) {
            return;
        }
        showPlayerError(error.message || "Impossible de demarrer le flux video.");
    });
    const result = await Promise.race([
        playPromise.then(() => "settled"),
        delay(DASH_PLAY_PROMISE_TIMEOUT_MS).then(() => "timeout")
    ]);
    timedOut = result === "timeout";
}

function isPlaybackPermissionError(error) {
    const message = String(error?.message || "").toLowerCase();
    return error?.name === "NotAllowedError"
        || message.includes("request is not allowed")
        || message.includes("not allowed by the user")
        || message.includes("denied permission")
        || message.includes("user denied permission")
        || message.includes("current context")
        || message.includes("user didn't interact")
        || message.includes("user did not interact");
}

async function attemptPlaybackPromise(playPromise, generation, options = {}) {
    try {
        if (typeof playPromise === "function") {
            playPromise = playPromise();
        }
        if (options.waitForAttempt) {
            await waitForDashPlayAttempt(playPromise, generation);
        } else if (playPromise && typeof playPromise.then === "function") {
            await playPromise;
        }
        return true;
    } catch (error) {
        if (isPlaybackPermissionError(error)) {
            showNativePlaybackLaunchPanel();
            return false;
        }
        throw error;
    }
}

function attemptStreamPlayerPlay(generation, options = {}) {
    return attemptPlaybackPromise(() => elements.streamPlayer.play(), generation, options);
}

async function startStreamPlayback(item, proxyUrl, playbackMode, options = {}) {
    const streamUrl = resolveApiResourceUrl(proxyUrl);
    const generation = state.playerGeneration + 1;
    state.playerGeneration = generation;
    state.activeProxyUrl = proxyUrl;
    state.activePlaybackMode = playbackMode;
    state.activeEmbedFallbackUrl = playbackMode === "hls" ? options.embedFallbackUrl || null : null;
    state.activeEmbedFallbackLabel = playbackMode === "hls" ? options.embedFallbackLabel || "" : "";
    state.activeVisualWatchdogEnabled = playbackMode === "hls" && Boolean(options.visualWatch);
    state.activePreferredAudioLanguage = options.preferredAudioLanguage || null;
    state.activePreferredSubtitleLanguage = options.preferredSubtitleLanguage || null;
    state.playerVisualFallbackPending = false;
    state.playerLastProgressAt = Date.now();
    state.nativePlaybackRequiresUserLaunch = false;
    if (playbackMode === "embed") {
        detachPlayerMedia();
        state.playerGeneration = generation;
        state.activeProxyUrl = proxyUrl;
        state.activePlaybackMode = playbackMode;
        prepareEmbedPlayer(streamUrl);
        elements.streamPlayer.hidden = true;
        elements.playerQuality.disabled = true;
        if (shouldGateEmbedLaunch(item)) {
            showEmbedLaunchPanel();
            setEmbedPlayerAwaitingLaunch();
        } else {
            launchEmbedInline();
        }
        syncEmbedActionLinks();
        return;
    }
    elements.embedPlayer.hidden = true;
    elements.streamPlayer.hidden = false;
    elements.streamPlayer.preload = "auto";
    schedulePlayerStartupWatchdog(generation);
    schedulePlayerVisualWatchdog(generation);
    const useMpegTs = playbackMode === "mpegts";
    if (playbackMode === "dash") {
        if (!window.dashjs?.MediaPlayer) {
            throw new Error("Lecteur DASH indisponible dans ce navigateur.");
        }
        await assertDashPlaybackSupported(streamUrl);
        const player = window.dashjs.MediaPlayer().create();
        state.dashPlayer = player;
        player.updateSettings({
            streaming: {
                buffer: {
                    fastSwitchEnabled: true
                }
            }
        });
        player.on(window.dashjs.MediaPlayer.events?.ERROR || "error", (event) => {
            if (generation !== state.playerGeneration) return;
            schedulePlayerRecovery(
                event?.error?.message || "Le flux DASH a ete interrompu.",
                true,
                false
            );
        });
        player.initialize(elements.streamPlayer, streamUrl, false);
        try {
            await attemptStreamPlayerPlay(generation, { waitForAttempt: true });
        } catch (error) {
            destroyDashPlayer(player);
            throw error;
        }
        return;
    }
    if (playbackMode === "hls") {
        if (elements.streamPlayer.canPlayType("application/vnd.apple.mpegurl")) {
            elements.streamPlayer.src = streamUrl;
            await attemptStreamPlayerPlay(generation);
            return;
        }
        if (!window.Hls?.isSupported?.()) {
            throw new Error("Lecteur HLS indisponible dans ce navigateur.");
        }
        const player = new window.Hls({
            enableWorker: true,
            lowLatencyMode: item.type === "live",
            backBufferLength: item.type === "live" ? 30 : 90
        });
        let mediaRecoveries = 0;
        state.hlsPlayer = player;
        player.on(window.Hls.Events.ERROR, (event, data) => {
            if (generation !== state.playerGeneration || !data?.fatal) return;
            if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 1) {
                mediaRecoveries += 1;
                player.recoverMediaError();
                return;
            }
            schedulePlayerRecovery(hlsErrorMessage(data), true, data.type === window.Hls.ErrorTypes.NETWORK_ERROR);
        });
        try {
            await attachHlsPlayer(player, elements.streamPlayer, streamUrl, {
                preferredAudioLanguage: state.activePreferredAudioLanguage,
                preferredSubtitleLanguage: state.activePreferredSubtitleLanguage,
                enableSubtitles: Boolean(options.enableSubtitles)
            });
            await attemptStreamPlayerPlay(generation, { waitForAttempt: true });
        } catch (error) {
            if (!isPlaybackPermissionError(error)) {
                destroyHlsPlayer(player);
            }
            throw error;
        }
        return;
    }
    if (useMpegTs && window.mpegts?.getFeatureList().mseLivePlayback) {
        const sourceIsLive = item.type === "live";
        const player = window.mpegts.createPlayer({
            type: "mpegts",
            isLive: sourceIsLive,
            url: streamUrl
        }, {
            enableWorker: true,
            enableStashBuffer: true,
            stashInitialSize: sourceIsLive ? 384 * 1024 : 1024 * 1024,
            lazyLoad: false,
            lazyLoadMaxDuration: 0,
            lazyLoadRecoverDuration: 0,
            deferLoadAfterSourceOpen: false,
            autoCleanupSourceBuffer: true,
            autoCleanupMaxBackwardDuration: sourceIsLive ? 45 : 120,
            autoCleanupMinBackwardDuration: sourceIsLive ? 15 : 45,
            fixAudioTimestampGap: true,
            liveBufferLatencyChasing: sourceIsLive,
            liveBufferLatencyMaxLatency: 10,
            liveBufferLatencyMinRemain: 1.5
        });
        state.mpegtsPlayer = player;
        player.attachMediaElement(elements.streamPlayer);
        player.on(window.mpegts.Events.ERROR, (type, detail, info) => {
            if (generation !== state.playerGeneration) return;
            const status = mpegtsErrorStatus(info);
            if (isRecoverableMpegtsError(type, detail, info)) {
                schedulePlayerRecovery(
                    mpegtsErrorMessage(type, detail, info),
                    [429, 502, 503, 504].includes(status) || state.activePlaybackQuality !== "auto",
                    [429, 502, 503, 504].includes(status)
                );
                return;
            }
            showPlayerError(mpegtsErrorMessage(type, detail, info));
        });
        try {
            player.load();
            await attemptPlaybackPromise(() => player.play(), generation);
        } catch (error) {
            destroyMpegtsPlayer(player);
            throw error;
        }
        return;
    }

    elements.streamPlayer.src = streamUrl;
    await attemptStreamPlayerPlay(generation);
}

function isMobileEmbedEnvironment() {
    const hasCoarsePointer = window.matchMedia?.(MOBILE_EMBED_QUERY).matches;
    const isMobileUserAgent = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || "");
    return hasCoarsePointer || isMobileUserAgent;
}

function shouldGateEmbedLaunch(item) {
    return isTmdbPlayable(item) && isMobileEmbedEnvironment();
}

function configureEmbedFrame() {
    elements.embedPlayer.removeAttribute("sandbox");
    elements.embedPlayer.setAttribute("allow", EMBED_PLAYER_ALLOW);
    elements.embedPlayer.setAttribute("allowfullscreen", "");
    elements.embedPlayer.setAttribute("webkitallowfullscreen", "");
    elements.embedPlayer.referrerPolicy = "no-referrer";
}

function clearEmbedFrame() {
    elements.embedPlayer.hidden = true;
    elements.embedPlayer.src = "about:blank";
    lockEmbedShield(true);
}

function loadEmbedFrame(streamUrl) {
    configureEmbedFrame();
    elements.embedPlayer.hidden = false;
    if (elements.embedPlayer.src !== streamUrl) {
        elements.embedPlayer.src = streamUrl;
    }
    unlockEmbedShield();
}

function lockEmbedShield(forceHide = false) {
    if (!elements.embedClickShield) return;
    if (state.embedShieldTimer) {
        window.clearTimeout(state.embedShieldTimer);
        state.embedShieldTimer = null;
    }
    state.embedShieldUnlockedUntil = 0;
    const shouldShow = !forceHide
        && state.activePlaybackMode === "embed"
        && !elements.embedPlayer.hidden
        && Boolean(state.activeEmbedUrl);
    elements.embedClickShield.hidden = !shouldShow;
}

function unlockEmbedShield(milliseconds = 0) {
    if (!elements.embedClickShield || state.activePlaybackMode !== "embed") return;
    if (state.embedShieldTimer) {
        window.clearTimeout(state.embedShieldTimer);
        state.embedShieldTimer = null;
    }
    state.embedShieldUnlockedUntil = milliseconds > 0 ? Date.now() + milliseconds : Number.POSITIVE_INFINITY;
    elements.embedClickShield.hidden = true;
    elements.playerMessage.textContent = "Interaction avec le lecteur autorisee.";
    if (milliseconds > 0) {
        state.embedShieldTimer = window.setTimeout(() => {
            lockEmbedShield();
            elements.playerMessage.textContent = "Protection anti-redirection reactivee.";
        }, milliseconds);
    }
}

function isExternalEmbedDomRemovalError(event) {
    const message = String(event?.message || event?.error?.message || "");
    const filename = String(event?.filename || "");
    return message.includes("removeChild")
        && message.includes("not a child")
        && /(?:player\.videasy|69be39811437728d|\/_next\/static\/chunks\/).*\.js/i.test(filename);
}

function prepareEmbedPlayer(streamUrl) {
    state.activeEmbedUrl = streamUrl;
    state.embedRequiresUserLaunch = false;
    state.nativePlaybackRequiresUserLaunch = false;
    state.embedAssistShown = false;
    state.embedManualRetryUsed = false;
    state.embedLoadCount = 0;
    state.embedReloading = false;
    configureEmbedFrame();
    clearEmbedFrame();
    elements.embedLaunchPanel.hidden = true;
    elements.embedLaunchPanel.querySelector("p").textContent = "Appuyez pour lancer le lecteur TMDB.";
    elements.embedLaunchInlineButton.textContent = "Lancer ici";
    syncPlayerEmbedMode(true);
    syncEmbedActionLinks();
}

function showEmbedLaunchPanel() {
    state.embedRequiresUserLaunch = true;
    state.nativePlaybackRequiresUserLaunch = false;
    clearEmbedFrame();
    elements.embedLaunchPanel.querySelector("p").textContent = "Appuyez pour lancer le lecteur TMDB.";
    elements.embedLaunchInlineButton.textContent = "Lancer ici";
    elements.embedLaunchPanel.hidden = false;
}

function showNativePlaybackLaunchPanel() {
    state.nativePlaybackRequiresUserLaunch = true;
    clearPlayerStartupTimer();
    clearPlayerPauseResumeTimer();
    elements.streamPlayer.hidden = true;
    elements.embedPlayer.hidden = true;
    elements.embedLaunchPanel.hidden = false;
    elements.embedOpenExternalLink.hidden = true;
    elements.embedOpenExternalLink.setAttribute("aria-disabled", "true");
    elements.embedLaunchPanel.querySelector("p").textContent = "Votre telephone demande une action directe pour lancer la lecture.";
    elements.embedLaunchInlineButton.textContent = "Lancer la lecture";
    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = "Touchez Lancer la lecture pour demarrer le flux.";
}

function launchEmbedInline() {
    if (!state.activeEmbedUrl) return;
    state.embedRequiresUserLaunch = false;
    elements.embedLaunchPanel.hidden = true;
    loadEmbedFrame(state.activeEmbedUrl);
    setEmbedPlayerOpened(embedOpenedMessage());
    scheduleEmbedAssistMessage();
    syncEmbedActionLinks();
}

function embedOpenedMessage() {
    if (isNodeFrenchPlayerItem()) {
        return "Lecteur FR ouvert dans Nexora. Si le chargement bloque, utilisez Reessayer ici.";
    }
    if (isEpornerSource(state.activePlayerItem)) {
        return "Lecteur Adults ouvert dans Nexora. Si le chargement bloque, utilisez Reessayer ici.";
    }
    return "Lecteur TMDB ouvert dans Nexora. Si le chargement bloque, utilisez Reessayer ici.";
}

function confirmNonFrenchPlayback(source) {
    if (!source?.requiresLanguageConfirmation) return Promise.resolve(true);
    return new Promise((resolve, reject) => {
        state.pendingNonFrenchConfirmation = { resolve, reject };
        clearPlayerStartupTimer();
        clearPlayerVisualWatchdog();
        elements.streamPlayer.hidden = true;
        elements.embedPlayer.hidden = true;
        elements.embedLaunchPanel.hidden = false;
        elements.embedOpenExternalLink.hidden = true;
        elements.embedOpenExternalLink.setAttribute("aria-disabled", "true");
        elements.embedLaunchPanel.querySelector("p").textContent = (
            source.languageWarning
            || "Aucune version francaise n'a ete detectee pour ce titre. Vous pouvez lancer une source non francaise."
        );
        elements.embedLaunchInlineButton.textContent = "Lancer quand meme";
        elements.playerPlaceholder.hidden = true;
        elements.playerMessage.textContent = "Source disponible, mais pas en francais.";
    });
}

function resolveNonFrenchPlaybackConfirmation() {
    const pending = state.pendingNonFrenchConfirmation;
    if (!pending) return false;
    state.pendingNonFrenchConfirmation = null;
    elements.embedLaunchPanel.hidden = true;
    elements.embedLaunchInlineButton.textContent = "Lancer ici";
    elements.embedLaunchPanel.querySelector("p").textContent = "Appuyez pour lancer le lecteur TMDB.";
    pending.resolve(true);
    return true;
}

function resolveNativePlaybackLaunch() {
    if (!state.nativePlaybackRequiresUserLaunch) return false;
    state.nativePlaybackRequiresUserLaunch = false;
    elements.embedLaunchPanel.hidden = true;
    elements.embedLaunchInlineButton.textContent = "Lancer ici";
    elements.streamPlayer.hidden = false;
    elements.playerPlaceholder.hidden = false;
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.querySelector("p").textContent = "Demarrage de la lecture...";
    elements.playerMessage.textContent = "Ouverture du flux video.";
    if (state.activeVisualWatchdogEnabled) {
        schedulePlayerVisualWatchdog(state.playerGeneration);
    }
    attemptStreamPlayerPlay(state.playerGeneration)
        .catch((error) => {
            if (isPlaybackPermissionError(error)) {
                showNativePlaybackLaunchPanel();
                return;
            }
            showPlayerError(error.message || "Impossible de demarrer le flux video.");
        });
    return true;
}

function rejectNonFrenchPlaybackConfirmation() {
    const pending = state.pendingNonFrenchConfirmation;
    if (!pending) return;
    state.pendingNonFrenchConfirmation = null;
    elements.embedLaunchPanel.hidden = true;
    elements.embedLaunchInlineButton.textContent = "Lancer ici";
    elements.embedLaunchPanel.querySelector("p").textContent = "Appuyez pour lancer le lecteur TMDB.";
    pending.reject(new Error("Lecture annulee: aucune version francaise detectee."));
}

function handleEmbedLaunchInlineButtonClick() {
    if (resolveNonFrenchPlaybackConfirmation()) return;
    if (resolveNativePlaybackLaunch()) return;
    launchEmbedInline();
}

function retryEmbedInline() {
    if (!state.activeEmbedUrl) return;
    if (state.embedManualRetryUsed) {
        showToast("Le lecteur a deja ete relance. Cette source bloque la lecture integree.", true);
        return;
    }
    state.embedManualRetryUsed = true;
    state.embedAssistShown = false;
    clearEmbedAssistTimer();
    elements.playerEmbedRetryButton.hidden = true;
    clearEmbedFrame();
    elements.playerMessage.textContent = embedRetryMessage();
    window.setTimeout(() => {
        if (state.activePlaybackMode !== "embed" || !state.activeEmbedUrl) return;
        loadEmbedFrame(state.activeEmbedUrl);
        lockEmbedShield();
        scheduleEmbedAssistMessage();
    }, 80);
}

function syncPlayerEmbedMode(isEmbed) {
    elements.playerModal.classList.toggle("is-embed-player", Boolean(isEmbed));
}

function syncEmbedActionLinks() {
    const hasEmbed = state.activePlaybackMode === "embed" && Boolean(state.activeEmbedUrl);
    elements.embedOpenExternalLink.href = "#";
    elements.embedOpenExternalLink.hidden = true;
    elements.embedOpenExternalLink.setAttribute("aria-disabled", "true");
    elements.playerEmbedOpenLink.href = "#";
    elements.playerEmbedOpenLink.hidden = true;
    elements.playerEmbedOpenLink.setAttribute("aria-disabled", "true");
    elements.playerEmbedRetryButton.hidden = !hasEmbed || !state.embedAssistShown || state.embedManualRetryUsed;
    elements.playerEmbedRetryButton.disabled = !hasEmbed || !state.embedAssistShown || state.embedManualRetryUsed;
}

function scheduleEmbedAssistMessage() {
    clearEmbedAssistTimer();
    state.embedAssistShown = false;
    syncEmbedActionLinks();
    state.embedAssistTimer = window.setTimeout(() => {
        if (state.activePlaybackMode !== "embed" || !state.activeEmbedUrl || elements.embedPlayer.hidden) return;
        state.embedAssistShown = true;
        elements.playerMessage.textContent = embedAssistMessage();
        elements.playerEmbedRetryButton.hidden = state.embedManualRetryUsed;
        elements.playerEmbedRetryButton.disabled = state.embedManualRetryUsed;
        elements.playerEmbedOpenLink.hidden = true;
    }, 14000);
}

function clearEmbedAssistTimer() {
    if (state.embedAssistTimer) {
        window.clearTimeout(state.embedAssistTimer);
        state.embedAssistTimer = null;
    }
}

function embedRetryMessage() {
    if (isNodeFrenchPlayerItem()) {
        return "Relance du lecteur FR dans Nexora...";
    }
    if (isEpornerSource(state.activePlayerItem)) {
        return "Relance du lecteur Adults dans Nexora...";
    }
    return "Relance du lecteur TMDB dans Nexora...";
}

function embedAssistMessage() {
    if (isNodeFrenchPlayerItem()) {
        return "Si le lecteur FR tourne encore, ouvrez-le dans un onglet separe.";
    }
    if (isEpornerSource(state.activePlayerItem)) {
        return "Si le lecteur Adults tourne encore, ouvrez-le dans un onglet separe.";
    }
    return "Si le lecteur TMDB tourne encore, la source Videasy est probablement indisponible pour ce titre.";
}

async function requestPlayerFullscreen() {
    const playerCard = elements.playerModal.querySelector(".player-card");
    const preferredTarget = state.activePlaybackMode === "embed"
        ? elements.embedPlayer
        : playerCard;
    const target = preferredTarget?.requestFullscreen ? preferredTarget : playerCard;
    try {
        await target?.requestFullscreen?.();
    } catch {
        try {
            await playerCard?.requestFullscreen?.();
        } catch {
            showToast("Le plein ecran n'est pas autorise par ce navigateur.", true);
        }
    }
}

function isRecoverableMpegtsError(type, detail, info) {
    const status = mpegtsErrorStatus(info);
    if ([401, 403, 404].includes(status)) return false;
    if (detail === "HttpStatusCodeInvalid" && status > 0 && status < 500) return false;
    return type !== "MediaError" || state.playerRecoveryAttempts === 0;
}

function mpegtsErrorStatus(info) {
    return Number(info?.code || info?.status || 0);
}

function setPlayerLoading(placeholderText, statusText) {
    if (!state.activeSessionToken && elements.playerModal.hidden) return;
    elements.playerPlaceholder.hidden = false;
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.querySelector("p").textContent = placeholderText;
    elements.playerMessage.textContent = statusText;
}

function setPlayerPlaying() {
    state.playerHasStarted = true;
    state.playerErrorShown = false;
    state.playerLastProgressAt = Date.now();
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    clearPlayerPauseResumeTimer();
    state.playerRecoveryShouldFailover = false;
    if (state.activePlaybackMode !== "embed" && elements.streamPlayer.currentTime > 8) {
        state.playerRecoveryAttempts = 0;
    }
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = "Lecture en cours.";
}

function setEmbedPlayerAwaitingLaunch() {
    state.playerHasStarted = true;
    state.playerLastProgressAt = Date.now();
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    state.playerRecoveryShouldFailover = false;
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = "Lecteur TMDB pret. Lancez-le depuis le bouton affiche.";
}

function setEmbedPlayerOpened(message = "Lecteur externe ouvert.") {
    state.playerHasStarted = true;
    state.playerLastProgressAt = Date.now();
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    state.playerRecoveryShouldFailover = false;
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = message;
}

function setPlayerBuffering(placeholderText, statusText) {
    if (!state.playerHasStarted) {
        setPlayerLoading(placeholderText, statusText);
        return;
    }

    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = statusText;
}

function clearPlayerRecoveryTimer() {
    if (state.playerRecoveryTimer) {
        window.clearTimeout(state.playerRecoveryTimer);
        state.playerRecoveryTimer = null;
    }
}

function clearPlayerStartupTimer() {
    if (state.playerStartupTimer) {
        window.clearTimeout(state.playerStartupTimer);
        state.playerStartupTimer = null;
    }
}

function clearPlayerVisualWatchdog() {
    if (state.playerVisualWatchTimer) {
        window.clearTimeout(state.playerVisualWatchTimer);
        state.playerVisualWatchTimer = null;
    }
}

function clearPlayerPauseResumeTimer() {
    if (state.playerPauseResumeTimer) {
        window.clearTimeout(state.playerPauseResumeTimer);
        state.playerPauseResumeTimer = null;
    }
}

function markPlayerUserIntent() {
    state.playerLastUserIntentAt = Date.now();
}

function isLikelyManualPause() {
    return Date.now() - state.playerLastUserIntentAt < PLAYER_MANUAL_PAUSE_WINDOW_MS;
}

function schedulePausedPlaybackResume() {
    clearPlayerPauseResumeTimer();
    if (!hasActivePlayback()
        || state.activePlaybackMode === "embed"
        || state.playerErrorShown
        || state.playerRecovering
        || elements.streamPlayer.ended) {
        return;
    }

    elements.playerPlaceholder.hidden = false;
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.querySelector("p").textContent = "Reprise de la lecture...";
    elements.playerMessage.textContent = "La lecture s'est interrompue, tentative de reprise.";

    const generation = state.playerGeneration;
    state.playerPauseResumeTimer = window.setTimeout(async () => {
        state.playerPauseResumeTimer = null;
        if (generation !== state.playerGeneration
            || !hasActivePlayback()
            || state.activePlaybackMode === "embed"
            || state.playerErrorShown
            || !elements.streamPlayer.paused
            || elements.streamPlayer.ended) {
            return;
        }
        try {
            await elements.streamPlayer.play();
            window.setTimeout(() => {
                if (generation !== state.playerGeneration
                    || !hasActivePlayback()
                    || state.activePlaybackMode === "embed"
                    || state.playerErrorShown
                    || !elements.streamPlayer.paused) {
                    return;
                }
                schedulePlayerRecovery("La lecture reste bloquee en pause, redemarrage du flux.", true, false);
            }, PLAYER_PAUSE_RESUME_DELAY_MS);
        } catch (error) {
            schedulePlayerRecovery(
                error?.message || "La lecture s'est arretee, tentative de reprise.",
                true,
                false
            );
        }
    }, PLAYER_PAUSE_RESUME_DELAY_MS);
}

function schedulePlayerVisualWatchdog(generation) {
    clearPlayerVisualWatchdog();
    if (!state.activeVisualWatchdogEnabled || state.activePlaybackMode !== "hls") {
        return;
    }
    state.playerVisualWatchStartedAt = Date.now();
    state.playerVisualWatchTimer = window.setTimeout(
        () => inspectPlayerVisualTrack(generation),
        PLAYER_VISUAL_WATCHDOG_MS
    );
}

function inspectPlayerVisualTrack(generation) {
    state.playerVisualWatchTimer = null;
    if (generation !== state.playerGeneration
        || state.activePlaybackMode !== "hls"
        || !state.activeVisualWatchdogEnabled
        || state.playerErrorShown
        || state.playerVisualFallbackPending
        || !hasActivePlayback()) {
        return;
    }

    const video = elements.streamPlayer;
    if (video.videoWidth > 0 && video.videoHeight > 0) {
        return;
    }

    const hasAudioProgress = video.currentTime > 1
        && !video.paused
        && !video.ended
        && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA;
    const watchedFor = Date.now() - state.playerVisualWatchStartedAt;
    if (!hasAudioProgress && watchedFor < PLAYER_VISUAL_WATCHDOG_MAX_MS) {
        state.playerVisualWatchTimer = window.setTimeout(
            () => inspectPlayerVisualTrack(generation),
            1500
        );
        return;
    }

    if (state.activeEmbedFallbackUrl) {
        void switchToEmbedFallback(
            "Le flux FR donne du son sans image; bascule vers le lecteur du provider."
        );
        return;
    }

    if (isNodeFrenchPlayerItem()) {
        void switchToNodeTmdbFallback(
            "Le flux FR donne du son sans image; bascule vers une source alternative."
        );
        return;
    }

    void switchToUniversalEmbedFallback(
        "Ce flux renvoie du son sans image; bascule vers le lecteur integre."
    );
}

async function switchToEmbedFallback(reason) {
    const fallbackUrl = state.activeEmbedFallbackUrl;
    const item = state.activePlayerItem;
    if (!fallbackUrl || !item || state.activePlaybackMode === "embed") return false;

    state.playerVisualFallbackPending = true;
    const fallbackItem = {
        ...item,
        playbackProvider: "node-fr-embed",
        playbackProviderName: state.activeEmbedFallbackLabel || "Source FR"
    };

    try {
        setPlayerControlsBusy(true);
        setPlayerLoading("Bascule vers le lecteur FR...", reason);
        detachPlayerMedia();
        await settleDetachedPlayer();
        state.activePlayerItem = fallbackItem;
        await startStreamPlayback(fallbackItem, fallbackUrl, "embed");
        elements.playerMessage.textContent = "Lecteur FR ouvert: le flux direct donnait du son sans image.";
        return true;
    } catch (error) {
        const opened = await switchToUniversalEmbedFallback(
            error.message || "Impossible d'ouvrir le lecteur FR alternatif."
        );
        if (!opened) {
            detachPlayerMedia();
            showPlayerError(error.message || "Impossible d'ouvrir le lecteur FR alternatif.");
        }
        return false;
    } finally {
        state.playerVisualFallbackPending = false;
        setPlayerControlsBusy(false);
    }
}

async function switchToNodeTmdbFallback(reason) {
    const item = state.activePlayerItem;
    if (!item || item.playbackProvider === "node-fr-embed" || !nodeApiEnabled()) {
        return switchToUniversalEmbedFallback(reason);
    }
    const fallbackEndpoint = nodeTmdbEmbedFallbackEndpointForItem(item);
    if (!fallbackEndpoint) {
        return switchToUniversalEmbedFallback(reason);
    }

    state.playerVisualFallbackPending = true;
    try {
        setPlayerControlsBusy(true);
        setPlayerLoading("Bascule vers une autre source...", reason);
        detachPlayerMedia();
        await settleDetachedPlayer();
        const source = await nodeApi(fallbackEndpoint);
        const stream = nodeFrenchStreamFromSource(source);
        const sourceLabel = [
            stream.hoster.nom || "Source alternative",
            stream.hoster.lang || ""
        ].filter(Boolean).join(" - ");
        const fallbackItem = {
            ...item,
            playbackProvider: "node-fr-embed",
            playbackProviderName: sourceLabel || "Source alternative"
        };
        state.activePlayerItem = fallbackItem;
        elements.playerBadge.textContent = source.requiresLanguageConfirmation ? "VO" : "FR";
        await startStreamPlayback(fallbackItem, stream.proxyUrl, stream.playbackMode, {
            embedFallbackUrl: stream.playbackMode === "hls" ? stream.embedUrl : "",
            embedFallbackLabel: sourceLabel,
            preferredAudioLanguage: stream.preferredAudioLanguage,
            preferredSubtitleLanguage: stream.preferredSubtitleLanguage,
            visualWatch: false
        });
        elements.playerMessage.textContent = "Source alternative ouverte.";
        return true;
    } catch (error) {
        const opened = await switchToUniversalEmbedFallback(
            error.message || "Impossible d'ouvrir une source alternative."
        );
        if (!opened) {
            detachPlayerMedia();
            showPlayerError(error.message || "Impossible d'ouvrir une source alternative.");
        }
        return false;
    } finally {
        state.playerVisualFallbackPending = false;
        setPlayerControlsBusy(false);
    }
}

async function switchToUniversalEmbedFallback(reason) {
    const item = state.activePlayerItem;
    const fallbackUrl = videasyUrlForItem(item);
    if (!item || !fallbackUrl) {
        detachPlayerMedia();
        showPlayerError("Ce flux FR renvoie du son, mais aucune image lisible dans ce navigateur.");
        return false;
    }

    state.playerVisualFallbackPending = true;
    try {
        setPlayerControlsBusy(true);
        setPlayerLoading("Bascule vers le lecteur integre...", reason);
        detachPlayerMedia();
        await settleDetachedPlayer();
        const fallbackItem = {
            ...item,
            playbackProvider: "videasy",
            playbackProviderName: "Lecteur integre"
        };
        state.activePlayerItem = fallbackItem;
        elements.playerBadge.textContent = "ALT";
        await startStreamPlayback(fallbackItem, fallbackUrl, "embed");
        elements.playerMessage.textContent = "Lecteur integre ouvert: la source FR directe donnait du son sans image.";
        return true;
    } catch (error) {
        detachPlayerMedia();
        showPlayerError(error.message || "Impossible d'ouvrir le lecteur integre.");
        return false;
    } finally {
        state.playerVisualFallbackPending = false;
        setPlayerControlsBusy(false);
    }
}

function schedulePlayerStartupWatchdog(generation) {
    clearPlayerStartupTimer();
    state.playerStartupTimer = window.setTimeout(() => {
        if (generation !== state.playerGeneration
            || state.playerHasStarted
            || state.playerRecovering
            || state.playerErrorShown
            || !hasActivePlayback()) {
            return;
        }
        if (!state.activeSessionToken) {
            if (state.activeEmbedFallbackUrl) {
                void switchToEmbedFallback("La source FR directe ne demarre pas; bascule vers le lecteur du provider.");
                return;
            }
            if (isNodeFrenchPlayerItem()) {
                void switchToNodeTmdbFallback("La source FR directe ne demarre pas; recherche d'une alternative.");
                return;
            }
            showPlayerError("La source FR ne produit aucune image lisible pour le moment.");
            return;
        }
        const quality = state.activePlaybackQuality || "auto";
        if (quality !== "auto") {
            recoverPlayer(`${qualityLabel(quality)} ne demarre pas, retour en Auto.`);
            return;
        }
        schedulePlayerRecovery("Le flux met trop de temps a demarrer.", true, true);
    }, PLAYER_STARTUP_TIMEOUT_MS);
}

function schedulePlayerRecovery(reason, immediate = false, preferFailover = false) {
    if (!state.activeSessionToken || state.playerRecovering || state.playerErrorShown) {
        return;
    }
    state.playerRecoveryShouldFailover = state.playerRecoveryShouldFailover || Boolean(preferFailover);
    if (state.playerRecoveryTimer) {
        if (!preferFailover) {
            return;
        }
        clearPlayerRecoveryTimer();
    }
    const stalledFor = Date.now() - state.playerLastProgressAt;
    const delay = immediate
        ? 500
        : state.playerHasStarted && stalledFor > PLAYER_RECOVERY_DELAY_MS
        ? 900
        : PLAYER_RECOVERY_DELAY_MS;
    elements.playerMessage.textContent = reason || "Le flux ralentit, tentative de reprise...";
    state.playerRecoveryTimer = window.setTimeout(() => recoverPlayer(reason), delay);
}

async function recoverPlayer(reason) {
    clearPlayerRecoveryTimer();
    if (!state.activeSessionToken || state.playerErrorShown || state.playerRecovering) return;
    if (state.playerOpening) {
        state.playerRecoveryTimer = window.setTimeout(
            () => recoverPlayer(reason),
            500
        );
        return;
    }
    state.playerRecovering = true;
    state.playerRecoveryAttempts += 1;
    const preferFailover = state.playerRecoveryShouldFailover;
    state.playerRecoveryShouldFailover = false;

    try {
        if (state.playerRecoveryAttempts > PLAYER_MAX_RECOVERY_ATTEMPTS) {
            showPlayerError("Le flux reste instable après plusieurs tentatives de reprise.");
            return;
        }

        setPlayerLoading(
            state.playerRecoveryAttempts > 1 ? "Bascule vers une autre source..." : "Reprise du flux...",
            reason || "Nexora tente de stabiliser la lecture."
        );

        if (state.activePlaybackQuality && state.activePlaybackQuality !== "auto") {
            const fallback = await fallbackActiveSessionToAuto(
                `${qualityLabel(state.activePlaybackQuality)} instable, retour en Auto.`
            );
            if (fallback) return;
        }

        if (preferFailover) {
            if (state.activeCanFailover) {
                const failover = await tryFailoverPlayer();
                if (failover) {
                    await restartPlaybackFromPayload(failover);
                    return;
                }
            }
            showPlayerError(reason || "Ce flux est momentanement indisponible chez le fournisseur.");
            return;
        }

        if (state.activeCanFailover && state.playerRecoveryAttempts > 1) {
            const failover = await tryFailoverPlayer();
            if (failover) {
                await restartPlaybackFromPayload(failover);
                return;
            }
        }

        await restartCurrentPlayback();
    } catch (error) {
        if (isInactiveSessionError(error)) {
            showPlayerError("Cette session de lecture a expire. Relancez le programme.");
            return;
        }
        if (state.playerRecoveryAttempts >= PLAYER_MAX_RECOVERY_ATTEMPTS) {
            showPlayerError(error.message || "Impossible de reprendre ce flux.");
            return;
        }
        state.playerRecoveryTimer = window.setTimeout(
            () => recoverPlayer(error.message || reason),
            PLAYER_RECOVERY_DELAY_MS
        );
    } finally {
        state.playerRecovering = false;
    }
}

async function tryFailoverPlayer() {
    if (!state.activeSessionToken || !state.activeCanFailover) return null;
    try {
        return await api(`/stream/failover/${state.activeSessionToken}`, {
            method: "POST",
            retryTransient: true
        });
    } catch (error) {
        if (isInactiveSessionError(error)) {
            throw error;
        }
        return null;
    }
}

async function restartPlaybackFromPayload(stream) {
    applyStreamPayload(stream, stream.quality || state.activePlaybackQuality || "auto");
    startHeartbeat();
    await restartCurrentPlayback(
        stream.proxyUrl,
        stream.playbackMode
    );
}

async function restartCurrentPlayback(proxyUrl = state.activeProxyUrl, playbackMode = state.activePlaybackMode) {
    if (!state.activePlayerItem || !proxyUrl || !playbackMode) {
        throw new Error("Session de lecture incomplète.");
    }
    detachPlayerMedia();
    state.playerHasStarted = false;
    state.playerLastProgressAt = Date.now();
    await startStreamPlayback(state.activePlayerItem, proxyUrl, playbackMode);
}

function detachPlayerMedia() {
    state.playerDetaching = true;
    state.playerGeneration += 1;
    rejectNonFrenchPlaybackConfirmation();
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    clearPlayerVisualWatchdog();
    clearPlayerPauseResumeTimer();
    clearEmbedAssistTimer();
    state.playerVisualWatchStartedAt = 0;
    state.playerLastUserIntentAt = 0;
    state.playerVisualFallbackPending = false;
    state.nativePlaybackRequiresUserLaunch = false;
    state.activeEmbedFallbackUrl = null;
    state.activeEmbedFallbackLabel = "";
    state.activeVisualWatchdogEnabled = false;
    state.activePreferredAudioLanguage = null;
    state.activePreferredSubtitleLanguage = null;
    if (state.mpegtsPlayer) {
        destroyMpegtsPlayer(state.mpegtsPlayer);
        state.mpegtsPlayer = null;
    }
    if (state.dashPlayer) {
        destroyDashPlayer(state.dashPlayer);
        state.dashPlayer = null;
    }
    if (state.hlsPlayer) {
        destroyHlsPlayer(state.hlsPlayer);
        state.hlsPlayer = null;
    }
    clearEmbedFrame();
    state.activeEmbedUrl = null;
    state.embedRequiresUserLaunch = false;
    lockEmbedShield(true);
    state.embedAssistShown = false;
    state.embedManualRetryUsed = false;
    state.embedLoadCount = 0;
    state.embedReloading = false;
    elements.embedLaunchPanel.hidden = true;
    elements.embedLaunchInlineButton.textContent = "Lancer ici";
    elements.embedLaunchPanel.querySelector("p").textContent = "Appuyez pour lancer le lecteur TMDB.";
    elements.streamPlayer.hidden = false;
    elements.streamPlayer.pause();
    elements.streamPlayer.removeAttribute("src");
    elements.streamPlayer.load();
    state.playerDetaching = false;
    syncPlayerEmbedMode(false);
    syncEmbedActionLinks();
}

function destroyMpegtsPlayer(player) {
    try {
        player.pause();
        player.unload();
        player.detachMediaElement();
        player.destroy();
    } catch {
        // The player may already be detached after a network error.
    }
    if (state.mpegtsPlayer === player) {
        state.mpegtsPlayer = null;
    }
}

function destroyDashPlayer(player) {
    try {
        player.reset();
    } catch {
        // The player may already be detached after a network error.
    }
    if (state.dashPlayer === player) {
        state.dashPlayer = null;
    }
}

function destroyHlsPlayer(player) {
    try {
        player.destroy();
    } catch {
        // The player may already be detached after a network error.
    }
    if (state.hlsPlayer === player) {
        state.hlsPlayer = null;
    }
}

function hlsAudioTrackMatchesLanguage(track, language) {
    if (!language) return false;
    const wanted = String(language).toLowerCase();
    const text = `${track?.lang || ""} ${track?.name || ""}`.toLowerCase();
    if (wanted === "fr") {
        return /\b(fr|fra|fre|french|francais|fran[cç]ais|vf)\b/i.test(text);
    }
    return text.includes(wanted);
}

function hlsSubtitleTrackMatchesLanguage(track, language) {
    if (!language) return false;
    const wanted = String(language).toLowerCase();
    const text = `${track?.lang || ""} ${track?.name || ""} ${track?.label || ""}`.toLowerCase();
    if (wanted === "fr") {
        return /\b(fr|fra|fre|french|francais|fran[cÃ§]ais|vostfr|sous[\s-]?titres?)\b/i.test(text);
    }
    return text.includes(wanted);
}

function selectPreferredHlsAudioTrack(player, language) {
    if (!language || !Array.isArray(player.audioTracks)) return false;
    const index = player.audioTracks.findIndex((track) => hlsAudioTrackMatchesLanguage(track, language));
    if (index < 0) return false;
    player.audioTrack = index;
    elements.playerMessage.textContent = "Piste audio francaise selectionnee.";
    return true;
}

function selectPreferredHlsSubtitleTrack(player, language) {
    if (!language || !Array.isArray(player.subtitleTracks)) return false;
    const index = player.subtitleTracks.findIndex((track) => hlsSubtitleTrackMatchesLanguage(track, language));
    if (index < 0) return false;
    player.subtitleDisplay = true;
    player.subtitleTrack = index;
    elements.playerMessage.textContent = "Sous-titres francais selectionnes.";
    return true;
}

function selectAnyHlsSubtitleTrack(player) {
    if (!Array.isArray(player.subtitleTracks) || !player.subtitleTracks.length) return false;
    player.subtitleDisplay = true;
    player.subtitleTrack = 0;
    elements.playerMessage.textContent = "Sous-titres disponibles actives.";
    return true;
}

function attachHlsPlayer(player, video, streamUrl, options = {}) {
    return new Promise((resolve, reject) => {
        let settled = false;
        const timeout = window.setTimeout(() => {
            fail(new Error("Le manifeste HLS met trop longtemps a repondre."));
        }, DASH_MANIFEST_TIMEOUT_MS);
        const cleanup = () => {
            window.clearTimeout(timeout);
            player.off(window.Hls.Events.MANIFEST_PARSED, onParsed);
            player.off(window.Hls.Events.ERROR, onError);
        };
        const finish = () => {
            if (settled) return;
            settled = true;
            cleanup();
            resolve();
        };
        const fail = (error) => {
            if (settled) return;
            settled = true;
            cleanup();
            reject(error);
        };
        const onParsed = () => {
            const audioSelected = selectPreferredHlsAudioTrack(player, options.preferredAudioLanguage);
            const subtitlesSelected = selectPreferredHlsSubtitleTrack(player, options.preferredSubtitleLanguage);
            if (!subtitlesSelected && options.enableSubtitles) {
                selectAnyHlsSubtitleTrack(player);
            }
            if (audioSelected && !subtitlesSelected && options.preferredSubtitleLanguage) {
                selectPreferredHlsSubtitleTrack(player, options.preferredSubtitleLanguage);
            }
            finish();
        };
        const onError = (event, data) => {
            if (data?.fatal) {
                fail(new Error(hlsErrorMessage(data)));
            }
        };
        player.on(window.Hls.Events.MANIFEST_PARSED, onParsed);
        player.on(window.Hls.Events.ERROR, onError);
        player.loadSource(streamUrl);
        player.attachMedia(video);
    });
}

function hlsErrorMessage(data) {
    if (data?.response?.code) {
        return `Le flux HLS a ete interrompu (HTTP ${data.response.code}).`;
    }
    if (data?.details) {
        return `Le flux HLS a ete interrompu: ${data.details}.`;
    }
    return "Le flux HLS a ete interrompu.";
}

function showPlayerError(message) {
    if (state.playerErrorShown) return;
    state.playerErrorShown = true;
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    clearPlayerVisualWatchdog();
    clearPlayerPauseResumeTimer();
    elements.playerPlaceholder.hidden = false;
    elements.playerPlaceholder.classList.add("error");
    elements.playerPlaceholder.querySelector("p").textContent = message;
    elements.playerMessage.textContent = "Essayez une autre qualité ou une autre chaîne.";
    if (state.activeSessionToken) {
        window.setTimeout(() => stopPlayer(), 0);
    }
}

function mpegtsErrorMessage(type, detail, info) {
    const status = Number(info?.code || info?.status || 0);
    if (status === 503) {
        return "Ce flux est momentanément indisponible chez le fournisseur.";
    }
    if (status === 401 || status === 403) {
        return "Le fournisseur a refusé l’accès à ce flux.";
    }
    if (status === 404) {
        return "Ce flux n’existe plus chez le fournisseur.";
    }
    if (detail === "HttpStatusCodeInvalid") {
        return "Le fournisseur n’a pas pu ouvrir ce flux vidéo.";
    }
    if (type === "MediaError") {
        return "Le format vidéo de ce flux n’est pas pris en charge.";
    }
    return "La lecture MPEG-TS a été interrompue.";
}

function playerOpenErrorMessage(error, item = state.activePlayerItem) {
    const message = error?.message || "";
    const unavailable = error?.status === 503
        || error?.code === "provider_unavailable"
        || error?.code === "service_unavailable"
        || error?.code === "stream_unavailable"
        || /impossible de joindre|service unavailable|timeout/i.test(message);
    if (unavailable && isAddonPublicId(item?.id)) {
        return "Le lien video fourni par cet add-on ne repond pas pour le moment.";
    }
    if (unavailable) {
        return "Le fournisseur video ne repond pas pour le moment.";
    }
    return message || "Ce flux est momentanement indisponible.";
}

function isAddonPublicId(value) {
    return String(value || "").startsWith("addon~");
}

function isInactiveSessionError(error) {
    const message = String(error?.message || "").toLowerCase();
    return (error?.status === 404 && error?.code === "not_found")
        || (error?.status === 422
            && message.includes("session")
            && (message.includes("fermee")
                || message.includes("expiree")
                || message.includes("introuvable")));
}

function startHeartbeat() {
    stopHeartbeat();
    state.heartbeatId = window.setInterval(async () => {
        if (!state.activeSessionToken) return;
        try {
            await api(`/stream/heartbeat/${state.activeSessionToken}`, { method: "POST" });
        } catch (error) {
            stopHeartbeat();
            if (isInactiveSessionError(error) && state.activeSessionToken) {
                showPlayerError("Cette session de lecture a expire. Relancez le programme.");
            }
        }
    }, 30000);
}

function stopHeartbeat() {
    if (state.heartbeatId) {
        window.clearInterval(state.heartbeatId);
        state.heartbeatId = null;
    }
}

async function stopPlayer() {
    const token = state.activeSessionToken;
    state.activeSessionToken = null;
    state.playerHasStarted = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    state.activeProxyUrl = null;
    state.activePlaybackMode = null;
    state.activePlaybackQuality = null;
    state.activePreferredAudioLanguage = null;
    state.activePreferredSubtitleLanguage = null;
    state.activeCanFailover = false;
    stopHeartbeat();
    detachPlayerMedia();

    if (token && state.token) {
        try {
            await api(`/stream/close/${token}`, { method: "DELETE" });
        } catch {
            // Closing the visual player remains immediate even if the API is unavailable.
        }
    }
}

function releasePlayerSession() {
    const sessionToken = state.activeSessionToken;
    const accessToken = state.token;
    if (!sessionToken || !accessToken) return;

    state.activeSessionToken = null;
    state.playerRecovering = false;
    state.playerRecoveryAttempts = 0;
    state.activeProxyUrl = null;
    state.activePlaybackMode = null;
    state.activePlaybackQuality = null;
    state.activePreferredAudioLanguage = null;
    state.activePreferredSubtitleLanguage = null;
    state.activeEmbedFallbackUrl = null;
    state.activeEmbedFallbackLabel = "";
    state.activeVisualWatchdogEnabled = false;
    state.activeCanFailover = false;
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    clearPlayerVisualWatchdog();
    stopHeartbeat();
    fetch(apiUrl(`/stream/close/${encodeURIComponent(sessionToken)}`), {
        method: "DELETE",
        headers: {
            Accept: "application/json",
            Authorization: `Bearer ${accessToken}`
        },
        keepalive: true
    }).catch(() => {
        // The backend timeout cleanup remains the fallback if the browser aborts the request.
    });
}

let toastTimer;
function showToast(message, isError = false) {
    window.clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", isError);
    elements.toast.hidden = false;
    toastTimer = window.setTimeout(() => {
        elements.toast.hidden = true;
    }, 3600);
}

function typeLabel(type) {
    return { live: "Direct", movie: "Film", series: "Série" }[type] || "Programme";
}

function statusLabel(status) {
    return {
        ACTIVE: "Actif",
        TRIALING: "Période d’essai",
        TRIAL: "Période d’essai",
        CANCELED: "Résilié",
        EXPIRED: "Expiré",
        PENDING: "En attente"
    }[status] || status || "Inactif";
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function escapeCssUrl(value) {
    return String(value || "/assets/images/hero.jpg")
        .replaceAll("\\", "\\\\")
        .replaceAll('"', '\\"')
        .replaceAll("\n", "");
}

function configureHomeNavigation() {
    const nav = document.querySelector(".main-nav");
    if (!nav) return;
    nav.innerHTML = `
        <button class="nav-link active" type="button" data-filter="all">Accueil</button>
        <button class="nav-link" type="button" data-filter="series">S\u00e9ries</button>
        <button class="nav-link" type="button" data-filter="drama">Dramas</button>
        <button class="nav-link" type="button" data-filter="movie">Films</button>
        <a class="adult-nav-entry" href="/adults.html" aria-label="Ouvrir Adults 18+">Adults 18+</a>
        <button class="nav-link" type="button" data-filter="live">TV en direct</button>
        <button class="nav-link" type="button" data-nav-action="list">Ma liste</button>
    `;
    elements.navLinks = [...document.querySelectorAll(".nav-link")];
}

function handleNavAction(action) {
    if (action !== "list") return;
    const target = document.querySelector(".home-continue, .recent-row, #catalogue");
    target?.scrollIntoView({ behavior: "smooth", block: "start" });
    elements.navLinks.forEach((link) => link.classList.toggle("active", link.dataset.navAction === action));
}

function bindNavigationLinks() {
    elements.navLinks.forEach((link) => {
        link.addEventListener("click", async () => {
            const action = link.dataset.navAction;
            if (action) {
                handleNavAction(action);
                return;
            }
            await setFilter(link.dataset.filter || "all");
        });
    });
}

window.addEventListener("scroll", () => {
    elements.topbar.classList.toggle("scrolled", window.scrollY > 35);
}, { passive: true });

window.addEventListener("pagehide", releasePlayerSession);

configureHomeNavigation();
bindNavigationLinks();

let searchTimer;
elements.searchShell.addEventListener("click", (event) => {
    if (event.target.closest(".search-box") && !event.target.closest("#searchClear")) {
        elements.searchInput.focus();
    }
});

elements.searchInput.addEventListener("input", (event) => {
    clearHiddenCatalogCards();
    state.query = event.target.value;
    const normalizedQuery = normalizeSearchText(state.query);
    state.searchResults = [];
    state.searchResultQuery = "";
    state.catalog = state.browseCatalog.length ? [...state.browseCatalog] : [...fallbackCatalog];
    window.clearTimeout(searchTimer);
    updateSearchChrome();
    renderCatalog();
    renderSearchSuggestions(true);

    if (!state.token || normalizedQuery.length < MIN_REMOTE_SEARCH_LENGTH) {
        return;
    }
    searchTimer = window.setTimeout(() => loadCatalog(), SEARCH_DEBOUNCE_MS);
});

elements.searchInput.addEventListener("focus", () => {
    updateSearchChrome();
    renderSearchSuggestions(true);
});

elements.searchInput.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown") {
        event.preventDefault();
        if (elements.searchPanel.hidden) renderSearchSuggestions(true);
        setSearchActiveIndex(state.searchActiveIndex + 1);
        return;
    }

    if (event.key === "ArrowUp") {
        event.preventDefault();
        if (elements.searchPanel.hidden) renderSearchSuggestions(true);
        setSearchActiveIndex(state.searchActiveIndex - 1);
        return;
    }

    if (event.key === "Enter") {
        event.preventDefault();
        if (state.searchActiveIndex >= 0) {
            const item = state.searchSuggestions[state.searchActiveIndex];
            if (item) {
                closeSearchSuggestions();
                selectCatalogItem(item);
            }
        } else if (state.query.trim()) {
            commitSearch();
        }
        return;
    }

    if (event.key === "Escape") {
        event.preventDefault();
        closeSearchSuggestions();
        elements.searchInput.blur();
    }
});

elements.searchClear.addEventListener("click", async () => {
    resetSearchQuery(true);
    renderSearchSuggestions(true);
    if (state.token) await loadCatalog();
});

elements.playerSearchInput?.addEventListener("input", () => {
    const hasQuery = Boolean(elements.playerSearchInput.value.trim());
    elements.playerSearchForm?.classList.toggle("has-query", hasQuery);
    if (elements.playerSearchClear) elements.playerSearchClear.hidden = !hasQuery;
});

elements.playerSearchClear?.addEventListener("click", () => {
    elements.playerSearchInput.value = "";
    elements.playerSearchInput.focus();
    elements.playerSearchForm?.classList.remove("has-query");
    elements.playerSearchClear.hidden = true;
});

elements.playerSearchForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const query = elements.playerSearchInput.value.trim();
    if (!query) {
        elements.playerSearchInput.focus();
        return;
    }
    closeModal("playerModal");
    window.clearTimeout(searchTimer);
    clearHiddenCatalogCards();
    state.query = query;
    state.activeType = "all";
    state.activeCategory = "";
    state.activeAddonFilter = "";
    state.addonPages = 1;
    state.addonHasMore = false;
    elements.searchInput.value = query;
    elements.navLinks.forEach((link) => link.classList.toggle("active", link.dataset.filter === "all"));
    elements.dramaSection.hidden = true;
    elements.catalogRows.hidden = false;
    updateSearchChrome();
    closeSearchSuggestions();
    await loadCatalog();
    document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth", block: "start" });
});

elements.searchPanel.addEventListener("click", (event) => {
    const result = event.target.closest("[data-search-index]");
    if (result) {
        const item = state.searchSuggestions[Number(result.dataset.searchIndex)];
        if (item) {
            closeSearchSuggestions();
            selectCatalogItem(item);
        }
        return;
    }

    if (event.target.closest("[data-search-all]")) {
        commitSearch();
    }
});

elements.genreList.addEventListener("click", async (event) => {
    const chip = event.target.closest("[data-category]");
    if (!chip) return;
    resetSearchQuery(false);
    state.activeCategory = chip.dataset.category;
    const category = state.categories.find((item) => item.id === state.activeCategory);
    state.activeAddonFilter = category?.filterOptions?.[0] || "";
    state.addonPages = 1;
    state.addonHasMore = false;
    if (category) {
        state.activeType = category.type;
        state.visibleCatalog[category.type] = defaultCatalogLimit(category.type);
        elements.navLinks.forEach((link) => (
            link.classList.toggle("active", link.dataset.filter === category.type)
        ));
    }
    renderCategories();
    renderAddonFilter();
    renderLanguageFilter();
    await loadCatalog();
});

elements.addonFilterSelect.addEventListener("change", async (event) => {
    state.activeAddonFilter = event.target.value;
    state.addonPages = 1;
    state.addonHasMore = false;
    await loadCatalog();
});

elements.languageSelect.addEventListener("change", async (event) => {
    state.activeLanguage = event.target.value;
    state.activeCategory = "";
    state.activeAddonFilter = "";
    state.addonPages = 1;
    state.addonHasMore = false;
    renderCategories();
    renderAddonFilter();
    renderLanguageFilter();
    await loadCatalog();
});

elements.movieSort.addEventListener("change", async (event) => {
    state.movieSort = event.target.value;
    localStorage.setItem(MOVIE_SORT_KEY, state.movieSort);
    renderCatalog();
    if (state.token) {
        await loadCatalog();
    }
});

elements.catalogRows.addEventListener("click", async (event) => {
    const clickedCard = event.target.closest("[data-item-id]");
    if (activeHomeDrag?.moved && !clickedCard) {
        event.preventDefault();
        event.stopPropagation();
        return;
    }

    const removeCard = event.target.closest("[data-remove-card]");
    if (removeCard) {
        event.preventDefault();
        event.stopPropagation();
        hideCatalogCard(removeCard.dataset.itemType, removeCard.dataset.itemId);
        return;
    }

    const browseCategory = event.target.closest("[data-browse-category]");
    if (browseCategory) {
        const type = browseCategory.dataset.browseType || state.activeType;
        state.activeType = type;
        state.activeCategory = browseCategory.dataset.browseCategory || "";
        state.activeAddonFilter = "";
        state.addonPages = 1;
        state.addonHasMore = false;
        state.visibleCatalog[type] = defaultCatalogLimit(type);
        elements.navLinks.forEach((link) => link.classList.toggle("active", link.dataset.filter === type));
        renderCategories();
        renderAddonFilter();
        renderLanguageFilter();
        await loadCatalog();
        document.querySelector("#catalogue")?.scrollIntoView({ behavior: "smooth", block: "start" });
        return;
    }

    const filterShortcut = event.target.closest("[data-filter-shortcut]");
    if (filterShortcut) {
        await setFilter(filterShortcut.dataset.filterShortcut || "all");
        return;
    }

    const rowScroll = event.target.closest("[data-row-scroll]");
    if (rowScroll) {
        const track = document.getElementById(rowScroll.dataset.rowTarget);
        if (track) {
            const direction = Number(rowScroll.dataset.rowScroll);
            scrollRail(track, direction);
        }
        return;
    }

    const homeScroll = event.target.closest("[data-home-scroll]");
    if (homeScroll) {
        const track = document.getElementById(homeScroll.dataset.homeScroll);
        if (track) {
            const direction = Number(homeScroll.dataset.homeScrollDirection || 1);
            scrollRail(track, direction);
        }
        return;
    }

    const continueRemove = event.target.closest("[data-continue-remove]");
    if (continueRemove) {
        event.preventDefault();
        event.stopPropagation();
        dismissContinueCard(continueRemove.dataset.continueType, continueRemove.dataset.continueRemove);
        return;
    }

    const loadMore = event.target.closest("[data-load-more]");
    if (loadMore) {
        if (state.addonHasMore && state.activeCategory) {
            state.addonPages += 1;
            state.visibleCatalog[loadMore.dataset.loadMore] += LOAD_MORE_INCREMENT;
            await loadCatalog();
            return;
        }
        state.visibleCatalog[loadMore.dataset.loadMore] += LOAD_MORE_INCREMENT;
        await loadCatalog();
        return;
    }
    const card = clickedCard || event.target.closest("[data-item-id]");
    if (!card) return;
    const item = findCatalogItem(card.dataset.itemId, card.dataset.itemType);
    if (item) selectCatalogItem(item);
});

elements.catalogRows.addEventListener("wheel", (event) => {
    const track = event.target.closest(".home-continue-track, .home-poster-track, .card-track, .browse-track, .browse-universe-track");
    if (!track) return;
    if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
    if (track.scrollWidth <= track.clientWidth) return;
    event.preventDefault();
    track.scrollLeft += event.deltaY;
}, { passive: false });

let activeHomeDrag = null;

elements.catalogRows.addEventListener("pointerdown", (event) => {
    const track = event.target.closest(".home-continue-track, .home-poster-track, .card-track, .browse-track, .browse-universe-track");
    if (!track || event.button !== 0 || track.scrollWidth <= track.clientWidth) return;
    if (event.target.closest("[data-item-id], button, a, input, select, textarea")) return;
    activeHomeDrag = {
        track,
        pointerId: event.pointerId,
        startX: event.clientX,
        scrollLeft: track.scrollLeft,
        moved: false
    };
    track.classList.add("dragging");
    track.setPointerCapture?.(event.pointerId);
});

elements.catalogRows.addEventListener("pointermove", (event) => {
    if (!activeHomeDrag || activeHomeDrag.pointerId !== event.pointerId) return;
    const delta = event.clientX - activeHomeDrag.startX;
    if (Math.abs(delta) > 12) activeHomeDrag.moved = true;
    activeHomeDrag.track.scrollLeft = activeHomeDrag.scrollLeft - delta;
});

["pointerup", "pointercancel", "pointerleave"].forEach((eventName) => {
    elements.catalogRows.addEventListener(eventName, (event) => {
        if (!activeHomeDrag || activeHomeDrag.pointerId !== event.pointerId) return;
        activeHomeDrag.track.classList.remove("dragging");
        activeHomeDrag.track.releasePointerCapture?.(event.pointerId);
        window.setTimeout(() => {
            activeHomeDrag = null;
        }, activeHomeDrag.moved ? 80 : 0);
    });
});

elements.hero?.addEventListener("click", async (event) => {
    const filterShortcut = event.target.closest("[data-filter-shortcut]");
    if (filterShortcut) {
        await setFilter(filterShortcut.dataset.filterShortcut || "all");
        return;
    }

    const card = event.target.closest("[data-item-id]");
    if (!card) return;
    const item = findCatalogItem(card.dataset.itemId, card.dataset.itemType);
    if (item) selectCatalogItem(item);
});

elements.catalogRows.addEventListener("keydown", (event) => {
    if (event.target.closest("[data-continue-remove]")) return;

    const continueCard = event.target.closest(".home-continue-card[data-item-id]");
    if (continueCard && ["Enter", " "].includes(event.key)) {
        event.preventDefault();
        const item = findCatalogItem(continueCard.dataset.itemId, continueCard.dataset.itemType);
        if (item) selectCatalogItem(item);
        return;
    }

    const removeCard = event.target.closest("[data-remove-card]");
    if (!removeCard || !["Enter", " "].includes(event.key)) return;
    event.preventDefault();
    event.stopPropagation();
    hideCatalogCard(removeCard.dataset.itemType, removeCard.dataset.itemId);
});

elements.dramaSearchForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        await searchDramas();
    } catch (error) {
        setDramaStatus(error.message || "Recherche drama impossible.", true);
    }
});

elements.dramaLang?.addEventListener("change", async (event) => {
    state.dramaLang = event.target.value || "fr";
    state.dramaLoaded = false;
    state.dramaShelves = [];
    state.dramaShelfVisible = {};
    state.dramaShelfPaging = {};
    try {
        await loadDramaHome();
    } catch (error) {
        setDramaStatus(error.message || "Impossible de changer la langue drama.", true);
    }
});

elements.dramaShelves?.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-drama-shelf]");
    if (!button) return;
    elements.dramaShelves.querySelectorAll(".drama-chip").forEach((chip) => chip.classList.remove("active"));
    button.classList.add("active");
    const value = button.dataset.dramaShelf;
    try {
        if (value === "search") {
            await searchDramas();
            return;
        }
        state.dramaMode = "shelf";
        state.dramaCurrentShelfIndex = Number(value);
        state.dramaSearchHasMore = false;
        renderDramaShelves();
        renderDramaShelfRows();
        document.querySelector(`#dramaShelfRow-${value}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
        const shelf = state.dramaShelves[Number(value)];
        setDramaStatus(`${shelf?.books?.length || 0} dramas dans ${shelf?.bookshelf_name || "ce rayon"}`);
    } catch (error) {
        setDramaStatus(error.message || "Rayon drama indisponible.", true);
    }
});

elements.dramaGrid?.addEventListener("click", async (event) => {
    const card = event.target.closest("[data-drama-book]");
    if (!card) return;
    try {
        await playDramaBook(card.dataset.dramaBook);
    } catch (error) {
        setDramaStatus(error.message || "Impossible de lancer ce drama.", true);
    }
});

elements.dramaShelfRows?.addEventListener("click", async (event) => {
    const more = event.target.closest("[data-drama-row-more]");
    if (more) {
        const index = Number(more.dataset.dramaRowMore);
        const shelf = state.dramaShelves[index];
        const currentVisible = state.dramaShelfVisible[index] || DRAMA_VISIBLE_INCREMENT;
        if (currentVisible >= (shelf?.books?.length || 0)) {
            await loadMoreDramaShelf(index, DRAMA_VISIBLE_INCREMENT);
        }
        state.dramaShelfVisible[index] = currentVisible + DRAMA_VISIBLE_INCREMENT;
        renderDramaShelfRows();
        document.querySelector(`#dramaShelfRow-${index}`)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
        return;
    }
    const card = event.target.closest("[data-drama-book]");
    if (!card) return;
    try {
        await playDramaBook(card.dataset.dramaBook);
    } catch (error) {
        setDramaStatus(error.message || "Impossible de lancer ce drama.", true);
    }
});

elements.dramaEpisodeList?.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-drama-episode]");
    if (!button) return;
    try {
        await playDramaEpisode(Number(button.dataset.dramaEpisode), button.dataset.dramaChapter);
    } catch (error) {
        setDramaStatus(error.message || "Lecture drama impossible.", true);
    }
});

elements.playerEpisodePanelList?.addEventListener("click", async (event) => {
    const drama = event.target.closest("[data-player-drama-episode]");
    if (drama) {
        await playPlayerPanelEntry("drama", drama.dataset.playerDramaEpisode, drama.dataset.playerDramaChapter);
        return;
    }
    const series = event.target.closest("[data-player-series-episode]");
    if (series) {
        await playPlayerPanelEntry("series", series.dataset.playerSeriesEpisode);
        return;
    }
    const catalog = event.target.closest("[data-player-catalog-item]");
    if (catalog) {
        await playPlayerPanelEntry("catalog", catalog.dataset.playerCatalogItem, "", catalog.dataset.playerCatalogType);
    }
});

elements.playerPrevEpisodeButton?.addEventListener("click", async (event) => {
    const button = event.currentTarget;
    await playPlayerPanelEntry(button.dataset.playerEpisodeKind, button.dataset.playerEpisodeId, button.dataset.playerDramaChapter, button.dataset.playerCatalogType);
});

elements.playerNextEpisodeButton?.addEventListener("click", async (event) => {
    const button = event.currentTarget;
    await playPlayerPanelEntry(button.dataset.playerEpisodeKind, button.dataset.playerEpisodeId, button.dataset.playerDramaChapter, button.dataset.playerCatalogType);
});

elements.playerResumeButton?.addEventListener("click", () => {
    if (!elements.streamPlayer.hidden) {
        elements.streamPlayer.play().catch(() => {});
    }
});

elements.playerFavoriteButton?.addEventListener("click", () => {
    if (!state.activePlayerItem) return;
    trackRecentlyWatched(state.activePlayerItem);
    showToast("Ajoute a votre liste recente.");
});

elements.playerVfButton?.addEventListener("click", () => {
    state.activePreferredAudioLanguage = "fr";
    elements.playerMessage.textContent = "VF demandee quand la source la propose.";
});

elements.playerVostfrButton?.addEventListener("click", () => {
    state.activePreferredSubtitleLanguage = "fr";
    elements.playerMessage.textContent = "Sous-titres FR demandes quand la source les propose.";
});

elements.playerAllEpisodesButton?.addEventListener("click", () => {
    if (state.activePlayerItem?.type === "movie") {
        closeModal("playerModal");
        setFilter("movie");
        return;
    }
    if (state.activeDramaBook && state.activeDramaEpisodes.length) {
        closeModal("playerModal");
        elements.dramaEpisodes.hidden = false;
        elements.dramaEpisodes.scrollIntoView({ behavior: "smooth", block: "start" });
        return;
    }
    if (state.activeSeries) {
        closeModal("playerModal");
        openModal("seriesModal");
    }
});

document.querySelector(".player-room-nav")?.addEventListener("click", async (event) => {
    const filterButton = event.target.closest("[data-player-filter]");
    if (filterButton) {
        closeModal("playerModal");
        await setFilter(filterButton.dataset.playerFilter || "all");
        return;
    }

    const actionButton = event.target.closest("[data-player-nav-action]");
    if (actionButton) {
        closeModal("playerModal");
        handleNavAction(actionButton.dataset.playerNavAction);
    }
});

elements.dramaLoadMore?.addEventListener("click", async () => {
    try {
        if (state.dramaMode === "search") {
            await searchDramas(state.dramaSearchPage + 1, true);
            return;
        }
        state.dramaVisibleLimit += DRAMA_VISIBLE_INCREMENT;
        renderDramaBooks(state.dramaBooks.map((book) => ({
            book_id: book.id,
            book_title: book.title,
            filtered_title: book.slug,
            book_pic: book.image,
            chapter_count: book.episodeCount,
            special_desc: book.summary
        })), state.dramaCurrentTitle || "Dramas", true);
        setDramaStatus(`${Math.min(state.dramaVisibleLimit, state.dramaBooks.length)} / ${state.dramaBooks.length} dramas affiches`);
    } catch (error) {
        setDramaStatus(error.message || "Impossible de charger la suite.", true);
    }
});

elements.seriesSeasonSelect.addEventListener("change", (event) => {
    renderSeriesSeason(event.target.value);
});

elements.episodeList.addEventListener("click", (event) => {
    const episode = event.target.closest("[data-episode-id]");
    if (episode) playSeriesEpisode(episode.dataset.episodeId);
});

elements.detailPlay.addEventListener("click", () => {
    if (!state.activeDetail) return;
    const item = state.activeDetail;
    if (item.streamAvailable === false && !isTmdbPlayable(item)) {
        showToast(item.streamUnavailableReason || "Ce contenu n'a pas de flux disponible.", true);
        return;
    }
    closeModal("detailModal");
    playItem(item);
});

elements.accountButton.addEventListener("click", handleAccountClick);
elements.footerAccountButton.addEventListener("click", handleAccountClick);
elements.heroPlay.addEventListener("click", playHeroSelection);
elements.discoverButton.addEventListener("click", discoverHeroSelection);
elements.heroPrev.addEventListener("click", () => changeHeroSlide(-1));
elements.heroNext.addEventListener("click", () => changeHeroSlide(1));
elements.heroDots.addEventListener("click", (event) => {
    const dot = event.target.closest("[data-hero-slide]");
    if (dot) setHeroSlide(Number(dot.dataset.heroSlide), true);
});
elements.hero.addEventListener("mouseenter", pauseHeroCarousel);
elements.hero.addEventListener("mouseleave", resumeHeroCarousel);
elements.hero.addEventListener("focusin", pauseHeroCarousel);
elements.hero.addEventListener("focusout", resumeHeroCarousel);
elements.resetFilters.addEventListener("click", () => {
    resetSearchQuery(false);
    state.activeCategory = "";
    state.activeLanguage = "";
    renderLanguageFilter();
    setFilter("all");
});
elements.authTabs.addEventListener("click", (event) => {
    const button = event.target.closest("[data-auth-mode]");
    if (!button) return;
    if (button.dataset.authMode === "register") {
        window.location.href = "/signup.html";
        return;
    }
    setAuthMode(button.dataset.authMode);
});
elements.authForm.addEventListener("submit", submitAuth);
elements.forgotPasswordButton.addEventListener("click", toggleForgotPasswordMode);
elements.settingsNav.addEventListener("click", (event) => {
    const button = event.target.closest("[data-settings-tab]");
    if (button) switchSettingsTab(button.dataset.settingsTab);
});
elements.profileSettingsForm.addEventListener("submit", submitProfileSettings);
elements.playbackSettingsForm.addEventListener("submit", submitPlaybackSettings);
elements.passwordSettingsForm.addEventListener("submit", submitPasswordSettings);
elements.settingsTwoFactor.addEventListener("change", toggleTwoFactor);
elements.logoutAllButton.addEventListener("click", logoutAllDevices);
elements.logoutButton.addEventListener("click", logout);
elements.playerFullscreenButton.addEventListener("click", requestPlayerFullscreen);
elements.embedLaunchInlineButton.addEventListener("click", handleEmbedLaunchInlineButtonClick);
elements.embedUnlockButton?.addEventListener("click", () => unlockEmbedShield());
elements.playerEmbedRetryButton.addEventListener("click", retryEmbedInline);

window.addEventListener("error", (event) => {
    if (isExternalEmbedDomRemovalError(event)) {
        event.preventDefault();
        return true;
    }
    return false;
}, true);

document.addEventListener("click", (event) => {
    if (!elements.searchShell.contains(event.target)) {
        closeSearchSuggestions();
    }
    const closeButton = event.target.closest("[data-close-modal]");
    if (closeButton) closeModal(closeButton.dataset.closeModal);
});

document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const openModalElement = [...document.querySelectorAll(".modal")].find((modal) => !modal.hidden);
    if (openModalElement) closeModal(openModalElement.id);
});

elements.embedPlayer.addEventListener("load", () => {
    if (state.activePlaybackMode !== "embed" || !state.activeEmbedUrl) return;
    if (state.embedReloading) return;
    state.embedLoadCount += 1;
});

elements.streamPlayer.addEventListener("pointerdown", markPlayerUserIntent);
elements.streamPlayer.addEventListener("keydown", markPlayerUserIntent);
elements.streamPlayer.addEventListener("touchstart", markPlayerUserIntent, { passive: true });

elements.streamPlayer.addEventListener("playing", () => {
    if (hasActivePlayback()) {
        setPlayerPlaying();
    }
});

elements.streamPlayer.addEventListener("loadstart", () => {
    if (hasActivePlayback()) {
        setPlayerBuffering("Chargement du programme...", "Ouverture du flux vidéo.");
    }
});

elements.streamPlayer.addEventListener("waiting", () => {
    if (hasActivePlayback()) {
        const text = state.playerHasStarted
            ? "Mise en mémoire tampon..."
            : "Chargement des premières images...";
        setPlayerBuffering(text, "Mise en mémoire tampon...");
        schedulePlayerRecovery("La mise en mémoire tampon dure trop longtemps.");
    }
});

elements.streamPlayer.addEventListener("stalled", () => {
    if (hasActivePlayback()) {
        setPlayerBuffering(
            "Le fournisseur met plus de temps à répondre...",
            "Chargement du flux en cours."
        );
        schedulePlayerRecovery("Le fournisseur ne renvoie plus assez de données.");
    }
});

elements.streamPlayer.addEventListener("canplay", () => {
    if (!hasActivePlayback()) return;
    if (state.playerHasStarted && !elements.streamPlayer.paused) {
        setPlayerPlaying();
    } else if (!state.playerHasStarted) {
        setPlayerLoading("Démarrage de la lecture...", "Le flux est prêt.");
    }
});

elements.streamPlayer.addEventListener("timeupdate", () => {
    if (hasActivePlayback() && !elements.streamPlayer.paused && elements.streamPlayer.currentTime > 0) {
        state.playerLastProgressAt = Date.now();
        setPlayerPlaying();
    }
});

elements.streamPlayer.addEventListener("progress", () => {
    if (hasActivePlayback() && state.playerHasStarted
        && !elements.streamPlayer.paused && elements.streamPlayer.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
        state.playerLastProgressAt = Date.now();
        setPlayerPlaying();
    }
});

elements.streamPlayer.addEventListener("pause", () => {
    if (state.playerDetaching) return;
    if (hasActivePlayback() && state.playerHasStarted && !elements.streamPlayer.ended) {
        elements.playerPlaceholder.hidden = true;
        if (isLikelyManualPause()) {
            clearPlayerPauseResumeTimer();
            elements.playerMessage.textContent = "Lecture en pause.";
            return;
        }
        schedulePausedPlaybackResume();
    }
});

elements.streamPlayer.addEventListener("error", () => {
    if (hasActivePlayback() && !state.playerOpening && !state.playerRecovering) {
        const code = elements.streamPlayer.error?.code;
        if (code === 2 || code === 3 || isNodeFrenchPlayerItem()) {
            schedulePlayerRecovery("Le flux a été interrompu, tentative de reprise.", true, code === 2);
            return;
        }
        showPlayerError("Le flux ne répond pas ou son codec n’est pas pris en charge par ce navigateur.");
    }
});
elements.playerQuality.addEventListener("change", changePlayerQuality);

renderCatalog();
applyLaunchIntent();
restoreSession();
