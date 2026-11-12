package com.gallr.app.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.gallr.app.ui.diary.DiaryEntryControl
import com.gallr.app.ui.diary.RecordingDiaryEntryActions
import com.gallr.app.viewmodel.DiaryEntryUiState
import com.gallr.app.viewmodel.DiaryEntryViewModel
import com.gallr.app.viewmodel.DiaryVisitRowState
import com.gallr.app.viewmodel.FakeDiaryVisitRepository
import com.gallr.app.viewmodel.FakeHiddenThoughtAuthorRepository
import com.gallr.app.viewmodel.FakePublicNoteRepository
import com.gallr.app.viewmodel.FakeThoughtRepository
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.thoughts.PublicThought
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

// Value: protects=감상 남기기 is the only way to write a 감상, shows only on opened shows and opens the entry sheet with
// the note first; the reader's own 감상 in review shows its status; 수정 on the reader's own card opens the sheet even
// for a 감상 with no visit (spec 103 US3, FR-011, FR-022); fails_when=the page keeps the composer or an emoji, offers
// 감상 남기기 on an upcoming show, drops the own-note block, or 수정 does nothing without a visit; why_new=the composer
// it replaces had no test; seam=ExhibitionDetailScreen with fake repositories and a real DiaryEntryViewModel;
// no_claim=the server's publish rules

/** Spec 103 US3 on the exhibition page: 감상 남기기, the reader's own 감상 and 수정. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class WriteNoteTest {
    private val reader = AuthState.Authenticated(GallrUser("reader", "하나", null))

    // The page's ViewModels run on Main; the test dispatcher runs them at once.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun anOpenedShowOffersWriteANoteWithoutEmojiAndItOpensTheSheetWithTheNoteFirst() =
        runComposeUiTest {
            val actions = RecordingDiaryEntryActions()
            setPage(opened, DiaryEntryControl(DiaryEntryUiState(row = DiaryVisitRowState.NoEntry), actions))

            onNodeWithText("감상 남기기").performScrollTo().performClick()

            assertEquals(listOf("open-note"), actions.calls)
            onNodeWithText("✍", substring = true).assertDoesNotExist()
        }

    @Test
    fun anUpcomingShowOffersNeitherTheRowNorWriteANote() =
        runComposeUiTest {
            setPage(
                upcoming,
                DiaryEntryControl(DiaryEntryUiState(row = DiaryVisitRowState.NoEntry), RecordingDiaryEntryActions()),
            )

            onNodeWithText("감상").performScrollTo()
            onNodeWithText("다녀왔어요").assertDoesNotExist()
            onNodeWithText("감상 남기기").assertDoesNotExist()
        }

    @Test
    fun writeANoteSpeaksEnglish() =
        runComposeUiTest {
            setPage(
                opened,
                DiaryEntryControl(DiaryEntryUiState(row = DiaryVisitRowState.NoEntry), RecordingDiaryEntryActions()),
                language = AppLanguage.EN,
            )

            onNodeWithText("WRITE A NOTE").performScrollTo().assertExists()
        }

    @Test
    fun theReadersOwnNoteInReviewShowsItsStatusAndOpensTheSheet() =
        runComposeUiTest {
            val actions = RecordingDiaryEntryActions()
            val state =
                DiaryEntryUiState(
                    row = DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), null),
                    publicNote = ownNote(PublicNoteModeration.PENDING),
                    noteStatus = PublicNoteStatus.IN_REVIEW,
                )
            setPage(opened, DiaryEntryControl(state, actions))

            onNodeWithText("검토 중").performScrollTo().assertExists()
            onNodeWithText("작은 방의 빛").assertExists()
            // The diary row above also reads 수정; the note's own is the last.
            onAllNodesWithText("수정").onLast().performClick()
            assertEquals(listOf("open-note"), actions.calls)
        }

    @Test
    fun editOnTheReadersOwnCardOpensTheSheetForANoteWithNoVisit() =
        runComposeUiTest {
            // A 감상 from app 1.15: public, approved, and no visit behind it (FR-022).
            val notes = FakePublicNoteRepository(listOf(ownNote(PublicNoteModeration.APPROVED)))
            val thoughts = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(ownCard)))
            val diary =
                DiaryEntryViewModel(
                    exhibition = opened,
                    visitRepository = FakeDiaryVisitRepository(),
                    publicNoteRepository = notes,
                    authState = MutableStateFlow(reader),
                    syncStatus = MutableStateFlow(MyGallrSyncStatus.SYNCED),
                    onVisitRecorded = {},
                )
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner()) {
                    val state by diary.state.collectAsState()
                    ExhibitionDetailScreen(
                        exhibition = opened,
                        lang = AppLanguage.KO,
                        isBookmarked = false,
                        onBookmarkToggle = {},
                        onBack = {},
                        diaryEntry = DiaryEntryControl(state, diary),
                        thoughtRepository = thoughts,
                        hiddenThoughtAuthorRepository = FakeHiddenThoughtAuthorRepository(),
                        publicNoteRepository = notes,
                        authState = reader,
                    )
                }
            }

            onNodeWithContentDescription("더 보기").performScrollTo().performClick()
            // The menu's 수정 is the last on screen (the diary row also reads 수정).
            onAllNodesWithText("수정").onLast().performClick()
            waitForIdle()

            val editor = assertNotNull(diary.state.value.editor)
            assertEquals("작은 방의 빛", editor.note)
            onNodeWithText("날짜를 정해 주세요").assertExists()
        }

    private fun ComposeUiTest.setPage(
        exhibition: Exhibition,
        control: DiaryEntryControl,
        language: AppLanguage = AppLanguage.KO,
    ) {
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner()) {
                ExhibitionDetailScreen(
                    exhibition = exhibition,
                    lang = language,
                    isBookmarked = false,
                    onBookmarkToggle = {},
                    onBack = {},
                    diaryEntry = control,
                    thoughtRepository = FakeThoughtRepository(),
                    hiddenThoughtAuthorRepository = FakeHiddenThoughtAuthorRepository(),
                    publicNoteRepository = FakePublicNoteRepository(),
                    authState = reader,
                )
            }
        }
    }

    private fun storeOwner(): ViewModelStoreOwner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    private val ownCard =
        PublicThought(
            id = "thought-1",
            authorId = "reader",
            content = "작은 방의 빛",
            createdAt = Instant.parse("2026-10-01T00:00:00Z"),
            authorDisplayName = "하나",
            authorAvatarUrl = null,
            reportedByMe = false,
        )

    private fun ownNote(moderation: PublicNoteModeration) =
        OwnPublicNote(
            exhibitionId = "show",
            thoughtId = "thought-1",
            content = "작은 방의 빛",
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
