package com.gallr.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.viewmodel.ExhibitionThoughtsUiState
import com.gallr.app.viewmodel.ThoughtsMessage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.thoughts.PublicThought
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Spec 103 US4: the ⋯ on each 감상 card (contracts/diary-ui.md "감상 card menu") and the hide snackbar. */
@OptIn(ExperimentalTestApi::class)
class ThoughtCardMenuTest {
    private val other = thought("other", authorId = "b", name = "민지")
    private val kept = thought("kept", authorId = "c", name = "서준", reportedByMe = true)
    private val own = thought("own", authorId = "reader", name = "Reader")

    @Test
    fun anotherPersonsCardOffersReportAndHide() =
        runComposeUiTest {
            val events = render(listOf(other))

            assertEquals(1, onAllNodesWithContentDescription("더 보기").fetchSemanticsNodes().size)
            onAllNodesWithContentDescription("더 보기")[0].performClick()
            onNodeWithText("신고").assertIsEnabled()
            onNodeWithText("이 사용자의 감상 숨기기").assertExists()
            assertEquals(0, onAllNodesWithText("수정").fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("공개 중단").fetchSemanticsNodes().size)

            onNodeWithText("신고").performClick()
            onAllNodesWithContentDescription("더 보기")[0].performClick()
            onNodeWithText("이 사용자의 감상 숨기기").performClick()

            assertEquals(listOf("report other", "hide b"), events)
        }

    @Test
    fun aKeptReportReadsReportedAndIsDisabled() =
        runComposeUiTest {
            val events = render(listOf(kept))

            onAllNodesWithContentDescription("더 보기")[0].performClick()
            onNodeWithText("신고함").assertIsNotEnabled().performClick()
            onNodeWithText("이 사용자의 감상 숨기기").assertIsEnabled()

            assertEquals(emptyList(), events)
        }

    @Test
    fun theReadersOwnCardOffersEditAndUnpublishWithAConfirm() =
        runComposeUiTest {
            val events = render(listOf(own, other))

            onAllNodesWithContentDescription("더 보기")[0].performClick()
            onNodeWithText("수정").assertExists()
            onNodeWithText("공개 중단").assertExists()
            assertEquals(0, onAllNodesWithText("신고").fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("이 사용자의 감상 숨기기").fetchSemanticsNodes().size)

            onNodeWithText("수정").performClick()
            onAllNodesWithContentDescription("더 보기")[0].performClick()
            onNodeWithText("공개 중단").performClick()
            onNodeWithText("감상 공개를 중단할까요? 메모는 일기에 남아요.").assertExists()
            assertEquals(listOf("edit own"), events)

            // The dialog's confirm button carries the same words as the menu item.
            onAllNodesWithText("공개 중단").fetchSemanticsNodes().let { assertEquals(1, it.size) }
            onNodeWithText("공개 중단").performClick()

            assertEquals(listOf("edit own", "unpublish own"), events)
        }

    @Test
    fun englishMenuWords() =
        runComposeUiTest {
            render(listOf(other), language = AppLanguage.EN)

            onAllNodesWithContentDescription("MORE")[0].performClick()
            onNodeWithText("REPORT").assertExists()
            onNodeWithText("HIDE THIS PERSON'S NOTES").assertExists()
            onNodeWithText("NOTES").assertExists()
        }

    @Test
    fun hidingShowsTheSnackbarAndUndoBringsThePersonBack() =
        runComposeUiTest {
            val undone = mutableListOf<String>()
            var message by mutableStateOf<ThoughtsMessage?>(null)
            setContent {
                val hostState = remember { SnackbarHostState() }
                Column {
                    ThoughtsMessageEffect(
                        message = message,
                        hostState = hostState,
                        language = AppLanguage.KO,
                        screenReaderActive = false,
                        onUndoHide = { undone += it },
                        onShown = { message = null },
                    )
                    SnackbarHost(hostState)
                }
            }

            message = ThoughtsMessage.AuthorHidden("b", "민지")
            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(SNACKBAR_SETTLE_MILLIS)

            onNodeWithText("민지님의 감상을 숨겼어요").assertExists()
            onNodeWithText("되돌리기").performClick()
            mainClock.advanceTimeBy(SNACKBAR_SETTLE_MILLIS)

            assertEquals(listOf("b"), undone)
        }

    private fun ComposeUiTest.render(
        thoughts: List<PublicThought>,
        language: AppLanguage = AppLanguage.KO,
    ): MutableList<String> {
        val events = mutableListOf<String>()
        setContent {
            ThoughtsSectionContent(
                state = ExhibitionThoughtsUiState(thoughts = thoughts, currentUserId = "reader", hasLoaded = true),
                lang = language,
                onRetry = {},
                onReport = { events += "report ${it.id}" },
                onHideAuthor = { events += "hide ${it.authorId}" },
                onEditOwn = { events += "edit ${it.id}" },
                onUnpublishOwn = { events += "unpublish ${it.id}" },
            )
        }
        return events
    }

    private fun thought(
        id: String,
        authorId: String,
        name: String,
        reportedByMe: Boolean = false,
    ) = PublicThought(
        id = id,
        authorId = authorId,
        content = "content $id",
        createdAt = Instant.parse("2026-11-10T03:00:00Z"),
        authorDisplayName = name,
        authorAvatarUrl = null,
        reportedByMe = reportedByMe,
    )

    private companion object {
        const val SNACKBAR_SETTLE_MILLIS = 500L
    }
}
