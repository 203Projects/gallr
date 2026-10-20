package com.gallr.shared.repository

import com.gallr.shared.app.UpdateRequirement
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * Whether the running build must or should be updated (spec 098). [requirement] follows the last policy that was
 * read successfully, kept on the device, so an outage never blocks anyone and going offline never lifts a gate.
 */
interface AppUpdateRepository {
    val requirement: Flow<UpdateRequirement>

    /** The day the visitor dismissed the recommended-update nudge, or null. */
    val nudgeDismissedOn: Flow<LocalDate?>

    /** Reads the policy once; a failed read changes nothing. */
    suspend fun refresh()

    suspend fun dismissNudge(today: LocalDate)
}
