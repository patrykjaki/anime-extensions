package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/**
 * Decides whether a release/file name is about one episode of one entry. This is matching, not
 * tagging: it reads episode/season numbers and checks the series title so rows can be placed or
 * dropped. Group, codec, censorship etc. stay untouched for the app's parser.
 *
 * animetosho.net's anidb tags are not trusted on their own: it tags other seasons, recut
 * editions (Director's Cut, Shin Henshuu-ban) and spin-offs with the first season's ids
 * (recorded in the Re:ZERO fixtures). So every row's name must also agree with the episode.
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

    /** an episode split into halves: "- 01A", "1B", "Episode 1B", "- 01 - Part 2" */
    private val PART_SPLIT = listOf(
        Regex("""(?i)(?:\s-\s|(?<![a-z0-9])(?:EP?|Episode)\s?\.?\s?)#?\d{1,4}[AB](?=[\s\[(.]|$)"""),
        Regex("""(?i)\s-\s\d{1,4}\s-\sPart\s?\d"""),
    )

    /**
     * Recut editions, extras and spin-off words. A name containing one is not the plain episode,
     * unless the entry's own title contains the word too.
     */
    private val EDITION_WORDS = listOf(
        "director'?s?\\s?cut", "shin\\s?henshuu?[\\s-]?ban", "henshuu?[\\s-]?ban", "recap", "ovas?", "oads?",
        "movie", "gekijou?ban", "break\\s?time", "kyuukei\\s?jikan", "petit", "picture\\s?drama",
        "mini\\s?anime", "nc\\s?op", "nc\\s?ed", "creditless", "preview", "trailer", "pv",
    ).map { Regex("(?i)(?<![a-z0-9])(?:$it)(?![a-z0-9])") }

    /** what may follow the entry's title in a release name before the numbers start */
    private val TITLE_TAIL_OK = Regex("""^(?:s\d{1,2}|season\d{1,2}|\d{1,2}(?:st|nd|rd|th)season|(?:19|20)\d\d|tv|part\d|cour\d)*$""")

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

    /** The name's numbers say it is exactly this episode. */
    fun isEpisode(name: String, ep: EpisodeRef): Boolean = when (val x = numbering(name)) {
        is Numbering.Single -> numberMatches(x.ep, x.season, ep)
        else -> false
    }

    /** The name may contain this episode among others (needs the file list to be sure). */
    fun mayContain(name: String, ep: EpisodeRef): Boolean = when (val x = numbering(name)) {
        is Numbering.Range -> seasonOk(x.season, ep) &&
            (ep.anidbEp in x.from..x.to || (ep.absolute != null && ep.absolute in x.from..x.to) ||
                (ep.seasonEp != null && x.season != null && ep.seasonEp in x.from..x.to))
        is Numbering.SeasonPack -> x.season == ep.effectiveSeason
        Numbering.Batch -> true
        else -> false
    }

    /** The name's numbers say it is something else (another episode, season or range). */
    fun contradicts(name: String, ep: EpisodeRef): Boolean = when (val x = numbering(name)) {
        is Numbering.Single -> !numberMatches(x.ep, x.season, ep)
        is Numbering.Range, is Numbering.SeasonPack -> !mayContain(name, ep)
        Numbering.Batch, Numbering.Unknown -> false
    }

    private fun seasonOk(nameSeason: Int?, ep: EpisodeRef): Boolean =
        nameSeason == null || nameSeason == ep.effectiveSeason

    private fun numberMatches(n: Int, nameSeason: Int?, ep: EpisodeRef): Boolean {
        if (nameSeason != null) {
            // "S04E01" on a season-1 entry is another season, whatever the number says
            if (nameSeason != ep.effectiveSeason) return false
            // "S02E03" counts within the season; some groups number S2 absolutely ("S02E29")
            return n == (ep.seasonEp ?: ep.anidbEp) || n == ep.absolute
        }
        return n == ep.anidbEp || n == ep.absolute
    }

    /**
     * Name-level rejection, independent of numbers: split halves (01A/01B), edition/extra words
     * and series titles that continue past this entry's title ("... Isekai Seikatsu Hyouketsu no
     * Kizuna", "ReZero kara Hajimeru Break Time"). With [requireTitle] (title-search sources
     * that have no anidb id) the name must also start with one of the entry's titles.
     */
    fun rejectReason(name: String, ep: EpisodeRef, requireTitle: Boolean): String? {
        val n = name.substringAfterLast('/')
        if (PART_SPLIT.any { it.containsMatchIn(n) }) return "split episode"
        for (w in EDITION_WORDS) {
            val m = w.find(n) ?: continue
            if (ep.titles.none { it.contains(Titles.key(m.value)) }) return "edition: ${m.value}"
        }
        if (ep.titles.isEmpty()) return null
        val series = Titles.key(seriesPart(n))
        if (series.isEmpty()) return if (requireTitle) "no title" else null
        val best = ep.titles.firstOrNull { series.startsWith(it) }
            ?: return if (requireTitle) "other title" else null
        val tail = series.removePrefix(best)
        return if (TITLE_TAIL_OK.matches(tail)) null else "title continues: $tail"
    }

    private val GROUP_PREFIX = Regex("""^\s*(?:\[[^\]]*]\s*)+""")
    private val NUMBER_MARKERS = listOf(SXXEYY_RANGE, SXXEYY, BRACKET_RANGE, DASH_RANGE, DASH_EP, EP_WORD, SEASON_ONLY, SEASON_WORD)

    /** series title part of a release name: after the [group] tags, before the first number/tag/alt title */
    fun seriesPart(name: String): String {
        var s = name.replace(GROUP_PREFIX, "")
        s = s.substringBefore('|')
        val cut = NUMBER_MARKERS.mapNotNull { it.find(s)?.range?.first }.minOrNull()
        if (cut != null) s = s.substring(0, cut)
        // tags and alternate titles in brackets don't belong to the series title
        s = s.replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
        s = s.substringBefore('[').substringBefore('(')
        // scene names: "Show.Name.S01E05.1080p" -> dots are spaces; drop a trailing resolution
        s = s.replace('.', ' ').replace(Regex("""(?i)\b\d{3,4}p\b.*$"""), "")
        return s.trim()
    }

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "ts", "m2ts", "mov", "wmv", "flv")

    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** Pick the file for [ep] from a torrent's file list; null when no single file matches. */
    fun pickFile(files: List<TorrentFile>, ep: EpisodeRef, rowIsThisEpisode: Boolean): TorrentFile? {
        val videos = files.filter { !it.isPad && isVideo(it.path) }
        if (videos.isEmpty()) return null
        val matching = videos.filter { isEpisode(it.path, ep) && rejectReason(it.path, ep, requireTitle = false) == null }
        if (matching.isNotEmpty()) return matching.maxBy { it.size }
        // a torrent tagged with this episode whose only video has no number in its name.
        // (animetosho tags some whole-season batches with the first episode's eid, so never
        // guess among several videos.)
        val only = videos.singleOrNull() ?: return null
        return if (rowIsThisEpisode && !contradicts(only.path, ep) && rejectReason(only.path, ep, false) == null) only else null
    }
}
