package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedCacheTest {
    @Test
    fun `entry is gone after its time`() {
        var now = 0L
        val c = TimedCache<String>(10, 1000) { now }
        c.put("a", "1")
        now = 1000
        assertEquals("1", c.get("a"))
        now = 1001
        assertNull(c.get("a"))
    }

    @Test
    fun `least recently used entry is dropped when full`() {
        val c = TimedCache<String>(2, Long.MAX_VALUE)
        c.put("a", "1")
        c.put("b", "2")
        c.get("a")
        c.put("c", "3")
        assertEquals("1", c.get("a"))
        assertNull(c.get("b"))
        assertEquals("3", c.get("c"))
    }
}
