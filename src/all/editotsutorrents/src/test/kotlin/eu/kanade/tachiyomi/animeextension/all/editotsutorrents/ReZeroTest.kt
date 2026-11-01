package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import eu.kanade.tachiyomi.animeextension.all.editotsutorrents.Selection.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re:ZERO season 1 episode 1 (anilist 21355, anidb 11370, eid 176270), recorded 2026-11-01.
 * animetosho.net tags S04E01, Shin Henshuu-ban, Director's Cut, Break Time, Hyouketsu no Kizuna
 * and the 01A/01B halves all with this eid; none of them may come out as episode 1.
 */
class ReZeroTest {
    private val ep: EpisodeRef = AniZip.parse(
        Fixtures.text("anizip_anilist21355.json"),
        AniListParser.parseTitles(Fixtures.text("anilist_titles21355.json")),
    ).episodes.first { it.ref.anidbEp == 1 }.ref

    private val wrong = Regex(
        """(?i)S0[2-4]|[234](?:nd|rd|th)\s|Season\s?[234]|Shin\s?Henshuu|Director|Break\s?Time|Hyouketsu|Memory Snow|""" +
            """\b0?1[AB]\b|Episode\s?1[AB]|Part\s?[12]\b|\bOVA""",
    )

    @Test
    fun episodeRefFromFixtures() {
        assertEquals(176270, ep.eid)
        assertEquals(1, ep.season)
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", ep.query) // not ani.zip's "rizero"
        assertTrue(ep.titles.contains("rezerokarahajimeruisekaiseikatsu"))
        assertTrue(ep.titles.contains("rezerostartinglifeinanotherworld"))
        assertEquals(ep, EpisodeRef.fromUrl(ep.toUrl()))
    }

    @Test
    fun animetoshoRows() {
        val rows = listOf("tosho_eid176270.json", "tosho_aid11370_p1.json", "tosho_aid11370_p2.json")
            .flatMap { ToshoParser.parseList(Fixtures.text(it)) }
        val c = Selection.candidates(rows, ep)
        val bad = c.filter { wrong.containsMatchIn(it.row.title) && it is Candidate.Direct }
        assertTrue("wrong rows: ${bad.map { it.row.title }}", bad.isEmpty())
        val direct = c.filterIsInstance<Candidate.Direct>().map { it.row.title }
        assertTrue(direct.any { it.startsWith("[DameDesuYo] ReZero kara Hajimeru Isekai Seikatsu - 01 (1280x720 10bit AAC)") })
        assertTrue(direct.any { it.startsWith("[ToonsHub] Re-Zero kara Hajimeru Isekai Seikatsu S01E01") })
        assertTrue(direct.any { it.startsWith("[Golumpa] Re ZERO Starting Life in Another World - 01") })
        assertTrue(direct.any { it.startsWith("[PHF] Re:ZERO -Starting Life in Another World- S01E01") })
        assertFalse(direct.any { it.contains("S04E01") })
        // the season-1 batch stays, resolved per file later
        assertTrue(c.any { it is Candidate.NeedsFiles && it.row.title == "[Erai-raws] Re.Zero kara Hajimeru Isekai Seikatsu - 01 ~ 25 [1080p][Multiple Subtitle]" })
        // multi-season / edition packs are only kept for their file list, never as a direct row
        c.filterIsInstance<Candidate.NeedsFiles>().forEach { assertFalse(it.row.title, it.row.title.contains("S04E")) }
        println("re:zero s1e1 animetosho: ${direct.size} direct, ${c.size - direct.size} packs")
        direct.forEach { println("  $it") }
    }

    @Test
    fun nyaaRowsNeedTheTitle() {
        val rows = NyaaParser.parse(Fixtures.text("nyaa_rezero01.xml"))
        val c = Selection.candidates(rows, ep)
        val names = c.map { it.row.title }
        assertTrue("wrong rows: ${names.filter { wrong.containsMatchIn(it) }}", names.none { wrong.containsMatchIn(it) })
        assertTrue(names.any { it.startsWith("[DeadFish] Re:Zero kara Hajimeru Isekai Seikatsu - 01 [720p]") })
        assertTrue(names.any { it.startsWith("[TPABR] Re:Zero kara Hajimeru Isekai Seikatsu - 01 [WEBRip.1080p]") })
        // all-bracket names with no readable title are dropped for a title-search source
        assertTrue(names.none { it.startsWith("[Xrip]") || it.startsWith("[晚街") })
        println("re:zero s1e1 nyaa: $names")
    }

    @Test
    fun rejectReasons() {
        fun r(n: String) = EpisodeMatcher.rejectReason(n, ep, requireTitle = false)
        assertEquals(null, r("[DameDesuYo] ReZero kara Hajimeru Isekai Seikatsu - 01 (1280x720 10bit AAC) [F9A34879].mkv"))
        assertEquals(null, r("[FLE] Re ZERO Starting Life in Another World - S01E01 (WEB 1080p) | Re: Zero Kara Hajimeru"))
        assertTrue(r("[HorribleSubs] Re Zero kara Hajimeru Isekai Seikatsu - 01B [720p].mkv")!!.startsWith("split"))
        assertTrue(r("[KamiS3] Re - ZERO - Starting Life in Another World - Episode 1B [1080p]")!!.startsWith("split"))
        assertTrue(r("[BakedFish] Re:Zero kara Hajimeru Isekai Seikatsu - 01 - Part 1 [720p][AAC].mp4")!!.startsWith("split"))
        assertTrue(r("[Judas] Re:Zero Kara Hajimeru Isekai Seikatsu - Shin Henshuu-Ban - 01 [1080p]")!!.startsWith("edition"))
        assertTrue(r("[Hakata Ramen] Re: Zero kara Hajimeru Isekai Seikatsu (Director's Cut) 01 [1080p HEVC] HR-GZ")!!.startsWith("edition"))
        assertTrue(r("[Serene] ReZero kara Hajimeru Break Time - 01 [TV 720p].mkv") != null)
        assertTrue(r("[Anime Time] Re Zero kara Hajimeru Isekai Seikatsu Hyouketsu no Kizuna - 1 [1080p].mp4")!!.startsWith("title continues"))
    }
}
