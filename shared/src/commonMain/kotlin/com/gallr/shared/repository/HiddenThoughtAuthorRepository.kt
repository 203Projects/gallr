package com.gallr.shared.repository

import com.gallr.shared.thoughts.HiddenAuthor
import kotlinx.coroutines.flow.Flow

/**
 * The people whose 감상 this device hides (FR-024). Kept on this device only, for guests and accounts alike: never
 * synced, never cleared on sign-out. Writes may throw; callers turn a failure into UI state or a log line.
 */
interface HiddenThoughtAuthorRepository {
    /** In the order they were hidden; a malformed stored list reads as empty. */
    val hiddenAuthors: Flow<List<HiddenAuthor>>

    /** Hides [author], replacing an earlier entry for the same person. */
    suspend fun hide(author: HiddenAuthor)

    suspend fun unhide(userId: String)
}
