package com.gallr.shared.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** data-model.md "Author-visible status" and research P9: a queued publish never reads as a failure while pending. */
class PublicNoteStatusTest {
    private fun status(
        entryNote: String? = "메모",
        publicNote: OwnPublicNote? = null,
        pending: PendingNoteMutation? = null,
        syncFailed: Boolean = false,
        sessionRefreshPending: Boolean = false,
    ) = diaryNoteStatus(entryNote, publicNote, pending, syncFailed, sessionRefreshPending)

    @Test
    fun noNoteAndNoPublicNoteHasNoStatus() {
        assertNull(status(entryNote = null))
    }

    @Test
    fun aNoteThatWasNeverPublishedIsPrivate() {
        assertEquals(PublicNoteStatus.PRIVATE, status())
    }

    @Test
    fun aQueuedPublishOrUnpublishIsSending() {
        assertEquals(PublicNoteStatus.SENDING, status(pending = PendingNoteMutation.PUBLISH))
        assertEquals(
            PublicNoteStatus.SENDING,
            status(publicNote = ownNote("ex"), pending = PendingNoteMutation.UNPUBLISH),
        )
    }

    @Test
    fun aFailedSyncWhileTheSessionRefreshIsPendingIsStillSending() {
        assertEquals(
            PublicNoteStatus.SENDING,
            status(pending = PendingNoteMutation.PUBLISH, syncFailed = true, sessionRefreshPending = true),
        )
    }

    @Test
    fun aFailedSyncOutsideAPendingRefreshAsksForARetry() {
        assertEquals(PublicNoteStatus.RETRY, status(pending = PendingNoteMutation.PUBLISH, syncFailed = true))
        // A failure with nothing of this note queued says nothing about the note.
        assertEquals(PublicNoteStatus.PRIVATE, status(syncFailed = true))
    }

    @Test
    fun moderationStatesMapToReviewPublishedAndNotPublished() {
        assertEquals(
            PublicNoteStatus.IN_REVIEW,
            status(publicNote = ownNote("ex", PublicNoteModeration.PENDING)),
        )
        assertEquals(
            PublicNoteStatus.PUBLISHED,
            status(publicNote = ownNote("ex", PublicNoteModeration.APPROVED)),
        )
        val rejected =
            ownNote(
                "ex",
                PublicNoteModeration.REJECTED,
                rejectionReason = ThoughtRejectionReason.PROMOTIONAL,
            )
        assertEquals(PublicNoteStatus.NOT_PUBLISHED, status(publicNote = rejected))
        assertEquals(ThoughtRejectionReason.PROMOTIONAL, rejected.rejectionReason)
    }

    @Test
    fun aPublicNoteWithoutAnEntryNoteStillHasItsStatus() {
        // The undated entry of a 1.15 감상 with no visit behind it.
        assertEquals(
            PublicNoteStatus.PUBLISHED,
            status(entryNote = null, publicNote = ownNote("ex", PublicNoteModeration.APPROVED)),
        )
    }

    @Test
    fun rejectionReasonsReadTheirWireIds() {
        assertEquals(
            listOf("not_about_exhibition", "promotional", "inappropriate_language", "personal_information", "other"),
            ThoughtRejectionReason.entries.map(ThoughtRejectionReason::wire),
        )
        ThoughtRejectionReason.entries.forEach { assertEquals(it, ThoughtRejectionReason.fromWire(it.wire)) }
        assertNull(ThoughtRejectionReason.fromWire("spam"))
        assertNull(ThoughtRejectionReason.fromWire(null))
    }
}
