const API_ROOT = window.NexoraApi?.root?.() || "/api";
const TOKEN_KEY = "nexora_access_token";
const AGE_KEY = "nexora_adults_age_confirmed";
const CATEGORY_ID = "adults-eporner";
const SEARCH_DELAY_MS = 750;
const INITIAL_LIMIT = 50;
const PAGE_LIMIT = 50;
const BROWSE_RAILS = [
    { key: "all", label: "Tout", query: "", note: "Flux global" },
    { key: "4k", label: "4K", query: "4k", note: "Ultra HD" },
    { key: "hd", label: "HD", query: "hd", note: "1080p et 720p" },
    { key: "amateur", label: "Amateur", query: "amateur", note: "Selection amateur" },
    { key: "homemade", label: "Homemade", query: "homemade", note: "Maison" },
    { key: "asian", label: "Asian", query: "asian", note: "Rayon asiatique" },
    { key: "mature", label: "Mature", query: "mature", note: "Mature" },
    { key: "latina", label: "Latina", query: "latina", note: "Latina" },
    { key: "french", label: "French", query: "french", note: "Recherche FR" },
    { key: "long", label: "Longs", query: "", sort: "longest", note: "Durees longues" },
    { key: "rated", label: "Mieux notes", query: "", sort: "top-rated", note: "Top notes" },
    { key: "weekly", label: "Top semaine", query: "", sort: "top-weekly", note: "Tendance" },
    { key: "popular", label: "Most viewed", query: "", sort: "most-popular", note: "Populaires" }
];
const SORT_LABELS = {
    latest: "Nouveautes",
    "most-popular": "Populaires",
    "top-weekly": "Top semaine",
    "top-monthly": "Top mois",
    "top-rated": "Mieux notes",
    longest: "Longs formats",
    shortest: "Formats courts"
};

const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: null,
    authMode: "login",
    twoFactorEmail: "",
    query: "",
    browseKey: "all",
    browseQuery: "",
    sort: "latest",
    categoryFilter: "",
    items: [],
    categories: [],
    page: 1,
    hasMore: false,
    loading: false,
    loadingMore: false,
    hasAccess: false,
    accessChecked: false,
    profileLoaded: false,
    openingItemId: "",
    activeSessionToken: "",
    activeExternalUrl: "",
    heartbeatId: null,
    playerLoadTimer: null,
    searchTimer: null
};

const el = {
    userLabel: document.querySelector("#userLabel"),
    accountButton: document.querySelector("#adultAccountButton"),
    accountAvatar: document.querySelector("#accountAvatar"),
    adminLink: document.querySelector("#adminLink"),
    logoutButton: document.querySelector("#logoutButton"),
    statusBar: document.querySelector("#statusBar"),
    accessLabel: document.querySelector("#accessLabel"),
    searchForm: document.querySelector("#searchForm"),
    searchInput: document.querySelector("#searchInput"),
    topbarSearchInput: document.querySelector("#adultTopbarSearchInput"),
    loginPanel: document.querySelector("#loginPanel"),
    loginForm: document.querySelector("#loginForm"),
    loginSubmit: document.querySelector("#loginSubmit"),
    loginError: document.querySelector("#loginError"),
    passwordField: document.querySelector("#passwordField"),
    codeField: document.querySelector("#codeField"),
    blockedPanel: document.querySelector("#blockedPanel"),
    discoveryPanel: document.querySelector("#discoveryPanel"),
    browseRails: document.querySelector("#browseRails"),
    sortSelect: document.querySelector("#sortSelect"),
    categoryTabs: document.querySelector("#categoryTabs"),
    adultGrid: document.querySelector("#adultGrid"),
    featuredPanel: document.querySelector("#featuredPanel"),
    videoCount: document.querySelector("#videoCount"),
    activeRailLabel: document.querySelector("#activeRailLabel"),
    activeSortLabel: document.querySelector("#activeSortLabel"),
    ageModal: document.querySelector("#ageModal"),
    ageConfirmButton: document.querySelector("#ageConfirmButton"),
    playerModal: document.querySelector("#playerModal"),
    embedFrame: document.querySelector("#embedFrame"),
    playerTitle: document.querySelector("#playerTitle"),
    playerExternalLink: document.querySelector("#playerExternalLink"),
    playerNotice: document.querySelector("#playerNotice"),
    playerNoticeLink: document.querySelector("#playerNoticeLink"),
    toast: document.querySelector("#toast")
};

