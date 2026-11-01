package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneOffset

/** api.ani.zip/mappings?anilist_id=N: anilist -> anidb ids and per-episode anidb eids. */
object AniZip {
    data class Episode(
        val ref: EpisodeRef,
        val title: String?,
        val airDateMillis: Long?,
        val image: String?,
        val overview: String?,
    )

    data class Mapping(val aid: Int?, val romaji: String?, val english: String?, val episodes: List<Episode>)

    fun parse(body: String): Mapping {
        val o = JSONObject(body)
        val maps = o.optJSONObject("mappings")
        val aid = maps?.optIntOrNull("anidb_id")
        val titles = o.optJSONObject("titles")
        val romaji = titles?.optStringOrNull("x-jat")
        val english = titles?.optStringOrNull("en")
        val query = romaji ?: english ?: ""
        val eps = o.optJSONObject("episodes")
        val list = if (aid == null || eps == null) {
            emptyList()
        } else {
            eps.keys().asSequence()
                // regular episodes only; specials are keyed "S1", "C1", ...
                .mapNotNull { key ->
                    val num = key.toIntOrNull() ?: return@mapNotNull null
                    val obj: JSONObject = eps.optJSONObject(key) ?: return@mapNotNull null
                    num to obj
                }
                .map { (num, e) ->
                    val epTitle = e.optJSONObject("title")
                    Episode(
                        ref = EpisodeRef(
                            aid = aid,
                            eid = e.optIntOrNull("anidbEid"),
                            anidbEp = e.optStringOrNull("episode")?.toIntOrNull() ?: num,
                            season = e.optIntOrNull("seasonNumber"),
                            seasonEp = e.optIntOrNull("episodeNumber"),
                            absolute = e.optIntOrNull("absoluteEpisodeNumber"),
                            query = query,
                        ),
                        title = epTitle?.optStringOrNull("en") ?: epTitle?.optStringOrNull("x-jat"),
                        airDateMillis = parseDate(e.optStringOrNull("airDateUtc") ?: e.optStringOrNull("airdate")),
                        image = e.optStringOrNull("image"),
                        overview = e.optStringOrNull("overview") ?: e.optStringOrNull("summary"),
                    )
                }
                .sortedBy { it.ref.anidbEp }
                .toList()
        }
        return Mapping(aid, romaji, english, list)
    }

    private fun parseDate(s: String?): Long? = s?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}

/** graphql.anilist.co responses, for the catalogue (search/popular/details). */
object AniListParser {
    data class Media(
        val id: Int,
        val romaji: String?,
        val english: String?,
        val cover: String?,
        val description: String?,
        val status: String?,
        val genres: List<String>,
        val studios: List<String>,
    ) {
        val title: String get() = romaji ?: english ?: id.toString()
    }

    data class Page(val media: List<Media>, val hasNext: Boolean)

    fun parsePage(body: String): Page {
        val page = JSONObject(body).optJSONObject("data")?.optJSONObject("Page") ?: return Page(emptyList(), false)
        val arr = page.optJSONArray("media")
        val media = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.let(::parseMedia) }
        return Page(media, page.optJSONObject("pageInfo")?.optBoolean("hasNextPage", false) ?: false)
    }

    fun parseSingle(body: String): Media? =
        JSONObject(body).optJSONObject("data")?.optJSONObject("Media")?.let(::parseMedia)

    private fun parseMedia(m: JSONObject): Media? {
        val t = m.optJSONObject("title")
        val g = m.optJSONArray("genres")
        val s = m.optJSONObject("studios")?.optJSONArray("nodes")
        return Media(
            id = m.optIntOrNull("id") ?: return null,
            romaji = t?.optStringOrNull("romaji"),
            english = t?.optStringOrNull("english"),
            cover = m.optJSONObject("coverImage")?.let { it.optStringOrNull("extraLarge") ?: it.optStringOrNull("large") },
            description = m.optStringOrNull("description")?.replace(Regex("<[^>]+>"), "")?.trim(),
            status = m.optStringOrNull("status"),
            genres = (0 until (g?.length() ?: 0)).mapNotNull { g!!.optString(it).ifEmpty { null } },
            studios = (0 until (s?.length() ?: 0)).mapNotNull { s!!.optJSONObject(it)?.optStringOrNull("name") },
        )
    }
}
