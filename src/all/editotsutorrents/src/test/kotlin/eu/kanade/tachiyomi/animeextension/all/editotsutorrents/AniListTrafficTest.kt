package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Requests per episode-list open, by service. Before 16.8 every open sent one AniList
 * request. 16.8: none when ani.zip has the AniDB entry and titles. 16.9 (Kitsu catalogue):
 * none on the normal path either way; AniList only when Kitsu can't give an episode count.
 */
class AniListTrafficTest {
    private val reZeroZip = Fixtures.text("anizip_anilist21355.json")
    private val reZeroAniList = Fixtures.text("anilist_titles21355.json")
    private val frierenZip = Fixtures.text("anizip_kitsu46474.json")
    private val beerusZip = """{"mappings":{"kitsu_id":50497,"anilist_id":206814,"anidb_id":null}}"""
    private val kusuriyaZip = """{"mappings":{"kitsu_id":50021,"anilist_id":185660,"anidb_id":null}}"""
    private val beerusAniList =
        """{"data":{"Media":{"title":{"romaji":"Dragon Ball Super: Beerus","english":null},"synonyms":[],"episodes":null,"status":"RELEASING","nextAiringEpisode":{"episode":5}}}}"""
    private val kusuriyaAniList =
        """{"data":{"Media":{"title":{"romaji":"Kusuriya no Hitorigoto 3rd Season","english":null},"synonyms":[],"episodes":null,"status":"RELEASING","nextAiringEpisode":{"episode":6}}}}"""

    /** fake network: counts calls per service; caches like the source's in-memory caches */
    private class Api(
        val zips: Map<ShowUrl.Id, String?>,
        val aniList: Map<Int, String> = emptyMap(),
        val kitsuDown: Boolean = false,
    ) : EpisodeApi {
        val calls = mutableMapOf<String, Int>()
        private val kitsuInfo = mutableMapOf<Int, EpisodeList.Info>()
        private val aniListCache = mutableMapOf<Int, String>()
        private fun count(k: String) { calls[k] = (calls[k] ?: 0) + 1 }
        fun n(k: String) = calls[k] ?: 0

        override fun aniZip(id: ShowUrl.Id): String? { count("anizip"); return zips.getValue(id) }
        override fun kitsuMedia(kitsuId: Int): Kitsu.Media {
            count("kitsu")
            if (kitsuDown) throw Exception("Kitsu HTTP 503")
            return Kitsu.parseSingle(Fixtures.text("kitsu_details$kitsuId.json"))!!
        }
        override fun kitsuEpisodes(kitsuId: Int, page: Int): Kitsu.EpisodePage {
            count("kitsu")
            return Kitsu.parseEpisodes(Fixtures.text("kitsu_eps$kitsuId.json"))
        }
        override fun aniListMedia(aniListId: Int): String {
            count("anilist")
            return aniList.getValue(aniListId).also { aniListCache[aniListId] = it }
        }
        override fun cachedAniList(aniListId: Int) = aniListCache[aniListId]
        override fun cachedKitsu(kitsuId: Int) = kitsuInfo[kitsuId]
        override fun rememberKitsu(kitsuId: Int, info: EpisodeList.Info) { kitsuInfo[kitsuId] = info }
    }

    @Before
    fun reset() = AniListCache.cache.clear()

    @Test
    fun kitsuEntryWithAniDbMappingNeedsNoCatalogueRequest() {
        val id = ShowUrl.Id.Kitsu(46474)
        val api = Api(mapOf(id to frierenZip))
        val eps = EpisodeLoader.load(id, "Sousou no Frieren", api)
        assertEquals(0, api.n("anilist"))
        assertEquals(0, api.n("kitsu"))
        assertEquals(1, api.n("anizip"))
        val ep5 = eps.first { it.ref.anidbEp == 5 }.ref
        assertEquals(Fixtures.FRIEREN_EP5.eid, ep5.eid)
        assertEquals("Sousou no Frieren", ep5.query)
    }

    @Test
    fun noAniDbEntryCountsFromKitsu() {
        val id = ShowUrl.Id.Kitsu(50497)
        val api = Api(mapOf(id to beerusZip))
        val eps = EpisodeLoader.load(id, "Dragon Ball Super: Beerus", api)
        assertEquals(0, api.n("anilist"))
        assertEquals(2, api.n("kitsu")) // details + one episodes page
        assertTrue(eps.size >= 4) // 4 dated on or before 2026-11-04
        assertEquals(NoAniDb.AID, eps.first().ref.aid)
        assertEquals("Dragon Ball Super: Beerus", eps.first().ref.query)
        assertTrue(eps.first().ref.titles.contains("dbsbeerus"))
        // reopened: cached
        EpisodeLoader.load(id, "Dragon Ball Super: Beerus", api)
        assertEquals(2, api.n("kitsu"))
        assertEquals(0, api.n("anilist"))
    }

