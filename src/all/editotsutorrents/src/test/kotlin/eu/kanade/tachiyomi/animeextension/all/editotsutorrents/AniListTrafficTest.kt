package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AniList requests per episode-list open. Before 16.8 every open sent one Media(id) request,
 * cached or not, AniDB entry or not. Now: none when ani.zip has the AniDB entry and titles,
 * one for a show without an AniDB entry, none again while that answer is cached.
 */
class AniListTrafficTest {
    private val reZeroZip = Fixtures.text("anizip_anilist21355.json")
    private val reZeroAniList = Fixtures.text("anilist_titles21355.json")
    private val noAniDbZip = """{"mappings":{"anilist_id":206814}}"""
    private val noAniDbAniList =
        """{"data":{"Media":{"title":{"romaji":"Dragon Ball Super: Beerus","english":null},"synonyms":[],"episodes":null,"status":"RELEASING","nextAiringEpisode":{"episode":4}}}}"""

    private var calls = 0
    private fun fetch(body: String?): () -> String? = { calls++; body }

    /** what getEpisodeList does: cache first, fetch stores into the cache */
    private fun open(id: Int, title: String, zip: String, aniList: String): List<AniZip.Episode> {
        val key = AniListCache.mediaKey(id)
        return EpisodeList.build(title, zip, AniListCache.get(key)) {
            calls++
            aniList.also { AniListCache.putResponse(key, it) }
        }
    }

    @Before
    fun reset() {
        AniListCache.cache.clear()
        calls = 0
    }

    @Test
    fun aniDbEntryNeedsNoAniList() {
        val eps = EpisodeList.build("Re:Zero kara Hajimeru Isekai Seikatsu", reZeroZip, null, fetch(reZeroAniList))
        assertEquals(0, calls)
        val ep1 = eps.first { it.ref.anidbEp == 1 }.ref
        assertEquals(176270, ep1.eid)
        // the catalogue title, not ani.zip's "rizero"
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", ep1.query)
        assertTrue(ep1.titles.contains("rezerostartinglifeinanotherworld"))
    }

    @Test
    fun cachedAniListTitlesAreStillUsed() {
        val eps = EpisodeList.build("whatever", reZeroZip, reZeroAniList, fetch(null))
        assertEquals(0, calls)
        val ep1 = eps.first().ref
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", ep1.query)
        assertTrue(ep1.titles.contains("rezero")) // AniList synonym
    }

    @Test
    fun noAniDbEntryAsksOnceThenUsesTheCache() {
        val first = open(206814, "Dragon Ball Super: Beerus", noAniDbZip, noAniDbAniList)
        assertEquals(1, calls)
        assertEquals(3, first.size)
        assertEquals(NoAniDb.AID, first.first().ref.aid)
        val second = open(206814, "Dragon Ball Super: Beerus", noAniDbZip, noAniDbAniList)
        assertEquals(1, calls)
        assertEquals(first, second)
    }

    @Test
    fun aniZipWithoutTitlesAsksAniList() {
        val zip = """{"mappings":{"anidb_id":11370},"episodes":{"1":{"episode":"1","anidbEid":176270}}}"""
        val eps = EpisodeList.build("Re:Zero", zip, null, fetch(reZeroAniList))
        assertEquals(1, calls)
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", eps.single().ref.query)
    }

    @Test
    fun aniListDownStillGivesTheAniDbList() {
        val zip = """{"mappings":{"anidb_id":11370},"episodes":{"1":{"episode":"1","anidbEid":176270}}}"""
        val eps = EpisodeList.build("Re:Zero", zip, null, fetch(null))
        assertEquals(1, calls)
        assertEquals("Re:Zero", eps.single().ref.query)
    }

    @Test
    fun searchPageSeedsEveryShowItReturns() {
        assertTrue(AniListCache.putResponse("page:x", Fixtures.text("anilist_search_frieren.json")))
        val frieren = AniListCache.get(AniListCache.mediaKey(154587))
        assertNotNull(frieren)
        assertEquals(listOf("Sousou no Frieren", "Frieren: Beyond Journey’s End"), AniListParser.parseTitles(frieren!!))
        assertNotNull(AniListCache.get(AniListCache.mediaKey(182255)))
        assertNotNull(AniListCache.get("page:x"))
    }

    @Test
    fun errorBodiesAreNotCached() {
        assertFalse(AniListCache.putResponse("page:y", """{"data":null,"errors":[{"message":"Too Many Requests.","status":429}]}"""))
        assertFalse(AniListCache.putResponse("page:y", "<html>429</html>"))
        assertNull(AniListCache.get("page:y"))
    }

    @Test
    fun cacheExpiresAfterTtl() {
        val c = TtlCache(ttlMs = 1000, maxEntries = 10)
        c.put("a", "1", now = 0)
        assertEquals("1", c.get("a", now = 999))
        assertNull(c.get("a", now = 1000))
    }

    @Test
    fun cacheDropsOldestPastMax() {
        val c = TtlCache(ttlMs = 1000, maxEntries = 2)
        c.put("a", "1", now = 0)
        c.put("b", "2", now = 0)
        c.put("c", "3", now = 0)
        assertNull(c.get("a", now = 0))
        assertEquals("2", c.get("b", now = 0))
        assertEquals("3", c.get("c", now = 0))
    }

    @Test
    fun pacerSpacesBurstsAndHoldsAfter429() {
        val p = AniListPacer(2000)
        assertEquals(0L, p.reserve(10_000))
        assertEquals(2000L, p.reserve(10_000)) // second of a burst waits one gap
        assertEquals(3000L, p.reserve(11_000)) // third: two gaps after the first
        assertEquals(0L, p.reserve(20_000)) // idle long enough: no wait
        p.holdUntil(30_000)
        assertEquals(9000L, p.reserve(21_000))
    }
}
