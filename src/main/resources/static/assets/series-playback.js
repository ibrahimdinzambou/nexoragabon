(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.NexoraSeriesPlayback = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    const TITLE_FIELDS = [
        "seriesName",
        "seriesTitle",
        "parentTitle",
        "name",
        "title",
        "originalTitle",
        "seriesOriginalTitle",
        "original_name",
        "originalName",
        "contentNexoraTitle"
    ];

    function positiveInteger(value) {
        const number = Number(value);
        return Number.isFinite(number) && number > 0 ? Math.trunc(number) : 0;
    }

    function normalizeSeriesTitle(value) {
        return String(value || "")
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .toLowerCase()
            .replace(/\b(?:saison|season)\s*\d+\b.*$/i, "")
            .replace(/\bs\d+\s*e\d+\b.*$/i, "")
            .replace(/\b(?:episode|ep\.?|e)\s*\d+\b.*$/i, "")
            .replace(/\((?:19|20)\d{2}\)/g, " ")
            .replace(/\b(?:4k|uhd|fhd|hd|sd|multi|vostfr|vf|vff)\b$/i, "")
            .replace(/[^a-z0-9]+/g, " ")
            .replace(/\s+/g, " ")
            .trim();
    }

    function titleValues(value) {
        if (!value || typeof value !== "object") return [];
        return [...new Set(TITLE_FIELDS
            .map((field) => normalizeSeriesTitle(value[field]))
            .filter(Boolean))];
    }

    function releaseYear(value) {
        if (!value || typeof value !== "object") return 0;
        const candidates = [
            value.seriesReleaseYear,
            value.releaseYear,
            value.year,
            value.firstAirYear,
            value.first_air_date,
            value.firstAirDate,
            value.releaseDate,
            ...TITLE_FIELDS.map((field) => value[field])
        ];
        for (const candidate of candidates) {
            const match = String(candidate || "").match(/(?:19|20)\d{2}/);
            if (match) return positiveInteger(match[0]);
        }
        return 0;
    }

    function externalId(value, ...fields) {
        for (const field of fields) {
            const candidate = String(value?.[field] || "").trim().toLowerCase();
            if (candidate) return candidate;
        }
        return "";
    }

    function tokenSimilarity(left, right) {
        const leftTokens = new Set(String(left || "").split(" ").filter(Boolean));
        const rightTokens = new Set(String(right || "").split(" ").filter(Boolean));
        if (!leftTokens.size || !rightTokens.size) return 0;
        const intersection = [...leftTokens].filter((token) => rightTokens.has(token)).length;
        const union = new Set([...leftTokens, ...rightTokens]).size;
        return union ? intersection / union : 0;
    }

    function contentType(value) {
        const type = String(value?.type || value?.contentType || "").toLowerCase();
        if (["series", "tv", "season", "episode"].includes(type)) return "series";
        if (["movie", "film"].includes(type)) return "movie";
        return "";
    }

    function sameContent(requested, candidate, expectedType = "") {
        if (!requested || !candidate) return false;
        const requestedType = contentType(requested);
        const candidateType = contentType(candidate);
        const requiredType = contentType({ type: expectedType });
        if (requiredType && requestedType && requestedType !== requiredType) return false;
        if (requiredType && candidateType && candidateType !== requiredType) return false;
        if (requestedType && candidateType && requestedType !== candidateType) return false;

        const requestedTmdb = positiveInteger(requested.tmdbId || requested.tmdb_id);
        const candidateTmdb = positiveInteger(candidate.tmdbId || candidate.tmdb_id);
        if (requestedTmdb && candidateTmdb && requestedTmdb !== candidateTmdb) return false;

        const requestedImdb = externalId(requested, "imdbId", "imdb_id");
        const candidateImdb = externalId(candidate, "imdbId", "imdb_id");
        if (requestedImdb && candidateImdb && requestedImdb !== candidateImdb) return false;

        const requestedYear = releaseYear(requested);
        const candidateYear = releaseYear(candidate);
        if (requestedYear && candidateYear && requestedYear !== candidateYear) return false;

        if (requestedTmdb && candidateTmdb || requestedImdb && candidateImdb) return true;

        const requestedTitles = titleValues(requested);
        const candidateTitles = titleValues(candidate);
        if (!requestedTitles.length || !candidateTitles.length) return false;
        if (requestedTitles.some((title) => candidateTitles.includes(title))) return true;

        return requestedTitles.some((left) => candidateTitles.some((right) => (
            left.length >= 8
            && right.length >= 8
            && tokenSimilarity(left, right) >= 0.86
        )));
    }

    function sameSeries(requested, candidate) {
        return sameContent(requested, candidate, "series");
    }

    function seasonNumber(value, fallback = 0) {
        const declared = positiveInteger(
            value?.season ?? value?.seasonNumber ?? value?.season_number ?? value?.number
        );
        if (declared) return declared;
        const text = `${value?.name || ""} ${value?.title || ""} ${value?.url || ""}`;
        return positiveInteger(text.match(/(?:saison|season|\bs)\s*[-_:]?\s*(\d+)/i)?.[1])
            || positiveInteger(fallback);
    }

    function episodeNumber(value, fallback = 0) {
        const declared = positiveInteger(
            value?.episode ?? value?.episodeNumber ?? value?.episode_number ?? value?.number ?? value?.index
        );
        if (declared) return declared;
        const text = `${value?.name || ""} ${value?.title || ""} ${value?.url || ""}`;
        return positiveInteger(text.match(/\bs\d+\s*e(\d+)\b/i)?.[1])
            || positiveInteger(text.match(/(?:episode|ep\.?|\be)\s*[-_:]?\s*(\d+)/i)?.[1])
            || positiveInteger(fallback);
    }

    function episodeGroupRank(language) {
        const preferred = ["vf", "fr", "vostfr", "vjstfr", "vastfr", "vo"];
        const rank = preferred.indexOf(String(language || "").toLowerCase());
        return rank < 0 ? preferred.length : rank;
    }

    function groupedEpisodeCollections(value) {
        if (!value || typeof value !== "object" || Array.isArray(value)) return [];
        const sourceFields = new Set([
            "players",
            "sources",
            "hosters",
            "links",
            "streams",
            "qualities"
        ]);
        return Object.entries(value)
            .filter(([language, entries]) => (
                !sourceFields.has(String(language).toLowerCase())
                && Array.isArray(entries)
                && entries.length
            ))
            .map(([language, entries]) => ({ language, entries }));
    }

    function episodeGroups(episodes) {
        if (!episodes || typeof episodes !== "object") return [];

        if (!Array.isArray(episodes)) {
            return groupedEpisodeCollections(episodes)
                .sort((left, right) => episodeGroupRank(left.language) - episodeGroupRank(right.language));
        }

        const groups = new Map();
        const flatEntries = [];
        episodes.forEach((entry) => {
            const nestedGroups = episodeNumber(entry) ? [] : groupedEpisodeCollections(entry);
            if (!nestedGroups.length) {
                flatEntries.push(entry);
                return;
            }
            nestedGroups.forEach(({ language, entries }) => {
                const key = String(language || "");
                groups.set(key, [...(groups.get(key) || []), ...entries]);
            });
        });

        if (flatEntries.length) groups.set("", [...(groups.get("") || []), ...flatEntries]);
        return [...groups.entries()]
            .map(([language, entries]) => ({ language, entries }))
            .sort((left, right) => episodeGroupRank(left.language) - episodeGroupRank(right.language));
    }

    function episodeNumbers(episodes) {
        const numbers = new Set();
        episodeGroups(episodes).forEach((group) => {
            group.entries.forEach((entry, index) => {
                const number = episodeNumber(entry, index + 1);
                if (number) numbers.add(number);
            });
        });
        return [...numbers].sort((left, right) => left - right);
    }

    function episodeEntries(episodes, requestedEpisode) {
        const wanted = positiveInteger(requestedEpisode);
        if (!wanted) return [];
        return episodeGroups(episodes)
            .map((group) => {
                const match = group.entries.find((entry, index) => episodeNumber(entry, index + 1) === wanted);
                return match ? { ...match, contentNexoraLanguage: group.language } : null;
            })
            .filter(Boolean);
    }

    function findEpisode(series, requestedSeason, requestedEpisode) {
        const seasonWanted = positiveInteger(requestedSeason);
        const episodeWanted = positiveInteger(requestedEpisode);
        if (!seasonWanted || !episodeWanted) return null;
        const season = (series?.seasons || []).find((entry) => seasonNumber(entry) === seasonWanted);
        if (!season) return null;
        const episode = (season.episodes || []).find((entry, index) => (
            episodeNumber(entry, index + 1) === episodeWanted
        ));
        return episode ? { season, episode } : null;
    }

    function remoteEpisodeEntries(content, requestedSeason, requestedEpisode) {
        const seasonWanted = positiveInteger(requestedSeason);
        const episodeWanted = positiveInteger(requestedEpisode);
        if (!seasonWanted || !episodeWanted) return [];
        const seasons = Array.isArray(content?.seasons) ? content.seasons : [];
        const season = seasons.find((entry, index) => seasonNumber(entry, index + 1) === seasonWanted);
        return season ? episodeEntries(season.episodes, episodeWanted) : [];
    }

    return {
        positiveInteger,
        normalizeSeriesTitle,
        titleValues,
        releaseYear,
        sameContent,
        sameSeries,
        seasonNumber,
        episodeNumber,
        episodeGroups,
        episodeNumbers,
        episodeEntries,
        findEpisode,
        remoteEpisodeEntries
    };
});
