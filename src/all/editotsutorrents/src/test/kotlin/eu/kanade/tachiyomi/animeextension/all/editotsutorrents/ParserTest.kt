package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test
    fun toshoEidFeed() {
        val rows = ToshoParser.parseList(Fixtures.text("tosho_eid271418.json"))
        assertEquals(37, rows.size)
        val first = rows.first()
        assertEquals("dc6f53840d7bb6324b502ff4c70d5814712cd1eb", first.infoHash)
        assertEquals("[keenoe] Sousou no Frieren - 01", first.torrentName)
        assertEquals(1112885018L, first.totalSize)
        assertEquals(12, first.seeders)
        assertEquals(0, first.leechers)
        assertEquals(1, first.numFiles)
        assertEquals(17617, first.anidbAid)
        assertEquals(271418, first.anidbEid)
        assertEquals("nyaa", first.tracker)
        assertEquals(5, first.trackersEncoded.size)
        assertTrue(first.trackersEncoded.first().startsWith("http%3A%2F%2Fnyaa.tracker.wf"))
        assertTrue(rows.all { it.infoHash.matches(Regex("[0-9a-f]{40}")) })
    }

    @Test
    fun toshoAidFeedTrackersAndNulls() {
        val rows = ToshoParser.parseList(Fixtures.text("tosho_aid17617_p1.json"))
        assertEquals(75, rows.size)
        val br = rows.first { it.infoHash == "05bd3a45348e1338117e89cf1e25fd76b7a30de3" }
        assertEquals("nekobt", br.tracker)
        assertNull(br.anidbEid)
        assertEquals(28, br.numFiles)
        assertNotNull(br.torrentUrl)
        assertEquals(1, rows.count { it.toshoIsSeadex })
    }

    @Test
    fun base32HashIsConverted() {
        // the eid fixture's first magnet uses base32 for the same hash as info_hash
        assertEquals("dc6f53840d7bb6324b502ff4c70d5814712cd1eb", Text.normalizeInfoHash("3RXVHBANPO3DES2QF72MODKYCRYSZUPL"))
        assertNull(Text.normalizeInfoHash("not a hash"))
    }

    @Test
    fun nyaaRss() {
        val rows = NyaaParser.parse(Fixtures.text("nyaa_frieren05.xml"))
        assertEquals(19, rows.size)
        rows.forEach {
            assertEquals("nyaa", it.tracker)
            assertTrue(it.infoHash.matches(Regex("[0-9a-f]{40}")))
            assertTrue(it.sizeApprox)
            assertNotNull(it.totalSize)
            assertNotNull(it.seeders)
            assertTrue(it.torrentUrl!!.startsWith("https://nyaa.si/download/"))
        }
    }

    @Test
    fun nyaaSize() {
        assertEquals((6.6 * (1L shl 30)).toLong(), Text.parseSize("6.6 GiB"))
        assertEquals(350L shl 20, Text.parseSize("350.0 MiB"))
    }

    @Test
    fun aniZipMapping() {
        val m = AniZip.parse(Fixtures.text("anizip_anilist154587.json"))
        assertEquals(17617, m.aid)
        assertEquals("Sousou no Frieren", m.romaji)
        assertEquals(28, m.episodes.size)
        val ep5 = m.episodes.first { it.ref.anidbEp == 5 }.ref
        assertEquals(Fixtures.FRIEREN_EP5, ep5.copy(query = Fixtures.FRIEREN_EP5.query, titles = emptyList()))
        assertTrue(ep5.titles.containsAll(listOf("sousounofrieren", "frierenbeyondjourneysend")))
        assertNotNull(m.episodes.first().airDateMillis)
        assertEquals(ep5, EpisodeRef.fromUrl(ep5.toUrl()))
    }

    @Test
    fun aniListSearch() {
        val p = AniListParser.parsePage(Fixtures.text("anilist_search_frieren.json"))
        assertEquals(154587, p.media.first().id)
        assertEquals("Sousou no Frieren", p.media.first().title)
        assertFalse(p.hasNext)
    }

    @Test
    fun torrentFileOrderMatchesToshoFileList() {
        val meta = Bencode.parseTorrent(Fixtures.bytes("tosho_687355.torrent"))!!
        assertEquals(28, meta.files.size)
        val show = org.json.JSONObject(Fixtures.text("tosho_show687355.json")).getJSONArray("files")
        val names = (0 until show.length()).map { show.getJSONObject(it).getString("filename") }
        assertEquals(names, meta.files.map { it.path.substringAfterLast('/') })
        assertTrue(meta.announce.isNotEmpty())
    }
}
