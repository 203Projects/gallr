package com.gallr.shared.thoughts

import com.gallr.shared.diary.PublicNoteStatus
import kotlin.time.Instant

/**
 * An approved 감상 as an exhibition page shows it (spec 103 US4), read through `list_exhibition_thoughts`. The reader's
 * own open reports are already left out by the server; [reportedByMe] is true only after staff kept a 감상 this reader
 * reported in its current text, so the menu reads 신고함. [authorDisplayName] is empty when the author has no name.
 */
data class PublicThought(
    val id: String,
    val authorId: String,
    val content: String,
    val createdAt: Instant,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
    val reportedByMe: Boolean,
)

/** The fixed reasons a reader can give for a report, in the sheet's order; [wire] is what `report_thought` accepts. */
enum class ThoughtReportReason(
    val wire: String,
) {
    INAPPROPRIATE("inappropriate"),
    PROMOTIONAL("promotional"),
    NOT_ABOUT_EXHIBITION("not_about_exhibition"),
    PERSONAL_INFORMATION("personal_information"),
    OTHER("other"),
}

/**
 * The 감상 a reader sees: [thoughts] without those whose author this device hides (FR-024), in their order. The
 * reader's own 감상 always stay, whatever the hidden list holds.
 */
fun visibleThoughts(
    thoughts: List<PublicThought>,
    hiddenAuthorIds: Set<String>,
    currentUserId: String?,
): List<PublicThought> {
    if (hiddenAuthorIds.isEmpty()) return thoughts
    return thoughts.filter { it.authorId == currentUserId || it.authorId !in hiddenAuthorIds }
}

/**
 * The page as its author sees it: their own card stays only while their diary reads [PublicNoteStatus.PUBLISHED].
 * Asking again with a changed text sends it back to review, which takes it off the page (FR-019, the server's
 * `publish_note`), and 나만 보기 or deleting the entry removes it (FR-020, FR-006); in each case the author's own
 * status block replaces the card, so the author sees one card for their own 감상, with its true status, even while the
 * list still holds what the server sent before the change.
 */
fun withOwnOnlyWhilePublished(
    thoughts: List<PublicThought>,
    currentUserId: String?,
    ownStatus: PublicNoteStatus?,
): List<PublicThought> {
    if (currentUserId == null || ownStatus == PublicNoteStatus.PUBLISHED) return thoughts
    return thoughts.filterNot { it.authorId == currentUserId }
}
