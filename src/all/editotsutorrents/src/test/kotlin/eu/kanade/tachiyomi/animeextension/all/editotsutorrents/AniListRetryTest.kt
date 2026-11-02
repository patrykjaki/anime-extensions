package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListRetryTest {
    @Test
    fun onlyRateLimitIsRetried() {
        assertNull(AniListRetryPolicy.waitSeconds(200, "10", 1))
        assertNull(AniListRetryPolicy.waitSeconds(500, "10", 1))
    }

    @Test
    fun waitFollowsRetryAfterWithinBounds() {
        assertEquals(12L, AniListRetryPolicy.waitSeconds(429, "12", 1))
        assertEquals(60L, AniListRetryPolicy.waitSeconds(429, "600", 1))
        assertEquals(1L, AniListRetryPolicy.waitSeconds(429, "0", 1))
        assertEquals(5L, AniListRetryPolicy.waitSeconds(429, null, 1))
        assertEquals(5L, AniListRetryPolicy.waitSeconds(429, "Wed, 21 Oct 2026 07:28:00 GMT", 1))
    }

    @Test
    fun givesUpAfterTwoRetries() {
        assertEquals(5L, AniListRetryPolicy.waitSeconds(429, null, 2))
        assertNull(AniListRetryPolicy.waitSeconds(429, null, 3))
    }
}
