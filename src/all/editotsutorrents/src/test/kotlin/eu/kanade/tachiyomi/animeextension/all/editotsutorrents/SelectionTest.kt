package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import eu.kanade.tachiyomi.animeextension.all.editotsutorrents.Selection.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val eidRows = ToshoParser.parseList(Fixtures.text("tosho_eid271418.json"))
    private val aidRows = ToshoParser.parseList(Fixtures.text("tosho_aid17617_p1.json"))

    @Test
    fun eidFeedForEpisode1() {
        val c = Selection.candidates(eidRows + aidRows, Fixtures.FRIEREN_EP1)
        val direct = c.filterIsInstance<Candidate.Direct>()
        // 31 single-file rows tagged with eid 271418 in the eid feed (duplicates in the aid feed dropped)
        assertEquals(31, direct.size)
        assertTrue(direct.all { it.row.anidbEid == 271418 && it.row.numFiles == 1 })
        // batches tagged with the ep1 eid are kept, but need their file list
        val tagged = c.filterIsInstance<Candidate.NeedsFiles>().filter { it.rowIsThisEpisode }
        assertEquals(setOf(28, 16, 13), tagged.map { it.row.numFiles }.toSet())
    }

    @Test
    fun aidFeedForEpisode5() {
        val c = Selection.candidates(aidRows, Fixtures.FRIEREN_EP5)
        // the only row tagged with ep5's eid is single-file
        val direct = c.filterIsInstance<Candidate.Direct>()
        assertEquals(listOf(271708), direct.map { it.row.anidbEid })
        val batches = c.filterIsInstance<Candidate.NeedsFiles>()
        val names = batches.map { it.row.title }
        assertTrue(names.any { it.startsWith("[BlackRabbit] Frieren - Beyond Journey's End (2023) - S01 v2") })
        assertTrue(names.any { it == "[SubsPlease] Sousou no Frieren (01-28) (1080p) [V2] [Batch]" })
        assertTrue(names.any { it.startsWith("[Beatrice-Raws] Sousou no Frieren [BDRip") }) // anonymous batch
        // season 2 packs listed under the season 1 aid are not candidates
        assertFalse(names.any { it.contains("- S02 [") })
        // rows tagged with other episodes are not candidates
        assertTrue(c.none { it.row.anidbEid != null && it.row.anidbEid != 271708 })
        val fetch = Selection.toFetch(c, 10)
        assertEquals(10, fetch.size)
        assertTrue(fetch.first().priority <= fetch.last().priority)
    }

    @Test
    fun batchResolvesToExactFileIndex() {
        val row = aidRows.first { it.infoHash == "05bd3a45348e1338117e89cf1e25fd76b7a30de3" }
        val c = Selection.candidates(listOf(row), Fixtures.FRIEREN_EP5).single() as Candidate.NeedsFiles
        val meta = Bencode.parseTorrent(Fixtures.bytes("tosho_687355.torrent"))!!
        val p = Selection.fromFiles(c, meta, Fixtures.FRIEREN_EP5)!!
        assertEquals(4, p.fileIdx)
        assertTrue(p.fileName!!.endsWith("S01E05 - Phantoms of the Dead [Bluray-1080p][Opus 2.0][AV1]-BlackRabbit.mkv"))
        assertEquals(meta.files[4].size, p.sizeBytes)
    }

    @Test
    fun nyaaFallbackRows() {
        val rows = NyaaParser.parse(Fixtures.text("nyaa_frieren05.xml"))
        val c = Selection.candidates(rows, Fixtures.FRIEREN_EP5)
        assertTrue(c.isNotEmpty())
        c.filterIsInstance<Candidate.Direct>().forEach { assertTrue(EpisodeMatcher.isEpisode(it.row.torrentName, Fixtures.FRIEREN_EP5)) }
    }
}