    @Test
    fun aniListOnlyWhenKitsuHasNoDates() {
        val id = ShowUrl.Id.Kitsu(50021)
        val api = Api(mapOf(id to kusuriyaZip), aniList = mapOf(185660 to kusuriyaAniList))
        val eps = EpisodeLoader.load(id, "Kusuriya no Hitorigoto 3rd Season", api)
        assertEquals(1, api.n("anilist"))
        assertEquals(5, eps.size)
        EpisodeLoader.load(id, "Kusuriya no Hitorigoto 3rd Season", api)
        assertEquals(1, api.n("anilist"))
    }

    @Test
    fun aniListWhenKitsuIsDown() {
        val id = ShowUrl.Id.Kitsu(50497)
        val api = Api(mapOf(id to beerusZip), aniList = mapOf(206814 to beerusAniList), kitsuDown = true)
        val eps = EpisodeLoader.load(id, "Dragon Ball Super: Beerus", api)
        assertEquals(1, api.n("anilist"))
        assertEquals(4, eps.size)
    }

    @Test
    fun kitsuDownWithAniDbMappingStillNeedsNothing() {
        val id = ShowUrl.Id.Kitsu(46474)
        val api = Api(mapOf(id to frierenZip), kitsuDown = true)
        assertEquals(28, EpisodeLoader.load(id, "Sousou no Frieren", api).size)
        assertEquals(0, api.n("anilist") + api.n("kitsu"))
    }

    @Test
    fun aniZipWithoutTheEntryUsesKitsu() {
        val id = ShowUrl.Id.Kitsu(50497)
        val api = Api(mapOf(id to null))
        assertTrue(EpisodeLoader.load(id, "Dragon Ball Super: Beerus", api).size >= 4)
        assertEquals(0, api.n("anilist"))
    }

    // entries picked before 16.9 ("/anime/<anilist id>")

    @Test
    fun oldAniListEntryWithAniDbNeedsNoAniList() {
        val id = ShowUrl.Id.AniList(21355)
        val api = Api(mapOf(id to reZeroZip), aniList = mapOf(21355 to reZeroAniList))
        val ep1 = EpisodeLoader.load(id, "Re:Zero kara Hajimeru Isekai Seikatsu", api).first { it.ref.anidbEp == 1 }.ref
        assertEquals(0, api.n("anilist"))
        assertEquals(0, api.n("kitsu"))
        assertEquals(176270, ep1.eid)
        // the catalogue title, not ani.zip's "rizero"
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", ep1.query)
        assertTrue(ep1.titles.contains("rezerostartinglifeinanotherworld"))
    }

    @Test
    fun oldAniListEntryWithoutAniDbAsksAniListOnce() {
        val id = ShowUrl.Id.AniList(206814)
        val api = Api(mapOf(id to """{"mappings":{"anilist_id":206814}}"""), aniList = mapOf(206814 to beerusAniList))
        val first = EpisodeLoader.load(id, "Dragon Ball Super: Beerus", api)
        assertEquals(4, first.size)
        assertEquals(first, EpisodeLoader.load(id, "Dragon Ball Super: Beerus", api))
        assertEquals(1, api.n("anilist"))
        assertEquals(0, api.n("kitsu"))
    }

    @Test
    fun cachedAniListTitlesAreStillUsed() {
        val eps = EpisodeList.build("whatever", reZeroZip, EpisodeList.fromAniList(reZeroAniList)) { error("no fetch") }
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", eps.first().ref.query)
        assertTrue(eps.first().ref.titles.contains("rezero")) // AniList synonym
    }

    @Test
    fun aniZipWithoutTitlesAsksTheCatalogue() {
        val zip = """{"mappings":{"anidb_id":11370},"episodes":{"1":{"episode":"1","anidbEid":176270}}}"""
        var calls = 0
        val eps = EpisodeList.build("Re:Zero", zip, null) { calls++; EpisodeList.fromAniList(reZeroAniList) }
        assertEquals(1, calls)
        assertEquals("Re:Zero kara Hajimeru Isekai Seikatsu", eps.single().ref.query)
        // catalogue down: still the AniDB list
        assertEquals("Re:Zero", EpisodeList.build("Re:Zero", zip, null) { null }.single().ref.query)
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
        val c = TtlCache<String>(ttlMs = 1000, maxEntries = 10)
        c.put("a", "1", now = 0)
        assertEquals("1", c.get("a", now = 999))
        assertNull(c.get("a", now = 1000))
    }

    @Test
    fun cacheDropsOldestPastMax() {
        val c = TtlCache<String>(ttlMs = 1000, maxEntries = 2)
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
