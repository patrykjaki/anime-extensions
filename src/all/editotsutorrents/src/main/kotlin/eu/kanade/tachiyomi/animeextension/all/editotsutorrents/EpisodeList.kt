package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/**
 * Builds the episode list from the ani.zip mapping. The catalogue (Kitsu, or AniList for
 * entries picked before 16.9) is asked only when nothing cached covers it and it's needed:
 * no AniDB entry (episode count + titles for the Nyaa search) or ani.zip has no titles.
 * Otherwise the entry's own title ([fallbackTitle], the catalogue's romaji) plus ani.zip's
 * titles are enough for matching.
 */
object EpisodeList {
    /** what the catalogue says about an entry. [airedCount] null = it can't tell. */
    data class Info(val titles: List<String>, val airedCount: Int?)

    fun build(
        fallbackTitle: String,
        aniZipBody: String?,
        cached: Info?,
        fetch: (needCount: Boolean) -> Info?,
    ): List<AniZip.Episode> {
        var info = cached
        fun titles(): List<String> = info?.titles?.ifEmpty { null } ?: listOf(fallbackTitle)

        fun parse() = aniZipBody?.let { runCatching { AniZip.parse(it, titles()) }.getOrNull() }
            ?: AniZip.Mapping(null, null, null, emptyList())
        var mapping = parse()
        val zipHasTitles = mapping.romaji != null || mapping.english != null
        val needCount = mapping.aid == null && info?.airedCount == null
        if ((info == null && !zipHasTitles) || needCount) {
            fetch(needCount)?.let { info = it }
            if (mapping.aid != null && info != null) mapping = parse()
        }
        if (mapping.aid != null) return mapping.episodes

        // no AniDB entry yet: episodes from the catalogue, releases by title search
        val catalogueTitles = titles()
        val query = catalogueTitles.firstOrNull { it.isNotBlank() } ?: fallbackTitle
        return NoAniDb.episodes(info?.airedCount ?: 0, query, Titles.keys(catalogueTitles))
            .ifEmpty { throw Exception("No AniDB entry and no aired episodes known") }
    }

    /** AniList Media(id) body -> Info; AniList always gives a count (0 when it doesn't say) */
    fun fromAniList(body: String): Info? = runCatching {
        Info(AniListParser.parseTitles(body), NoAniDb.airedCount(body))
    }.getOrNull()?.takeIf { it.titles.isNotEmpty() }
}

/** The network calls an episode-list open may need; the source implements them with its caches. */
interface EpisodeApi {
    /** ani.zip mapping body, null when ani.zip doesn't know the entry */
    fun aniZip(id: ShowUrl.Id): String?
    fun kitsuMedia(kitsuId: Int): Kitsu.Media
    fun kitsuEpisodes(kitsuId: Int, page: Int): Kitsu.EpisodePage
    /** AniList Media(id) body */
    fun aniListMedia(aniListId: Int): String

    /** in-memory answers from earlier calls, without a request */
    fun cachedAniList(aniListId: Int): String? = null
    fun cachedKitsu(kitsuId: Int): EpisodeList.Info? = null
    fun rememberKitsu(kitsuId: Int, info: EpisodeList.Info) {}
}

object EpisodeLoader {
    private const val KITSU_EPISODE_PAGES = 5

    fun load(id: ShowUrl.Id, title: String, api: EpisodeApi): List<AniZip.Episode> {
        val zip = api.aniZip(id)
        return when (id) {
            is ShowUrl.Id.AniList -> {
                val cached = api.cachedAniList(id.id)?.let(EpisodeList::fromAniList)
                EpisodeList.build(title, zip, cached) { aniListInfo(api, id.id) }
            }
            is ShowUrl.Id.Kitsu -> EpisodeList.build(title, zip, api.cachedKitsu(id.id)) { needCount ->
                val k = kitsuInfo(api, id.id, needCount)
                // AniList only when Kitsu can't answer
                val info = if (k != null && (!needCount || k.airedCount != null)) {
                    k
                } else {
                    zip?.let(AniZip::aniListId)?.let { aniListInfo(api, it) }
                        ?.let { a -> if (k != null) a.copy(titles = k.titles) else a } ?: k
                }
                info?.also { if (it.airedCount != null || !needCount) api.rememberKitsu(id.id, it) }
            }
        }
    }

    private fun aniListInfo(api: EpisodeApi, aniListId: Int): EpisodeList.Info? =
        runCatching { api.aniListMedia(aniListId) }.getOrNull()?.let(EpisodeList::fromAniList)

    /** Kitsu titles, and with [needCount] the aired count from its episode list (null if it can't tell) */
    private fun kitsuInfo(api: EpisodeApi, kitsuId: Int, needCount: Boolean): EpisodeList.Info? {
        val media = runCatching { api.kitsuMedia(kitsuId) }.getOrNull() ?: return null
        if (!needCount) return EpisodeList.Info(media.titles, null)
        val eps = runCatching {
            buildList {
                for (page in 1..KITSU_EPISODE_PAGES) {
                    val p = api.kitsuEpisodes(kitsuId, page)
                    addAll(p.episodes)
                    if (!Kitsu.needsMoreEpisodes(p)) break
                }
            }
        }.getOrNull() ?: return EpisodeList.Info(media.titles, null)
        return EpisodeList.Info(media.titles, Kitsu.airedCount(media, eps))
    }
}
