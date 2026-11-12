package com.gallr.shared.thoughts

import com.gallr.shared.diary.PublicNoteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Spec 103 FR-024: a hidden author's 감상 leave every page on this device; the reader's own never do. */
class ThoughtVisibilityTest {
    @Test
    fun hiddenAuthorsAreDroppedAndTheOrderIsKept() {
        val thoughts = listOf(thought("1", "a"), thought("2", "b"), thought("3", "a"), thought("4", "c"))

        val visible = visibleThoughts(thoughts, hiddenAuthorIds = setOf("a"), currentUserId = null)

        assertEquals(listOf("2", "4"), visible.map { it.id })
    }

    @Test
    fun theReadersOwnThoughtsStayEvenWhenTheirIdIsHidden() {
        val thoughts = listOf(thought("1", "me"), thought("2", "b"))

        val visible = visibleThoughts(thoughts, hiddenAuthorIds = setOf("me", "b"), currentUserId = "me")

        assertEquals(listOf("1"), visible.map { it.id })
    }

    @Test
    fun nothingHiddenKeepsEveryThought() {
        val thoughts = listOf(thought("1", "a"), thought("2", "b"))

        assertEquals(thoughts, visibleThoughts(thoughts, hiddenAuthorIds = emptySet(), currentUserId = "a"))
    }

    /**
     * End-to-end defect 1: a changed text goes back to review and leaves the page (FR-019, the server's
     * `publish_note`), 나만 보기 removes it (FR-020); the author's page must not show an older public card meanwhile.
     */
    @Test
    fun theReadersOwnCardStaysOnlyWhileTheirNoteIsPublished() {
        val thoughts = listOf(thought("1", "me"), thought("2", "b"))

        assertEquals(
            listOf("1", "2"),
            withOwnOnlyWhilePublished(thoughts, "me", PublicNoteStatus.PUBLISHED).map { it.id },
        )
        val notPublic =
            PublicNoteStatus.entries.filterNot { it == PublicNoteStatus.PUBLISHED } + listOf<PublicNoteStatus?>(null)
        notPublic.forEach { status ->
            assertEquals(listOf("2"), withOwnOnlyWhilePublished(thoughts, "me", status).map { it.id }, "$status")
        }
        // A guest has no own card to drop.
        assertEquals(thoughts, withOwnOnlyWhilePublished(thoughts, null, null))
    }

    @Test
    fun reportReasonsCarryTheServerWireValuesInSheetOrder() {
        assertEquals(
            listOf("inappropriate", "promotional", "not_about_exhibition", "personal_information", "other"),
            ThoughtReportReason.entries.map { it.wire },
        )
    }

    private fun thought(
        id: String,
        authorId: String,
    ) = PublicThought(
        id = id,
        authorId = authorId,
        content = "content $id",
        createdAt = Instant.parse("2026-11-10T03:00:00Z"),
        authorDisplayName = "name $authorId",
        authorAvatarUrl = null,
        reportedByMe = false,
    )
}
