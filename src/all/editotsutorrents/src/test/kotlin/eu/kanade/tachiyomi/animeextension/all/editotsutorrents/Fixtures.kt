package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    /** Frieren S1 (anilist 154587, anidb 17617) episode 5, numbers from the ani.zip fixture */
    val FRIEREN_EP5 = EpisodeRef(aid = 17617, eid = 271708, anidbEp = 5, season = 1, seasonEp = 5, absolute = 5, query = "Sousou no Frieren")
    val FRIEREN_EP1 = EpisodeRef(aid = 17617, eid = 271418, anidbEp = 1, season = 1, seasonEp = 1, absolute = 1, query = "Sousou no Frieren")
}
