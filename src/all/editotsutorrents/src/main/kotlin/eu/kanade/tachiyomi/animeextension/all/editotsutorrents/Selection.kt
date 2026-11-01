package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/** Feed rows -> candidates for one episode, without network access (tested on fixtures). */
object Selection {
    sealed interface Candidate {
        val row: TorrentRow

        /** single-file torrent tagged with / named as this episode: playable as is, index 0 */
        data class Direct(override val row: TorrentRow) : Candidate

        /**
         * Needs the .torrent file list. [priority]: 0 = tagged/named as this episode,
         * 1 = name says it covers a range/season containing it, 2 = untagged multi-file
         * release of the same anidb anime whose name states no numbers.
         */
        data class NeedsFiles(override val row: TorrentRow, val rowIsThisEpisode: Boolean, val priority: Int) : Candidate
    }

    fun candidates(rows: List<TorrentRow>, ep: EpisodeRef): List<Candidate> {
        val seen = HashSet<String>()
        return rows.mapNotNull { r ->
            if (!seen.add(r.infoHash)) return@mapNotNull null
            if (r.anidbAid != null && r.anidbAid != ep.aid) return@mapNotNull null
            val tagged = ep.eid != null && r.anidbEid == ep.eid
            if (r.anidbEid != null && !tagged) return@mapNotNull null // feed says: another episode
            val names = listOf(r.torrentName, r.title).distinct()
            // a tag is not enough: the name's own numbers must not say another episode/season
            if (names.any { EpisodeMatcher.contradicts(it, ep) }) return@mapNotNull null
            // title-search rows (no anidb aid) must carry this entry's title
            val requireTitle = r.anidbAid == null
            val isThis = tagged || names.any { EpisodeMatcher.isEpisode(it, ep) }
            val mayContain = !isThis && names.any { EpisodeMatcher.mayContain(it, ep) }
            val anonymousBatch = !isThis && !mayContain && r.anidbAid == ep.aid && (r.numFiles ?: 0) > 1 &&
                names.all { EpisodeMatcher.numbering(it) == EpisodeMatcher.Numbering.Unknown }
            val singleFile = r.numFiles == 1 || (r.numFiles == null && r.source == "nyaa" && isThis && !tagged)
            // single files are judged by their name now; packs by the name of the file picked later
            if (singleFile || requireTitle) {
                if (names.any { EpisodeMatcher.rejectReason(it, ep, requireTitle) != null }) {
                    return@mapNotNull null
                }
            }
            when {
                isThis && singleFile -> Candidate.Direct(r)
                isThis -> Candidate.NeedsFiles(r, true, 0)
                mayContain -> Candidate.NeedsFiles(r, false, 1)
                anonymousBatch -> Candidate.NeedsFiles(r, false, 2)
                else -> null
            }
        }
    }

    /** which multi-file candidates to fetch .torrent files for */
    fun toFetch(cands: List<Candidate>, max: Int): List<Candidate.NeedsFiles> =
        cands.filterIsInstance<Candidate.NeedsFiles>()
            .filter { it.row.torrentUrl != null }
            .sortedWith(compareBy<Candidate.NeedsFiles> { it.priority }.thenByDescending { it.row.seeders ?: -1 })
            .take(max)

    fun direct(c: Candidate.Direct): Pick =
        Pick(c.row, 0, null, c.row.totalSize, c.row.sizeApprox)

    fun fromFiles(c: Candidate.NeedsFiles, meta: Bencode.TorrentMeta, ep: EpisodeRef): Pick? {
        val f = EpisodeMatcher.pickFile(meta.files, ep, c.rowIsThisEpisode) ?: return null
        val extra = if (c.row.trackersEncoded.isEmpty()) meta.announce.map(Text::urlEncode) else emptyList()
        return Pick(c.row, f.index, f.path, f.size, false, extra)
    }

    /** most seeded first; unknown seeders last */
    fun sort(picks: List<Pick>): List<Pick> = picks.sortedByDescending { it.row.seeders ?: -1 }
}
