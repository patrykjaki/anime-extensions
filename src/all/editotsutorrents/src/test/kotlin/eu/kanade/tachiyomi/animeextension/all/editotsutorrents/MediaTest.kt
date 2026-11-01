package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private val blackRabbit = Fixtures.text("tosho_show687355.json")
    private val dameDesuYo = Fixtures.text("tosho_show156249.json")

    @Test
    fun packFileFromMediaInfo() {
        val m = MediaInfo.fromShow(blackRabbit, "Frieren (2023)/Frieren - Beyond Journey's End (2023) - S01E05 - Phantoms of the Dead [Bluray-1080p][Opus 2.0][AV1]-BlackRabbit.mkv")!!
        assertEquals("AV1", m.codec)
        assertEquals(10, m.bitDepth)
        assertEquals(1920, m.width)
        assertEquals(1080, m.height)
        assertNull(m.hdr)
        assertEquals(listOf("ja", "en"), m.audio.map { it.lang })
        assertEquals(listOf("Opus", "Opus"), m.audio.map { it.codec })
        assertEquals(listOf(2, 2), m.audio.map { it.channels })
        assertEquals(listOf("en", "en"), m.subtitles.map { it.lang })
        assertEquals(listOf(false, true), m.subtitles.map { it.forced })
        assertEquals("Full Subtitles [9volt]", m.subtitles[0].title)
        assertEquals(1471, m.durationSec) // 24 min 31 s
    }

    @Test
    fun singleFileFromMediaInfo() {
        val m = MediaInfo.fromShow(dameDesuYo, null)!!
        assertEquals("AVC", m.codec)
        assertEquals(10, m.bitDepth)
        assertEquals(720, m.height)
        assertEquals(listOf("ja"), m.audio.map { it.lang })
        assertEquals(listOf("AAC"), m.audio.map { it.codec })
        // the file tags its (English) subtitle track as Japanese; we report the file's tag
        assertEquals(listOf("ja"), m.subtitles.map { it.lang })
        assertEquals("ASS", m.subtitles[0].format)
    }

    @Test
    fun languages() {
        assertEquals("ja", Languages.code("Japanese"))
        assertEquals("en", Languages.code("English"))
        assertEquals("pt-BR", Languages.code("Portuguese (Brazil)"))
        assertEquals("es-419", Languages.code("Spanish (Latin America)"))
        assertEquals("ja", Languages.code("jpn"))
        assertEquals("en-US", Languages.code("en-us"))
        assertNull(Languages.code(""))
        assertEquals("id", Languages.code("Indonesian"))
        assertEquals("he", Languages.code("Hebrew"))
    }

    @Test
    fun hdrAndCodecVocabulary() {
        val report = "Video\nFormat : HEVC\nWidth : 3 840 pixels\nHeight : 2 160 pixels\nBit depth : 10 bits\n" +
            "HDR format : Dolby Vision, Version 1.0, dvhe.08.06, BL+RPU, HDR10 compatible / SMPTE ST 2086, HDR10 compatible\n" +
            "\nAudio\nFormat : E-AC-3 JOC\nCommercial name : Dolby Digital Plus with Dolby Atmos\nChannel(s) : 6 channels\nLanguage : English\n" +
            "\nAudio #2\nFormat : MLP FBA\nCommercial name : Dolby TrueHD with Dolby Atmos\nChannel(s) : 8 channels\nLanguage : Japanese\n"
        val m = MediaInfo.parse(report)!!
        assertEquals("HEVC", m.codec)
        assertEquals("Dolby Vision", m.hdr)
        assertEquals(2160, VideoData.classOf(m.width, m.height))
        assertEquals(listOf("EAC3", "TrueHD"), m.audio.map { it.codec })
        assertEquals(listOf(6, 8), m.audio.map { it.channels })
        assertEquals(1080, VideoData.classOf(1920, 800)) // cropped scope
    }

    @Test
    fun nameTags() {
        val t = ReleaseTags.of("[ToonsHub] Frieren Beyond Journeys End S02E01 1080p NF WEB-DL AAC2.0 H.264 (Sousou no Frieren, Multi-Subs)")
        assertEquals("ToonsHub", t.group)
        assertEquals("WEB-DL", t.source)
        assertEquals("NF", t.service)
        assertEquals("AVC", t.codec)
        assertTrue(t.multiSubs)
        val s = ReleaseTags.of("Re ZERO Starting Life in Another World S04E01 1080p CR WEB-DL DUAL AAC2.0 H 264-VARYG")
        assertEquals("VARYG", s.group)
        assertEquals("CR", s.service)
        assertEquals("dual", s.audio)
        val b = ReleaseTags.of("[EMBER] Frieren (2023) (Season 1) [BDRip] [1080p Dual Audio HEVC 10 bits DDP] (Batch)")
        assertEquals("EMBER", b.group)
        assertEquals("BD", b.source)
        assertEquals("HEVC", b.codec)
        assertEquals(10, b.bitDepth)
        assertEquals("dual", b.audio)
        val r = ReleaseTags.of("[Group] Show - 05v2 [1080p] [Uncensored]")
        assertEquals("v2", r.revision)
        assertEquals("Uncensored", r.censorship)
        assertNull(ReleaseTags.of("[晚街与灯][Re：从零开始的异世界生活]").group)
    }

    @Test
    fun internalDataCarriesChipData() {
        val row = ToshoParser.parseList(Fixtures.text("tosho_aid17617_p1.json")).first { it.infoHash == "05bd3a45348e1338117e89cf1e25fd76b7a30de3" }
        val fileName = "Frieren (2023)/Frieren - Beyond Journey's End (2023) - S01E05 - Phantoms of the Dead [Bluray-1080p][Opus 2.0][AV1]-BlackRabbit.mkv"
        val p = Pick(row, 4, fileName, 336967292L, false, media = MediaInfo.fromShow(blackRabbit, fileName))
        val o = JSONObject(VideoData.internalData(p, 271708))
        val media = o.getJSONObject("media")
        assertEquals("mediainfo", media.getString("from"))
        assertEquals("AV1", media.getString("codec"))
        assertEquals(2, media.getJSONArray("audio").length())
        assertEquals("ja", media.getJSONArray("audio").getJSONObject(0).getString("lang"))
        assertTrue(media.getJSONArray("subtitles").getJSONObject(1).getBoolean("forced"))
        val tags = o.getJSONObject("tags")
        assertEquals("BlackRabbit", tags.getString("group"))
        assertEquals("BD", tags.getString("source"))
        assertEquals(1080, VideoData.resolution(p))
        assertEquals(687355L, row.toshoId)
    }

    @Test
    fun nyaaWithoutMediaInfoSendsNameFields() {
        val nyaa = NyaaParser.parse(Fixtures.text("nyaa_rezero01.xml")).first { it.title.startsWith("[Erai-raws] Re:Zero kara Hajimeru Isekai Seikatsu 4th Season - 01 [1080p CR WEBRip HEVC") }
        val o = JSONObject(VideoData.internalData(Pick(nyaa, 0, null, nyaa.totalSize, true), null))
        val media = o.getJSONObject("media")
        assertEquals("name", media.getString("from"))
        assertEquals("HEVC", media.getString("codec"))
        assertTrue(media.getBoolean("multiSubs"))
        assertEquals("CR", o.getJSONObject("tags").getString("service"))
        assertFalse(o.getJSONObject("media").has("audio"))
    }
}
