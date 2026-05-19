package com.gallr.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.withTimeout

/**
 * Downloads an exhibition cover image as raw bytes for the story-share card.
 *
 * Returns `null` (never throws) on a blank URL, network failure, timeout, or an
 * empty response so callers can fall back to the placeholder tile.
 */
interface CoverImageDownloader {
    suspend fun download(url: String): ByteArray?
}

private typealias ByteFetcher = suspend (String) -> ByteArray

/**
 * Ktor-backed downloader. The [fetch] lambda is injectable so the failure,
 * timeout, and empty-response branches are testable without a network or a
 * MockEngine dependency. Production callers use [ktor].
 */
class KtorCoverImageDownloader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val fetch: ByteFetcher,
) : CoverImageDownloader {

    override suspend fun download(url: String): ByteArray? {
        if (url.isBlank()) return null
        return runCatching {
            withTimeout(timeoutMillis) { fetch(url) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L

        /** Production downloader backed by a Ktor [HttpClient] (default engine per platform). */
        fun ktor(
            client: HttpClient = HttpClient(),
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ): KtorCoverImageDownloader =
            KtorCoverImageDownloader(timeoutMillis) { url ->
                client.get(url).readRawBytes()
            }
    }
}
