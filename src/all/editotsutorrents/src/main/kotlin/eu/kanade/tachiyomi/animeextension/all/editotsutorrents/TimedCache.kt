package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/** Small in-memory cache: at most [max] entries (least recently used goes first), each kept for [ttlMs]. */
class TimedCache<V : Any>(private val max: Int, private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val map = object : LinkedHashMap<String, Pair<Long, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, V>>) = size > max
    }

    @Synchronized
    fun get(key: String): V? {
        val (at, value) = map[key] ?: return null
        if (ttlMs != Long.MAX_VALUE && now() - at > ttlMs) {
            map.remove(key)
            return null
        }
        return value
    }

    @Synchronized
    fun put(key: String, value: V) {
        map[key] = now() to value
    }
}
