package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONArray
import org.json.JSONObject

/**
 * One http exchange with a debrid service. The body is returned whatever the status, since the
 * services describe failures in it; an exchange that got no answer throws.
 */
fun interface DebridHttp {
    fun call(method: String, url: String, bearer: String?, form: List<Pair<String, String>>?): String
}

/**
 * Debrid services: accounts that hold torrents on their own servers and hand out a plain https
 * link to a file. With one set up, a release the service already has plays from that link
 * instead of from peers.
 *
 * Everything here is the user's own account, reached with the key they typed into the settings.
 * The key goes in the Authorization header, and in the query only where a service requires it
 * there (Premiumize; TorBox's link request). It is never part of a link this file returns.
 */
object Debrid {
    enum class Service(val id: String, val label: String) {
        TORBOX("torbox", "TorBox"),
        REAL_DEBRID("realdebrid", "Real-Debrid"),
        ALL_DEBRID("alldebrid", "AllDebrid"),
        PREMIUMIZE("premiumize", "Premiumize"),
        DEBRID_LINK("debridlink", "Debrid-Link"),
        ;

        companion object {
            fun of(id: String?): Service? = entries.firstOrNull { it.id == id }
        }
    }

    /** the file to play: its torrent, and what is known about the file inside it */
    data class Want(val magnet: String, val infoHash: String, val fileName: String?, val sizeBytes: Long?)

    /** a file as a service lists it; [ref] is whatever that service needs to make a link for it */
    data class File(val name: String, val size: Long, val ref: String)

    private val VIDEO = Regex("""(?i)\.(mkv|mp4|m4v|avi|webm|ts|m2ts|mov|wmv|ogm)$""")

    /**
     * The service's file that is [want]: same file name, else the only file of that exact size.
     * When the release is a single file (no name known), the largest video. Null when a named
     * file can't be found: a guess there would play another episode of a pack.
     */
    fun pickFile(files: List<File>, want: Want): File? {
        fun base(s: String) = s.replace('\\', '/').substringAfterLast('/').lowercase()
        want.fileName?.let { name ->
            files.firstOrNull { base(it.name) == base(name) }?.let { return it }
        }
        want.sizeBytes?.let { size ->
            files.filter { it.size == size }.singleOrNull()?.let { return it }
        }
        if (want.fileName != null) return null
        return files.filter { VIDEO.containsMatchIn(it.name) }.maxByOrNull { it.size }
            ?: files.singleOrNull()
    }

    /**
     * Which of [hashes] the service has ready, or null when it can't be asked without adding
     * each torrent to the account (Real-Debrid, AllDebrid, Debrid-Link).
     */
    fun cached(service: Service, http: DebridHttp, key: String, hashes: List<String>): Set<String>? = when (service) {
        Service.TORBOX -> hashes.chunked(80).flatMap { part ->
            val body = json(http.call("GET", "$TORBOX/torrents/checkcached?format=list&hash=${part.joinToString(",")}", key, null))
            body.optJSONArray("data").objects().mapNotNull { it.optStringOrNull("hash")?.lowercase() }
        }.toSet()
        Service.PREMIUMIZE -> hashes.chunked(50).flatMap { part ->
            val items = part.joinToString("&") { "items%5B%5D=$it" }
            val body = json(http.call("GET", "$PREMIUMIZE/cache/check?apikey=${Text.urlEncode(key)}&$items", null, null))
            val flags = body.optJSONArray("response")
            part.filterIndexed { i, _ -> flags?.optBoolean(i, false) == true }
        }.toSet()
        else -> null
    }

