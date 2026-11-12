package com.gallr.shared.thoughts

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
