package com.gallr.app.viewmodel

import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.thoughts.PublicThought
import com.gallr.shared.thoughts.ThoughtReportReason
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

internal class FakeThoughtRepository(
    var publicThoughtsResult: Result<List<PublicThought>> = Result.success(emptyList()),
    var reportResult: Result<Unit> = Result.success(Unit),
) : ThoughtRepository {
    var listCalls = 0
    val reports = mutableListOf<Pair<String, ThoughtReportReason>>()

    /** When set, a report waits here, so a test can look at the sheet while it sends. */
    var reportGate: CompletableDeferred<Unit>? = null

    override suspend fun exhibitionThoughts(
        exhibitionId: String,
        limit: Int,
    ): List<PublicThought> {
        listCalls += 1
        return publicThoughtsResult.getOrThrow()
    }

    override suspend fun report(
        thoughtId: String,
        reason: ThoughtReportReason,
    ) {
        reports += thoughtId to reason
        reportGate?.await()
        reportResult.getOrThrow()
    }
}

internal fun publicThought(
    id: String,
    authorId: String = "author-$id",
    displayName: String = "Author $id",
    reportedByMe: Boolean = false,
): PublicThought =
    PublicThought(
        id = id,
        authorId = authorId,
        content = "Thought $id",
        createdAt = Instant.parse("2026-11-10T03:00:00Z"),
        authorDisplayName = displayName,
        authorAvatarUrl = null,
        reportedByMe = reportedByMe,
    )
