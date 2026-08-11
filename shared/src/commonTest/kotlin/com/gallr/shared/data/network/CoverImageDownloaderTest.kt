package com.gallr.shared.data.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverImageDownloaderTest {
    @Test
    fun `returns bytes when fetch succeeds`() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 3_000) {
                    CoverImageFetchResult(bytes, "image/jpeg")
                }

            assertContentEquals(bytes, downloader.download("https://example.com/cover.jpg"))
        }

    @Test
    fun `returns null when url is blank`() =
        runTest {
            val downloader = KtorCoverImageDownloader(timeoutMillis = 3_000) { error("must not fetch") }

            assertNull(downloader.download("   "))
        }

    @Test
    fun `returns null when fetch throws`() =
        runTest {
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 3_000) {
                    throw RuntimeException("network down")
                }

            assertNull(downloader.download("https://example.com/cover.jpg"))
        }

    @Test
    fun `returns null when fetch exceeds timeout`() =
        runTest {
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 50) {
                    kotlinx.coroutines.delay(10_000)
                    CoverImageFetchResult(byteArrayOf(9), "image/jpeg")
                }

            assertNull(downloader.download("https://example.com/cover.jpg"))
        }

    @Test
    fun `returns null when fetched bytes are empty`() =
        runTest {
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 3_000) {
                    CoverImageFetchResult(ByteArray(0), "image/jpeg")
                }

            assertNull(downloader.download("https://example.com/cover.jpg"))
        }

    @Test
    fun `returns null when response is not a supported raster image`() =
        runTest {
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 3_000) {
                    CoverImageFetchResult(byteArrayOf(1, 2, 3), "text/html")
                }

            assertNull(downloader.download("https://example.com/cover.jpg"))
        }

    @Test
    fun `returns null when response exceeds byte limit`() =
        runTest {
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 3_000, maxBytes = 3) {
                    CoverImageFetchResult(byteArrayOf(1, 2, 3, 4), "image/png")
                }

            assertNull(downloader.download("https://example.com/cover.png"))
        }

    @Test
    fun `normalizes content type parameters`() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3)
            val downloader =
                KtorCoverImageDownloader(timeoutMillis = 3_000) {
                    CoverImageFetchResult(bytes, " Image/WebP ; charset=binary")
                }

            assertContentEquals(bytes, downloader.download("https://example.com/cover.webp"))
        }

    @Test
    fun `close is idempotent and prevents later downloads`() =
        runTest {
            var fetchCount = 0
            var closeCount = 0
            val downloader =
                KtorCoverImageDownloader(
                    timeoutMillis = 3_000,
                    fetch = {
                        fetchCount += 1
                        CoverImageFetchResult(byteArrayOf(1), "image/jpeg")
                    },
                    closeAction = { closeCount += 1 },
                )

            downloader.close()
            downloader.close()

            assertNull(downloader.download("https://example.com/cover.jpg"))
            assertEquals(0, fetchCount)
            assertEquals(1, closeCount)
        }
}
