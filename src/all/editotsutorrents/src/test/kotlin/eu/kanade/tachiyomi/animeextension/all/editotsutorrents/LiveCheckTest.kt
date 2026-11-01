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

    @Test
    fun live() {
        assumeTrue(System.getenv("EDITOTSU_LIVE") == "1")
        run(154587, 5) // Sousou no Frieren, old show, eid-tagged rows + batches
        run(182255, 1) // Sousou no Frieren season 2
    }
}
