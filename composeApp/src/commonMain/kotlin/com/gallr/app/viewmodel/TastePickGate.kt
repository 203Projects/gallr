package com.gallr.app.viewmodel

import com.gallr.shared.taste.TastePickState

/** Screens that must be dealt with before the taste sheet may open on its own (spec 101 FR-006). */
internal data class TastePickBlockers(
    /** The full-screen required-update notice (spec 098). */
    val updateRequired: Boolean,
    /** The Home update nudge, until it is answered. */
    val updateNudgeShowing: Boolean,
    /** A sign-in screen opened over the current one. */
    val accountOpen: Boolean,
) {
    val any: Boolean get() = updateRequired || updateNudgeShowing || accountOpen
}

/**
 * Whether Home opens the taste sheet by itself: only for a visitor with nothing to go on (no save, visit,
 * follow or pick) who has not skipped it, and never over the update screen, the update nudge or sign-in.
 * [hasHistory] is null until the visitor's signals have been read, and then nothing is decided.
 */
internal fun shouldOfferTastePick(
    hasHistory: Boolean?,
    taste: TastePickState,
    blockers: TastePickBlockers,
): Boolean = hasHistory == false && taste.seed.isEmpty && !taste.skipped && !blockers.any
