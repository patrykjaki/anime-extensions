package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** kitsu.io replies recorded 2026-11-04. */
class KitsuTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun searchPage() {
        val p = Kitsu.parsePage(Fixtures.text("kitsu_search_frieren.json"))
        assertEquals(listOf(46474, 49240, 50629, 48105, 48642), p.media.map { it.id })
        assertTrue(p.hasNext)
        val f = p.media.first()
        assertEquals("Sousou no Frieren", f.title)
        assertTrue(f.titles.contains("Frieren: Beyond Journey’s End"))
        assertTrue(f.titles.contains("Frieren at the Funeral")) // abbreviated title
        assertEquals("finished", f.status)
        assertEquals(28, f.episodeCount)
        assertTrue(f.cover!!.startsWith("https://"))
    }

    @Test
    fun detailsHaveGenres() {
        val m = Kitsu.parseSingle(Fixtures.text("kitsu_details46474.json"))!!
        assertEquals(46474, m.id)
        assertTrue(m.genres.containsAll(listOf("Fantasy", "Adventure", "Drama")))
        assertTrue(m.description!!.startsWith("After the party of heroes"))
    }

    @Test
    fun errorRepliesAreRejected() {
        val err = """{"errors":[{"title":"Invalid page value","status":"400"}]}"""
        assertTrue(runCatching { Kitsu.parsePage(err) }.isFailure)
        assertNull(Kitsu.parseSingle(err))
    }

    @Test
    fun airingShowCountsDatedEpisodes() {
        // Dragon Ball Super: Beerus: 19 planned, episodes 1-4 dated (AniList: next is 5)
        val m = Kitsu.parseSingle(Fixtures.text("kitsu_details50497.json"))!!
        val eps = Kitsu.parseEpisodes(Fixtures.text("kitsu_eps50497.json"))
        assertEquals(19, eps.episodes.size)
        assertEquals(4, Kitsu.airedCount(m, eps.episodes, today))
        assertEquals(3, Kitsu.airedCount(m, eps.episodes, LocalDate.of(2026, 10, 31)))
        assertFalse(Kitsu.needsMoreEpisodes(eps, today))
    }

    @Test
    fun airingShowWithoutDatesIsUnknown() {
        // Kusuriya no Hitorigoto 3rd Season: airing, no episode dated on Kitsu
        val m = Kitsu.parseSingle(Fixtures.text("kitsu_details50021.json"))!!
        val eps = Kitsu.parseEpisodes(Fixtures.text("kitsu_eps50021.json"))
        assertNull(Kitsu.airedCount(m, eps.episodes, today))
    }

    @Test
    fun finishedAndUnreleased() {
        val m = Kitsu.parseSingle(Fixtures.text("kitsu_details46474.json"))!!
        assertEquals(28, Kitsu.airedCount(m, emptyList(), today))
        assertEquals(0, Kitsu.airedCount(m.copy(status = "unreleased"), emptyList(), today))
        assertEquals(0, Kitsu.airedCount(m.copy(status = "upcoming"), emptyList(), today))
    }

    @Test
    fun moreEpisodePagesOnlyWhileAllAired() {
        val d = today.minusDays(1)
        val full = Kitsu.EpisodePage((1..20).map { it to d }, hasNext = true)
        assertTrue(Kitsu.needsMoreEpisodes(full, today))
        assertFalse(Kitsu.needsMoreEpisodes(full.copy(hasNext = false), today))
        assertFalse(Kitsu.needsMoreEpisodes(Kitsu.EpisodePage((1..20).map { it to if (it < 20) d else null }, true), today))
    }

    @Test
    fun showUrls() {
        assertEquals(ShowUrl.Id.Kitsu(46474), ShowUrl.parse(ShowUrl.kitsu(46474)))
        // entries picked before 16.9 carry the AniList id
        assertEquals(ShowUrl.Id.AniList(154587), ShowUrl.parse("/anime/154587"))
        assertEquals("anilist_id=154587", ShowUrl.aniZipQuery(ShowUrl.Id.AniList(154587)))
        assertEquals("kitsu_id=46474", ShowUrl.aniZipQuery(ShowUrl.Id.Kitsu(46474)))
        assertNull(ShowUrl.parse("/manga/1"))
        assertNull(ShowUrl.parse("/kitsu/abc"))
    }

    @Test
    fun aniZipByKitsuIdHasTheSameMapping() {
        val body = Fixtures.text("anizip_kitsu46474.json")
        val m = AniZip.parse(body, listOf("Sousou no Frieren"))
        assertEquals(17617, m.aid)
        assertEquals(154587, AniZip.aniListId(body))
        assertEquals(Fixtures.FRIEREN_EP5.eid, m.episodes.first { it.ref.anidbEp == 5 }.ref.eid)
    }
}
