package com.gallr.shared.data.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class CoverImageDownloaderTest {

    @Test
    fun `returns bytes when fetch succeeds`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val downloader = KtorCoverImageDownloader(timeoutMillis = 3_000) { bytes }

        assertContentEquals(bytes, downloader.download("https://example.com/cover.jpg"))
    }

    @Test
    fun `returns null when url is blank`() = runTest {
        val downloader = KtorCoverImageDownloader(timeoutMillis = 3_000) { error("must not fetch") }

        assertNull(downloader.download("   "))
    }

    @Test
    fun `returns null when fetch throws`() = runTest {
        val downloader = KtorCoverImageDownloader(timeoutMillis = 3_000) {
            throw RuntimeException("network down")
        }

        assertNull(downloader.download("https://example.com/cover.jpg"))
    }

    @Test
    fun `returns null when fetch exceeds timeout`() = runTest {
        val downloader = KtorCoverImageDownloader(timeoutMillis = 50) {
            kotlinx.coroutines.delay(10_000)
            byteArrayOf(9)
        }

        assertNull(downloader.download("https://example.com/cover.jpg"))
    }

    @Test
    fun `returns null when fetched bytes are empty`() = runTest {
        val downloader = KtorCoverImageDownloader(timeoutMillis = 3_000) { ByteArray(0) }

        assertNull(downloader.download("https://example.com/cover.jpg"))
    }
}
