const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const series = require("../../main/resources/static/assets/series-playback.js");

test("normalise les accents, variantes de ponctuation et suffixes de saison", () => {
    assert.equal(series.normalizeSeriesTitle("L'Été d'Adèle — Saison 2"), "l ete d adele");
    assert.equal(series.normalizeSeriesTitle("L ETE D ADELE - season 2"), "l ete d adele");
});

test("refuse une série homonyme d'une autre année", () => {
    assert.equal(series.sameSeries(
        { name: "Shōgun", type: "series", releaseYear: 2024 },
        { title: "Shogun - Saison 1", type: "series", year: 1980 }
    ), false);
});

test("refuse des identifiants TMDB incohérents même si le titre correspond", () => {
    assert.equal(series.sameSeries(
        { name: "Dark", type: "series", tmdbId: 70523 },
        { title: "Dark", type: "series", tmdbId: 99999 }
    ), false);
});

test("conserve les numéros d'épisode non continus", () => {
    const episodes = {
        vf: [
            { episode_number: 5, title: "Episode 5" },
            { episode_number: 8, title: "Episode 8" }
        ],
        vostfr: [{ title: "S02E08" }]
    };
    assert.deepEqual(series.episodeNumbers(episodes), [5, 8]);
    assert.equal(series.episodeEntries(episodes, 8).length, 2);
    assert.equal(series.episodeEntries(episodes, 6).length, 0);
});

test("ne cherche l'épisode que dans la saison demandée", () => {
    const tree = {
        seasons: [
            { season: 1, episodes: [{ episode: 3, id: "s1e3" }] },
            { season: 2, episodes: [{ episode: 3, id: "s2e3" }] }
        ]
    };
    assert.equal(series.findEpisode(tree, 2, 3).episode.id, "s2e3");
    assert.equal(series.findEpisode(tree, 3, 3), null);
    assert.equal(series.findEpisode(tree, 2, 4), null);
});

test("ne retombe pas sur la première saison d'une réponse Content-Nexora", () => {
    const content = {
        seasons: [
            { season_number: 1, episodes: { vf: [{ episode: 2, players: ["s1e2"] }] } },
            { season_number: 2, episodes: { vf: [{ episode: 2, players: ["s2e2"] }] } }
        ]
    };
    assert.equal(series.remoteEpisodeEntries(content, 2, 2)[0].players[0], "s2e2");
    assert.deepEqual(series.remoteEpisodeEntries(content, 3, 2), []);
    assert.deepEqual(series.remoteEpisodeEntries(content, 2, 3), []);
});

test("déplie les groupes de langues enveloppés dans la liste d'épisodes Content-Nexora", () => {
    const content = {
        seasons: [{
            season: 1,
            episodes: [{
                vf: [
                    { episode: 1, players: [{ url: "https://vf.example/s1e1" }] },
                    { episode: 2, players: [{ url: "https://vf.example/s1e2" }] }
                ],
                vostfr: [
                    { episode: 1, players: [{ url: "https://vostfr.example/s1e1" }] }
                ]
            }]
        }]
    };

    assert.deepEqual(series.episodeNumbers(content.seasons[0].episodes), [1, 2]);
    const episode = series.remoteEpisodeEntries(content, 1, 1);
    assert.equal(episode.length, 2);
    assert.deepEqual(episode.map((entry) => entry.contentNexoraLanguage), ["vf", "vostfr"]);
    assert.equal(episode[0].players[0].url, "https://vf.example/s1e1");
    assert.deepEqual(series.remoteEpisodeEntries(content, 1, 3), []);
});

test("fait correspondre un titre TMDB original avec le résultat Content-Nexora", () => {
    assert.equal(series.sameContent(
        {
            type: "movie",
            name: "Le Voyage de Chihiro",
            originalTitle: "Sen to Chihiro no kamikakushi",
            releaseYear: 2001,
            tmdbId: 129
        },
        {
            type: "movie",
            title: "Sen to Chihiro no kamikakushi (2001)"
        },
        "movie"
    ), true);
});

test("refuse un homonyme Content-Nexora d'une autre année", () => {
    assert.equal(series.sameContent(
        { type: "movie", name: "Gladiator", releaseYear: 2024 },
        { type: "movie", title: "Gladiator (2000)" },
        "movie"
    ), false);
});

test("rapproche une card TMDB de son résultat Content par l'affiche officielle", () => {
    assert.equal(series.sameContent(
        {
            type: "movie",
            name: "Titre localisé TMDB",
            releaseYear: 2024,
            poster: "https://image.tmdb.org/t/p/w500/abc123poster.jpg"
        },
        {
            type: "movie",
            title: "Titre français Content (2024)",
            image: "https://image.tmdb.org/t/p/w300/abc123poster.jpg"
        },
        "movie"
    ), true);
});

test("rapproche aussi une affiche TMDB brute d'une image Content proxifiée", () => {
    assert.equal(series.sameContent(
        {
            type: "series",
            name: "Titre TMDB",
            releaseYear: 2024,
            tmdbPosterPath: "/season-poster.jpg"
        },
        {
            type: "series",
            title: "Titre Content différent - Saison 1 (2024)",
            image: "https://image.tmdb.org/t/p/w400/season-poster.jpg"
        },
        "series"
    ), true);
});

