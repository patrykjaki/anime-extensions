package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/**
 * One episode as the extension stores it in SEpisode.url (see [EpisodeRef.toUrl]).
 * Numbers come from ani.zip (anidb + tvdb numbering).
 */
data class EpisodeRef(
    val aid: Int,
    val eid: Int?,
    val anidbEp: Int,
    val season: Int?,
    val seasonEp: Int?,
    val absolute: Int?,
    val query: String,
    /** normalized titles of this entry ([Titles.key]); empty = title checks off */
    val titles: List<String> = emptyList(),
) {
    /** season used for name checks: the tvdb season, or 1 when ani.zip doesn't state one */
    val effectiveSeason: Int get() = season ?: 1

    fun toUrl(): String = buildString {
        append("/ep?aid=").append(aid)
        eid?.let { append("&eid=").append(it) }
        append("&ep=").append(anidbEp)
        season?.let { append("&s=").append(it) }
        seasonEp?.let { append("&se=").append(it) }
        absolute?.let { append("&abs=").append(it) }
        append("&q=").append(Text.urlEncode(query))
        if (titles.isNotEmpty()) append("&t=").append(titles.joinToString("."))
    }

    companion object {
        fun fromUrl(url: String): EpisodeRef? {
            val params = url.substringAfter('?', "").split('&').mapNotNull {
                val k = it.substringBefore('=', "")
                if (k.isEmpty()) null else k to Text.urlDecode(it.substringAfter('='))
            }.toMap()
            return EpisodeRef(
                aid = params["aid"]?.toIntOrNull() ?: return null,
                eid = params["eid"]?.toIntOrNull(),
                anidbEp = params["ep"]?.toIntOrNull() ?: return null,
                season = params["s"]?.toIntOrNull(),
                seasonEp = params["se"]?.toIntOrNull(),
                absolute = params["abs"]?.toIntOrNull(),
                query = params["q"].orEmpty(),
                titles = params["t"]?.split('.')?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }
    }
}

/** One torrent listing, from either feed. Only facts the feed states; nothing parsed out of names. */
data class TorrentRow(
    /** lowercase 40-char hex v1 info hash */
    val infoHash: String,
    val torrentName: String,
    val title: String,
    /** total torrent size; null when the feed doesn't state it */
    val totalSize: Long?,
    /** true when [totalSize] was rounded by the feed (nyaa "6.6 GiB") */
    val sizeApprox: Boolean,
    val seeders: Int?,
    val leechers: Int?,
    /** null = unknown */
    val numFiles: Int?,
    val anidbAid: Int?,
    val anidbEid: Int?,
    /** nyaa, nekobt, anidex, tokyotosho, sukebei, unknown */
    val tracker: String,
    /** tracker announce urls, already url-encoded for a magnet */
    val trackersEncoded: List<String>,
    /** where the .torrent file can be fetched (needed for multi-file torrents) */
    val torrentUrl: String?,
    val toshoIsSeadex: Boolean,
    val trusted: Boolean?,
    val remake: Boolean?,
    val timestamp: Long?,
    val source: String,
    /** animetosho.net listing id (for `json?show=torrent&id=`); null for Nyaa rows */
    val toshoId: Long? = null,
)

/** A file inside a torrent; [index] is the position in the info dict file list (libtorrent file index). */
data class TorrentFile(val index: Int, val path: String, val size: Long, val isPad: Boolean)

/** A row resolved to one playable file. */
data class Pick(
    val row: TorrentRow,
    val fileIdx: Int,
    /** file name inside the torrent; null when not known without fetching the .torrent */
    val fileName: String?,
    val sizeBytes: Long?,
    val sizeApprox: Boolean,
    val extraTrackersEncoded: List<String> = emptyList(),
    /** what the file contains, from MediaInfo; null when animetosho has no report for it */
    val media: Media? = null,
)

/**
 * SAnime.url of this source: "/kitsu/<id>" since 16.9, "/anime/<anilist id>" before.
 * Entries picked with the old form keep working through AniList and ani.zip's anilist_id.
 */
object ShowUrl {
    sealed class Id {
        data class Kitsu(val id: Int) : Id()
        data class AniList(val id: Int) : Id()
    }

    fun kitsu(id: Int) = "/kitsu/$id"
    fun aniList(id: Int) = "/anime/$id"

    fun parse(url: String): Id? {
        val path = url.substringBefore('?').trimEnd('/')
        val id = path.substringAfterLast('/').toIntOrNull() ?: return null
        return when (path.substringBeforeLast('/').substringAfterLast('/')) {
            "kitsu" -> Id.Kitsu(id)
            "anime" -> Id.AniList(id)
            else -> null
        }
    }

    fun aniZipQuery(id: Id) = when (id) {
        is Id.Kitsu -> "kitsu_id=${id.id}"
        is Id.AniList -> "anilist_id=${id.id}"
    }
}