function apiUrl(path) {
    return window.NexoraApi?.url ? window.NexoraApi.url(path) : `${API_ROOT}${path}`;
}

function resolveApiResourceUrl(value) {
    return window.NexoraApi?.resolve
        ? window.NexoraApi.resolve(value)
        : new URL(value, window.location.origin).href;
}

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");
    if (options.body && !(options.body instanceof FormData)) {
        headers.set("Content-Type", "application/json");
    }
    if (state.token) {
        headers.set("Authorization", `Bearer ${state.token}`);
    }

    const canRetry = !options.method || String(options.method).toUpperCase() === "GET";
    for (let attempt = 0; attempt < 3; attempt += 1) {
        const response = await fetch(apiUrl(path), { ...options, headers });
        const body = await response.json().catch(() => ({}));
        if (response.ok && body.success !== false) {
            return body.data;
        }
        if (!canRetry || ![502, 503, 504].includes(response.status) || attempt === 2) {
            const error = new Error(body.message || "Requete refusee.");
            error.status = response.status;
            error.code = body.code;
            throw error;
        }
        await new Promise((resolve) => window.setTimeout(resolve, 500 * (attempt + 1)));
    }
    return null;
}

function setStatus(label, tone = "") {
    el.statusBar.classList.toggle("ready", tone === "ready");
    el.statusBar.classList.toggle("loading", tone === "loading");
    el.statusBar.querySelector("strong").textContent = label;
}

function activeBrowseQuery() {
    return state.query || state.browseQuery || "";
}

function activeSort() {
    return state.sort || "latest";
}

function activeRail() {
    return BROWSE_RAILS.find((rail) => rail.key === state.browseKey) || BROWSE_RAILS[0];
}

function showToast(message) {
    el.toast.textContent = message;
    el.toast.hidden = false;
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => {
        el.toast.hidden = true;
    }, 4200);
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#039;"
    }[char]));
}

function imageSource(value) {
    const source = String(value || "").trim();
    if (!source) {
        return "/assets/images/landscape-1.jpg";
    }
    if (source.startsWith("/api/") || source.startsWith("api/")) {
        return resolveApiResourceUrl(source);
    }
    try {
        const parsed = new URL(source, window.location.origin);
        return ["http:", "https:"].includes(parsed.protocol)
            ? parsed.href
            : "/assets/images/landscape-1.jpg";
    } catch {
        return "/assets/images/landscape-1.jpg";
    }
}

function durationLabel(item) {
    return item.duration || item.lengthMin || "";
}

function viewsLabel(item) {
    const views = Number(item.views || 0);
    return views ? `${views.toLocaleString("fr-FR")} vues` : "";
}

function ratingLabel(item) {
    const rating = Number(item.rating || 0);
    return rating ? `${Math.round(rating * 20)}%` : "";
}

function qualityLabel(item) {
    const text = `${item.name || ""} ${(item.genres || []).join(" ")}`.toLowerCase();
    if (text.includes("4k") || text.includes("2160")) return "4K";
    if (text.includes("1080")) return "1080p";
    if (text.includes("720")) return "720p";
    if (text.includes("hd")) return "HD";
    return "HD";
}

function metaChips(item) {
    return [
        "Reserve",
        qualityLabel(item),
        ratingLabel(item),
        viewsLabel(item)
    ].filter(Boolean).slice(0, 4);
}

function itemCategories(item) {
    const values = Array.isArray(item.genres) ? item.genres : [];
    return values
        .map((value) => String(value || "").trim())
        .filter(Boolean)
        .slice(0, 6);
}

function categoryKey(value) {
    return String(value || "").trim().toLocaleLowerCase("fr");
}

function visibleItems() {
    if (!state.categoryFilter) return state.items;
    return state.items.filter((item) => itemCategories(item).some((category) => categoryKey(category) === state.categoryFilter));
}

