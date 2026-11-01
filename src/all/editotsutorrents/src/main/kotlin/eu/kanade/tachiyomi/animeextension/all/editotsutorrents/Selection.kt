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

    /**
     * v1/v2 of one release are one choice. Picks whose names differ only by the revision tag
     * (v2, REPACK, PROPER) and the CRC are one release: keep the highest revision that has
     * seeders (unknown counts as alive), else the highest; ties go to more seeders. The same
     * name reposted (other tracker/uploader) merges the same way. Single files and packs never
     * merge with each other.
     */
    fun dropSuperseded(picks: List<Pick>): List<Pick> {
        val keys = picks.associateWith(::revisionKey)
        val keep = picks.groupBy { keys.getValue(it) }.values.mapTo(HashSet()) { g ->
            val alive = g.filter { (it.row.seeders ?: 1) > 0 }.ifEmpty { g }
            alive.maxWith(compareBy<Pick>(::revisionOf).thenBy { it.row.seeders ?: -1 })
        }
        return picks.filter { it in keep }
    }

    private fun isPack(p: Pick) = (p.row.numFiles ?: 1) > 1 || p.fileName != null

    private fun names(p: Pick) = listOfNotNull(p.row.torrentName, p.fileName?.substringAfterLast('/'))

    /** 1 for an unrevised release, N for vN, 2 for REPACK/PROPER */
    fun revisionOf(p: Pick): Int = names(p).maxOf { n ->
        val m = REVISION.find(n) ?: return@maxOf 1
        m.groupValues[2].takeIf { it.isNotEmpty() }?.drop(1)?.toInt() ?: 2
    }

    fun revisionKey(p: Pick): String {
        val parts = if (isPack(p)) names(p) else listOf(p.row.torrentName)
        return (if (isPack(p)) "pack|" else "file|") + parts.joinToString("|") { normalizeRelease(it) }
    }

    fun normalizeRelease(name: String): String = name.lowercase()
        .replace(MEDIA_EXT, "")
        .replace(CRC, " ")
        .replace(REVISION_ATTACHED, "")
        .replace(REVISION, " ")
        .replace(SEPARATORS, " ")
        .trim()

    // same tokens ReleaseTags reads as the revision
    private val REVISION = Regex("""(?i)(?<![a-z0-9])(repack\d?|proper)(?![a-z0-9])|(?<![a-z])(v[2-9])(?![a-z0-9])""")
    private val REVISION_ATTACHED = Regex("""(?i)(?<=\d)v[2-9](?![a-z0-9])""")
    private val CRC = Regex("""(?i)[\[(][0-9a-f]{8}[\])]""")
    private val MEDIA_EXT = Regex("""(?i)\.(mkv|mp4|avi|m2ts|webm|ts)$""")
    private val SEPARATORS = Regex("""[\s._\-\[\]()]+""")

    /** most seeded first; unknown seeders last */
    fun sort(picks: List<Pick>): List<Pick> = picks.sortedByDescending { it.row.seeders ?: -1 }
}
