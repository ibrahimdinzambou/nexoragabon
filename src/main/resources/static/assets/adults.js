const API_ROOT = window.NexoraApi?.root?.() || "/api";
const TOKEN_KEY = "nexora_access_token";
const AGE_KEY = "nexora_adults_age_confirmed";
const CATEGORY_ID = "adults-eporner";
const SEARCH_DELAY_MS = 750;
const INITIAL_LIMIT = 32;
const PAGE_LIMIT = 32;
const BROWSE_RAILS = [
    { key: "all", label: "Tout", query: "", note: "Flux global" },
    { key: "hd", label: "HD", query: "hd", note: "Images nettes" },
    { key: "amateur", label: "Amateur", query: "amateur", note: "Selection amateur" },
    { key: "asian", label: "Asian", query: "asian", note: "Rayon asiatique" },
    { key: "mature", label: "Mature", query: "mature", note: "Mature" },
    { key: "latina", label: "Latina", query: "latina", note: "Latina" },
    { key: "french", label: "French", query: "french", note: "Recherche FR" },
    { key: "long", label: "Longs", query: "", sort: "longest", note: "Durees longues" },
    { key: "rated", label: "Mieux notes", query: "", sort: "top-rated", note: "Top notes" },
    { key: "weekly", label: "Top semaine", query: "", sort: "top-weekly", note: "Tendance" }
];

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
    heartbeatId: null,
    searchTimer: null
};

const el = {
    userLabel: document.querySelector("#userLabel"),
    adminLink: document.querySelector("#adminLink"),
    logoutButton: document.querySelector("#logoutButton"),
    statusBar: document.querySelector("#statusBar"),
    accessLabel: document.querySelector("#accessLabel"),
    searchForm: document.querySelector("#searchForm"),
    searchInput: document.querySelector("#searchInput"),
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
    ageModal: document.querySelector("#ageModal"),
    ageConfirmButton: document.querySelector("#ageConfirmButton"),
    playerModal: document.querySelector("#playerModal"),
    embedFrame: document.querySelector("#embedFrame"),
    playerTitle: document.querySelector("#playerTitle"),
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

function metaChips(item) {
    return [
        "18+",
        durationLabel(item),
        item.rating ? `${item.rating}/5` : "",
        item.views ? `${Number(item.views).toLocaleString("fr-FR")} vues` : ""
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
        (categories.length ? categories : ["Adults"]).forEach((category) => {
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

function cardTemplate(item) {
    return `
        <button class="adult-card" type="button" data-item-id="${escapeHtml(item.id)}">
            <img src="${escapeHtml(imageSource(item.image || item.poster || item.backdrop))}" alt="" loading="lazy" decoding="async">
            <span class="adult-card-copy">
                <strong>${escapeHtml(item.name || "Adults")}</strong>
                <small>${escapeHtml(itemCategories(item)[0] || item.categoryName || "Adults")}</small>
                <span class="adult-chip-line">
                    ${metaChips(item).map((chip) => `<span>${escapeHtml(chip)}</span>`).join("")}
                </span>
            </span>
        </button>
    `;
}

function renderGroupedItems(items) {
    const groups = new Map();
    items.forEach((item) => {
        const label = itemCategories(item)[0] || "Adults";
        const key = categoryKey(label);
        if (!groups.has(key)) groups.set(key, { label, items: [] });
        groups.get(key).items.push(item);
    });
    return Array.from(groups.values()).map((group) => `
        <section class="adult-category-section">
            <div class="adult-category-head">
                <h2>${escapeHtml(group.label)}</h2>
                <span>${group.items.length} video(s)</span>
            </div>
            <div class="adult-card-grid">${group.items.map(cardTemplate).join("")}</div>
        </section>
    `).join("");
}

function renderGrid() {
    if (!state.token || !state.hasAccess) {
        el.adultGrid.innerHTML = "";
        renderBrowseRails();
        renderCategoryTabs();
        return;
    }
    if (state.loading) {
        renderBrowseRails();
        el.adultGrid.innerHTML = '<div class="adult-skeleton-grid"><span></span><span></span><span></span><span></span><span></span><span></span></div>';
        renderCategoryTabs();
        return;
    }
    renderBrowseRails();
    syncCategories();
    renderCategoryTabs();
    const items = visibleItems();
    if (!items.length) {
        el.adultGrid.innerHTML = '<div class="adult-empty">Aucun resultat pour cette recherche.</div>';
        return;
    }
    const content = state.categoryFilter
        ? `<div class="adult-card-grid">${items.map(cardTemplate).join("")}</div>`
        : renderGroupedItems(items);
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

function mergeItems(nextItems, replace = false) {
    const values = replace ? [] : [...state.items];
    const seen = new Set(values.map((item) => item.id));
    (nextItems || []).forEach((item) => {
        if (item?.id && String(item.id).startsWith("eporner~video~") && !seen.has(item.id)) {
            seen.add(item.id);
            values.push(item);
        }
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
    setStatus("Synchronisation Adults...", "loading");
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
        setStatus("Adults indisponible", "");
        el.adultGrid.innerHTML = `<div class="adult-empty">Provider temporairement indisponible.<button class="adult-retry" type="button" data-retry-adults>Reessayer</button></div>`;
        showToast(error.message || "Impossible de charger Adults.");
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
    if (!String(itemId || "").startsWith("eporner~video~")) {
        showToast("Cette page ouvre uniquement les contenus Adults Eporner.");
        return;
    }
    if (state.openingItemId) return;
    const item = state.items.find((value) => value.id === itemId);
    if (!item) return;
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
            throw new Error("Le provider Adults doit s'ouvrir en iframe.");
        }
        state.activeSessionToken = stream.token;
        el.playerTitle.textContent = item.name || "Adults";
        el.embedFrame.src = resolveApiResourceUrl(stream.proxyUrl);
        el.playerModal.hidden = false;
        startHeartbeat();
        setStatus("Lecture Adults ouverte", "ready");
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
        setStatus("Verification 18+", "");
        return;
    }
    loadAdults();
}

el.ageConfirmButton.addEventListener("click", confirmAge);
el.loginForm.addEventListener("submit", submitLogin);
el.logoutButton.addEventListener("click", logout);
el.searchForm.addEventListener("submit", (event) => {
    event.preventDefault();
    state.query = el.searchInput.value.trim();
    state.browseKey = "search";
    state.browseQuery = "";
    state.categoryFilter = "";
    loadAdults();
});
el.searchInput.addEventListener("input", () => {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = window.setTimeout(() => {
        state.query = el.searchInput.value.trim();
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
