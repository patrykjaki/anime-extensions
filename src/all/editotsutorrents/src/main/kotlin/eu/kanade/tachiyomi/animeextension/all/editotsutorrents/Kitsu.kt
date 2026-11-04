package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * kitsu.io/api/edge (JSON:API), the catalogue since 16.9. It has no tight per-IP limit,
 * unlike AniList, whose quota the app also spends.
 */
object Kitsu {
    const val API = "https://kitsu.io/api/edge"
    const val PAGE_SIZE = 20 // kitsu's maximum
    private const val ANIME_FIELDS =
        "fields%5Banime%5D=canonicalTitle,titles,abbreviatedTitles,posterImage,status,synopsis,episodeCount,nsfw"

    private fun pageParams(page: Int) = "page%5Blimit%5D=$PAGE_SIZE&page%5Boffset%5D=${(page - 1) * PAGE_SIZE}&$ANIME_FIELDS"
    fun popularUrl(page: Int) = "$API/anime?sort=-userCount&${pageParams(page)}"
    fun airingUrl(page: Int) = "$API/anime?filter%5Bstatus%5D=current&sort=-userCount&${pageParams(page)}"
    fun searchUrl(page: Int, query: String) = "$API/anime?filter%5Btext%5D=${Text.urlEncode(query)}&${pageParams(page)}"
    fun detailsUrl(id: Int) = "$API/anime/$id?include=categories&fields%5Bcategories%5D=title"
    fun episodesUrl(id: Int, page: Int) =
        "$API/anime/$id/episodes?sort=number&page%5Blimit%5D=$PAGE_SIZE&page%5Boffset%5D=${(page - 1) * PAGE_SIZE}&fields%5Bepisodes%5D=number,airdate"

    data class Media(
        val id: Int,
        val title: String,
        val titles: List<String>,
        val cover: String?,
        val description: String?,
        val status: String?,
        val episodeCount: Int?,
        val nsfw: Boolean,
        val genres: List<String>,
    )

    data class Page(val media: List<Media>, val hasNext: Boolean)

    fun parsePage(body: String): Page {
        val o = JSONObject(body)
        val arr = o.optJSONArray("data") ?: throw Exception("Kitsu returned no data")
        val media = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { m -> parseMedia(m, null) } }
            .filterNot { it.nsfw }
        return Page(media, o.optJSONObject("links")?.optStringOrNull("next") != null)
    }

    fun parseSingle(body: String): Media? {
        val o = JSONObject(body)
        return o.optJSONObject("data")?.let { parseMedia(it, o.optJSONArray("included")) }
    }

    private fun parseMedia(m: JSONObject, included: JSONArray?): Media? {
        val id = m.optString("id").toIntOrNull() ?: return null
        val a = m.optJSONObject("attributes") ?: return null
        val t = a.optJSONObject("titles")
        val canonical = a.optStringOrNull("canonicalTitle")
        val abbr = a.optJSONArray("abbreviatedTitles")
        // romaji first: it's the title release names use most and the Nyaa query
        val titles = (
            listOf(canonical, t?.optStringOrNull("en_jp"), t?.optStringOrNull("en"), t?.optStringOrNull("en_us")) +
                (0 until (abbr?.length() ?: 0)).map { abbr!!.optString(it).ifEmpty { null } }
            ).filterNotNull().distinct()
        val poster = a.optJSONObject("posterImage")
        val genres = (0 until (included?.length() ?: 0)).mapNotNull { i ->
            included!!.optJSONObject(i)?.takeIf { it.optString("type") == "categories" }
                ?.optJSONObject("attributes")?.optStringOrNull("title")
        }
        return Media(
            id = id,
            title = titles.firstOrNull() ?: id.toString(),
            titles = titles,
            cover = poster?.let { it.optStringOrNull("large") ?: it.optStringOrNull("original") ?: it.optStringOrNull("medium") },
            description = a.optStringOrNull("synopsis")?.trim(),
            status = a.optStringOrNull("status"),
            episodeCount = a.optIntOrNull("episodeCount")?.takeIf { it > 0 },
            nsfw = a.optBoolean("nsfw", false),
            genres = genres,
        )
    }

    /** one page of /anime/{id}/episodes: (number, air date or null), and whether more pages follow */
    data class EpisodePage(val episodes: List<Pair<Int, LocalDate?>>, val hasNext: Boolean)

    fun parseEpisodes(body: String): EpisodePage {
        val o = JSONObject(body)
        val arr = o.optJSONArray("data") ?: throw Exception("Kitsu returned no episodes")
        val eps = (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
            val n = a.optIntOrNull("number") ?: return@mapNotNull null
            n to a.optStringOrNull("airdate")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        }
        return EpisodePage(eps, o.optJSONObject("links")?.optStringOrNull("next") != null)
    }

    /**
     * Episodes out, for entries without an AniDB id. Finished: the total. Not started: 0.
     * Airing: the highest episode whose air date has passed. null = kitsu can't tell (airing
     * but no dated episode, e.g. Kusuriya S3 on 2026-11-04), then AniList is asked instead.
     */
    fun airedCount(media: Media, episodes: List<Pair<Int, LocalDate?>>, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int? {
        val dated = episodes.filter { (_, d) -> d != null && !d.isAfter(today) }.maxOfOrNull { it.first }
        return when (media.status) {
            "finished" -> media.episodeCount ?: dated ?: episodes.maxOfOrNull { it.first }
            "upcoming", "unreleased", "tba" -> 0
            else -> dated
        }
    }

    /** whether another episodes page could still hold aired episodes */
    fun needsMoreEpisodes(page: EpisodePage, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Boolean =
        page.hasNext && page.episodes.isNotEmpty() && page.episodes.all { (_, d) -> d != null && !d.isAfter(today) }
}
