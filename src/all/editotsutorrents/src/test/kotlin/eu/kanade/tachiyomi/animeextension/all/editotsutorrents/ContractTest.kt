package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ContractTest {
    private val row = ToshoParser.parseList(Fixtures.text("tosho_aid17617_p1.json"))
        .first { it.infoHash == "05bd3a45348e1338117e89cf1e25fd76b7a30de3" }
    private val pick = Pick(row, 4, "Show/S01E05.mkv", 336967292L, false)

    @Test
    fun magnetIsReadableByEditotsu() {
        val url = VideoData.magnet(pick)
        assertTrue(url.startsWith("magnet:?xt=urn:btih:05bd3a45348e1338117e89cf1e25fd76b7a30de3&dn="))
        // SelectorDialogFragment / ExoplayerView: url.substringAfter("index=").toIntOrNull() ?: 0
        assertEquals(4, url.substringAfter("index=").toIntOrNull())
        // TorrentServerManager.parseMagnetHash: decode whole url, btih up to the first '&'
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertEquals("05bd3a45348e1338117e89cf1e25fd76b7a30de3", decoded.substringAfter("xt=urn:btih:").substringBefore('&'))
        // the name must not leak a raw '&' or "index=" before the real index
        assertEquals(1, Regex("index=").findAll(url).count())
        assertFalse(url.substringAfter("&dn=").substringBefore("&tr=").contains(' '))
    }

    @Test
    fun internalDataFields() {
        val o = JSONObject(VideoData.internalData(pick, targetEid = 271708))
        assertEquals(1, o.getInt("editotsu"))
        assertEquals(row.infoHash, o.getString("infoHash"))
        assertEquals(4, o.getInt("fileIdx"))
        assertEquals(row.torrentName, o.getString("torrentName"))
        assertEquals("Show/S01E05.mkv", o.getString("fileName"))
        assertEquals(336967292L, o.getLong("sizeBytes"))
        assertEquals(42, o.getInt("seeders"))
        assertEquals(5, o.getInt("leechers"))
        assertEquals("nekobt", o.getString("tracker"))
        assertEquals(271708, o.getInt("anidbEid"))
        // raw facts only: no parsed tags
        for (k in listOf("group", "censored", "revision", "codec", "audio", "source", "seadex")) {
            if (k == "source") continue // "source" = which feed, not a release tag
            assertFalse("unexpected key $k", o.has(k))
        }
    }

    @Test
    fun unknownValuesAreOmitted() {
        val nyaa = NyaaParser.parse(Fixtures.text("nyaa_frieren05.xml")).first()
        val o = JSONObject(VideoData.internalData(Pick(nyaa, 0, null, nyaa.totalSize, true), targetEid = null))
        assertFalse(o.has("fileName"))
        assertFalse(o.has("anidbEid"))
        assertTrue(o.getBoolean("sizeApprox"))
        assertEquals("nyaa", o.getString("source"))
    }

    @Test
    fun titleCarriesWhatTheAppParserReads() {
        val t = VideoData.title(pick.copy(row = row.copy(numFiles = 28)))
        val lines = t.lines()
        assertEquals(row.title, lines[0])
        assertEquals("S01E05.mkv", lines[1])
        // the app's regexes (SourceRowClassifier REGEX_SEEDERS / REGEX_SIZE / REGEX_COG_VALUE)
        assertEquals("42", Regex("""👤\s*(\d+)""").find(t)!!.groupValues[1])
        assertEquals("321.4 MB", Regex("""💾\s*([\d.]+\s*[KMGT]?B)""", RegexOption.IGNORE_CASE).find(t)!!.groupValues[1])
        assertEquals("nekoBT", Regex("""⚙️\s*([^|\n]+)""").find(t)!!.groupValues[1].trim())
        // single-file row: no file line
        assertEquals(2, VideoData.title(Pick(row.copy(numFiles = 1), 0, null, 1L shl 30, false)).lines().size)
        assertEquals("1.00 GB", VideoData.sizeText(1L shl 30))
    }

    @Test
    fun resolution() {
        assertEquals(1080, VideoData.resolution(null, "[SubsPlease] Sousou no Frieren - 05 (1080p)"))
        assertEquals(1080, VideoData.resolution("[Beatrice-Raws] Sousou no Frieren 05 [BDRip 1920x1080 HEVC TrueHD].mkv"))
        assertEquals(2160, VideoData.resolution("Frieren.S01.2160p.WEB-DL"))
        assertEquals(2160, VideoData.resolution("[Tenrai-Sensei] Frieren [BD][4k][HEVC]"))
        assertEquals(null, VideoData.resolution("[Group] Show - 05"))
    }
}
