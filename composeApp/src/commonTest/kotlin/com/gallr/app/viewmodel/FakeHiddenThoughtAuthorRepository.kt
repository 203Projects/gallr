package com.gallr.app.viewmodel

import com.gallr.shared.repository.HiddenThoughtAuthorRepository
import com.gallr.shared.thoughts.HiddenAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/** The device's hidden 감상 authors in memory, with a switch that makes the next writes fail. */
internal class FakeHiddenThoughtAuthorRepository(
    initial: List<HiddenAuthor> = emptyList(),
) : HiddenThoughtAuthorRepository {
    val authors = MutableStateFlow(initial)
    var failure: Exception? = null

    override val hiddenAuthors: Flow<List<HiddenAuthor>> = authors

    override suspend fun hide(author: HiddenAuthor) {
        failure?.let { throw it }
        authors.value = authors.value.filterNot { it.userId == author.userId } + author
    }

    override suspend fun unhide(userId: String) {
        failure?.let { throw it }
        authors.value = authors.value.filterNot { it.userId == userId }
    }
}

internal fun hiddenAuthor(
    userId: String,
    displayName: String = "Name $userId",
): HiddenAuthor = HiddenAuthor(userId, displayName, Instant.parse("2026-11-11T00:00:00Z"))
