package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** New episodes of an airing show: AniList's aired count over ani.zip's lagging list. */
class EpisodeAiredTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun ep(n: Int, aired: Long?) = AniZip.Episode(
        ref = EpisodeRef(aid = 18000, eid = 100 + n, anidbEp = n, season = 3, seasonEp = n, absolute = 40 + n, query = "Show"),
        title = "t$n", airDateMillis = aired, image = null, overview = null,
    )

    @Test
    fun missingAiredEpisodesAreAddedByNumber() {
        val list = listOf(ep(1, now - 14 * day), ep(2, now - 7 * day))
        val out = EpisodeLoader.withAired(list, aired = 3, now = now)
        assertEquals(listOf(1, 2, 3), out.map { it.ref.anidbEp })
        val added = out.last()
        assertNull(added.ref.eid)
        assertEquals(3, added.ref.seasonEp)
        assertEquals(43, added.ref.absolute)
        assertEquals("Show", added.ref.query)
    }

    @Test
    fun episodeDatedAheadShowsOnceAniListCountsIt() {
        // ani.zip lists episode 3 with a planned date a day late; AniList says it aired
        val list = listOf(ep(1, now - 14 * day), ep(2, now - 7 * day), ep(3, now + day), ep(4, now + 8 * day))
        val out = EpisodeLoader.withAired(list, aired = 3, now = now)
        assertEquals(listOf(1, 2, 3), out.map { it.ref.anidbEp })
        assertEquals(103, out.last().ref.eid)
    }

    @Test
    fun nothingAddedWhenListIsAhead() {
        val list = listOf(ep(1, now - 14 * day), ep(2, now - 7 * day))
        assertEquals(listOf(1, 2), EpisodeLoader.withAired(list, aired = 1, now = now).map { it.ref.anidbEp })
    }

    @Test
    fun airingGuess() {
        assertTrue(EpisodeLoader.looksAiring(listOf(ep(1, now - 30 * day), ep(2, now + day)), now))
        assertTrue(EpisodeLoader.looksAiring(listOf(ep(1, now - 10 * day)), now))
        assertFalse(EpisodeLoader.looksAiring(listOf(ep(1, now - 400 * day), ep(2, now - 393 * day)), now))
    }
}