    /**
     * A https link to [want]'s file when the service has it ready now, else null.
     *
     * [keep] decides what happens to a torrent that is not ready. false: it is not added, or
     * removed again when it had to be added to find out, so nothing is left downloading in the
     * account (the release plays as a torrent instead). true: it stays in the account and
     * downloads there, for networks where the torrent can't play.
     */
    fun resolve(service: Service, http: DebridHttp, key: String, want: Want, keep: Boolean = false): String? = when (service) {
        Service.TORBOX -> torBox(http, key, want, keep)
        Service.REAL_DEBRID -> realDebrid(http, key, want, keep)
        Service.ALL_DEBRID -> allDebrid(http, key, want, keep)
        Service.PREMIUMIZE -> premiumize(http, key, want, keep)
        Service.DEBRID_LINK -> debridLink(http, key, want, keep)
    }

    // ============================== TorBox ==============================

    private fun torBox(http: DebridHttp, key: String, want: Want, keep: Boolean): String? {
        val created = json(
            http.call(
                "POST", "$TORBOX/torrents/createtorrent", key,
                listOf("magnet" to want.magnet, "allow_zip" to "false", "add_only_if_cached" to (!keep).toString()),
            ),
        )
        if (!created.optBoolean("success", false)) return null
        val id = created.optJSONObject("data")?.optLongOrNull("torrent_id") ?: return null
        val listed = json(http.call("GET", "$TORBOX/torrents/mylist?id=$id&bypass_cache=true", key, null))
        // one item, as an object or as a one-element list
        val item = listed.optJSONObject("data") ?: listed.optJSONArray("data")?.optJSONObject(0) ?: return null
        // with keep the torrent may have been added to download: no link until it is there
        if (keep && !item.optBoolean("download_present", false) && !item.optBoolean("download_finished", false)) return null
        val files = item.optJSONArray("files").objects().map {
            File(it.optStringOrNull("name") ?: it.optStringOrNull("short_name").orEmpty(), it.optLongOrNull("size") ?: 0, it.optLong("id").toString())
        }
        val file = pickFile(files, want) ?: return null
        val link = json(
            http.call("GET", "$TORBOX/torrents/requestdl?token=${Text.urlEncode(key)}&torrent_id=$id&file_id=${file.ref}", key, null),
        )
        return link.optStringOrNull("data")?.takeIf { link.optBoolean("success", false) && it.startsWith("http") }
    }

    // ============================== Real-Debrid ==============================

    private fun realDebrid(http: DebridHttp, key: String, want: Want, keep: Boolean): String? {
        val id = json(http.call("POST", "$REAL_DEBRID/torrents/addMagnet", key, listOf("magnet" to want.magnet)))
            .optStringOrNull("id") ?: return null
        fun info() = json(http.call("GET", "$REAL_DEBRID/torrents/info/$id", key, null))
        fun giveUp(): String? {
            if (!keep) runCatching { http.call("DELETE", "$REAL_DEBRID/torrents/delete/$id", key, null) }
            return null
        }
        // the file list is there once the service has the torrent's metadata; it has it at once
        // for a torrent it holds, so a short wait is enough to tell
        var listed = info()
        var tries = 0
        while (listed.optJSONArray("files").objects().isEmpty() && tries++ < 4) {
            Thread.sleep(400)
            listed = info()
        }
        val files = listed.optJSONArray("files").objects().map {
            File(it.optStringOrNull("path").orEmpty(), it.optLongOrNull("bytes") ?: 0, it.optLong("id").toString())
        }
        val file = pickFile(files, want) ?: return giveUp()
        http.call("POST", "$REAL_DEBRID/torrents/selectFiles/$id", key, listOf("files" to file.ref))
        val ready = info()
        val hosted = ready.optJSONArray("links")?.optString(0).orEmpty()
        if (ready.optString("status") != "downloaded" || hosted.isEmpty()) return giveUp()
        return json(http.call("POST", "$REAL_DEBRID/unrestrict/link", key, listOf("link" to hosted)))
            .optStringOrNull("download")?.takeIf { it.startsWith("http") }
    }

    // ============================== AllDebrid ==============================