function syncCategories() {
    const counts = new Map();
    state.items.forEach((item) => {
        const categories = itemCategories(item);
        (categories.length ? categories : ["Reserve"]).forEach((category) => {
            const key = categoryKey(category);
            if (!key) return;
            const current = counts.get(key) || { label: category, count: 0 };
            current.count += 1;
            counts.set(key, current);
        });
    });
    state.categories = Array.from(counts.values())
        .sort((left, right) => right.count - left.count || left.label.localeCompare(right.label, "fr"))
        .slice(0, 12);
    if (state.categoryFilter && !state.categories.some((category) => categoryKey(category.label) === state.categoryFilter)) {
        state.categoryFilter = "";
    }
}

function renderCategoryTabs() {
    if (!state.token || !state.hasAccess || !state.categories.length) {
        el.categoryTabs.hidden = true;
        el.categoryTabs.innerHTML = "";
        return;
    }
    el.categoryTabs.hidden = false;
    el.categoryTabs.innerHTML = `
        <button class="${state.categoryFilter ? "" : "active"}" type="button" data-adult-category="">Toutes <span>${state.items.length}</span></button>
        ${state.categories.map((category) => `
            <button class="${state.categoryFilter === categoryKey(category.label) ? "active" : ""}" type="button" data-adult-category="${escapeHtml(categoryKey(category.label))}">
                ${escapeHtml(category.label)} <span>${category.count}</span>
            </button>
        `).join("")}
    `;
}

function renderBrowseRails() {
    if (!el.discoveryPanel || !el.browseRails) return;
    el.discoveryPanel.hidden = !state.token || !state.hasAccess;
    if (el.discoveryPanel.hidden) {
        el.browseRails.innerHTML = "";
        return;
    }
    el.browseRails.innerHTML = BROWSE_RAILS.map((rail) => `
        <button class="${state.browseKey === rail.key ? "active" : ""}" type="button" data-browse-rail="${escapeHtml(rail.key)}">
            <strong>${escapeHtml(rail.label)}</strong>
            <span>${escapeHtml(rail.note)}</span>
        </button>
    `).join("");
    if (el.sortSelect) {
        el.sortSelect.value = activeSort();
    }
}

function updateDashboardStats() {
    if (el.videoCount) {
        el.videoCount.textContent = state.items.length.toLocaleString("fr-FR");
    }
    if (el.activeRailLabel) {
        el.activeRailLabel.textContent = state.query ? "Recherche" : activeRail().label;
    }
    if (el.activeSortLabel) {
        el.activeSortLabel.textContent = SORT_LABELS[activeSort()] || activeSort();
    }
    if (el.accessLabel) {
        el.accessLabel.textContent = state.hasAccess ? "Attribue" : "Verrouille";
    }
}

function featuredItem() {
    return [...state.items].sort((left, right) => {
        const rightScore = Number(right.views || 0) + Number(right.rating || 0) * 10000;
        const leftScore = Number(left.views || 0) + Number(left.rating || 0) * 10000;
        return rightScore - leftScore;
    })[0] || null;
}

function renderFeatured() {
    if (!el.featuredPanel || !state.token || !state.hasAccess || state.loading) {
        if (el.featuredPanel) {
            el.featuredPanel.hidden = true;
            el.featuredPanel.innerHTML = "";
        }
        return;
    }
    const item = featuredItem();
    if (!item) {
        el.featuredPanel.hidden = true;
        el.featuredPanel.innerHTML = "";
        return;
    }
    const categories = itemCategories(item);
    el.featuredPanel.hidden = false;
    el.featuredPanel.innerHTML = `
        <div class="adult-featured-media">
            <img src="${escapeHtml(imageSource(item.image || item.poster || item.backdrop))}" alt="" loading="lazy" decoding="async">
            <span class="adult-quality-badge">${escapeHtml(qualityLabel(item))}</span>
        </div>
        <div class="adult-featured-copy">
            <p class="adult-kicker">MISE EN AVANT</p>
            <h2>${escapeHtml(item.name || "Reserve")}</h2>
            <p>${escapeHtml([durationLabel(item), ratingLabel(item), viewsLabel(item), categories.slice(0, 3).join(" / ")].filter(Boolean).join(" · "))}</p>
            <span class="adult-chip-line">${metaChips(item).map((chip) => `<span>${escapeHtml(chip)}</span>`).join("")}</span>
            <div class="adult-featured-actions">
                <button type="button" data-item-id="${escapeHtml(item.id)}">Lire maintenant</button>
            </div>
        </div>
    `;
}

