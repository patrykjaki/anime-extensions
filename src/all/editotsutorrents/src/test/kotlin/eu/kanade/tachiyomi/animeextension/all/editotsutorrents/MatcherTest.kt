package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import eu.kanade.tachiyomi.animeextension.all.editotsutorrents.EpisodeMatcher.Numbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private val ep5 = Fixtures.FRIEREN_EP5

    @Test
    fun numberingOfCommonNames() {
        assertEquals(Numbering.Single(1, null), EpisodeMatcher.numbering("[keenoe] Sousou no Frieren - 01"))
        assertEquals(Numbering.Single(5, null), EpisodeMatcher.numbering("[SubsPlease] Sousou no Frieren - 05 (1080p) [ABCD1234].mkv"))
        assertEquals(Numbering.Single(5, null), EpisodeMatcher.numbering("[Group] Sousou no Frieren - 05v2 [1080p]"))
        assertEquals(Numbering.Single(5, 1), EpisodeMatcher.numbering("Frieren - Beyond Journey's End (2023) - S01E05 - Phantoms of the Dead [Bluray-1080p].mkv"))
        assertEquals(Numbering.Single(5, 1), EpisodeMatcher.numbering("Frieren.S01E05.1080p.WEB.x264.mkv"))
        assertEquals(Numbering.Range(1, 28, null), EpisodeMatcher.numbering("[SubsPlease] Sousou no Frieren (01-28) (1080p) [Batch]"))
        assertEquals(Numbering.Range(1, 10, 2), EpisodeMatcher.numbering("[SubsPlease] Sousou no Frieren S2 (01-10) (1080p) [Batch]"))
        assertEquals(Numbering.SeasonPack(1), EpisodeMatcher.numbering("[BlackRabbit] Frieren - Beyond Journey's End (2023) - S01 v2 [Bluray-1080p][Opus 2.0][Dual Audio][AV1]"))
        assertEquals(Numbering.SeasonPack(1), EpisodeMatcher.numbering("[EMBER] Frieren: Beyond Journey`s End (2023) (Season 1) [BDRip] [1080p Dual Audio HEVC 10 bits DDP]"))
        assertEquals(Numbering.SeasonPack(2), EpisodeMatcher.numbering("[SubsPlease] Sousou no Frieren 2nd Season - Batch"))
        assertEquals(Numbering.Unknown, EpisodeMatcher.numbering("[Beatrice-Raws] Sousou no Frieren [BDRip 1920x1080 HEVC TrueHD]"))
    }

    @Test
    fun resolutionAndCodecNumbersAreNotEpisodes() {
        assertFalse(EpisodeMatcher.isEpisode("[X] Show - 1080p", ep5.copy(anidbEp = 1080, absolute = null)))
        assertEquals(Numbering.Unknown, EpisodeMatcher.numbering("[Polarwindz] Frieren Beyond Journey's End (BD 1080p HEVC 10bit Opus 2.0)"))
        assertEquals(Numbering.Unknown, EpisodeMatcher.numbering("Show [x265 H.264 AAC5.1]"))
    }

    @Test
    fun episodeMatching() {
        assertTrue(EpisodeMatcher.isEpisode("[SubsPlease] Sousou no Frieren - 05 (1080p)", ep5))
        assertFalse(EpisodeMatcher.isEpisode("[SubsPlease] Sousou no Frieren - 15 (1080p)", ep5))
        assertFalse(EpisodeMatcher.isEpisode("Frieren S02E05", ep5)) // another season
        assertTrue(EpisodeMatcher.mayContain("[SubsPlease] Sousou no Frieren (01-28) (1080p) [Batch]", ep5))
        assertFalse(EpisodeMatcher.mayContain("[SubsPlease] Sousou no Frieren S2 (01-10) (1080p) [Batch]", ep5))
        assertFalse(EpisodeMatcher.mayContain("[BlackRabbit] Frieren - Beyond Journey's End (2023) - S02 [WEBDL-1080p]", ep5))
        // season 2 entry numbered absolutely by some groups
        val s2e1 = EpisodeRef(aid = 18000, eid = null, anidbEp = 1, season = 2, seasonEp = 1, absolute = 29, query = "")
        assertTrue(EpisodeMatcher.isEpisode("[Group] Sousou no Frieren - 29 [1080p]", s2e1))
        assertTrue(EpisodeMatcher.isEpisode("Frieren S02E01 1080p", s2e1))
        assertTrue(EpisodeMatcher.isEpisode("[SubsPlease] Sousou no Frieren S2 - 01 (1080p)", s2e1))
    }

    @Test
    fun explicitSeasonMustMatch() {
        // ani.zip without a tvdb season: treat the entry as season 1
        val noSeason = Fixtures.FRIEREN_EP1.copy(season = null, seasonEp = null)
        assertFalse(EpisodeMatcher.isEpisode("Re ZERO Starting Life in Another World S04E01 1080p BILI WEB-DL-VARYG", noSeason))
        assertTrue(EpisodeMatcher.contradicts("Show S04E01", noSeason))
        assertTrue(EpisodeMatcher.isEpisode("Show S01E01 1080p", noSeason))
        assertTrue(EpisodeMatcher.contradicts("[Erai-raws] Re:Zero kara Hajimeru Isekai Seikatsu 4th Season - 01 [1080p]", Fixtures.FRIEREN_EP1))
        assertFalse(EpisodeMatcher.mayContain("[Group] Show S04 [1080p]", noSeason))
    }

    @Test
    fun pickFileFromBatch() {
        val meta = Bencode.parseTorrent(Fixtures.bytes("tosho_687355.torrent"))!!
        val f = EpisodeMatcher.pickFile(meta.files, ep5, rowIsThisEpisode = false)!!
        assertEquals(4, f.index)
        assertTrue(f.path.contains("S01E05"))
        // tagged-but-many-videos must not guess
        val ep99 = ep5.copy(anidbEp = 99, seasonEp = 99, absolute = 99)
        assertEquals(null, EpisodeMatcher.pickFile(meta.files, ep99, rowIsThisEpisode = true))
    }
}
