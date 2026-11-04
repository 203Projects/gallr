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

    private val featuredCovers = listOf(exhibition("featured-1"), exhibition("featured-2"))

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
                TasteSeed(setOf("style:minimalist", "theme:city"), setOf("featured-1")),
                repository.current.value.seed,
            )
        }

    @Test
    fun `a sheet opened while the catalogue is still loading waits for it and then offers its covers`() =
        runTest(dispatcher) {
            val featured = listOf(exhibition("featured-1"), exhibition("featured-2"), exhibition("featured-3"))
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val viewModel =
                TastePickViewModel(
                    exhibitionsState = exhibitions,
                    featuredState = featuredState,
                    tastePreferences = RecordingTasteRepository(),
                    backgroundDispatcher = dispatcher,
                    todayProvider = { today },
                )

            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()
            exhibitions.value = ExhibitionListState.Success(featured)
            featuredState.value = ExhibitionListState.Success(featured)
            advanceUntilIdle()

            val sheet = assertNotNull(viewModel.sheet.value)
            val covers = sheet.covers
            assertEquals(listOf("featured-1", "featured-2", "featured-3"), covers.map(Exhibition::id))
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

    @Test
    fun `dismissed terms are listed to see less of and releasing one keeps the hide`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.dismiss(
                TasteDismissal(
                    "theme:city-1",
                    LocalDate(2026, 12, 1),
                    termIds = setOf("theme:city", "medium:painting"),
                ),
            )
            val viewModel = createViewModel(repository)

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()
            val opened = assertNotNull(viewModel.sheet.value)
            assertEquals(setOf("medium:painting", "theme:city"), opened.seeingLess.mapTo(mutableSetOf(), ArtTerm::id))
            assertFalse(opened.terms.any { it.id == "theme:city" })

            viewModel.releaseTerm("theme:city")
            advanceUntilIdle()

            assertEquals(
                listOf("medium:painting"),
                viewModel.sheet.value!!
                    .seeingLess
                    .map(ArtTerm::id),
            )
            assertEquals(setOf("medium:painting"), repository.current.value.dismissals.dismissedTermIds)
            assertEquals(setOf("theme:city-1"), repository.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `the first run offer waits through a failed catalogue and opens once it loads`() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Error("network"))
            val viewModel =
                TastePickViewModel(
                    exhibitionsState = exhibitions,
                    featuredState = MutableStateFlow(ExhibitionListState.Error("network")),
                    tastePreferences = RecordingTasteRepository(),
                    backgroundDispatcher = dispatcher,
                    todayProvider = { today },
                )

            viewModel.offerFirstRun()
            advanceUntilIdle()
            assertNull(viewModel.sheet.value, "an empty sheet could only be skipped")

            val catalogue =
                terms.flatMap { term ->
                    (1..3).map { exhibition("${term.id}-$it", artTerms = listOf(term)) }
                }
            exhibitions.value = ExhibitionListState.Success(catalogue + featuredCovers)
            advanceUntilIdle()

            val sheet = assertNotNull(viewModel.sheet.value)
            assertEquals(TastePickMode.FIRST_RUN, sheet.mode)
            assertTrue(sheet.terms.isNotEmpty() || sheet.covers.isNotEmpty())
        }

    @Test
    fun `a first run sheet with fewer than three choices is not offered`() =
        runTest(dispatcher) {
            val viewModel =
                TastePickViewModel(
                    exhibitionsState = MutableStateFlow(ExhibitionListState.Success(emptyList())),
                    featuredState = MutableStateFlow(ExhibitionListState.Success(emptyList())),
                    tastePreferences = RecordingTasteRepository(),
                    backgroundDispatcher = dispatcher,
                    todayProvider = { today },
                )

            viewModel.offerFirstRun()
            advanceUntilIdle()

            assertNull(viewModel.sheet.value)
        }

    @Test
    fun `a first run offer with too few choices opens once more choices load`() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(emptyList()))
            val featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(emptyList()))
            val viewModel =
                TastePickViewModel(
                    exhibitionsState = exhibitions,
                    featuredState = featuredState,
                    tastePreferences = RecordingTasteRepository(),
                    backgroundDispatcher = dispatcher,
                    todayProvider = { today },
                )
            viewModel.offerFirstRun()
            advanceUntilIdle()
            assertNull(viewModel.sheet.value)

            val covers = featuredCovers + exhibition("featured-3")
            exhibitions.value = ExhibitionListState.Success(covers)
            featuredState.value = ExhibitionListState.Success(covers)
            advanceUntilIdle()
            // Home makes the offer again whenever it holds anew; a sheet that never appeared was never offered.
            viewModel.offerFirstRun()
            advanceUntilIdle()

            assertEquals(TastePickMode.FIRST_RUN, viewModel.sheet.value?.mode)
        }

    // Value: protects=a taste save whose DataStore write throws is logged and the sheet still closes;
    // fails_when=the write helper stops catching failures so the save fails as an uncaught exception;
    // why_new=every sheet test uses a repository that never fails; seam=none; no_claim=a retry or an error message
    @Test
    fun `a save that fails to write is contained and the sheet still closes`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(FailingSaveTasteRepository())
            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()
            viewModel.toggleTerm("theme:city")

            viewModel.confirm()
            advanceUntilIdle()

            assertNull(viewModel.sheet.value)
        }

    @Test
    fun `closing a sheet still waiting for the catalogue keeps it from appearing later`() =
        runTest(dispatcher) {
            TastePickMode.entries.forEach { mode ->
                val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
                val featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
                val viewModel = loadingViewModel(exhibitions, featuredState)

                viewModel.open(mode)
                advanceUntilIdle()
                viewModel.close()
                exhibitions.value = ExhibitionListState.Success(featuredCovers)
                featuredState.value = ExhibitionListState.Success(featuredCovers)
                advanceUntilIdle()

                assertNull(viewModel.sheet.value, "mode $mode")
            }
        }

    @Test
    fun `the first run offer opens the sheet once per view model however often it is made`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(RecordingTasteRepository())

            viewModel.offerFirstRun()
            advanceUntilIdle()
            assertEquals(TastePickMode.FIRST_RUN, viewModel.sheet.value?.mode)
            viewModel.close()
            viewModel.offerFirstRun()
            advanceUntilIdle()

            assertNull(viewModel.sheet.value)
        }

    @Test
    fun `withdrawing the offer while the catalogue loads cancels the open`() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val viewModel = loadingViewModel(exhibitions, featuredState)

            viewModel.offerFirstRun()
            advanceUntilIdle()
            viewModel.withdrawOffer()
            exhibitions.value = ExhibitionListState.Success(featuredCovers)
            featuredState.value = ExhibitionListState.Success(featuredCovers)
            advanceUntilIdle()

            assertNull(viewModel.sheet.value)
        }

    @Test
    fun `withdrawing the offer leaves a sheet already showing open`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(RecordingTasteRepository())

            viewModel.offerFirstRun()
            advanceUntilIdle()
            viewModel.withdrawOffer()
            advanceUntilIdle()

            assertNotNull(viewModel.sheet.value)
        }

    @Test
    fun `an offer withdrawn before the sheet appeared can be made again`() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val viewModel = loadingViewModel(exhibitions, featuredState)

            val covers = featuredCovers + exhibition("featured-3")
            viewModel.offerFirstRun()
            advanceUntilIdle()
            viewModel.withdrawOffer()
            viewModel.offerFirstRun()
            advanceUntilIdle()
            exhibitions.value = ExhibitionListState.Success(covers)
            featuredState.value = ExhibitionListState.Success(covers)
            advanceUntilIdle()

            assertEquals(TastePickMode.FIRST_RUN, viewModel.sheet.value?.mode)
        }

    @Test
    fun `withdrawing the offer leaves an edit sheet that is still loading alone`() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val viewModel = loadingViewModel(exhibitions, featuredState)

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()
            viewModel.withdrawOffer()
            exhibitions.value = ExhibitionListState.Success(featuredCovers)
            featuredState.value = ExhibitionListState.Success(featuredCovers)
            advanceUntilIdle()

            assertEquals(TastePickMode.EDIT, viewModel.sheet.value?.mode)
        }

    @Test
    fun `clearing every pick in the edit sheet saves an empty seed and closes the sheet`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.saveSeed(TasteSeed(setOf("theme:city"), setOf("featured-1")))
            val viewModel = createViewModel(repository)
            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()

            viewModel.toggleTerm("theme:city")
            viewModel.toggleCover("featured-1")
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(TasteSeed.EMPTY, repository.savedSeeds.last())
            assertNull(viewModel.sheet.value)
        }

    @Test
    fun `a first run start with fewer than three picks saves nothing and keeps the sheet`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            val viewModel = createViewModel(repository)
            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()
            viewModel.toggleTerm("theme:city")
            viewModel.toggleCover("featured-1")

            viewModel.confirm()
            advanceUntilIdle()

            val sheet = assertNotNull(viewModel.sheet.value)
            assertEquals(setOf("theme:city"), sheet.selectedTermIds)
            assertEquals(setOf("featured-1"), sheet.selectedExhibitionIds)
            assertTrue(repository.savedSeeds.isEmpty())
            assertEquals(TastePickState.EMPTY, repository.current.value)
        }

    @Test
    fun `a picked term that a hide also carries stays in its place and stays selected`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.saveSeed(TasteSeed(termIds = setOf("theme:city")))
            repository.dismiss(TasteDismissal("theme:city-1", LocalDate(2026, 12, 1), termIds = setOf("theme:city")))
            val viewModel = createViewModel(repository)

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()

            val sheet = assertNotNull(viewModel.sheet.value)
            assertEquals(terms.map(ArtTerm::id), sheet.terms.map(ArtTerm::id))
            assertEquals(setOf("theme:city"), sheet.selectedTermIds)
            assertEquals(listOf("theme:city"), sheet.seeingLess.map(ArtTerm::id))
        }

    @Test
    fun `editing lets go of a picked cover that left the catalogue`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.saveSeed(TasteSeed(termIds = setOf("theme:city"), exhibitionIds = setOf("featured-1", "gone")))
            val viewModel = createViewModel(repository)

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()

            val sheet = assertNotNull(viewModel.sheet.value)
            assertEquals(setOf("featured-1"), sheet.selectedExhibitionIds)
            assertFalse(sheet.covers.any { it.id == "gone" })
        }

    @Test
    fun `a catalogue that failed to load keeps every picked cover selected`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.saveSeed(TasteSeed(exhibitionIds = setOf("featured-1", "gone")))
            val viewModel =
                loadingViewModel(
                    MutableStateFlow(ExhibitionListState.Error("network")),
                    MutableStateFlow(ExhibitionListState.Error("network")),
                    repository,
                )

            viewModel.open(TastePickMode.EDIT)
            advanceUntilIdle()

            assertEquals(setOf("featured-1", "gone"), viewModel.sheet.value?.selectedExhibitionIds)
        }

    @Test
    fun `releasing a term in the first run sheet works as in the edit sheet`() =
        runTest(dispatcher) {
            val repository = RecordingTasteRepository()
            repository.dismiss(
                TasteDismissal(
                    "theme:city-1",
                    LocalDate(2026, 12, 1),
                    termIds = setOf("theme:city", "medium:painting"),
                ),
            )
            val viewModel = createViewModel(repository)
            viewModel.open(TastePickMode.FIRST_RUN)
            advanceUntilIdle()

            viewModel.releaseTerm("theme:city")
            advanceUntilIdle()

            val sheet = assertNotNull(viewModel.sheet.value)
            assertEquals(TastePickMode.FIRST_RUN, sheet.mode)
            assertEquals(listOf("medium:painting"), sheet.seeingLess.map(ArtTerm::id))
            assertEquals(setOf("medium:painting"), repository.current.value.dismissals.dismissedTermIds)
            assertEquals(setOf("theme:city-1"), repository.current.value.dismissals.hiddenExhibitionIds)
        }

    private fun loadingViewModel(
        exhibitions: MutableStateFlow<ExhibitionListState>,
        featuredState: MutableStateFlow<ExhibitionListState>,
        repository: TastePreferenceRepository = RecordingTasteRepository(),
    ) = TastePickViewModel(
        exhibitionsState = exhibitions,
        featuredState = featuredState,
        tastePreferences = repository,
        backgroundDispatcher = dispatcher,
        todayProvider = { today },
    )

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
    val savedSeeds = mutableListOf<TasteSeed>()
    var skipCalls = 0
        private set
    override val state: Flow<TastePickState> = current

    override suspend fun saveSeed(seed: TasteSeed) {
        savedSeeds += seed
        current.value = current.value.copy(seed = seed, skipped = seed.isEmpty)
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

/** Saving the picks fails as a full disk would; everything else is recorded. */
private class FailingSaveTasteRepository(
    private val delegate: RecordingTasteRepository = RecordingTasteRepository(),
) : TastePreferenceRepository by delegate {
    override suspend fun saveSeed(seed: TasteSeed): Unit = throw IllegalStateException("disk full")
}