function cardTemplate(item) {
    return `
        <button class="adult-card" type="button" data-item-id="${escapeHtml(item.id)}">
            <span class="adult-card-media">
                <img src="${escapeHtml(imageSource(item.image || item.poster || item.backdrop))}" alt="" loading="lazy" decoding="async">
                <span class="adult-quality-badge">${escapeHtml(qualityLabel(item))}</span>
                ${durationLabel(item) ? `<span class="adult-duration">${escapeHtml(durationLabel(item))}</span>` : ""}
            </span>
            <span class="adult-card-copy">
                <strong>${escapeHtml(item.name || "Reserve")}</strong>
                <small>${escapeHtml(itemCategories(item)[0] || item.categoryName || "Reserve")}</small>
                <span class="adult-chip-line">
                    ${metaChips(item).map((chip) => `<span>${escapeHtml(chip)}</span>`).join("")}
                </span>
                <span class="adult-card-stats">
                    <span>${escapeHtml(viewsLabel(item) || "Nouveau")}</span>
                    <b>${escapeHtml(ratingLabel(item) || "R")}</b>
                </span>
            </span>
        </button>
    `;
}

function renderGroupedItems(items) {
    const groups = new Map();
    items.forEach((item) => {
        const label = itemCategories(item)[0] || "Reserve";
        const key = categoryKey(label);
        if (!groups.has(key)) groups.set(key, { label, items: [] });
        groups.get(key).items.push(item);
    });
    return Array.from(groups.values()).map((group) => `
        <section class="adult-category-section">
            <div class="adult-category-head">
                <h2>${escapeHtml(group.label)}</h2>
                <span>${Math.min(group.items.length, PAGE_LIMIT)} video(s)</span>
            </div>
            <div class="adult-card-grid">${group.items.slice(0, PAGE_LIMIT).map(cardTemplate).join("")}</div>
        </section>
    `).join("");
}

function setPlayerNotice(visible, externalUrl = state.activeExternalUrl) {
    if (!el.playerNotice) return;
    el.playerNotice.hidden = !visible;
    if (el.playerNoticeLink && externalUrl) {
        el.playerNoticeLink.href = externalUrl;
    }
}

function armPlayerFallback(externalUrl) {
    window.clearTimeout(state.playerLoadTimer);
    setPlayerNotice(false, externalUrl);
    state.playerLoadTimer = window.setTimeout(() => {
        if (el.playerModal.hidden || !state.activeExternalUrl) return;
        setPlayerNotice(true, state.activeExternalUrl);
        setStatus("Lecteur externe a ouvrir si besoin", "");
    }, 6500);
}

function clearPlayerFallback() {
    window.clearTimeout(state.playerLoadTimer);
    state.playerLoadTimer = null;
}

function renderAdultBrowseDashboard(items) {
    if (!items.length) return "";
    const trending = [...items]
        .sort((left, right) => Number(right.views || 0) - Number(left.views || 0))
        .slice(0, 12);
    const recommended = [...items]
        .sort((left, right) => Number(right.rating || 0) - Number(left.rating || 0))
        .slice(0, 12);
    const universes = state.categories.slice(0, 8);
    return `
        <section class="adult-browse-dashboard" aria-label="Selection Reserve">
            ${renderAdultBrowseRail("Tendances du moment", trending)}
            ${renderAdultBrowseRail("Recommandes pour vous", recommended)}
            ${renderAdultUniverseRail(universes)}
        </section>
    `;
}

function renderAdultBrowseRail(title, items) {
    if (!items.length) return "";
    return `
        <section class="adult-browse-rail">
            <div class="adult-browse-head">
                <h2>${escapeHtml(title)}</h2>
                <span>${items.length} selections</span>
            </div>
            <div class="adult-card-track">
                ${items.map(cardTemplate).join("")}
            </div>
        </section>
    `;
}

