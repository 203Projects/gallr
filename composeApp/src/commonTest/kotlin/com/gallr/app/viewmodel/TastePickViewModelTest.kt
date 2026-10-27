package com.gallr.app.viewmodel

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.TastePreferenceRepository
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 101 US1: the taste sheet's selection, start, skip and edit. */
@OptIn(ExperimentalCoroutinesApi::class)
class TastePickViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 10, 26)
    private val terms =
        listOf(
            ArtTerm("medium:painting", ArtTermCategory.MEDIUM, "회화", "Painting"),
            ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist"),
            ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City"),
            ArtTerm("mood:quiet-meditative", ArtTermCategory.MOOD, "고요함", "Quiet"),
        )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the first run sheet offers the catalogue's terms and covers and needs three picks to start`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            val viewModel = createViewModel(repository)
            backgroundScope.launch { viewModel.sheet.collect {} }

            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()
            val opened = assertNotNull(viewModel.sheet.value)
            assertEquals(terms.map(ArtTerm::id), opened.terms.map(ArtTerm::id))
            assertEquals(listOf("featured-1", "featured-2"), opened.covers.map(Exhibition::id))
            assertFalse(opened.canConfirm)
            assertEquals(3, opened.remaining)

            viewModel.toggleTerm("style:minimalist")
            viewModel.toggleTerm("theme:city")
            assertEquals(1, viewModel.sheet.value?.remaining)
            viewModel.toggleCover("featured-1")
            assertTrue(viewModel.sheet.value!!.canConfirm)

            viewModel.confirm()
            advanceUntilIdle()

            assertNull(viewModel.sheet.value)
            assertEquals(
                TasteSeed(setOf("style:minimalist", "theme:city"), setOf("featured-1"), today),
                repository.current.value.seed,
            )
        }

    @Test
    fun `toggling twice removes a pick`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(RecordingTasteRepository())
            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()

            viewModel.toggleTerm("theme:city")
            viewModel.toggleTerm("theme:city")

            assertEquals(3, viewModel.sheet.value?.remaining)
        }

    @Test
    fun `skipping is recorded once and closes the sheet while backing out records nothing`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            val viewModel = createViewModel(repository)

            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()
            viewModel.close()
            advanceUntilIdle()
            assertFalse(repository.current.value.skipped)

            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()
            viewModel.skip()
            advanceUntilIdle()

            assertTrue(repository.current.value.skipped)
            assertNull(viewModel.sheet.value)
            assertEquals(1, repository.skipCalls)
        }

    @Test
    fun `editing opens with the current picks selected and may save fewer than three`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.saveSeed(TasteSeed(setOf("theme:city", "mood:quiet-meditative"), setOf("featured-2")))
            val viewModel = createViewModel(repository)

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()
            val opened = assertNotNull(viewModel.sheet.value)
            assertEquals(setOf("theme:city", "mood:quiet-meditative"), opened.selectedTermIds)
            assertEquals(setOf("featured-2"), opened.selectedExhibitionIds)

            viewModel.toggleTerm("theme:city")
            viewModel.toggleCover("featured-2")
            assertTrue(viewModel.sheet.value!!.canConfirm)
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(setOf("mood:quiet-meditative"), repository.current.value.seed.termIds)
            assertTrue(
                repository.current.value.seed.exhibitionIds
                    .isEmpty(),
            )
        }

    @Test
    fun `a picked term no longer offered still shows so it can be removed`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            val gone = ArtTerm("theme:memory", ArtTermCategory.THEME, "기억", "Memory")
            repository.saveSeed(TasteSeed(termIds = setOf(gone.id)))
            val viewModel = createViewModel(repository, extraTerm = gone)

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()

            assertTrue(
                viewModel.sheet.value!!
                    .terms
                    .any { it.id == gone.id },
            )
        }

    private fun createViewModel(
        repository: TastePreferenceRepository,
        extraTerm: ArtTerm? = null,
    ): TastePickViewModel {
        val catalogue =
            terms.flatMap { term -> (1..3).map { exhibition("${term.id}-$it", artTerms = listOf(term)) } } +
                listOfNotNull(extraTerm?.let { exhibition("lone", artTerms = listOf(it)) })
        val featured = listOf(exhibition("featured-1"), exhibition("featured-2"))
        return TastePickViewModel(
            exhibitionsState = MutableStateFlow(ExhibitionListState.Success(catalogue + featured)),
            featuredState = MutableStateFlow(ExhibitionListState.Success(featured)),
            tastePreferences = repository,
            backgroundDispatcher = dispatcher,
            todayProvider = { today },
        )
    }

    private fun exhibition(
        id: String,
        artTerms: List<ArtTerm> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 10, 1),
        closingDate = LocalDate(2026, 12, 1),
        isFeatured = true,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        artTerms = artTerms,
    )
}

private class RecordingTasteRepository : TastePreferenceRepository {
    val current = MutableStateFlow(TastePickState.EMPTY)
    var skipCalls = 0
        private set
    override val state: Flow<TastePickState> = current

    override suspend fun saveSeed(seed: TasteSeed) {
        current.value = current.value.copy(seed = seed, skipped = false)
    }

    override suspend fun markSkipped() {
        skipCalls += 1
        current.value = current.value.copy(skipped = true)
    }

    override suspend fun dismiss(record: TasteDismissal) {
        current.value = current.value.copy(dismissals = current.value.dismissals.dismiss(record))
    }

    override suspend fun removeDismissals(exhibitionIds: Set<String>) {
        current.value = current.value.copy(dismissals = current.value.dismissals.remove(exhibitionIds))
    }

    override suspend fun releaseTerm(termId: String) {
        current.value = current.value.copy(dismissals = current.value.dismissals.releaseTerm(termId))
    }

    override suspend fun expire(today: LocalDate) {
        current.value = current.value.copy(dismissals = current.value.dismissals.expire(today))
    }
}
