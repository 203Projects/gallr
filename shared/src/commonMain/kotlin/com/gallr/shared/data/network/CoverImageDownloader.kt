package com.gallr.shared.data.network

import com.gallr.shared.util.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

/**
 * Downloads an exhibition cover image as raw bytes for the story-share card.
 *
 * Returns `null` (never throws) when a safe raster image cannot be downloaded so callers can fall
 * back to the placeholder tile. Implementations that own resources must release them in [close].
 */
interface CoverImageDownloader {
    suspend fun download(url: String): ByteArray?

    fun close() = Unit
}

data class CoverImageFetchResult(
    val bytes: ByteArray,
    val contentType: String?,
)

private typealias ByteFetcher = suspend (String) -> CoverImageFetchResult?

/**
 * Ktor-backed downloader. The [fetch] lambda is injectable so the failure,
 * timeout, and empty-response branches are testable without a network or a
 * MockEngine dependency. Production callers use [ktor].
 */
class KtorCoverImageDownloader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val closeAction: () -> Unit = {},
    private val fetch: ByteFetcher,
) : CoverImageDownloader {
    private var isClosed = false

    override suspend fun download(url: String): ByteArray? {
        if (url.isBlank() || isClosed) return null
        return runSuspendCatching {
            withTimeoutOrNull(timeoutMillis) { fetch(url) }
        }.getOrNull()
            ?.takeIf { result ->
                result.bytes.isNotEmpty() &&
                    result.bytes.size <= maxBytes &&
                    result.contentType.normalizedMediaType() in SUPPORTED_CONTENT_TYPES
            }?.bytes
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        closeAction()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
        const val DEFAULT_MAX_BYTES = 5 * 1024 * 1024

        private val SUPPORTED_CONTENT_TYPES =
            setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
            )

        /** Production downloader with sole ownership of its Ktor client. Call [close] after use. */
        fun ktor(
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
            maxBytes: Int = DEFAULT_MAX_BYTES,
        ): KtorCoverImageDownloader {
            val client = HttpClient()
            return KtorCoverImageDownloader(
                timeoutMillis = timeoutMillis,
                maxBytes = maxBytes,
                fetch = { url ->
                    val response = client.get(url)
                    if (response.status.value !in 200..299) return@KtorCoverImageDownloader null

                    val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declaredLength != null && declaredLength > maxBytes) {
                        return@KtorCoverImageDownloader null
                    }

                    val bytes =
                        response
                            .bodyAsChannel()
                            .readRemaining(maxBytes.toLong() + 1L)
                            .readByteArray()
                    CoverImageFetchResult(
                        bytes = bytes,
                        contentType = response.headers[HttpHeaders.ContentType],
                    )
                },
                closeAction = client::close,
            )
        }
    }
}

private fun String?.normalizedMediaType(): String? =
    this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
