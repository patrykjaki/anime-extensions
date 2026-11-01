package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One audio track as MediaInfo reports it. */
data class AudioTrack(
    /** BCP-47 language ("ja", "en", "pt-BR"); null when the track has none */
    val lang: String?,
    /** "AAC", "FLAC", "Opus", "EAC3", "AC3", "TrueHD", "DTS", ... */
    val codec: String?,
    val channels: Int?,
    val title: String?,
    val default: Boolean?,
)

/** One subtitle track as MediaInfo reports it. */
data class SubtitleTrack(
    val lang: String?,
    /** "ASS", "SRT", "PGS", ... */
    val format: String?,
    val title: String?,
    val forced: Boolean,
    val default: Boolean?,
)

/** What the file actually contains, read from MediaInfo (no guessing from names). */
data class Media(
    /** "AVC", "HEVC", "AV1", "VP9", ... */
    val codec: String?,
    val bitDepth: Int?,
    val width: Int?,
    val height: Int?,
    /** "Dolby Vision", "HDR10+", "HDR10", "HLG", "HDR"; null = SDR */
    val hdr: String?,
    val durationSec: Int?,
    val audio: List<AudioTrack>,
    val subtitles: List<SubtitleTrack>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("from", "mediainfo")
        codec?.let { put("codec", it) }
        bitDepth?.let { put("bitDepth", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        hdr?.let { put("hdr", it) }
        durationSec?.let { put("durationSec", it) }
        put(
            "audio",
            JSONArray(
                audio.map { a ->
                    JSONObject().apply {
                        a.lang?.let { put("lang", it) }
                        a.codec?.let { put("codec", it) }
                        a.channels?.let { put("channels", it) }
                        a.title?.let { put("title", it) }
                        a.default?.let { put("default", it) }
                    }
                },
            ),
        )
        put(
            "subtitles",
            JSONArray(
                subtitles.map { s ->
                    JSONObject().apply {
                        s.lang?.let { put("lang", it) }
                        s.format?.let { put("format", it) }
                        s.title?.let { put("title", it) }
                        put("forced", s.forced)
                        s.default?.let { put("default", it) }
                    }
                },
            ),
        )
    }
}

/**
 * Reads MediaInfo's text report ("Section" lines, then "Key : Value" lines). animetosho.net
 * runs MediaInfo on every file it mirrors and returns the report in `json?show=torrent`.
 * Values are read by field name; only number formatting ("1 080 pixels") is cleaned.
 */
object MediaInfo {
    fun parse(report: String): Media? {
        val sections = sections(report)
        val video = sections.firstOrNull { it.first.startsWith("Video") }?.second
        val audio = sections.filter { it.first.startsWith("Audio") }.map { it.second }
        val text = sections.filter { it.first.startsWith("Text") }.map { it.second }
        if (video == null && audio.isEmpty() && text.isEmpty()) return null
        val general = sections.firstOrNull { it.first == "General" }?.second.orEmpty()
        return Media(
            codec = video?.get("Format")?.let(::videoCodec),
            bitDepth = video?.get("Bit depth")?.let(::number),
            width = video?.get("Width")?.let(::number),
            height = video?.get("Height")?.let(::number),
            hdr = video?.let(::hdr),
            durationSec = (general["Duration"] ?: video?.get("Duration"))?.let(::durationSec),
            audio = audio.map { a ->
                AudioTrack(
                    lang = a["Language"]?.let(Languages::code),
                    codec = (a["Commercial name"]?.let(::audioCodecFromCommercial) ?: a["Format"]?.let(::audioCodec)),
                    channels = a["Channel(s)"]?.let(::number),
                    title = a["Title"],
                    default = a["Default"]?.let(::yes),
                )
            },
            subtitles = text.map { t ->
                SubtitleTrack(
                    lang = t["Language"]?.let(Languages::code),
                    format = t["Format"],
                    title = t["Title"],
                    forced = t["Forced"]?.let(::yes) ?: false,
                    default = t["Default"]?.let(::yes),
                )
            },
        )
    }

    /** The report of the file named [fileName] (any path, compared by base name), else the torrent's only/first video file. */
    fun fromShow(body: String, fileName: String?): Media? {
        val files = JSONObject(body).optJSONArray("files") ?: return null
        val list = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
        val wanted = fileName?.substringAfterLast('/')
        val file = wanted?.let { w -> list.firstOrNull { it.optString("filename").substringAfterLast('/') == w } }
            ?: list.filter { EpisodeMatcher.isVideo(it.optString("filename")) }.singleOrNull()
            ?: return null
        val report = file.optJSONObject("info")?.optString("mediainfo").orEmpty()
        val media = (if (report.isBlank()) null else parse(report)) ?: return null
        return withAttachmentLanguages(media, file.optJSONArray("attachments"))
    }

    /**
     * animetosho also extracts subtitle tracks as attachments with their own language code.
     * Fill subtitle languages MediaInfo left empty from them, in track order.
     */
    private fun withAttachmentLanguages(media: Media, attachments: JSONArray?): Media {
        if (attachments == null || media.subtitles.none { it.lang == null }) return media
        val subs = (0 until attachments.length()).mapNotNull { attachments.optJSONObject(it) }
            .filter { it.optString("type") == "subtitle" }
            .sortedBy { it.optJSONObject("info")?.optInt("tracknum", Int.MAX_VALUE) ?: Int.MAX_VALUE }
            .map { it.optJSONObject("info")?.optString("language_code")?.takeIf { c -> c.isNotBlank() }?.let(Languages::code) }
        if (subs.size != media.subtitles.size) return media
        return media.copy(subtitles = media.subtitles.mapIndexed { i, s -> if (s.lang == null) s.copy(lang = subs[i]) else s })
    }

    private fun sections(report: String): List<Pair<String, Map<String, String>>> {
        val out = ArrayList<Pair<String, MutableMap<String, String>>>()
        for (raw in report.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon < 0) {
                out += line.trim() to LinkedHashMap()
                continue
            }
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            // first value wins (MediaInfo repeats some keys with other units)
            out.lastOrNull()?.second?.putIfAbsent(key, value)
        }
        return out
    }

    private fun number(v: String): Int? = v.takeWhile { it.isDigit() || it == ' ' }.replace(" ", "").toIntOrNull()

    private fun yes(v: String): Boolean = v.equals("Yes", ignoreCase = true)

    private fun videoCodec(format: String): String = when (format.uppercase(Locale.ROOT)) {
        "AVC" -> "AVC"
        "HEVC" -> "HEVC"
        "AV1" -> "AV1"
        "VP9" -> "VP9"
        "MPEG-4 VISUAL" -> "MPEG-4"
        "MPEG VIDEO" -> "MPEG-2"
        else -> format
    }

    private fun audioCodec(format: String): String = when (format.uppercase(Locale.ROOT)) {
        "AAC", "AAC LC", "AAC LC SBR", "HE-AAC" -> "AAC"
        "E-AC-3", "E-AC-3 JOC" -> "EAC3"
        "AC-3" -> "AC3"
        "MLP FBA", "MLP FBA 16-CH" -> "TrueHD"
        "FLAC" -> "FLAC"
        "OPUS" -> "Opus"
        "VORBIS" -> "Vorbis"
        "DTS" -> "DTS"
        "MPEG AUDIO" -> "MP3"
        "PCM" -> "PCM"
        else -> format
    }

    private fun audioCodecFromCommercial(name: String): String? = when {
        name.contains("TrueHD", ignoreCase = true) -> "TrueHD"
        name.contains("DTS-HD", ignoreCase = true) -> "DTS-HD"
        else -> null
    }

    private fun hdr(video: Map<String, String>): String? {
        val format = video["HDR format"].orEmpty()
        val transfer = video["Transfer characteristics"].orEmpty()
        return when {
            format.contains("Dolby Vision", ignoreCase = true) -> "Dolby Vision"
            format.contains("2094", ignoreCase = true) || format.contains("HDR10+", ignoreCase = true) -> "HDR10+"
            format.contains("2086", ignoreCase = true) || format.contains("HDR10", ignoreCase = true) -> "HDR10"
            transfer.contains("HLG", ignoreCase = true) -> "HLG"
            transfer.contains("PQ", ignoreCase = false) -> "HDR"
            else -> null
        }
    }

    /** "24 min 31 s", "1 h 2 min", "1 h 2 min 3 s" */
    private fun durationSec(v: String): Int? {
        var total = 0
        var any = false
        val parts = v.split(' ')
        var i = 0
        while (i < parts.size - 1) {
            val n = parts[i].toIntOrNull()
            if (n != null) {
                when (parts[i + 1]) {
                    "h" -> { total += n * 3600; any = true }
                    "min" -> { total += n * 60; any = true }
                    "s" -> { total += n; any = true }
                }
            }
            i++
        }
        return if (any) total else null
    }
}

