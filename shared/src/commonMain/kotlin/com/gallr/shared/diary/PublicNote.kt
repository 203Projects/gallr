package com.gallr.shared.diary

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Where an author's 감상 stands in review (`public.thoughts.moderation_status`). */
@Serializable
enum class PublicNoteModeration { PENDING, APPROVED, REJECTED }

/** Why staff did not publish a 감상; [wire] is `public.thoughts.rejection_reason`. */
@Serializable
enum class ThoughtRejectionReason(
    val wire: String,
) {
    NOT_ABOUT_EXHIBITION("not_about_exhibition"),
    PROMOTIONAL("promotional"),
    INAPPROPRIATE_LANGUAGE("inappropriate_language"),
    PERSONAL_INFORMATION("personal_information"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): ThoughtRejectionReason? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The signed-in author's own 감상 for one exhibition, as the account archive holds it. It may exist without a diary
 * entry (a 감상 posted from 1.15). [thoughtId] is null only while a first publish waits in the outbox and the server
 * has not given the 감상 an id yet; [requestedAt] is then the entry's last save until the server answers.
 */
@Serializable
data class OwnPublicNote(
    val exhibitionId: String,
    val thoughtId: String?,
    val content: String,
    val moderation: PublicNoteModeration,
    val rejectionReason: ThoughtRejectionReason?,
    val requestedAt: Instant,
    val approvedAt: Instant?,
    val approvalSeen: Boolean,
)

/** The author-visible status of an entry's note (data-model "Author-visible status"). */
enum class PublicNoteStatus { PRIVATE, SENDING, RETRY, IN_REVIEW, PUBLISHED, NOT_PUBLISHED }

/** The latest publish or unpublish of an exhibition's note still waiting in the account outbox. */
enum class PendingNoteMutation { PUBLISH, UNPUBLISH }

/**
 * Null when the entry has no note and there is no public note. A queued publish or unpublish reads SENDING, and keeps
 * reading SENDING after a failed sync while the session refresh is pending (research P9): only a failure outside a
 * pending refresh asks for a retry. Otherwise the server's moderation state decides; a note never sent is PRIVATE.
 */
fun diaryNoteStatus(
    entryNote: String?,
    publicNote: OwnPublicNote?,
    pendingNoteMutation: PendingNoteMutation?,
    syncFailed: Boolean,
    sessionRefreshPending: Boolean,
): PublicNoteStatus? =
    when {
        entryNote == null && publicNote == null -> {
            null
        }

        pendingNoteMutation != null -> {
            if (syncFailed && !sessionRefreshPending) PublicNoteStatus.RETRY else PublicNoteStatus.SENDING
        }

        publicNote == null -> {
            PublicNoteStatus.PRIVATE
        }

        else -> {
            when (publicNote.moderation) {
                PublicNoteModeration.PENDING -> PublicNoteStatus.IN_REVIEW
                PublicNoteModeration.APPROVED -> PublicNoteStatus.PUBLISHED
                PublicNoteModeration.REJECTED -> PublicNoteStatus.NOT_PUBLISHED
            }
        }
    }
