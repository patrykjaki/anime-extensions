package eu.kanade.tachiyomi.animeextension.all.editotsutorrents

import okhttp3.Interceptor
import okhttp3.Response

/**
 * AniList allows few requests per minute per IP, and the app uses the same quota.
 * A 429 from AniList made search and episode lists fail. Wait as long as AniList
 * asks (Retry-After, capped) and try again, at most [AniListRetryPolicy.MAX_RETRIES] times.
 */
object AniListRetryPolicy {
    const val MAX_RETRIES = 2
    private const val MAX_WAIT_S = 60L
    private const val DEFAULT_WAIT_S = 5L

    /** Seconds to wait before retry number [attempt] (1-based); null = give up. */
    fun waitSeconds(code: Int, retryAfter: String?, attempt: Int): Long? {
        if (code != 429 || attempt > MAX_RETRIES) return null
        val asked = retryAfter?.trim()?.toLongOrNull() ?: DEFAULT_WAIT_S
        return asked.coerceIn(1L, MAX_WAIT_S)
    }
}

object AniListRetry : Interceptor {
    const val ANILIST_HOST = "graphql.anilist.co"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        if (request.url.host != ANILIST_HOST) return response
        var attempt = 1
        while (true) {
            val wait = AniListRetryPolicy.waitSeconds(response.code, response.header("Retry-After"), attempt) ?: return response
            response.close()
            Thread.sleep(wait * 1000)
            response = chain.proceed(request)
            attempt++
        }
    }
}