test("normalise les titres TMDB avant rapprochement Content-Nexora", () => {
    assert.equal(series.sameContent(
        {
            type: "series",
            name: "Que ça vous serve de leçon !",
            releaseYear: 2026,
            tmdbId: 276161
        },
        {
            type: "series",
            title: "Que a vous serve de leon - Saison 1 2026"
        },
        "series"
    ), true);
});

test("les anciennes cards TMDB s'affichent comme sources FR avec Content en principal", () => {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/app.js"), "utf8");
    const watch = fs.readFileSync(path.join(__dirname, "../../main/resources/static/watch.html"), "utf8");
    assert.match(app, /playbackProvider:\s*"content-nexora"/);
    assert.match(app, /primaryPlaybackProvider:\s*"content-nexora"/);
    assert.match(app, /fallbackPlaybackProvider:\s*"videasy"/);
    assert.match(app, /contentNexoraMatchCacheKey/);
    assert.match(app, /const endpoint = isTmdbCatalogItem\(item\) \? "\/content" : "\/series";/);
    assert.match(app, /retryTransient:\s*true/);
    assert.match(app, /TMDB_CONTENT_HYDRATION_CONCURRENCY\s*=\s*2/);
    assert.match(app, /IntersectionObserver/);
    assert.match(app, /maxQueries:\s*TMDB_CONTENT_HYDRATION_TITLE_LIMIT/);
    assert.match(app, /observeVisibleTmdbCardsForContentHydration\(\)/);
    assert.match(app, /contentSearchTitles/);
    assert.match(app, /tmdbContentSearchTitleVariants/);
    assert.match(app, /metadataProvider/);
    assert.match(app, /isTmdbCatalogItem\(value\)[\s\S]*?french-badge/);
    assert.doesNotMatch(app, />TMDB<|TMDB→FR/);
    assert.doesNotMatch(watch, /lecteur TMDB/i);
    assert.ok(app.indexOf("await playContentNexoraItem(playbackItem)") < app.indexOf("await playTmdbItem({"));
});

test("le catalogue affiche Content-Nexora et garde Anime-Nexora pour les animes", () => {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/app.js"), "utf8");
    const runtime = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/runtime-config.js"), "utf8");
    const watch = fs.readFileSync(path.join(__dirname, "../../main/resources/static/watch.html"), "utf8");
    const application = fs.readFileSync(path.join(__dirname, "../../main/resources/application.yml"), "utf8");

    assert.match(app, /CONTENT_NEXORA_CATALOG_SEEDS\s*=\s*\{/);
    assert.match(app, /browseContentNexoraCatalog\(type,\s*requestedLimit/);
    assert.match(app, /contentNexoraCatalogCategories\(\)/);
    assert.match(app, /directAnimeNexoraCategories\(\)/);
    assert.match(app, /function animeNexoraApiEnabled\(\)\s*\{\s*return Boolean\(ANIME_NEXORA_API_ROOT\);/);
    assert.match(app, /directAnimeItemsPromise/);
    assert.match(app, /shouldLoadAnimeNexoraCatalog\(type,\s*query\)/);
    assert.match(app, /isAnimeNexoraItem\(item\) && !activeAnimeNexoraCategory\(\) && !query/);
    assert.match(app, /hasContentNexoraResults && isTmdbCatalogItem\(item\)/);
    assert.match(runtime, /\/api\/external\/anime/);
    assert.match(application, /enabled:\s*\$\{ANIME_NEXORA_ENABLED:\$\{CONSUMET_ENABLED:false\}\}/);
    assert.match(watch, /runtime-config\.js\?v=20260728-content-catalog-1/);
    assert.match(watch, /app\.js\?v=20260728-content-catalog-1/);
});

test("Voir plus recharge aussi les rayons de l'accueil", () => {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/app.js"), "utf8");
    assert.match(app, /homeVisibleCatalog:\s*\{\s*live:\s*HOME_PREVIEW_LIMIT/);
    assert.match(app, /state\.activeType === "all"\s*\? state\.homeVisibleCatalog/);
    assert.match(app, /data-load-more="\$\{shelf\.type\}"/);
});

test("le super admin n'est jamais bloqué par la date d'abonnement dans l'interface", () => {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/app.js"), "utf8");
    assert.match(app, /function subscriptionIsUsable[\s\S]*?if \(isSuperAdminUser\(\)\) return true;/);
    assert.match(app, /"Accès illimité"/);
});

test("le lecteur intégré retire la sandbox incompatible avec certains flux", () => {
    const watch = fs.readFileSync(path.join(__dirname, "../../main/resources/static/watch.html"), "utf8");
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/app.js"), "utf8");
    const frame = watch.match(/<iframe\s+id="embedPlayer"[^>]+>/)?.[0] || "";
    assert.doesNotMatch(frame, /\ssandbox(?:=|\s|>)/);
    assert.match(app, /removeAttribute\("sandbox"\)/);
    assert.doesNotMatch(app, /EMBED_PLAYER_SANDBOX/);
});
