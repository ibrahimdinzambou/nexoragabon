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

test("le lecteur intégré retire la sandbox incompatible avec certains flux", () => {
    const watch = fs.readFileSync(path.join(__dirname, "../../main/resources/static/watch.html"), "utf8");
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/static/assets/app.js"), "utf8");
    const frame = watch.match(/<iframe\s+id="embedPlayer"[^>]+>/)?.[0] || "";
    assert.doesNotMatch(frame, /\ssandbox(?:=|\s|>)/);
    assert.match(app, /removeAttribute\("sandbox"\)/);
    assert.doesNotMatch(app, /EMBED_PLAYER_SANDBOX/);
});
