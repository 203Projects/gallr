package com.gallr.shared.repository

import com.gallr.shared.thoughts.PublicThought
import com.gallr.shared.thoughts.ThoughtReportReason

/**
 * 감상 on the server. The reader side (spec 103 US4) goes only through the reader RPCs: suspending calls may throw and
 * callers turn failures into UI state and logs.
 */
interface ThoughtRepository {
    /**
     * The approved 감상 of [exhibitionId], newest first, through `list_exhibition_thoughts`: without those the signed-in
     * reader has an open report on. A row that does not decode is left out; a failed call throws.
     */
    suspend fun exhibitionThoughts(
        exhibitionId: String,
        limit: Int = 20,
    ): List<PublicThought>

    /**
     * Reports [thoughtId] through `report_thought`. Throws [ThoughtReportAlreadySentException] when this reader already
     * reported its current text, [ThoughtReportSignInRequiredException] without a signed-in (non-anonymous) account,
     * [ThoughtNoLongerPublicException] when it is no longer shown and [ThoughtReportConnectionException] when the
     * request did not reach the server.
     */
    suspend fun report(
        thoughtId: String,
        reason: ThoughtReportReason,
    )
}

/** `thought_report_exists`: this reader already reported the 감상's current text; the menu reads 신고함. */
class ThoughtReportAlreadySentException : Exception("thought report already sent")

/** `thought_report_unauthenticated`: reporting needs a signed-in account, so the app opens sign-in. */
class ThoughtReportSignInRequiredException : Exception("thought report needs sign-in")

/** `thought_not_public` or `thought_not_found`: the 감상 left the page before the report reached the server. */
class ThoughtNoLongerPublicException : Exception("thought no longer public")

/** The report did not reach the server: no connection, a timeout, or a session that could not be refreshed. */
class ThoughtReportConnectionException : Exception("thought report did not reach the server")
