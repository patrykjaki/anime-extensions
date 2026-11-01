package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/**
 * Decides whether a release/file name is about one episode. This is matching, not tagging:
 * it only reads episode numbers so rows without an anidb eid can be placed. Group, codec,
 * censorship etc. stay untouched for the app's parser.
 */
object EpisodeMatcher {
    sealed interface Numbering {
        data class Single(val ep: Int, val season: Int?) : Numbering
        data class Range(val from: Int, val to: Int, val season: Int?) : Numbering
        data class SeasonPack(val season: Int) : Numbering
        data object Batch : Numbering
        data object Unknown : Numbering
    }

    private val SXXEYY = Regex("""(?i)(?<![a-z0-9])S(\d{1,2})\s?E(\d{1,4})(?:v\d+)?(?![0-9])""")
    private val SXXEYY_RANGE = Regex("""(?i)(?<![a-z0-9])S(\d{1,2})\s?E(\d{1,4})\s?[-~]\s?E?(\d{1,4})(?![0-9])""")
    private val BRACKET_RANGE = Regex("""(?i)[(\[]\s*(?:E|EP|Episodes?)?\s*(\d{1,4})\s*[-~]\s*(?:E|EP)?\s*(\d{1,4})\s*(?:END|FIN)?\s*[)\]]""")
    private val DASH_RANGE = Regex("""\s-\s(\d{1,4})\s*[-~]\s*(\d{1,4})(?=[\s\[(.]|$)""")
    private val DASH_EP = Regex("""\s-\s#?(\d{1,4})(?:v\d+)?(?:\s?END)?(?=[\s\[(.]|$)""")
    private val EP_WORD = Regex("""(?i)(?<![a-z0-9])(?:EP?|Episode|#)\s?\.?\s?(\d{1,4})(?:v\d+)?(?![0-9a-z])""")
    private val SEASON_ONLY = Regex("""(?i)(?<![a-z0-9])(?:S(\d{1,2})|Season\s?(\d{1,2}))(?![0-9a-z])""")
    private val SEASON_WORD = Regex("""(?i)(\d{1,2})(?:st|nd|rd|th)\s+Season""")
    private val BATCH = Regex("""(?i)\b(?:batch|complete|bd\s?box)\b""")

    fun numbering(name: String): Numbering {
        val n = name.substringAfterLast('/')
        SXXEYY_RANGE.find(n)?.let { m ->
            val (s, a, b) = m.destructured
            if (a.toInt() < b.toInt()) return Numbering.Range(a.toInt(), b.toInt(), s.toInt())
        }
        SXXEYY.find(n)?.let { m -> return Numbering.Single(m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        val season = seasonOf(n)
        (BRACKET_RANGE.find(n) ?: DASH_RANGE.find(n))?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a < b && b - a < 500) return Numbering.Range(a, b, season)
        }
        DASH_EP.find(n)?.let { m -> return Numbering.Single(m.groupValues[1].toInt(), season) }
        EP_WORD.find(n)?.let { m -> return Numbering.Single(m.groupValues[1].toInt(), season) }
        if (season != null) return Numbering.SeasonPack(season)
        if (BATCH.containsMatchIn(n)) return Numbering.Batch
        return Numbering.Unknown
    }

    private fun seasonOf(n: String): Int? {
        SEASON_ONLY.find(n)?.let { m -> return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt() }
        SEASON_WORD.find(n)?.let { m -> return m.groupValues[1].toInt() }
        return null
    }

    /** The name is exactly this episode. */
    fun isEpisode(name: String, ep: EpisodeRef): Boolean = when (val x = numbering(name)) {
        is Numbering.Single -> numberMatches(x.ep, x.season, ep)
        else -> false
    }

    /** The name may contain this episode among others (needs the file list to be sure). */
    fun mayContain(name: String, ep: EpisodeRef): Boolean = when (val x = numbering(name)) {
        is Numbering.Range -> seasonOk(x.season, ep) &&
            (ep.anidbEp in x.from..x.to || (ep.absolute != null && ep.absolute in x.from..x.to) ||
                (ep.seasonEp != null && x.season != null && ep.seasonEp in x.from..x.to))
        is Numbering.SeasonPack -> ep.season == null || x.season == ep.season
        Numbering.Batch -> true
        else -> false
    }

    private fun seasonOk(nameSeason: Int?, ep: EpisodeRef): Boolean =
        nameSeason == null || ep.season == null || nameSeason == ep.season

    private fun numberMatches(n: Int, nameSeason: Int?, ep: EpisodeRef): Boolean {
        if (nameSeason != null && ep.season != null) {
            // "S02E03": compare with the tvdb season numbering
            if (nameSeason == ep.season && n == (ep.seasonEp ?: ep.anidbEp)) return true
            // some groups number S2 absolutely ("S02E29")
            return nameSeason == ep.season && n == ep.absolute
        }
        return n == ep.anidbEp || n == ep.absolute
    }

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "ts", "m2ts", "mov", "wmv", "flv")

    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** Pick the file for [ep] from a torrent's file list; null when no single file matches. */
    fun pickFile(files: List<TorrentFile>, ep: EpisodeRef, rowIsThisEpisode: Boolean): TorrentFile? {
        val videos = files.filter { !it.isPad && isVideo(it.path) }
        if (videos.isEmpty()) return null
        val matching = videos.filter { isEpisode(it.path, ep) }
        if (matching.isNotEmpty()) return matching.maxBy { it.size }
        // a torrent tagged with this episode whose only video has no number in its name.
        // (animetosho tags some whole-season batches with the first episode's eid, so never
        // guess among several videos.)
        return if (rowIsThisEpisode && videos.size == 1) videos.single() else null
    }
}
