package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val want = Debrid.Want("magnet:?xt=urn:btih:$hash", hash, "Show - 02.mkv", 200)
    private val single = want.copy(fileName = null, sizeBytes = null)

    /** answers by the first url fragment that matches; records "METHOD url" of every call */
    private class Fake(private vararg val answers: Pair<String, String>) : DebridHttp {
        val calls = mutableListOf<String>()
        val bearers = mutableListOf<String?>()
        override fun call(method: String, url: String, bearer: String?, form: List<Pair<String, String>>?): String {
            calls += "$method $url"
            bearers += bearer
            return answers.firstOrNull { it.first in "$method $url" }?.second ?: "{}"
        }
    }

    @Test
    fun `the file is matched by name, then by size, and a named file is never guessed`() {
        val files = listOf(
            Debrid.File("Show/Show - 01.mkv", 100, "a"),
            Debrid.File("Show/Show - 02.mkv", 200, "b"),
            Debrid.File("Show/NCOP.mkv", 30, "c"),
        )
        assertEquals("b", Debrid.pickFile(files, want)?.ref)
        assertEquals("a", Debrid.pickFile(files, want.copy(fileName = "renamed.mkv", sizeBytes = 100))?.ref)
        assertNull(Debrid.pickFile(files, want.copy(fileName = "Show - 09.mkv", sizeBytes = 999)))
        // a single-file release: the largest video
        assertEquals("b", Debrid.pickFile(files + Debrid.File("readme.txt", 900, "t"), single)?.ref)
    }

    @Test
    fun `torbox, a release the account has ready gives its link without the key in it`() {
        val http = Fake(
            "createtorrent" to """{"success":true,"data":{"torrent_id":7,"hash":"$hash"}}""",
            "mylist" to """{"success":true,"data":{"id":7,"files":[{"id":0,"name":"Show/Show - 01.mkv","size":100},{"id":1,"name":"Show/Show - 02.mkv","short_name":"Show - 02.mkv","size":200}]}}""",
            "requestdl" to """{"success":true,"data":"https://cdn.example/f.mkv"}""",
        )
        assertEquals("https://cdn.example/f.mkv", Debrid.resolve(Debrid.Service.TORBOX, http, "KEY", want))
        assertTrue(http.calls.last().contains("torrent_id=7&file_id=1"))
        assertTrue(http.bearers.all { it == "KEY" })
    }

    @Test
    fun `torbox, a release it does not have is left to the torrent`() {
        val http = Fake("createtorrent" to """{"success":false,"error":"DOWNLOAD_NOT_CACHED","detail":"not cached"}""")
        assertNull(Debrid.resolve(Debrid.Service.TORBOX, http, "KEY", want))
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `torbox and premiumize say which releases are ready, the others can't`() {
        val other = "f".repeat(40)
        val torbox = Fake("checkcached" to """{"success":true,"data":[{"hash":"${hash.uppercase()}","files":null}]}""")
        assertEquals(setOf(hash), Debrid.cached(Debrid.Service.TORBOX, torbox, "KEY", listOf(hash, other)))
        val premiumize = Fake("cache/check" to """{"status":"success","response":[false,true]}""")
        assertEquals(setOf(other), Debrid.cached(Debrid.Service.PREMIUMIZE, premiumize, "KEY", listOf(hash, other)))
        assertNull(Debrid.cached(Debrid.Service.REAL_DEBRID, Fake(), "KEY", listOf(hash)))
    }

    @Test
    fun `real-debrid, ready after selecting the file gives the unrestricted link`() {
        var infos = 0
        val http = object : DebridHttp {
            val calls = mutableListOf<String>()
            override fun call(method: String, url: String, bearer: String?, form: List<Pair<String, String>>?): String {
                calls += "$method ${url.substringAfter("/rest/1.0/")} ${form.orEmpty().joinToString { "${it.first}=${it.second}" }}".trim()
                return when {
                    "addMagnet" in url -> """{"id":"T1","uri":"x"}"""
                    "info" in url && infos++ == 0 ->
                        """{"status":"waiting_files_selection","files":[{"id":1,"path":"/Show - 01.mkv","bytes":100},{"id":2,"path":"/Show - 02.mkv","bytes":200}],"links":[]}"""
                    "info" in url -> """{"status":"downloaded","links":["https://host/abc"]}"""
                    "unrestrict" in url -> """{"download":"https://dl.example/Show - 02.mkv"}"""
                    else -> ""
                }
            }
        }
        assertEquals("https://dl.example/Show - 02.mkv", Debrid.resolve(Debrid.Service.REAL_DEBRID, http, "KEY", want))
        assertTrue("POST torrents/selectFiles/T1 files=2" in http.calls)
        assertTrue(http.calls.none { it.startsWith("DELETE") })
    }

    @Test
    fun `real-debrid, a release that would have to download is removed from the account again`() {
        val http = Fake(
            "addMagnet" to """{"id":"T1"}""",
            "info" to """{"status":"queued","files":[{"id":2,"path":"/Show - 02.mkv","bytes":200}],"links":[]}""",
        )
        assertNull(Debrid.resolve(Debrid.Service.REAL_DEBRID, http, "KEY", want))
        assertTrue(http.calls.last().startsWith("DELETE") && http.calls.last().endsWith("torrents/delete/T1"))
    }

    @Test
    fun `alldebrid, the file is found inside folders and unlocked`() {
        val http = Fake(
            "magnet/upload" to """{"status":"success","data":{"magnets":[{"id":55,"ready":true}]}}""",
            "magnet/files" to """{"status":"success","data":{"magnets":[{"id":"55","files":[{"n":"Show","e":[{"n":"Show - 01.mkv","s":100,"l":"https://ad/1"},{"n":"Show - 02.mkv","s":200,"l":"https://ad/2"}]}]}]}}""",
            "link/unlock" to """{"status":"success","data":{"link":"https://dl.example/2"}}""",
        )
        assertEquals("https://dl.example/2", Debrid.resolve(Debrid.Service.ALL_DEBRID, http, "KEY", want))
        assertTrue(http.calls.last().contains("link=https%3A%2F%2Fad%2F2"))
        val notReady = Fake("magnet/upload" to """{"status":"success","data":{"magnets":[{"id":56,"ready":false}]}}""")
        assertNull(Debrid.resolve(Debrid.Service.ALL_DEBRID, notReady, "KEY", want))
        assertTrue(notReady.calls.last().contains("magnet/delete") && notReady.calls.last().endsWith("id=56"))
    }

    @Test
    fun `premiumize and debrid-link give the link of the wanted file only when it is ready`() {
        val premiumize = Fake(
            "directdl" to """{"status":"success","content":[{"path":"Show/Show - 01.mkv","size":100,"link":"https://pm/1"},{"path":"Show/Show - 02.mkv","size":200,"link":"https://pm/2"}]}""",
        )
        assertEquals("https://pm/2", Debrid.resolve(Debrid.Service.PREMIUMIZE, premiumize, "KEY", want))
        assertNull(Debrid.resolve(Debrid.Service.PREMIUMIZE, Fake("directdl" to """{"status":"error","message":"not cached"}"""), "KEY", want))

        val ready = Fake(
            "seedbox/add" to """{"success":true,"value":{"id":"S1","files":[{"name":"Show - 02.mkv","size":200,"downloadUrl":"https://dl/2","downloadPercent":100}]}}""",
        )
        assertEquals("https://dl/2", Debrid.resolve(Debrid.Service.DEBRID_LINK, ready, "KEY", want))
        val downloading = Fake(
            "seedbox/add" to """{"success":true,"value":{"id":"S2","files":[{"name":"Show - 02.mkv","size":200,"downloadUrl":"https://dl/2","downloadPercent":3}]}}""",
        )
        assertNull(Debrid.resolve(Debrid.Service.DEBRID_LINK, downloading, "KEY", want))
        assertTrue(downloading.calls.last() == "DELETE https://debrid-link.com/api/v2/seedbox/S2/remove")
    }

    @Test
    fun `the release data tells the app a debrid service is set up and whether it has the release`() {
        val row = ToshoParser.parseRow(
            org.json.JSONObject().put("info_hash", hash).put("title", "[G] Show - 02 [1080p].mkv").put("num_files", 1),
        )!!
        val pick = Pick(row, 0, null, 200, false)
        val plain = org.json.JSONObject(VideoData.internalData(pick, null))
        assertTrue(!plain.has("debrid") && !plain.has("resolve") && !plain.has("instant"))
        val data = org.json.JSONObject(VideoData.internalData(pick, null, "torbox", true))
        assertEquals("torbox", data.getString("debrid"))
        assertTrue(data.getBoolean("resolve") && data.getBoolean("instant"))
        assertTrue(!org.json.JSONObject(VideoData.internalData(pick, null, "realdebrid", null)).has("instant"))
    }

    @Test
    fun `debrid only, a release that is not ready stays in the account to download`() {
        val realDebrid = Fake(
            "addMagnet" to """{"id":"T1"}""",
            "info" to """{"status":"queued","files":[{"id":2,"path":"/Show - 02.mkv","bytes":200}],"links":[]}""",
        )
        assertNull(Debrid.resolve(Debrid.Service.REAL_DEBRID, realDebrid, "KEY", want, keep = true))
        assertTrue(realDebrid.calls.none { it.startsWith("DELETE") })

        val forms = mutableListOf<List<Pair<String, String>>?>()
        val torBox = DebridHttp { _, url, _, form ->
            forms += form
            when {
                "createtorrent" in url -> """{"success":true,"data":{"torrent_id":9}}"""
                "mylist" in url -> """{"success":true,"data":{"id":9,"download_present":false,"download_finished":false,"files":[]}}"""
                else -> "{}"
            }
        }
        assertNull(Debrid.resolve(Debrid.Service.TORBOX, torBox, "KEY", want, keep = true))
        assertTrue(forms.first()!!.contains("add_only_if_cached" to "false"))

        val premiumize = Fake("directdl" to """{"status":"error","message":"not cached"}""", "transfer/create" to """{"status":"success","id":"x"}""")
        assertNull(Debrid.resolve(Debrid.Service.PREMIUMIZE, premiumize, "KEY", want, keep = true))
        assertTrue(premiumize.calls.last().contains("transfer/create"))
    }
}
