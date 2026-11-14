package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import java.text.Normalizer

/** Title keys: lowercase ascii letters and digits only, used to compare release names with an entry. */
object Titles {
    fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * What release names call an entry with a very long title: the part before its first colon
     * ("Tsuihou Sareta ... wo Ouka Suru.: Ore wa Buki dake ja Naku, ..." is released as
     * "Tsuihou Sareta ... wo Ouka Suru"). Shorter titles are returned as they are, since for
     * those the part before a colon is often another show ("Fate/stay night: Unlimited ...").
     */
    fun short(title: String): String =
        if (title.length > LONG_TITLE && ':' in title) title.substringBefore(':').trim().trimEnd('.').trim() else title

    /** keys of every latin-script title and its [short] form, longest first; very short keys are dropped (too ambiguous) */
    fun keys(titles: Collection<String?>): List<String> =
        titles.filterNotNull().flatMap { listOf(it, short(it)) }.map(::key).filter { it.length >= 4 }.distinct()
            .sortedByDescending { it.length }

    private const val LONG_TITLE = 60
}
