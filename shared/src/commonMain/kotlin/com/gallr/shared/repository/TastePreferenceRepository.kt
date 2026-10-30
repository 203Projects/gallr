package com.gallr.shared.repository

import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The visitor's taste picks, the taste-sheet skip and their 덜 보기 dismissals (spec 101). Kept on this device
 * only: never synced, never sent, unchanged by sign-out or account switch. Writes may throw; callers turn a
 * failure into UI state or a log line.
 */
interface TastePreferenceRepository {
    /** Current state; a malformed stored value reads as empty. */
    val state: Flow<TastePickState>

    /** Replaces the picks and clears the skip; an empty seed is an answer too and is kept as a skip. */
    suspend fun saveSeed(seed: TasteSeed)

    /** Records that the visitor skipped the sheet, so it never opens on its own again. */
    suspend fun markSkipped()

    /** Adds a dismissal, replacing an earlier one for the same exhibition. */
    suspend fun dismiss(record: TasteDismissal)

    /** Deletes these exhibitions' dismissals: an undo, or a save, visit or follow that lifts the hide. */
    suspend fun removeDismissals(exhibitionIds: Set<String>)

    /** Stops a dismissed term counting against anything; the hides stand. */
    suspend fun releaseTerm(termId: String)

    /** Drops dismissals whose exhibition closed before [today]. */
    suspend fun expire(today: LocalDate)
}
