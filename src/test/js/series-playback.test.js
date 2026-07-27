const test = require("node:test");
const assert = require("node:assert/strict");
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
