package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * Hits the real services with the same parsers/selection the source uses (no okhttp/app classes).
 * Off by default; run with EDITOTSU_LIVE=1 ./gradlew :src:all:editotsutorrents:testDebugUnitTest --tests '*LiveCheck*'
 */
class LiveCheckTest {
    private fun get(url: String): ByteArray {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        return c.inputStream.use { it.readBytes() }.also { check(c.responseCode == 200) { "HTTP ${c.responseCode} $url" } }
    }

    private fun run(anilistId: Int, episode: Int) {
        val mapping = AniZip.parse(String(get("https://api.ani.zip/mappings?anilist_id=$anilistId")))
        val ep = mapping.episodes.first { it.ref.anidbEp == episode }.ref
        val rows = buildList {
            ep.eid?.let { addAll(ToshoParser.parseList(String(get("https://feed.animetosho.net/json?eid=$it")))) }
            for (p in 1..2) addAll(ToshoParser.parseList(String(get("https://feed.animetosho.net/json?aid=${ep.aid}&page=$p"))))
        }
        val cands = Selection.candidates(rows, ep)
        val direct = cands.filterIsInstance<Selection.Candidate.Direct>().map(Selection::direct)
        val files = Selection.toFetch(cands, 10).mapNotNull { c ->
            runCatching { Bencode.parseTorrent(get(c.row.torrentUrl!!))?.let { Selection.fromFiles(c, it, ep) } }
                .onFailure { println("  torrent fetch failed: ${c.row.title}: $it") }.getOrNull()
        }
        var picks = direct + files
        if (picks.isEmpty()) {
            val q = Text.urlEncode("${ep.query} ${ep.anidbEp.toString().padStart(2, '0')}")
            val nyaa = NyaaParser.parse(String(get("https://nyaa.si/?page=rss&c=1_0&f=0&q=$q")))
            picks = Selection.candidates(nyaa, ep).filterIsInstance<Selection.Candidate.Direct>().map(Selection::direct)
            println("  nyaa fallback: ${picks.size}")
        }
        println("anilist $anilistId ep $episode: aid=${ep.aid} eid=${ep.eid} rows=${rows.size} cands=${cands.size} picks=${picks.size} (direct ${direct.size}, from .torrent ${files.size})")
        Selection.sort(picks).take(6).forEach { p ->
            println("  [${p.row.tracker}] s=${p.row.seeders} idx=${p.fileIdx} res=${VideoData.resolution(p.fileName, p.row.torrentName)} ${VideoData.title(p).replace('\n', '|').take(150)}")
        }
        Selection.sort(picks).take(4).forEach { p ->
            val url = p.row.toshoId?.let { "https://feed.animetosho.net/json?show=torrent&id=$it" }
                ?: "https://feed.animetosho.net/json?show=torrent&btih=${p.row.infoHash}"
            val m = runCatching { MediaInfo.fromShow(String(get(url)), p.fileName) }.getOrNull()
            println("  media ${p.row.title.take(50)}: " + (m?.let { "${it.codec} ${it.bitDepth}bit ${VideoData.classOf(it.width, it.height)}p hdr=${it.hdr} audio=${it.audio.map { a -> "${a.lang}/${a.codec}/${a.channels}ch" }} subs=${it.subtitles.map { s -> "${s.lang}${if (s.forced) "(forced)" else ""}" }}" } ?: "none"))
        }
        Selection.sort(picks).firstOrNull()?.let { println("  magnet: " + VideoData.magnet(it).take(160) + " ... " + VideoData.magnet(it).takeLast(12)); println("  data: " + VideoData.internalData(it, ep.eid)) }
    }

    /** No AniDB entry: AniList episode count, Nyaa title search only. Prints counts. */
    private fun runNoAniDb(anilistId: Int, episode: Int) {
        val q = "query(\$id:Int){Media(id:\$id,type:ANIME){title{romaji english} synonyms episodes status nextAiringEpisode{episode}}}"
        val body = org.json.JSONObject().put("query", q).put("variables", org.json.JSONObject().put("id", anilistId)).toString()
        val c = URI("https://graphql.anilist.co").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val aniList = c.inputStream.use { String(it.readBytes()) }
        val titles = AniListParser.parseTitles(aniList)
        val eps = NoAniDb.episodes(NoAniDb.airedCount(aniList), titles.first(), Titles.keys(titles))
        val ep = eps.first { it.ref.anidbEp == episode }.ref
        val qs = Text.urlEncode("${ep.query} ${ep.anidbEp.toString().padStart(2, '0')}")
        val nyaa = NyaaParser.parse(String(get("https://nyaa.si/?page=rss&c=1_0&f=0&q=$qs")))
        val cands = Selection.candidates(nyaa, ep)
        println("anilist $anilistId (no anidb): episodes=${eps.size} nyaa rows=${nyaa.size} candidates=${cands.size} direct=${cands.count { it is Selection.Candidate.Direct }}")
    }

    @Test
    fun liveNoAniDb() {
        assumeTrue(System.getenv("EDITOTSU_LIVE") == "1")
        runNoAniDb(206814, 1) // Dragon Ball Super: Beerus (re-edit, no AniDB entry)
    }

    @Test
    fun live() {
        assumeTrue(System.getenv("EDITOTSU_LIVE") == "1")
        run(154587, 5) // Sousou no Frieren, old show, eid-tagged rows + batches
        run(182255, 1) // Sousou no Frieren season 2
    }
}
