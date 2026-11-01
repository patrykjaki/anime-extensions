package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Test

class RevisionTest {
    private var n = 0

    private fun row(name: String, seeders: Int?, numFiles: Int = 1, tracker: String = "nyaa") = TorrentRow(
        infoHash = "%040x".format(++n), torrentName = name, title = name, totalSize = 1L shl 30,
        sizeApprox = false, seeders = seeders, leechers = 0, numFiles = numFiles, anidbAid = 1, anidbEid = 2,
        tracker = tracker, trackersEncoded = emptyList(), torrentUrl = null, toshoIsSeadex = false,
        trusted = null, remake = null, timestamp = null, source = "animetosho",
    )

    private fun single(name: String, seeders: Int?) = Pick(row(name, seeders), 0, null, 1L shl 30, false)

    private fun pack(name: String, file: String, seeders: Int?) =
        Pick(row(name, seeders, numFiles = 12), 2, file, 1L shl 30, false)

    private fun kept(vararg picks: Pick) = Selection.dropSuperseded(picks.toList()).map { it.row.torrentName }

    @Test
    fun v2ReplacesV1() {
        val v1 = single("[ASW] Oshi no Ko - 27 [1080p HEVC][1A2B3C4D].mkv", 300)
        val v2 = single("[ASW] Oshi no Ko - 27v2 [1080p HEVC][5E6F7A8B].mkv", 40)
        assertEquals(listOf(v2.row.torrentName), kept(v1, v2))
        assertEquals(2, Selection.revisionOf(v2))
        assertEquals(1, Selection.revisionOf(v1))
    }

    @Test
    fun revisionSpellings() {
        val names = listOf(
            "[SubsPlease] Oshi no Ko S3 - 03 (1080p) [AAAAAAAA].mkv",
            "[SubsPlease] Oshi no Ko S3 - 03v2 (1080p) [BBBBBBBB].mkv",
            "[SubsPlease] Oshi no Ko S3 - 03 (1080p) [V3] [CCCCCCCC].mkv",
            "[SubsPlease] Oshi no Ko S3 - 03 v4 (1080p).mkv",
        )
        val keys = names.map { Selection.revisionKey(single(it, 1)) }.toSet()
        assertEquals(1, keys.size)
        assertEquals(listOf(names[3]), kept(*names.map { single(it, 5) }.toTypedArray()))
        assertEquals(2, Selection.revisionOf(single("Oshi.no.Ko.S03E03.1080p.WEB.REPACK-GRP", 1)))
    }

    @Test
    fun deadNewerVersionKeepsTheOlder() {
        val v1 = single("[ASW] Show - 05 [1080p HEVC][11111111].mkv", 50)
        val v2 = single("[ASW] Show - 05v2 [1080p HEVC][22222222].mkv", 0)
        assertEquals(listOf(v1.row.torrentName), kept(v1, v2))
        // unknown seeders count as alive
        val v2u = single("[ASW] Show - 05v2 [1080p HEVC][33333333].mkv", null)
        assertEquals(listOf(v2u.row.torrentName), kept(v1, v2u))
    }

    @Test
    fun differentReleasesStay() {
        val a = single("[ASW] Show - 05 [1080p HEVC][11111111].mkv", 50)
        val b = single("[ASW] Show - 05 [720p HEVC][22222222].mkv", 10)
        val c = single("[Judas] Show - 05 [1080p HEVC][33333333].mkv", 10)
        val d = single("[ASW] Show - 06 [1080p HEVC][44444444].mkv", 10)
        assertEquals(4, kept(a, b, c, d).size)
    }

    @Test
    fun singlesAndPacksNeverMerge() {
        val s = single("[SubsPlease] Oshi no Ko S3 - 03 (1080p) [AAAAAAAA].mkv", 237)
        val p = pack("[SubsPlease] Oshi no Ko S3 (01-11) (1080p) [Batch]", "Oshi no Ko S3 - 03v2 (1080p).mkv", 207)
        assertEquals(2, kept(s, p).size)
    }

    @Test
    fun packRevisions() {
        val p1 = pack("[Grp] Show S01 [1080p] [Batch]", "Show/[Grp] Show - 03 [1080p].mkv", 100)
        val p2 = pack("[Grp] Show S01 v2 [1080p] [Batch]", "Show/[Grp] Show - 03v2 [1080p].mkv", 20)
        assertEquals(listOf(p2.row.torrentName), kept(p1, p2))
    }

    @Test
    fun repostsMergeToTheMostSeeded() {
        val a = single("[ASW] Show - 05 [1080p HEVC][11111111].mkv", 5)
        val b = single("[ASW] Show - 05 [1080p HEVC][11111111].mkv", 80).let { it.copy(row = it.row.copy(tracker = "anidex")) }
        assertEquals(listOf(b.row.infoHash), Selection.dropSuperseded(listOf(a, b)).map { it.row.infoHash })
    }

    @Test
    fun orderIsKept() {
        val x = single("[A] Show - 05 [1080p].mkv", 1)
        val y1 = single("[B] Show - 05 [1080p].mkv", 9)
        val z = single("[C] Show - 05 [1080p].mkv", 3)
        val y2 = single("[B] Show - 05v2 [1080p].mkv", 2)
        assertEquals(listOf(x, z, y2).map { it.row.torrentName }, kept(x, y1, z, y2))
    }
}