/** MediaInfo language names ("Japanese", "Portuguese (Brazil)") to BCP-47 codes ("ja", "pt-BR"). */
object Languages {
    private val byName: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }
    }
    private val regionByName: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT) } +
            mapOf("latin america" to "419", "us" to "US", "uk" to "GB", "brazilian" to "BR")
    }

    fun code(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        // already a code ("ja", "jpn", "en-US")
        if (Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,3})?$").matches(n)) return normalizeCode(n)
        val base = n.substringBefore('(').trim().lowercase(Locale.ROOT)
        val lang = byName[base]?.let { LEGACY[it] ?: it } ?: return null
        val region = n.substringAfter('(', "").substringBefore(')').trim().lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }?.let { regionByName[it] }
        return if (region != null) "$lang-$region" else lang
    }

    private fun normalizeCode(c: String): String {
        val lang = c.substringBefore('-').lowercase(Locale.ROOT)
        val two = if (lang.length == 3) {
            Locale.getISOLanguages().firstOrNull { Locale.forLanguageTag(it).isO3Language == lang } ?: lang
        } else {
            lang
        }
        val region = c.substringAfter('-', "").uppercase(Locale.ROOT)
        val modern = LEGACY[two] ?: two
        return if (region.isEmpty()) modern else "$modern-$region"
    }

    /** Java still reports a few pre-1989 ISO codes */
    private val LEGACY = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
}
