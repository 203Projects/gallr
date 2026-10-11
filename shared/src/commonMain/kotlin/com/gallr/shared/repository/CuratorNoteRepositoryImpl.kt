package com.gallr.shared.repository

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.network.CuratorNoteApi
import com.gallr.shared.data.network.dto.CuratorNotePage
import com.gallr.shared.observability.AppLog
import com.gallr.shared.util.runSuspendCatching

private val noteLog = AppLog.tagged("CuratorNoteRepository")

/** The home carousel caps at ten; reading a few more lets ended or hidden exhibitions drop out without a gap. */
private const val PUBLISHED_PAGE_LIMIT = 30

class CuratorNoteRepositoryImpl(
    private val api: CuratorNoteApi,
) : CuratorNoteRepository {
    override suspend fun getPublishedNotes(): Result<List<CuratorNote>> =
        runSuspendCatching { api.fetchPublished(PUBLISHED_PAGE_LIMIT).logSkipped().notes }

    override suspend fun getNote(exhibitionId: String): Result<CuratorNote?> =
        runSuspendCatching {
            api
                .fetchForExhibition(exhibitionId)
                .logSkipped()
                .notes
                .firstOrNull()
        }

    private fun CuratorNotePage.logSkipped(): CuratorNotePage {
        if (skipped > 0) noteLog.warn("curator_note_row_skipped")
        return this
    }
}
