package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Thought
import com.gallr.shared.repository.ThoughtRepository

internal class FakeThoughtRepository(
    var exhibitionThoughtsResult: Result<List<Thought>> = Result.success(emptyList()),
    var userExhibitionThoughtResult: Result<Thought?> = Result.success(null),
    var userThoughtsResult: Result<List<Thought>> = Result.success(emptyList()),
    var pendingThoughtsResult: Result<List<Thought>> = Result.success(emptyList()),
    var submitResult: Result<Unit> = Result.success(Unit),
    var deleteResult: Result<Unit> = Result.success(Unit),
    var approveResult: Result<Unit> = Result.success(Unit),
    var rejectResult: Result<Unit> = Result.success(Unit),
) : ThoughtRepository {
    override suspend fun getThoughtsForExhibition(
        exhibitionId: String,
        limit: Int,
    ): List<Thought> = exhibitionThoughtsResult.getOrThrow()

    override suspend fun getUserThoughts(userId: String): List<Thought> = userThoughtsResult.getOrThrow()

    override suspend fun submitThought(
        exhibitionId: String,
        content: String,
    ) = submitResult.getOrThrow()

    override suspend fun updateThought(
        thoughtId: String,
        content: String,
    ) = Unit

    override suspend fun deleteThought(thoughtId: String) = deleteResult.getOrThrow()

    override suspend fun getUserThoughtForExhibition(exhibitionId: String): Thought? =
        userExhibitionThoughtResult.getOrThrow()

    override suspend fun getUserThoughtCount(userId: String): Int = userThoughtsResult.getOrThrow().size

    override suspend fun getPendingThoughts(): List<Thought> = pendingThoughtsResult.getOrThrow()

    override suspend fun approveThought(thoughtId: String) = approveResult.getOrThrow()

    override suspend fun rejectThought(thoughtId: String) = rejectResult.getOrThrow()
}

internal fun thought(
    id: String,
    exhibitionId: String = "exhibition-$id",
): Thought =
    Thought(
        id = id,
        userId = "user-1",
        exhibitionId = exhibitionId,
        content = "Thought $id",
        isApproved = true,
        createdAt = "2026-08-08T00:00:00Z",
        updatedAt = "2026-08-08T00:00:00Z",
        authorDisplayName = "User",
        authorAvatarUrl = null,
    )
