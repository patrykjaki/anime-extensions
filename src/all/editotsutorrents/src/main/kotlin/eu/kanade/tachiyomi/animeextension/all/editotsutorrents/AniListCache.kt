package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject

/**
 * Catalogue replies kept in memory for [ttlMs], so reopening a show or repeating a
 * search doesn't spend AniList's per-IP quota, which the app also uses. [AniListCache] keys: [mediaKey] for one entry
 * (`{"data":{"Media":...}}`, the shape [AniListParser.parseTitles] and [NoAniDb.airedCount] read),
 * [pageKey] for a catalogue page. Oldest entries go first past [maxEntries].
 */
class TtlCache<V : Any>(private val ttlMs: Long, private val maxEntries: Int) {
    private class Entry<V>(val body: V, val at: Long)

    private val map = LinkedHashMap<String, Entry<V>>()

    @Synchronized
    fun get(key: String, now: Long = System.currentTimeMillis()): V? {
        val e = map[key] ?: return null
        if (now - e.at >= ttlMs) {
            map.remove(key)
            return null
        }
        return e.body
    }

    @Synchronized
    fun put(key: String, body: V, now: Long = System.currentTimeMillis()) {
        map.remove(key)
        map[key] = Entry(body, now)
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    @Synchronized
    fun clear() = map.clear()
}

object AniListCache {
    const val TTL_MS = 30L * 60 * 1000
    val cache = TtlCache<String>(TTL_MS, maxEntries = 500)

    fun mediaKey(id: Int) = "media:$id"
    fun pageKey(sort: String, page: Int, search: String?) = "page:$sort:$page:${search.orEmpty()}"

    /**
     * Store [body] under [key], and every Media in it under its own [mediaKey], so a show
     * found by search needs no AniList request when its episode list opens.
     * Bodies with no data (errors, rate-limit replies) are not stored.
     */
    fun putResponse(key: String, body: String, now: Long = System.currentTimeMillis()): Boolean {
        val media = AniListParser.mediaObjects(body) ?: return false
        cache.put(key, body, now)
        for (m in media) {
            val id = m.optInt("id", 0).takeIf { it > 0 } ?: continue
            cache.put(mediaKey(id), JSONObject().put("data", JSONObject().put("Media", m)).toString(), now)
        }
        return true
    }

    fun get(key: String, now: Long = System.currentTimeMillis()) = cache.get(key, now)
}

/** Kitsu reply bodies by url, and episode-list [EpisodeList.Info] by Kitsu id, same TTL as AniList's. */
object KitsuCache {
    val bodies = TtlCache<String>(AniListCache.TTL_MS, maxEntries = 500)
    val info = TtlCache<EpisodeList.Info>(AniListCache.TTL_MS, maxEntries = 500)
}

/**
 * At least [minGapMs] between AniList request starts from this extension. AniList allows
 * about 30 a minute per IP, so a 2 s gap keeps bursts (search + details + episode list,
 * parallel catalogue pages) from tripping the 429 on their own.
 */
class AniListPacer(private val minGapMs: Long) {
    private var next = 0L

    /** Reserve the next slot; returns how long the caller must wait first (ms). */
    @Synchronized
    fun reserve(now: Long): Long {
        val start = maxOf(now, next)
        next = start + minGapMs
        return start - now
    }

    /** After a 429: nobody starts another request before [until]. */
    @Synchronized
    fun holdUntil(until: Long) {
        if (until > next) next = until
    }
}