    private fun allDebrid(http: DebridHttp, key: String, want: Want, keep: Boolean): String? {
        val up = json(http.call("POST", "$ALL_DEBRID/v4/magnet/upload?agent=$AGENT", key, listOf("magnets[]" to want.magnet)))
        val magnet = up.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0) ?: return null
        val id = magnet.optLongOrNull("id") ?: return null
        if (!magnet.optBoolean("ready", false)) {
            if (!keep) runCatching { http.call("GET", "$ALL_DEBRID/v4/magnet/delete?agent=$AGENT&id=$id", key, null) }
            return null
        }
        val listed = json(http.call("GET", "$ALL_DEBRID/v4/magnet/files?agent=$AGENT&id%5B%5D=$id", key, null))
        val tree = listed.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files")
        val file = pickFile(flatten(tree, ""), want) ?: return null
        val unlocked = json(http.call("GET", "$ALL_DEBRID/v4/link/unlock?agent=$AGENT&link=${Text.urlEncode(file.ref)}", key, null))
        return unlocked.optJSONObject("data")?.optStringOrNull("link")?.takeIf { it.startsWith("http") }
    }

    /** AllDebrid's file tree: n = name, s = size, l = link, e = entries of a folder */
    private fun flatten(nodes: JSONArray?, dir: String): List<File> = nodes.objects().flatMap { n ->
        val path = dir + n.optString("n")
        val children = n.optJSONArray("e")
        if (children != null) flatten(children, "$path/") else listOf(File(path, n.optLongOrNull("s") ?: 0, n.optString("l")))
    }

    // ============================== Premiumize ==============================

    private fun premiumize(http: DebridHttp, key: String, want: Want, keep: Boolean): String? {
        val body = json(
            http.call("POST", "$PREMIUMIZE/transfer/directdl?apikey=${Text.urlEncode(key)}", null, listOf("src" to want.magnet)),
        )
        if (body.optString("status") != "success") {
            if (keep) {
                runCatching {
                    http.call("POST", "$PREMIUMIZE/transfer/create?apikey=${Text.urlEncode(key)}", null, listOf("src" to want.magnet))
                }
            }
            return null
        }
        val files = body.optJSONArray("content").objects().mapNotNull {
            val link = it.optStringOrNull("link") ?: return@mapNotNull null
            File(it.optStringOrNull("path").orEmpty(), it.optLongOrNull("size") ?: 0, link)
        }
        return pickFile(files, want)?.ref?.takeIf { it.startsWith("http") }
    }

    // ============================== Debrid-Link ==============================

    private fun debridLink(http: DebridHttp, key: String, want: Want, keep: Boolean): String? {
        val added = json(http.call("POST", "$DEBRID_LINK/seedbox/add", key, listOf("url" to want.magnet, "async" to "true")))
        val torrent = added.optJSONObject("value") ?: return null
        val id = torrent.optStringOrNull("id")
        // every file is listed at once, with a link that only works when downloadPercent is 100
        val done = torrent.optJSONArray("files").objects().filter { it.optDouble("downloadPercent", 0.0) >= 100.0 }
        val files = done.mapNotNull {
            val link = it.optStringOrNull("downloadUrl") ?: return@mapNotNull null
            File(it.optStringOrNull("name").orEmpty(), it.optLongOrNull("size") ?: 0, link)
        }
        val file = pickFile(files, want)
        if (!keep && file == null && id != null && done.isEmpty()) {
            runCatching { http.call("DELETE", "$DEBRID_LINK/seedbox/$id/remove", key, null) }
        }
        return file?.ref?.takeIf { it.startsWith("http") }
    }

    private fun json(body: String): JSONObject = runCatching { JSONObject(body) }.getOrElse { JSONObject() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private const val TORBOX = "https://api.torbox.app/v1/api"
    private const val REAL_DEBRID = "https://api.real-debrid.com/rest/1.0"
    private const val ALL_DEBRID = "https://api.alldebrid.com"
    private const val PREMIUMIZE = "https://www.premiumize.me/api"
    private const val DEBRID_LINK = "https://debrid-link.com/api/v2"
    private const val AGENT = "editotsu"
}
