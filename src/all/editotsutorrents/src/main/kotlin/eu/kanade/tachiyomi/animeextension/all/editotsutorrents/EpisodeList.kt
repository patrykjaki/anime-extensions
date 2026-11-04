package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/**
 * Builds the episode list from the ani.zip mapping. AniList is asked only when nothing
 * cached covers it and it's needed: no AniDB entry (episode count + titles for the Nyaa
 * search) or ani.zip has no titles. Otherwise the entry's own title ([fallbackTitle], the
 * AniList romaji from the catalogue) plus ani.zip's titles are enough for matching.
 */
object EpisodeList {
    fun build(
        fallbackTitle: String,
        aniZipBody: String,
        cachedAniList: String?,
        fetchAniList: () -> String?,
    ): List<AniZip.Episode> {
        var aniListBody = cachedAniList
        fun titles(): List<String> =
            aniListBody?.let { runCatching { AniListParser.parseTitles(it) }.getOrNull() }
                ?.ifEmpty { null } ?: listOf(fallbackTitle)

        var mapping = AniZip.parse(aniZipBody, titles())
        val zipHasTitles = mapping.romaji != null || mapping.english != null
        if (aniListBody == null && (mapping.aid == null || !zipHasTitles)) {
            aniListBody = fetchAniList()
            if (mapping.aid != null && aniListBody != null) mapping = AniZip.parse(aniZipBody, titles())
        }
        if (mapping.aid != null) return mapping.episodes

        // no AniDB entry yet: episodes from AniList, releases by title search
        val aniListTitles = titles()
        val query = aniListTitles.firstOrNull { it.isNotBlank() } ?: fallbackTitle
        return NoAniDb.episodes(aniListBody?.let(NoAniDb::airedCount) ?: 0, query, Titles.keys(aniListTitles))
            .ifEmpty { throw Exception("No AniDB entry and no aired episodes on AniList") }
    }
}