function renderAdultUniverseRail(categories) {
    if (!categories.length) return "";
    return `
        <section class="adult-browse-rail">
            <div class="adult-browse-head">
                <h2>Explorer par univers</h2>
                <span>${categories.length} categories</span>
            </div>
            <div class="adult-universe-track">
                ${categories.map((category) => `
                    <button class="adult-universe-card" type="button" data-adult-category="${escapeHtml(categoryKey(category.label))}">
                        <strong>${escapeHtml(category.label)}</strong>
                        <span>${category.count} videos</span>
                    </button>
                `).join("")}
            </div>
        </section>
    `;
}

function renderGrid() {
    if (!state.token || !state.hasAccess) {
        el.adultGrid.innerHTML = "";
        renderBrowseRails();
        renderCategoryTabs();
        renderFeatured();
        updateDashboardStats();
        return;
    }
    if (state.loading) {
        renderBrowseRails();
        el.adultGrid.innerHTML = '<div class="adult-skeleton-grid"><span></span><span></span><span></span><span></span><span></span><span></span></div>';
        renderCategoryTabs();
        renderFeatured();
        updateDashboardStats();
        return;
    }
    renderBrowseRails();
    syncCategories();
    renderCategoryTabs();
    renderFeatured();
    updateDashboardStats();
    const items = visibleItems();
    if (!items.length) {
        el.adultGrid.innerHTML = '<div class="adult-empty">Aucun resultat pour cette recherche.</div>';
        return;
    }
    const content = state.categoryFilter
        ? `<div class="adult-card-grid">${items.map(cardTemplate).join("")}</div>`
        : `${renderAdultBrowseDashboard(items)}${renderGroupedItems(items)}`;
    el.adultGrid.innerHTML = `${content}${loadMoreTemplate()}`;
}

function loadMoreTemplate() {
    if (!state.hasMore || state.categoryFilter) return "";
    return `
        <div class="adult-load-more">
            <button type="button" data-load-more-adults ${state.loadingMore ? "disabled" : ""}>
                ${state.loadingMore ? "Chargement..." : "Charger plus"}
            </button>
        </div>
    `;
}

function isReserveItem(item) {
    const id = String(item?.id || "");
    const categoryId = String(item?.categoryId || "");
    const sourceCode = String(item?.sourceCode || "").toLowerCase();
    const playbackProvider = String(item?.playbackProvider || "").toLowerCase();
    const source = String(item?.source || item?.provider || "").toLowerCase();
    return id.startsWith("eporner~")
        || categoryId === CATEGORY_ID
        || categoryId.startsWith(`${CATEGORY_ID}-`)
        || sourceCode === "eporner"
        || playbackProvider === "eporner"
        || source.includes("eporner");
}

function mergeItems(nextItems, replace = false) {
    const values = replace ? [] : [...state.items];
    const seen = new Set(values.map((item) => String(item.id)));
    (nextItems || []).forEach((item) => {
        const id = String(item?.id || "");
        if (!id || seen.has(id)) return;
        seen.add(id);
        values.push(item);
    });
    state.items = values;
}

async function loadProfile() {
    if (!state.token) {
        state.user = null;
        syncChrome();
        return;
    }
    try {
        const profile = await api("/auth/me");
        state.user = profile.user;
        state.profileLoaded = true;
        syncChrome();
    } catch (error) {
        if (error.status === 401 || error.status === 403) {
            clearSession();
            showToast("Session expiree. Reconnectez-vous.");
            return;
        }
        throw error;
    }
}

function syncChrome() {
    const user = state.user;
    el.userLabel.textContent = user ? `${user.name || user.email}` : "Connexion requise";
    if (el.accountAvatar) {
        const label = user?.name || user?.email || "A";
        el.accountAvatar.textContent = label.trim().charAt(0).toUpperCase() || "A";
    }
    el.logoutButton.hidden = !state.token;
    el.adminLink.hidden = !["SUPER_ADMIN", "ADMIN", "OPS", "SUPPORT", "BILLING"].includes(String(user?.role || ""));
    el.loginPanel.hidden = Boolean(state.token);
    el.searchInput.disabled = !state.token || !state.hasAccess;
    if (el.accessLabel) {
        el.accessLabel.textContent = state.hasAccess ? "Attribue" : "Verrouille";
    }
}

