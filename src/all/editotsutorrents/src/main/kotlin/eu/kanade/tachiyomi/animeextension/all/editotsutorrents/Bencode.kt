package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

/** Minimal bencode reader, enough to list the files of a .torrent in libtorrent index order. */
object Bencode {
    class Reader(private val b: ByteArray) {
        var pos = 0

        fun read(): Any {
            return when (val c = b[pos].toInt().toChar()) {
                'i' -> {
                    val end = indexOf('e'.code.toByte(), pos)
                    val v = String(b, pos + 1, end - pos - 1, Charsets.US_ASCII).toLong()
                    pos = end + 1
                    v
                }
                'l' -> {
                    pos++
                    val out = ArrayList<Any>()
                    while (b[pos] != 'e'.code.toByte()) out += read()
                    pos++
                    out
                }
                'd' -> {
                    pos++
                    val out = LinkedHashMap<String, Any>()
                    while (b[pos] != 'e'.code.toByte()) {
                        val k = String(read() as ByteArray, Charsets.UTF_8)
                        out[k] = read()
                    }
                    pos++
                    out
                }
                in '0'..'9' -> {
                    val colon = indexOf(':'.code.toByte(), pos)
                    val len = String(b, pos, colon - pos, Charsets.US_ASCII).toInt()
                    val start = colon + 1
                    require(len >= 0 && start + len <= b.size) { "bad string length" }
                    pos = start + len
                    b.copyOfRange(start, start + len)
                }
                else -> error("bad bencode at $pos: $c")
            }
        }

        private fun indexOf(x: Byte, from: Int): Int {
            var i = from
            while (i < b.size) {
                if (b[i] == x) return i
                i++
            }
            error("unterminated bencode")
        }
    }

    data class TorrentMeta(val name: String, val files: List<TorrentFile>, val announce: List<String>)

    /** Returns null for unreadable or v2-only torrents. */
    fun parseTorrent(bytes: ByteArray): TorrentMeta? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val root = Reader(bytes).read() as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val info = root["info"] as? Map<String, Any> ?: return null
        val name = str(info["name.utf-8"] ?: info["name"]) ?: return null
        val list = info["files"] as? List<*>
        val files = if (list == null) {
            val len = info["length"] as? Long ?: return null
            listOf(TorrentFile(0, name, len, false))
        } else {
            list.mapIndexed { i, f ->
                val m = f as Map<*, *>
                val parts = (m["path.utf-8"] ?: m["path"]) as List<*>
                val path = parts.joinToString("/") { str(it).orEmpty() }
                val attr = str(m["attr"]).orEmpty()
                TorrentFile(i, "$name/$path", m["length"] as Long, attr.contains('p'))
            }
        }
        val announce = buildList {
            str(root["announce"])?.let(::add)
            (root["announce-list"] as? List<*>)?.forEach { tier ->
                (tier as? List<*>)?.forEach { str(it)?.let(::add) }
            }
        }.distinct()
        TorrentMeta(name, files, announce)
    }.getOrNull()

    private fun str(v: Any?): String? = (v as? ByteArray)?.toString(Charsets.UTF_8)
}
