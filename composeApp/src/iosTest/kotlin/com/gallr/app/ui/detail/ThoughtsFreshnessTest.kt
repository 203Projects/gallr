package com.gallr.app.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.gallr.app.ui.diary.DiaryEntryControl
import com.gallr.app.ui.diary.RecordingDiaryEntryActions
import com.gallr.app.viewmodel.DiaryEntryUiState
import com.gallr.app.viewmodel.DiaryVisitRowState
import com.gallr.app.viewmodel.ExhibitionThoughtsViewModel
import com.gallr.app.viewmodel.FakeHiddenThoughtAuthorRepository
import com.gallr.app.viewmodel.FakePublicNoteRepository
import com.gallr.app.viewmodel.FakeThoughtRepository
import com.gallr.app.viewmodel.publicThought
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.thoughts.PublicThought
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

// Value: protects=the 감상 list is read again each time the page is shown, so a 감상 taken down, kept or sent back to
// review elsewhere does not linger until a restart, and the author sees one card for their own 감상 with its true
// status (spec 103 e2e defect 1, US3 sc.7, US4 sc.11, FR-023); also the empty line on an upcoming show (defect 13);
// fails_when=the section keeps its first list for the session, shows the old public card beside the in-review block,
// or invites a first note where none can be written; why_new=the list had no freshness test;
// seam=ThoughtsSection and ExhibitionDetailScreen with fake repositories; no_claim=foreground return on a device

/** Spec 103 e2e defects 1 and 13 on the exhibition page. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ThoughtsFreshnessTest {
    private val reader = AuthState.Authenticated(GallrUser("reader", "하나", null))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun returningToThePageReadsTheListAgain() =
        runComposeUiTest {
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(publicThought("1"))))
            val viewModel =
                ExhibitionThoughtsViewModel(
                    exhibitionId = "show",
                    authState = reader,
                    thoughtRepository = repository,
                    hiddenThoughtAuthorRepository = FakeHiddenThoughtAuthorRepository(),
                    publicNoteRepository = FakePublicNoteRepository(),
                )
            var onPage by mutableStateOf(true)
            setContent {
                if (onPage) {
                    ThoughtsSection(
                        viewModel = viewModel,
                        authState = reader,
                        lang = AppLanguage.KO,
                        snackbarHostState = remember { SnackbarHostState() },
                        onSignInNeeded = {},
                        onEditOwnNote = {},
                    )
                }
            }
            onNodeWithText("Thought 1").assertExists()
            assertEquals(1, repository.listCalls)

            // Staff took the 감상 down while the reader was elsewhere.
            onPage = false
            waitForIdle()
            repository.publicThoughtsResult = Result.success(listOf(publicThought("2")))
            onPage = true
            waitForIdle()

            assertEquals(2, repository.listCalls)
            onNodeWithText("Thought 1").assertDoesNotExist()
            onNodeWithText("Thought 2").assertExists()
        }

    @Test
    fun theAuthorSeesOneCardForTheirOwnNoteWhileItIsBackInReview() =
        runComposeUiTest {
            // The page still lists the old approved text; the diary already knows the edit was sent for review.
            val thoughts =
                FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(ownCard("예전 공개 문장"))))
            val state =
                DiaryEntryUiState(
                    row = DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), null),
                    publicNote = ownNote("고친 문장", PublicNoteModeration.PENDING),
                    noteStatus = PublicNoteStatus.IN_REVIEW,
                )
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides remember { storeOwner() }) {
                    ExhibitionDetailScreen(
                        exhibition = opened,
                        lang = AppLanguage.KO,
                        isBookmarked = false,
                        onBookmarkToggle = {},
                        onBack = {},
                        diaryEntry = DiaryEntryControl(state, RecordingDiaryEntryActions()),
                        thoughtRepository = thoughts,
                        hiddenThoughtAuthorRepository = FakeHiddenThoughtAuthorRepository(),
                        publicNoteRepository = FakePublicNoteRepository(),
                        authState = reader,
                    )
                }
            }

            onNodeWithText("검토 중").performScrollTo().assertExists()
            onNodeWithText("고친 문장").assertExists()
            onNodeWithText("예전 공개 문장").assertDoesNotExist()
            assertEquals(1, onAllNodesWithText("검토 중").fetchSemanticsNodes().size)
        }

    @Test
    fun anUpcomingShowSaysThereAreNoNotesWithoutAskingForTheFirst() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides remember { storeOwner() }) {
                    ExhibitionDetailScreen(
                        exhibition = upcoming,
                        lang = AppLanguage.KO,
                        isBookmarked = false,
                        onBookmarkToggle = {},
                        onBack = {},
                        diaryEntry =
                            DiaryEntryControl(
                                DiaryEntryUiState(row = DiaryVisitRowState.NoEntry),
                                RecordingDiaryEntryActions(),
                            ),
                        thoughtRepository = FakeThoughtRepository(),
                        hiddenThoughtAuthorRepository = FakeHiddenThoughtAuthorRepository(),
                        publicNoteRepository = FakePublicNoteRepository(),
                        authState = reader,
                    )
                }
            }

            onNodeWithText("아직 감상이 없어요.").performScrollTo().assertExists()
            onNodeWithText("첫 번째로", substring = true).assertDoesNotExist()
        }

    private fun storeOwner(): ViewModelStoreOwner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    private fun ownCard(content: String) =
        PublicThought(
            id = "thought-1",
            authorId = "reader",
            content = content,
            createdAt = Instant.parse("2026-10-01T00:00:00Z"),
            authorDisplayName = "하나",
            authorAvatarUrl = null,
            reportedByMe = false,
        )

    private fun ownNote(
        content: String,
        moderation: PublicNoteModeration,
    ) = OwnPublicNote(
        exhibitionId = "show",
        thoughtId = "thought-1",
        content = content,
        moderation = moderation,
        rejectionReason = null,
        requestedAt = Instant.parse("2026-10-01T00:00:00Z"),
        approvedAt = null,
        approvalSeen = true,
    )

    private val opened = exhibition(LocalDate(2026, 1, 1))
    private val upcoming = exhibition(LocalDate(2099, 1, 1))

    private fun exhibition(opening: LocalDate) =
        Exhibition(
            id = "show",
            nameKo = "빛의 방",
            nameEn = "Room of Light",
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "",
            regionEn = "",
            openingDate = opening,
            closingDate = LocalDate(2099, 12, 31),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )
}