function isAdminUser(user) {
    return ["SUPER_ADMIN", "ADMIN", "OPS", "SUPPORT", "BILLING"].includes(String(user?.role || ""));
}

function hasAdultCategoryGrant(user) {
    return Array.isArray(user?.allowedCategories) && user.allowedCategories.includes(CATEGORY_ID);
}

async function verifyAdultsAccess() {
    if (state.accessChecked) {
        return state.hasAccess;
    }
    const categories = await api("/catalog/categories?type=movie");
    state.hasAccess = isAdminUser(state.user)
        || hasAdultCategoryGrant(state.user)
        || categories.some((category) => category.id === CATEGORY_ID);
    state.accessChecked = true;
    el.blockedPanel.hidden = state.hasAccess;
    syncChrome();
    if (!state.hasAccess) {
        setStatus("Acces non attribue", "");
        state.items = [];
        renderGrid();
        return false;
    }
    return true;
}

async function loadAdults() {
    state.page = 1;
    state.hasMore = false;
    await fetchAdultsPage({ replace: true });
}

async function fetchAdultsPage({ replace = false } = {}) {
    if (!state.token) {
        setStatus("Connexion requise", "");
        renderGrid();
        return;
    }
    state.loading = replace;
    state.loadingMore = !replace;
    setStatus("Synchronisation Reserve...", "loading");
    renderGrid();
    try {
        if (!state.profileLoaded) {
            await loadProfile();
        }
        const allowed = await verifyAdultsAccess();
        if (!allowed) return;
        const params = new URLSearchParams({
            type: "movie",
            categoryId: CATEGORY_ID,
            limit: String(replace ? INITIAL_LIMIT : PAGE_LIMIT),
            sort: activeSort(),
            page: String(state.page)
        });
        const query = activeBrowseQuery();
        if (query) {
            params.set("q", query);
        }
        const nextItems = await api(`/catalog/items?${params}`);
        mergeItems(nextItems, replace);
        state.hasMore = Array.isArray(nextItems) && nextItems.length >= Math.min(PAGE_LIMIT, replace ? INITIAL_LIMIT : PAGE_LIMIT) * 0.55;
        setStatus(`${state.items.length} video(s) disponibles`, "ready");
    } catch (error) {
        if (error.status === 401) {
            clearSession();
            showToast("Session expiree.");
            return;
        }
        setStatus("Reserve indisponible", "");
        el.adultGrid.innerHTML = `<div class="adult-empty">Provider temporairement indisponible.<button class="adult-retry" type="button" data-retry-adults>Reessayer</button></div>`;
        showToast(error.message || "Impossible de charger Reserve.");
    } finally {
        state.loading = false;
        state.loadingMore = false;
        if (!el.adultGrid.querySelector("[data-retry-adults]")) {
            renderGrid();
        }
    }
}

async function loadMoreAdults() {
    if (state.loading || state.loadingMore || !state.hasMore) return;
    state.page += 1;
    await fetchAdultsPage({ replace: false });
}

async function submitLogin(event) {
    event.preventDefault();
    el.loginError.hidden = true;
    el.loginSubmit.disabled = true;
    try {
        const form = new FormData(el.loginForm);
        const email = String(form.get("email") || state.twoFactorEmail || "").trim();
        let data;
        if (state.authMode === "twoFactor") {
            data = await api("/auth/2fa/verify", {
                method: "POST",
                body: JSON.stringify({ email, code: form.get("code") })
            });
        } else {
            data = await api("/auth/login", {
                method: "POST",
                body: JSON.stringify({ email, password: form.get("password") })
            });
        }
        if (data.requiresTwoFactor) {
            state.authMode = "twoFactor";
            state.twoFactorEmail = data.email || email;
            el.passwordField.hidden = true;
            el.codeField.hidden = false;
            el.loginSubmit.textContent = "Verifier";
            showToast("Code 2FA envoye.");
            return;
        }
        if (data.requiresEmailVerification) {
            throw new Error("Verification email requise depuis la page de connexion principale.");
        }
        state.token = data.token;
        localStorage.setItem(TOKEN_KEY, state.token);
        state.authMode = "login";
        el.loginForm.reset();
        el.passwordField.hidden = false;
        el.codeField.hidden = true;
        el.loginSubmit.textContent = "Se connecter";
        await loadAdults();
    } catch (error) {
        el.loginError.textContent = error.message || "Connexion refusee.";
        el.loginError.hidden = false;
    } finally {
        el.loginSubmit.disabled = false;
    }
}

