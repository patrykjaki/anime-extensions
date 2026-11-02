package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Test

class NoAniDbTest {
    private fun media(fields: String) = """{"data":{"Media":{$fields}}}"""

    @Test
    fun airingShowCountsEpisodesBeforeTheNextOne() {
        assertEquals(4, NoAniDb.airedCount(media(""""episodes":null,"status":"RELEASING","nextAiringEpisode":{"episode":5}""")))
    }

    @Test
    fun finishedShowUsesTotal() {
        assertEquals(12, NoAniDb.airedCount(media(""""episodes":12,"status":"FINISHED","nextAiringEpisode":null""")))
    }

    @Test
    fun unknownOrUnreleasedIsZero() {
        assertEquals(0, NoAniDb.airedCount(media(""""episodes":12,"status":"NOT_YET_RELEASED","nextAiringEpisode":null""")))
        assertEquals(0, NoAniDb.airedCount(media(""""episodes":null,"status":"RELEASING","nextAiringEpisode":null""")))
        assertEquals(0, NoAniDb.airedCount("not json"))
    }

    @Test
    fun episodesRoundTripThroughTheUrl() {
        val eps = NoAniDb.episodes(3, "Dragon Ball Super: Beerus", listOf("dragonballsuperbeerus"))
        assertEquals(listOf(1, 2, 3), eps.map { it.ref.anidbEp })
        val back = EpisodeRef.fromUrl(eps[1].ref.toUrl())!!
        assertEquals(NoAniDb.AID, back.aid)
        assertEquals(2, back.anidbEp)
        assertEquals("Dragon Ball Super: Beerus", back.query)
    }
}
