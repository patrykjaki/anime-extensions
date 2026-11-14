package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/** Search phrases for an episode of an entry that has no AniDB id, so only a title search can find it. */
object TitleSearch {
    /**
     * Each title is searched as "Title 03" and "Title S01E03" (groups use one or the other), and
     * the main title once without a number for packs; the matcher decides which rows are this
     * episode. Apostrophes are left out: release names write "Demons' Crest" as "Demons Crest"
     * as often as not.
     */
    fun queries(ep: EpisodeRef): List<String> {
        val number = ep.anidbEp.toString().padStart(2, '0')
        val season = ep.effectiveSeason.toString().padStart(2, '0')
        // the nickname's hyphen goes too: "Chii-Fuyo" is released as "Chii Fuyo"
        val titles = (listOf(ep.query, ep.altQuery).map(Titles::short) + ep.nickQuery.replace('-', ' '))
            .filter { it.isNotBlank() }.map { it.replace(Regex("['’`]"), "") }.distinct()
        return titles.flatMap { listOf("$it $number", "$it S${season}E$number") } + titles.take(1)
    }

    /**
     * Rows of a title search for an entry we have no AniDB id for. Their AniDB tags can't be
     * compared with anything, and the matcher drops a tagged row whose id differs from the
     * entry's, so the tags are removed and the rows are judged by title and numbers like any
     * other title-search row.
     */
    fun untagged(rows: List<TorrentRow>): List<TorrentRow> = rows.map { it.copy(anidbAid = null, anidbEid = null) }
}
