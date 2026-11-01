package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import java.text.Normalizer

/** Title keys: lowercase ascii letters and digits only, used to compare release names with an entry. */
object Titles {
    fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }

    /** keys of every latin-script title, longest first; very short keys are dropped (too ambiguous) */
    fun keys(titles: Collection<String?>): List<String> =
        titles.filterNotNull().map(::key).filter { it.length >= 4 }.distinct().sortedByDescending { it.length }
}
