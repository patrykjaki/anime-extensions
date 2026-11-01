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
        return o.toString()
    }

    private val RES = Regex("""(?i)(?<![0-9])(2160|1440|1080|720|576|540|480|360)[pi](?![a-z])""")
    private val WXH = Regex("""(?<![0-9])(?:3840|2560|1920|1280|1024|960|854|848|720|640)\s?[x×]\s?(2160|1440|1080|720|576|540|480|360)(?![0-9])""")
    private val UHD = Regex("""(?i)(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9])""")

    /** Video.resolution from the file name, else the torrent name. */
    fun resolution(vararg names: String?): Int? {
        for (n in names) {
            if (n == null) continue
            RES.find(n)?.let { return it.groupValues[1].toInt() }
            WXH.find(n)?.let { return it.groupValues[1].toInt() }
            if (UHD.containsMatchIn(n)) return 2160
        }
        return null
    }

    fun title(p: Pick): String =
        if (p.fileName != null && (p.row.numFiles ?: 1) > 1) {
            p.row.title + "\n" + p.fileName.substringAfterLast('/')
        } else {
            p.row.title
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
