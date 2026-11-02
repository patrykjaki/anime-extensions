package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONObject
import java.util.Locale

/**
 * Facts that exist only in release names (group, source, service, revision, censorship), read
 * once here so the app gets them as fields. For rows without MediaInfo (mostly Nyaa-only
 * releases) the same pass also fills the media fields a name states (codec, bit depth, HDR,
 * audio kind); those are marked `"from":"name"` in internalData so the app can tell them apart
 * from MediaInfo.
 */
data class ReleaseTags(
    val group: String?,
    /** "BD", "WEB-DL", "WEBRip", "DVD", "HDTV", "REMUX" */
    val source: String?,
    /** short service code: "CR", "NF", "AMZN", "DSNP", "BILI", "ADN", "HIDIVE", "ABEMA", ... */
    val service: String?,
    /** "REPACK", "PROPER", "v2", ... */
    val revision: String?,
    /** "Uncensored" / "Censored" */
    val censorship: String?,
    // name-only media fallback
    val codec: String?,
    val bitDepth: Int?,
    val hdr: String?,
    /** "dual" (original + dub), "dub", "multi"; null = not stated */
    val audio: String?,
    /** true when the name says multi-subs */
    val multiSubs: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("from", "name")
        group?.let { put("group", it) }
        source?.let { put("source", it) }
        service?.let { put("service", it) }
        revision?.let { put("revision", it) }
        censorship?.let { put("censorship", it) }
    }

    /** media fields stated by the name, for rows without MediaInfo */
    fun nameMediaJson(): JSONObject? {
        if (codec == null && bitDepth == null && hdr == null && audio == null && !multiSubs) return null
        return JSONObject().apply {
            put("from", "name")
            codec?.let { put("codec", it) }
            bitDepth?.let { put("bitDepth", it) }
            hdr?.let { put("hdr", it) }
            audio?.let { put("audioKind", it) }
            if (multiSubs) put("multiSubs", true)
        }
    }

    companion object {
        private val LEADING_GROUP = Regex("""^\s*\[([^\]]{1,40})]""")
        private val MEDIA_EXT = Regex("""(?i)\.(?:mkv|mp4|avi|m2ts|webm|ts)$""")
        /** notes after the scene group: "(Kusuriya no Hitorigoto, Multi-Subs)", "[rartv]" */
        private val TRAILING_NOTES = Regex("""(?:\s*[\[(][^\[\]()]*[\])])+\s*$""")
        /** "S01E01-E02", "01-02v2": a range, not a group */
        private val EPISODE_TOKEN = Regex("""(?i)(?:s\d{1,2})?e?\d{1,4}(?:v\d)?""")
        private val SCENE_GROUP = Regex("""[A-Za-z0-9][A-Za-z0-9-]{0,29}""")
        private val NOT_GROUPS = setOf("1080P", "720P", "2160P", "480P", "BATCH", "MULTI", "DUAL", "HEVC", "AV1", "DL", "RIP")

        private val SOURCES = listOf(
            "REMUX" to Regex("""(?i)(?<![a-z])remux(?![a-z])"""),
            // digits excluded on both sides: "[6D4B0BD7]" (a CRC) is not a BD source
            "BD" to Regex("""(?i)(?<![a-z0-9])(?:bd(?:rip)?|blu-?ray|bdmv)(?![a-z0-9])"""),
            "WEB-DL" to Regex("""(?i)(?<![a-z])web-?dl(?![a-z])"""),
            "WEBRip" to Regex("""(?i)(?<![a-z])web-?rip(?![a-z])"""),
            "WEB" to Regex("""(?i)(?<![a-z])web(?![a-z-])"""),
            "DVD" to Regex("""(?i)(?<![a-z])dvd(?:rip)?(?![a-z])"""),
            "HDTV" to Regex("""(?i)(?<![a-z])(?:hdtv|tv-?rip)(?![a-z])"""),
        )
        private val SERVICES = listOf("CR", "NF", "AMZN", "DSNP", "BILI", "B-GLOBAL", "ADN", "HIDIVE", "ABEMA", "U-NEXT", "UNEXT", "IQIYI", "BAHA", "FUNI", "HULU", "VRV", "YT")
        private val SERVICE_TOKEN = Regex("""(?i)(?<![a-z0-9])(${SERVICES.joinToString("|") { Regex.escape(it) }})(?![a-z0-9])""")
        private val REVISION = Regex("""(?i)(?<![a-z0-9])(REPACK\d?|PROPER)(?![a-z0-9])|(?<![a-z])(v[2-9])(?![a-z0-9])""")
        private val CENSOR = Regex("""(?i)(?<![a-z])(uncensored|censored)(?![a-z])""")
        private val CODEC = listOf(
            "HEVC" to Regex("""(?i)(?<![a-z0-9])(?:hevc|[xh]\.?265)(?![0-9])"""),
            "AVC" to Regex("""(?i)(?<![a-z0-9])(?:avc|[xh]\.?264)(?![0-9])"""),
            "AV1" to Regex("""(?i)(?<![a-z0-9])av1(?![0-9])"""),
            "VP9" to Regex("""(?i)(?<![a-z0-9])vp9(?![0-9])"""),
        )
        private val BIT10 = Regex("""(?i)(?<![a-z0-9])(?:10[\s-]?bits?|hi10p?)(?![a-z0-9])""")
        private val HDR = listOf(
            "Dolby Vision" to Regex("""(?i)(?<![a-z])(?:dolby[\s.]?vision|dovi|DV)(?![a-z])"""),
            "HDR10+" to Regex("""(?i)hdr10(?:\+|plus)"""),
            "HDR10" to Regex("""(?i)(?<![a-z])hdr10(?![0-9+p])"""),
            "HDR" to Regex("""(?i)(?<![a-z])hdr(?![a-z0-9])"""),
        )
        private val DUAL = Regex("""(?i)(?<![a-z])(?:dual[\s-]?audio|dual)(?![a-z])""")
        private val MULTI_AUDIO = Regex("""(?i)multi[\s-]?audio""")
        private val DUB = Regex("""(?i)(?<![a-z])(?:dub(?:bed)?|eng(?:lish)?[\s-]dub)(?![a-z])""")
        private val MULTI_SUBS = Regex("""(?i)multi[\s-]?subs?""")

        fun of(vararg names: String?): ReleaseTags {
            val ns = names.filterNotNull().map { it.substringAfterLast('/') }
            fun <T> first(f: (String) -> T?): T? = ns.firstNotNullOfOrNull(f)
            return ReleaseTags(
                group = first(::group),
                source = first { n -> SOURCES.firstOrNull { it.second.containsMatchIn(n) }?.first },
                service = first { n -> SERVICE_TOKEN.find(n)?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let { if (it == "UNEXT") "U-NEXT" else it } },
                revision = first { n ->
                    REVISION.find(n)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
                        ?.let { if (it.startsWith("v", ignoreCase = true)) it.lowercase(Locale.ROOT) else it.uppercase(Locale.ROOT) }
                },
                censorship = first { n -> CENSOR.find(n)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.replaceFirstChar { it.uppercase() } },
                codec = first { n -> CODEC.firstOrNull { it.second.containsMatchIn(n) }?.first },
                bitDepth = first { n -> if (BIT10.containsMatchIn(n)) 10 else null },
                hdr = first { n -> HDR.firstOrNull { it.second.containsMatchIn(n) }?.first },
                audio = first { n ->
                    when {
                        MULTI_AUDIO.containsMatchIn(n) -> "multi"
                        DUAL.containsMatchIn(n) -> "dual"
                        DUB.containsMatchIn(n) -> "dub"
                        else -> null
                    }
                },
                multiSubs = ns.any { MULTI_SUBS.containsMatchIn(it) },
            )
        }

        private fun group(name: String): String? {
            LEADING_GROUP.find(name)?.groupValues?.get(1)?.trim()?.let { g ->
                if (g.uppercase(Locale.ROOT) !in NOT_GROUPS && !g.any { it.code > 0x2E7F }) return g
            }
            // scene style: "Show.S01E01.1080p.WEB.x264-GROUP.mkv", "... H.264-VARYG (Show, Multi-Subs)",
            // "... x264-Tsundere-Raws (VF)": the part after the first '-' of the last token
            val core = name.trim().replace(MEDIA_EXT, "").replace(TRAILING_NOTES, "").trim()
            val token = core.substringAfterLast(' ').substringAfterLast('.')
            val dash = token.indexOf('-')
            if (dash <= 0) return null
            val g = token.substring(dash + 1).trimEnd('-')
            return g.takeIf {
                SCENE_GROUP.matches(it) && it.length >= 2 && it.uppercase(Locale.ROOT) !in NOT_GROUPS &&
                    !it.all(Char::isDigit) && !EPISODE_TOKEN.matches(it)
            }
        }
    }
}
