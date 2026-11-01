package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import java.net.URLDecoder
import java.net.URLEncoder

internal object Text {
    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val B32 = Regex("^[A-Za-z2-7]{32}$")

    /** v1 info hash as lowercase hex, from 40-hex or 32-base32; null otherwise. */
    fun normalizeInfoHash(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (HEX40.matches(s)) return s.lowercase()
        if (B32.matches(s)) return base32ToHex(s.uppercase())
        return null
    }

    private fun base32ToHex(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0L
        var n = 0
        val sb = StringBuilder()
        for (c in s) {
            bits = (bits shl 5) or alphabet.indexOf(c).toLong()
            n += 5
            if (n >= 8) {
                n -= 8
                sb.append("%02x".format(((bits shr n) and 0xFF).toInt()))
            }
        }
        return sb.toString()
    }

    /** "tr" values of a magnet, still url-encoded. */
    fun magnetTrackers(magnet: String?): List<String> =
        magnet?.substringAfter('?', "")?.split('&')
            ?.filter { it.startsWith("tr=") }
            ?.map { it.removePrefix("tr=") }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private val SIZE = Regex("""^\s*([\d.]+)\s*([KMGT]?i?B)\s*$""", RegexOption.IGNORE_CASE)

    /** nyaa "6.6 GiB" -> bytes (rounded by nyaa). */
    fun parseSize(s: String?): Long? {
        val m = SIZE.find(s ?: return null) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].uppercase()
        val mult = when (unit.first()) {
            'K' -> 1L shl 10
            'M' -> 1L shl 20
            'G' -> 1L shl 30
            'T' -> 1L shl 40
            else -> 1L
        }
        return (v * mult).toLong()
    }
}