function clearSession() {
    state.token = null;
    state.user = null;
    state.hasAccess = false;
    state.accessChecked = false;
    state.profileLoaded = false;
    state.items = [];
    state.categories = [];
    state.categoryFilter = "";
    state.page = 1;
    state.hasMore = false;
    localStorage.removeItem(TOKEN_KEY);
    stopHeartbeat();
    syncChrome();
    renderGrid();
    setStatus("Connexion requise", "");
    updateDashboardStats();
}

async function logout() {
    try {
        if (state.token) {
            await api("/auth/logout", { method: "POST" });
        }
    } catch {
        // Local logout still has to happen for expired tokens.
    }
    clearSession();
}

async function playItem(itemId) {
    const item = state.items.find((value) => String(value.id) === String(itemId));
    if (!item) {
        showToast("Contenu Reserve introuvable.");
        return;
    }
    if (state.openingItemId) return;
    state.openingItemId = itemId;
    try {
        setStatus("Ouverture du lecteur...", "loading");
        await closeActiveSession();
        const stream = await api("/stream/open", {
            method: "POST",
            body: JSON.stringify({
                type: "movie",
                itemId: item.id,
                quality: "auto"
            })
        });
        if (stream.playbackMode !== "embed") {
            throw new Error("Le provider Reserve doit s'ouvrir en iframe.");
        }
        state.activeSessionToken = stream.token;
        state.activeExternalUrl = resolveApiResourceUrl(stream.proxyUrl);
        el.playerTitle.textContent = item.name || "Reserve";
        if (el.playerExternalLink) {
            el.playerExternalLink.href = state.activeExternalUrl;
            el.playerExternalLink.hidden = false;
        }
        el.playerModal.hidden = false;
        el.embedFrame.removeAttribute("src");
        el.embedFrame.src = state.activeExternalUrl;
        armPlayerFallback(state.activeExternalUrl);
        startHeartbeat();
        setStatus("Lecture Reserve ouverte", "ready");
    } catch (error) {
        setStatus("Lecture refusee", "");
        showToast(error.message || "Impossible d'ouvrir cette video.");
    } finally {
        state.openingItemId = "";
    }
}

function startHeartbeat() {
    stopHeartbeat();
    state.heartbeatId = window.setInterval(() => {
        if (!state.activeSessionToken) return;
        api(`/stream/heartbeat/${state.activeSessionToken}`, { method: "POST" }).catch(() => {});
    }, 25000);
}

function stopHeartbeat() {
    if (state.heartbeatId) {
        window.clearInterval(state.heartbeatId);
        state.heartbeatId = null;
    }
}

async function closeActiveSession() {
    const token = state.activeSessionToken;
    stopHeartbeat();
    state.activeSessionToken = "";
    if (!token) return;
    try {
        await api(`/stream/close/${token}`, { method: "DELETE" });
    } catch {
        // Closing is best-effort; stale sessions are cleaned server-side.
    }
}

async function closePlayer() {
    clearPlayerFallback();
    setPlayerNotice(false);
    state.activeExternalUrl = "";
    if (el.playerExternalLink) {
        el.playerExternalLink.hidden = true;
        el.playerExternalLink.href = "#";
    }
    el.embedFrame.removeAttribute("src");
    el.playerModal.hidden = true;
    await closeActiveSession();
}

function confirmAge() {
    localStorage.setItem(AGE_KEY, "1");
    el.ageModal.hidden = true;
    loadAdults();
}

function boot() {
    syncChrome();
    renderGrid();
    if (localStorage.getItem(AGE_KEY) !== "1") {
        el.ageModal.hidden = false;
        setStatus("Verification Reserve", "");
        return;
    }
    loadAdults();
}

