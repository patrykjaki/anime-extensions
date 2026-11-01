package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject

/**
 * Builds the playable url and the editotsu internalData contract for one [Pick].
 *
 * Contract (editotsu picker, version 1):
 * {"editotsu":1,"infoHash","fileIdx","torrentName","fileName","sizeBytes","seeders","leechers",
 *  "tracker","anidbEid","seadex":{"best","alt"}} — raw facts only. Keys whose value is unknown are
 * left out (missing key = app falls back to text parsing for that field). Extra keys, ignored by
 * readers that don't know them: "sizeApprox", "trusted", "remake", "toshoIsSeadex", "source",
 * "numFiles", "anidbAid".
 */
object VideoData {
    fun magnet(p: Pick): String = buildString {
        append("magnet:?xt=urn:btih:").append(p.row.infoHash)
        append("&dn=").append(Text.urlEncode(p.row.torrentName))
        (p.row.trackersEncoded + p.extraTrackersEncoded).distinct().forEach { append("&tr=").append(it) }
        // editotsu reads the file index with substringAfter("index="), so it must be the last parameter
        append("&index=").append(p.fileIdx)
    }

    fun internalData(p: Pick, targetEid: Int?): String {
        val r = p.row
        val o = JSONObject()
        o.put("editotsu", 1)
        o.put("infoHash", r.infoHash)
        o.put("fileIdx", p.fileIdx)
        o.put("torrentName", r.torrentName)
        p.fileName?.let { o.put("fileName", it) }
        p.sizeBytes?.let { o.put("sizeBytes", it) }
        if (p.sizeApprox) o.put("sizeApprox", true)
        r.seeders?.let { o.put("seeders", it) }
        r.leechers?.let { o.put("leechers", it) }
        o.put("tracker", r.tracker)
        // the eid of the episode this row was matched to (row's own eid when the feed set it)
        (r.anidbEid ?: targetEid)?.let { o.put("anidbEid", it) }
        r.anidbAid?.let { o.put("anidbAid", it) }
        r.numFiles?.let { o.put("numFiles", it) }
        r.trusted?.let { o.put("trusted", it) }
        r.remake?.let { o.put("remake", it) }
        if (r.toshoIsSeadex) o.put("toshoIsSeadex", true)
        o.put("source", r.source)
        // chip data: what the file contains (MediaInfo), else what the names state
        val tags = ReleaseTags.of(r.torrentName, p.fileName, r.title)
        o.put("tags", tags.toJson())
        (p.media?.toJson() ?: tags.nameMediaJson())?.let { o.put("media", it) }
        return o.toString()
    }

    private val RES = Regex("""(?i)(?<![0-9])(2160|1440|1080|720|576|540|480|360)[pi](?![a-z])""")
    private val WXH = Regex("""(?<![0-9])(?:3840|2560|1920|1280|1024|960|854|848|720|640)\s?[x×]\s?(2160|1440|1080|720|576|540|480|360)(?![0-9])""")
    private val UHD = Regex("""(?i)(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9])""")

    /** Video.resolution: the file's real frame size (MediaInfo), else what the names say. */
    fun resolution(p: Pick): Int? =
        p.media?.let { m -> classOf(m.width, m.height) } ?: resolution(p.fileName, p.row.torrentName, p.row.title)

    /** 1920x800 (cropped) is still 1080p: classify by width and height */
    fun classOf(width: Int?, height: Int?): Int? {
        val byWidth = when {
            width == null -> null
            width >= 3800 -> 2160
            width >= 2500 -> 1440
            width >= 1900 -> 1080
            width >= 1270 -> 720
            width >= 840 -> 480
            else -> 360
        }
        val byHeight = when {
            height == null -> null
            height >= 2000 -> 2160
            height >= 1400 -> 1440
            height >= 1000 -> 1080
            height >= 700 -> 720
            height >= 460 -> 480
            else -> 360
        }
        return listOfNotNull(byWidth, byHeight).maxOrNull()
    }

    /** resolution stated in a file or torrent name */
    fun resolution(vararg names: String?): Int? {
        for (n in names) {
            if (n == null) continue
            RES.find(n)?.let { return it.groupValues[1].toInt() }
            WXH.find(n)?.let { return it.groupValues[1].toInt() }
            if (UHD.containsMatchIn(n)) return 2160
        }
        return null
    }

    /**
     * Video title in the Torrentio layout the app's text parser already reads:
     *   line 1  release name
     *   line 2  file name (multi-file torrents only; the parser treats a video-file line as the pack file)
     *   line 3  "👤 <seeders> 💾 <size> ⚙️ <tracker>" (each part only when known)
     * internalData carries the same facts structured.
     */
    fun title(p: Pick): String = buildString {
        append(p.row.title.replace('\n', ' ').trim())
        if (p.fileName != null && (p.row.numFiles ?: 1) > 1) {
            append('\n').append(p.fileName.substringAfterLast('/'))
        }
        val meta = listOfNotNull(
            p.row.seeders?.let { "👤 $it" },
            p.sizeBytes?.let { "💾 ${sizeText(it)}" },
            "⚙️ ${hosterName(p.row.tracker)}",
        )
        append('\n').append(meta.joinToString(" "))
    }

    /** "1.39 GB" / "350.2 MB": binary units with Torrentio's labels (the app's size regex wants [KMGT]B) */
    fun sizeText(bytes: Long): String {
        val units = listOf("B" to 1L, "KB" to (1L shl 10), "MB" to (1L shl 20), "GB" to (1L shl 30), "TB" to (1L shl 40))
        val (unit, div) = units.last { bytes >= it.second || it.second == 1L }
        if (div == 1L) return "$bytes B"
        val v = bytes.toDouble() / div
        return if (v >= 100) "%.1f %s".format(java.util.Locale.ROOT, v, unit) else "%.2f %s".format(java.util.Locale.ROOT, v, unit)
    }

    fun hosterName(tracker: String): String = when (tracker) {
        "nyaa" -> "Nyaa"
        "sukebei" -> "Sukebei"
        "nekobt" -> "nekoBT"
        "anidex" -> "AniDex"
        "tokyotosho" -> "TokyoTosho"
        else -> "Other"
    }
}
