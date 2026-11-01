package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** nyaa.si RSS (?page=rss) parser; nyaa:* elements checked against a live feed on 2026-11-01. */
object NyaaParser {
    private const val NS = "https://nyaa.si/xmlns/nyaa"

    /** Nyaa RSS has no tracker list; these are the announce urls nyaa puts in its own magnets. */
    val DEFAULT_TRACKERS = listOf(
        "http://nyaa.tracker.wf:7777/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
    ).map(Text::urlEncode)

    fun parse(xml: String): List<TorrentRow> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i -> parseItem(items.item(i) as Element) }
    }

    private fun parseItem(e: Element): TorrentRow? {
        fun plain(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim()
        fun nyaa(tag: String) = e.getElementsByTagNameNS(NS, tag).item(0)?.textContent?.trim()
        val hash = Text.normalizeInfoHash(nyaa("infoHash")) ?: return null
        val title = plain("title") ?: return null
        val sukebei = plain("guid")?.contains("sukebei.") == true
        return TorrentRow(
            infoHash = hash,
            torrentName = title,
            title = title,
            totalSize = Text.parseSize(nyaa("size")),
            sizeApprox = true,
            seeders = nyaa("seeders")?.toIntOrNull(),
            leechers = nyaa("leechers")?.toIntOrNull(),
            numFiles = null,
            anidbAid = null,
            anidbEid = null,
            tracker = if (sukebei) "sukebei" else "nyaa",
            trackersEncoded = DEFAULT_TRACKERS,
            torrentUrl = plain("link")?.takeIf { it.endsWith(".torrent") },
            toshoIsSeadex = false,
            trusted = nyaa("trusted")?.equals("Yes", ignoreCase = true),
            remake = nyaa("remake")?.equals("Yes", ignoreCase = true),
            timestamp = null,
            source = "nyaa",
        ).takeIf { nyaa("categoryId") != "1_1" } // 1_1 = AMVs
    }
}
