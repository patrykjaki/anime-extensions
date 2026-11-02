package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Torrent source for Editotsu. Catalogue = AniList; episodes = ani.zip (anidb ids);
 * releases = feed.animetosho.net (anidb-indexed), Nyaa RSS when that has nothing.
 * Every video carries the editotsu internalData contract (see [VideoData]).
 */
class EditotsuTorrents : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Editotsu Torrents"
    override val baseUrl = "https://animetosho.net"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addInterceptor(AniListRetry).build()
    }

    // ============================== Settings ==============================

    // the app stores this source's settings under "source_<id>"
    private val preferences: SharedPreferences? by lazy {
        runCatching { Injekt.get<Application>().getSharedPreferences("source_$id", 0) }.getOrNull()
    }

    private val showOldRevisions: Boolean
        get() = preferences?.getBoolean(PREF_OLD_REVISIONS, false) ?: false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_OLD_REVISIONS
            title = "Show older versions of a release"
            summary = "Also list v1 next to v2 (and the original next to a REPACK/PROPER). " +
                "Off: only the newest version that has seeders."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Catalogue (AniList) ==============================

    private fun aniListRequest(query: String, variables: JSONObject): Request {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        return Request.Builder()
            .url(ANILIST_URL)
            .headers(headers)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
    }

    private fun pageRequest(page: Int, sort: String, search: String?): Request {
        val vars = JSONObject().put("page", page).put("sort", sort)
        search?.let { vars.put("search", it) }
        return aniListRequest(if (search == null) PAGE_QUERY else SEARCH_QUERY, vars)
    }

    override fun popularAnimeRequest(page: Int) = pageRequest(page, "POPULARITY_DESC", null)
    override fun popularAnimeParse(response: Response) = parsePage(response)
    override fun latestUpdatesRequest(page: Int) = pageRequest(page, "TRENDING_DESC", null)
    override fun latestUpdatesParse(response: Response) = parsePage(response)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        pageRequest(page, "SEARCH_MATCH", query.trim())
    override fun searchAnimeParse(response: Response) = parsePage(response)

    private fun parsePage(response: Response): AnimesPage {
        val page = AniListParser.parsePage(response.use { it.body.string() })
        return AnimesPage(page.media.map(::toSAnime), page.hasNext)
    }

    private fun toSAnime(m: AniListParser.Media) = SAnime.create().apply {
        url = "/anime/${m.id}"
        title = m.title
        thumbnail_url = m.cover
        description = m.description
        genre = m.genres.joinToString().ifEmpty { null }
        author = m.studios.joinToString().ifEmpty { null }
        status = when (m.status) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
        initialized = true
    }

    private fun anilistId(anime: SAnime): Int =
        anime.url.substringAfterLast('/').toIntOrNull() ?: error("bad anime url ${anime.url}")

    override fun animeDetailsRequest(anime: SAnime) =
        aniListRequest(DETAILS_QUERY, JSONObject().put("id", anilistId(anime)))

    override fun animeDetailsParse(response: Response): SAnime {
        val m = AniListParser.parseSingle(response.use { it.body.string() }) ?: error("AniList returned no media")
        return toSAnime(m)
    }

    override fun getAnimeUrl(anime: SAnime) = "https://anilist.co/anime/${anilistId(anime)}"

    // ============================== Episodes (ani.zip) ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val id = anilistId(anime)
        val aniListTitles = runCatching {
            client.newCall(aniListRequest(TITLES_QUERY, JSONObject().put("id", id))).execute()
                .use { AniListParser.parseTitles(it.body.string()) }
        }.getOrElse { listOf(anime.title) }
        val req = Request.Builder().url("$ANIZIP_URL?anilist_id=$id").headers(headers).build()
        val mapping = client.newCall(req).execute().use { AniZip.parse(it.body.string(), aniListTitles) }
        if (mapping.aid == null) throw Exception("No AniDB mapping for this AniList entry")
        val now = System.currentTimeMillis()
        mapping.episodes
            .filter { (it.airDateMillis ?: 0L) <= now }
            .sortedByDescending { it.ref.anidbEp }
            .map { e ->
                SEpisode.create().apply {
                    url = e.ref.toUrl()
                    episode_number = e.ref.anidbEp.toFloat()
                    name = e.title?.let { "Episode ${e.ref.anidbEp}: $it" } ?: "Episode ${e.ref.anidbEp}"
                    date_upload = e.airDateMillis ?: 0L
                    preview_url = e.image
                    summary = e.overview
                }
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Releases ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = coroutineScope {
        val ep = EpisodeRef.fromUrl(episode.url) ?: return@coroutineScope emptyList()

        val toshoUrls = buildList {
            ep.eid?.let { add("$TOSHO_FEED?eid=$it") }
            for (page in 1..TOSHO_AID_PAGES) add("$TOSHO_FEED?aid=${ep.aid}&page=$page")
        }
        val toshoRows = toshoUrls.map { url ->
            async(Dispatchers.IO) { runCatching { ToshoParser.parseList(getText(url)) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()

        var picks = resolve(Selection.candidates(toshoRows, ep), ep)

        if (picks.isEmpty() && ep.query.isNotBlank()) {
            val q = Text.urlEncode("${ep.query} ${ep.anidbEp.toString().padStart(2, '0')}")
            val nyaaRows = withContext(Dispatchers.IO) {
                runCatching { NyaaParser.parse(getText("$NYAA_URL/?page=rss&c=1_0&f=0&q=$q")) }.getOrDefault(emptyList())
            }
            picks = resolve(Selection.candidates(nyaaRows, ep), ep)
        }

        // before MediaInfo, so superseded revisions don't use up its fetch cap
        if (!showOldRevisions) picks = Selection.dropSuperseded(picks)
        picks = withMedia(picks)

        // one flat list: the app prefixes every title with its hoster name, which its text parser
        // then took for the release group ("Nyaa" cards). The tracker is in the title's ⚙️ part.
        val videos = Selection.sort(picks).map { p -> toVideo(p, ep) }
        if (videos.isEmpty()) emptyList() else videos.toHosterList()
    }

    private suspend fun resolve(cands: List<Selection.Candidate>, ep: EpisodeRef): List<Pick> = coroutineScope {
        val direct = cands.filterIsInstance<Selection.Candidate.Direct>().map(Selection::direct)
        val needFiles = Selection.toFetch(cands, MAX_TORRENT_FETCHES)
        val fromFiles = needFiles.map { c ->
            async(Dispatchers.IO) {
                runCatching {
                    val meta = Bencode.parseTorrent(getBytes(c.row.torrentUrl!!)) ?: return@runCatching null
                    Selection.fromFiles(c, meta, ep)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        direct + fromFiles
    }

    /**
     * Attach MediaInfo to each pick: animetosho rows by listing id, Nyaa rows by info hash
     * (animetosho mirrors most of Nyaa). Rows it doesn't have keep media = null and the app
     * gets the name-derived fields instead. Most-seeded rows first, capped, in parallel.
     */
    private suspend fun withMedia(picks: List<Pick>): List<Pick> = coroutineScope {
        val sorted = Selection.sort(picks)
        val head = sorted.take(MAX_MEDIA_FETCHES)
        val shows = head.map { it.row }.distinctBy { it.infoHash }.map { row ->
            async(Dispatchers.IO) {
                val url = row.toshoId?.let { "$TOSHO_FEED?show=torrent&id=$it" }
                    ?: "$TOSHO_FEED?show=torrent&btih=${row.infoHash}"
                row.infoHash to runCatching { getText(url) }.getOrNull()
            }
        }.awaitAll().toMap()
        head.map { p ->
            val body = shows[p.row.infoHash] ?: return@map p
            p.copy(media = runCatching { MediaInfo.fromShow(body, p.fileName) }.getOrNull())
        } + sorted.drop(MAX_MEDIA_FETCHES)
    }

    private fun toVideo(p: Pick, ep: EpisodeRef) = Video(
        videoUrl = VideoData.magnet(p),
        videoTitle = VideoData.title(p),
        resolution = VideoData.resolution(p),
        internalData = VideoData.internalData(p, ep.eid),
        initialized = true,
    )

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    // ============================== Network ==============================

    private fun getText(url: String): String =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code} for $url")
            r.body.string()
        }

    private fun getBytes(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code} for $url")
            val len = r.body.contentLength()
            if (len > MAX_TORRENT_BYTES) throw Exception("torrent too large: $len")
            r.body.bytes().also { if (it.size > MAX_TORRENT_BYTES) throw Exception("torrent too large") }
        }

    companion object {
        private const val PREF_OLD_REVISIONS = "show_old_revisions"
        private const val ANILIST_URL = "https://graphql.anilist.co"
        private const val ANIZIP_URL = "https://api.ani.zip/mappings"
        private const val TOSHO_FEED = "https://feed.animetosho.net/json"
        private const val NYAA_URL = "https://nyaa.si"
        private const val TOSHO_AID_PAGES = 2
        private const val MAX_TORRENT_FETCHES = 10
        private const val MAX_MEDIA_FETCHES = 40
        private const val MAX_TORRENT_BYTES = 8L * 1024 * 1024
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val MEDIA_FIELDS =
            "id title{romaji english} coverImage{extraLarge large} status description genres studios(isMain:true){nodes{name}}"
        private const val PAGE_QUERY =
            "query(\$page:Int,\$sort:[MediaSort]){Page(page:\$page,perPage:30){pageInfo{hasNextPage} media(type:ANIME,isAdult:false,sort:\$sort){$MEDIA_FIELDS}}}"
        private const val SEARCH_QUERY =
            "query(\$page:Int,\$sort:[MediaSort],\$search:String){Page(page:\$page,perPage:30){pageInfo{hasNextPage} media(type:ANIME,isAdult:false,sort:\$sort,search:\$search){$MEDIA_FIELDS}}}"
        private const val TITLES_QUERY = "query(\$id:Int){Media(id:\$id,type:ANIME){title{romaji english} synonyms}}"
        private const val DETAILS_QUERY = "query(\$id:Int){Media(id:\$id,type:ANIME){$MEDIA_FIELDS}}"
    }
}