document.querySelectorAll("[data-watch-filter]").forEach((link) => {
    link.addEventListener("click", (event) => {
        event.preventDefault();
        const filter = link.dataset.watchFilter || "all";
        window.location.href = `/watch.html?filter=${encodeURIComponent(filter)}#catalogue`;
    });
});
el.ageConfirmButton.addEventListener("click", confirmAge);
el.loginForm.addEventListener("submit", submitLogin);
el.logoutButton.addEventListener("click", logout);
el.accountButton?.addEventListener("click", () => {
    if (state.token) {
        logout();
        return;
    }
    el.loginPanel.scrollIntoView({ behavior: "smooth", block: "start" });
});

el.embedFrame?.addEventListener("load", () => {
    clearPlayerFallback();
    setPlayerNotice(false);
    if (!el.playerModal.hidden) {
        setStatus("Lecteur Reserve charge", "ready");
    }
});

el.embedFrame?.addEventListener("error", () => {
    clearPlayerFallback();
    setPlayerNotice(true, state.activeExternalUrl);
    setStatus("Lecteur externe indisponible en iframe", "");
});
el.searchForm.addEventListener("submit", (event) => {
    event.preventDefault();
    state.query = el.searchInput.value.trim();
    if (el.topbarSearchInput) el.topbarSearchInput.value = state.query;
    state.browseKey = "search";
    state.browseQuery = "";
    state.categoryFilter = "";
    loadAdults();
});
el.searchInput.addEventListener("input", () => {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = window.setTimeout(() => {
        state.query = el.searchInput.value.trim();
        if (el.topbarSearchInput) el.topbarSearchInput.value = state.query;
        state.browseKey = state.query ? "search" : "all";
        state.browseQuery = "";
        state.categoryFilter = "";
        loadAdults();
    }, SEARCH_DELAY_MS);
});
el.topbarSearchInput?.addEventListener("input", () => {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = window.setTimeout(() => {
        state.query = el.topbarSearchInput.value.trim();
        el.searchInput.value = state.query;
        state.browseKey = state.query ? "search" : "all";
        state.browseQuery = "";
        state.categoryFilter = "";
        loadAdults();
    }, SEARCH_DELAY_MS);
});
el.browseRails.addEventListener("click", (event) => {
    const button = event.target.closest("[data-browse-rail]");
    if (!button) return;
    const rail = BROWSE_RAILS.find((item) => item.key === button.dataset.browseRail) || BROWSE_RAILS[0];
    state.browseKey = rail.key;
    state.browseQuery = rail.query || "";
    state.sort = rail.sort || state.sort || "latest";
    state.query = "";
    el.searchInput.value = "";
    if (el.topbarSearchInput) el.topbarSearchInput.value = "";
    state.categoryFilter = "";
    loadAdults();
});
el.sortSelect.addEventListener("change", () => {
    state.sort = el.sortSelect.value || "latest";
    state.categoryFilter = "";
    loadAdults();
});
el.categoryTabs.addEventListener("click", (event) => {
    const button = event.target.closest("[data-adult-category]");
    if (!button) return;
    state.categoryFilter = button.dataset.adultCategory || "";
    renderGrid();
});
el.adultGrid.addEventListener("click", (event) => {
    const category = event.target.closest(".adult-universe-card[data-adult-category]");
    if (category) {
        state.categoryFilter = category.dataset.adultCategory || "";
        renderGrid();
        el.categoryTabs.scrollIntoView({ behavior: "smooth", block: "nearest" });
        return;
    }
    if (event.target.closest("[data-retry-adults]")) {
        loadAdults();
        return;
    }
    if (event.target.closest("[data-load-more-adults]")) {
        loadMoreAdults();
        return;
    }
    const card = event.target.closest("[data-item-id]");
    if (card) {
        playItem(card.dataset.itemId);
    }
});
el.featuredPanel?.addEventListener("click", (event) => {
    const card = event.target.closest("[data-item-id]");
    if (card) {
        playItem(card.dataset.itemId);
    }
});
document.querySelectorAll("[data-close-player]").forEach((button) => {
    button.addEventListener("click", closePlayer);
});
window.addEventListener("beforeunload", () => {
    if (!state.activeSessionToken || !state.token) return;
    fetch(apiUrl(`/stream/close/${state.activeSessionToken}`), {
        method: "DELETE",
        headers: { Authorization: `Bearer ${state.token}` },
        keepalive: true
    }).catch(() => {});
});

boot();
