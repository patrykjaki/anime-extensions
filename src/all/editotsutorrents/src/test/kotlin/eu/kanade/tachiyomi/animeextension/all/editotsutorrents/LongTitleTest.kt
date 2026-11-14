package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTitleTest {
    private val romaji = "Tsuihou Sareta Cheat Fuyo Majutsushi wa Kimama na Second Life wo Ouka Suru.: Ore wa Buki dake ja Naku, " +
        "Arayuru Mono ni \"Kyouka Point\" wo Fuyo Dekiru shi, Ore no Ishi de Itsudemo Kouka wo Kaijo Dekiru kedo, Nokotta Hitotachi Daijoubu?"
    private val english = "The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life"
    private val ep = EpisodeRef(
        aid = NoAniDb.AID, eid = null, anidbEp = 1, season = null, seasonEp = 1, absolute = 1,
        query = romaji, titles = Titles.keys(listOf(romaji, english)),
    )

    @Test
    fun `a very long title is searched and matched by its part before the colon`() {
        assertEquals("Tsuihou Sareta Cheat Fuyo Majutsushi wa Kimama na Second Life wo Ouka Suru", Titles.short(romaji))
        for (name in listOf(
            "[Judas] Tsuihou sareta Cheat Fuyo Majutsushi wa Kimama na Second Life wo Ouka suru - S01E01 (Weekly) [1080p][HEVC x265 10bit][Dual-Audio]",
            "[Erai-raws] The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life - 01 [1080p CR WEB-DL AVC AAC][MultiSub]",
        )) {
            assertTrue(name, EpisodeMatcher.isEpisode(name, ep))
            assertNull(name, EpisodeMatcher.rejectReason(name, ep, requireTitle = true))
        }
    }

    @Test
    fun `a normal title keeps its colon`() {
        assertEquals("Fate/stay night: Unlimited Blade Works", Titles.short("Fate/stay night: Unlimited Blade Works"))
    }

    @Test
    fun `both titles are searched in both number forms`() {
        val q = TitleSearch.queries(ep.copy(altQuery = english))
        assertEquals(5, q.size)
        assertTrue(q.contains("Tsuihou Sareta Cheat Fuyo Majutsushi wa Kimama na Second Life wo Ouka Suru 01"))
        assertTrue(q.contains("The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life S01E01"))
    }

    @Test
    fun `a title-search row tagged with an AniDB id still matches an entry that has none`() {
        val row = ToshoParser.parseRow(
            org.json.JSONObject()
                .put("info_hash", "0123456789abcdef0123456789abcdef01234567")
                .put("title", "[Erai-raws] The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life - 01 [1080p CR WEB-DL AVC AAC][MultiSub].mkv")
                .put("num_files", 1).put("anidb_aid", 19000).put("anidb_eid", 300000),
        )!!
        assertTrue(Selection.candidates(listOf(row), ep).isEmpty())
        val cands = Selection.candidates(TitleSearch.untagged(listOf(row)), ep)
        assertTrue(cands.single() is Selection.Candidate.Direct)
    }
}
