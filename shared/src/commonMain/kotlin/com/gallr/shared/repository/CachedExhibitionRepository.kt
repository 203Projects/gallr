package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.CancellationException

private val cachedExhibitionLog = AppLog.tagged("CachedExhibitionRepository")

/** Persistent storage for one complete, verified exhibition catalogue snapshot. */
interface ExhibitionCache {
    /** Returns null when no verified catalogue has been cached yet. */
    suspend fun read(): List<Exhibition>?

    /** Replaces the cache with one complete, verified remote catalogue. */
    suspend fun write(exhibitions: List<Exhibition>)
}

/**
 * Keeps the network repository authoritative while allowing a last-known-good catalogue fallback.
 * The featured fallback is derived from the complete cached catalogue so partial responses are
 * never persisted as if they were a verified full snapshot.
 */
class CachedExhibitionRepository(
    private val remote: ExhibitionRepository,
    private val cache: ExhibitionCache,
) : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions(): Result<List<Exhibition>> {
        val remoteResult = remote.getFeaturedExhibitions()
        if (remoteResult.isSuccess) return remoteResult

        return fallback(
            surface = "featured",
            remoteFailure = remoteResult.exceptionOrNull(),
        ) { exhibitions -> exhibitions.filter(Exhibition::isFeatured) }
    }

    override suspend fun getExhibitions(): Result<List<Exhibition>> {
        val remoteResult = remote.getExhibitions()
        if (remoteResult.isSuccess) {
            persist(remoteResult.getOrThrow())
            return remoteResult
        }

        return fallback(
            surface = "all",
            remoteFailure = remoteResult.exceptionOrNull(),
            transform = { it },
        )
    }

    private suspend fun persist(exhibitions: List<Exhibition>) {
        try {
            cache.write(exhibitions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            cachedExhibitionLog.warn("catalog_cache_write_failed", error)
        }
    }

    private suspend fun fallback(
        surface: String,
        remoteFailure: Throwable?,
        transform: (List<Exhibition>) -> List<Exhibition>,
    ): Result<List<Exhibition>> {
        if (remoteFailure is CancellationException) throw remoteFailure
        val failure = remoteFailure ?: IllegalStateException("Remote catalogue load failed")

        val cached =
            try {
                cache.read()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                cachedExhibitionLog.warn("catalog_cache_read_failed", error)
                return Result.failure(failure)
            }

        if (cached == null) return Result.failure(failure)

        cachedExhibitionLog.warn("catalog_cache_fallback", failure)
        return Result.success(transform(cached))
    }
}
