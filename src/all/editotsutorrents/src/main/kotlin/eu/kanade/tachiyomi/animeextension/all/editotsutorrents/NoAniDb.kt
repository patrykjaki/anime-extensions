package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject

/**
 * Entries ani.zip has no AniDB id for (new shows, re-edits such as "Dragon Ball Super:
 * Beerus"): the episode list comes from AniList and releases from a title search only.
 */
object NoAniDb {
    /** aid of episodes without an AniDB entry; no AnimeTosho row carries it. */
    const val AID = 0

    /**
     * Episodes out according to AniList: those before the next airing one, else the
     * total for a finished show. 0 when AniList doesn't say.
     */
    fun airedCount(aniListBody: String): Int {
        val m = runCatching { JSONObject(aniListBody).optJSONObject("data")?.optJSONObject("Media") }
            .getOrNull() ?: return 0
        val next = m.optJSONObject("nextAiringEpisode")?.optInt("episode", 0) ?: 0
        if (next > 0) return next - 1
        if (m.optString("status") == "NOT_YET_RELEASED") return 0
        return m.optInt("episodes", 0).coerceAtLeast(0)
    }

    fun episodes(count: Int, query: String, titles: List<String>): List<AniZip.Episode> =
        (1..count).map { n ->
            AniZip.Episode(
                ref = EpisodeRef(
                    aid = AID, eid = null, anidbEp = n, season = null, seasonEp = n, absolute = n,
                    query = query, titles = titles,
                ),
                title = null, airDateMillis = null, image = null, overview = null,
            )
        }
}
