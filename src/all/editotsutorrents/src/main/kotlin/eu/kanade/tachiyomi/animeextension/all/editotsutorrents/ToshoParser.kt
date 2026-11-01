package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONArray
import org.json.JSONObject

/**
 * feed.animetosho.net/json (list) parser. Field names checked against live responses on
 * 2026-11-01 (fixtures in src/test/resources/fixtures).
 */
object ToshoParser {
    fun parseList(body: String): List<TorrentRow> {
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseRow) }
    }

    fun parseRow(o: JSONObject): TorrentRow? {
        val magnet = o.optStringOrNull("magnet_uri")
        val hash = Text.normalizeInfoHash(o.optStringOrNull("info_hash"))
            ?: Text.normalizeInfoHash(magnet?.let { magnetBtih(it) })
            ?: return null
        return TorrentRow(
            infoHash = hash,
            torrentName = o.optStringOrNull("torrent_name") ?: o.optStringOrNull("title") ?: return null,
            title = o.optStringOrNull("title") ?: o.optStringOrNull("torrent_name").orEmpty(),
            totalSize = o.optLongOrNull("total_size"),
            sizeApprox = false,
            seeders = o.optIntOrNull("seeders"),
            leechers = o.optIntOrNull("leechers"),
            numFiles = o.optIntOrNull("num_files"),
            anidbAid = o.optIntOrNull("anidb_aid"),
            anidbEid = o.optIntOrNull("anidb_eid"),
            tracker = tracker(o),
            trackersEncoded = Text.magnetTrackers(magnet),
            torrentUrl = o.optStringOrNull("torrent_url"),
            toshoIsSeadex = o.optBoolean("is_seadex", false),
            trusted = null,
            remake = null,
            timestamp = o.optLongOrNull("timestamp"),
            source = "animetosho",
        )
    }

    private fun tracker(o: JSONObject): String = when {
        o.optLongOrNull("nyaa_id") != null ->
            if (o.optStringOrNull("nyaa_subdom") == "sukebei") "sukebei" else "nyaa"
        o.optLongOrNull("nekobt_id") != null -> "nekobt"
        o.optLongOrNull("anidex_id") != null -> "anidex"
        o.optLongOrNull("tosho_id") != null -> "tokyotosho"
        else -> when (o.optStringOrNull("article_title")?.lowercase()) {
            "nyaa" -> "nyaa"
            "nbt" -> "nekobt"
            "anidex" -> "anidex"
            "tt" -> "tokyotosho"
            else -> "unknown"
        }
    }

    private fun magnetBtih(m: String): String? =
        m.substringAfter("xt=urn:btih:", "").substringBefore('&').ifEmpty { null }
}

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k)) null else optString(k, "").ifEmpty { null }

internal fun JSONObject.optLongOrNull(k: String): Long? =
    if (!has(k) || isNull(k)) null else when (val v = opt(k)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        null -> null
        else -> null
    }

internal fun JSONObject.optIntOrNull(k: String): Int? = optLongOrNull(k)?.toInt()
