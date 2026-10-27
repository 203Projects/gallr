package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.EstimatedLeg
import com.gallr.shared.map.NeighborhoodRoutePlanner
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.map.RouteLegQuality
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.ExhibitionRecommendationIndex
import com.gallr.shared.recommendation.ExhibitionRecommender
import com.gallr.shared.recommendation.RecommendationContext
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RouteRelevance
import com.gallr.shared.recommendation.RouteRelevanceContext
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.TastePreferenceRepository
import com.gallr.shared.repository.VisitRepository
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDiscoveryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 8, 30)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `cold catalogue prepares local recommendations without inferred signals`() =
        runTest(dispatcher) {
            val recommender = RecordingRecommender()
            val language = MutableStateFlow(AppLanguage.KO)
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a"), exhibition("b")),
                    language = language,
                    recommender = recommender,
                )
            advanceUntilIdle()

            val ready = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("a", "b"), ready.items.map { it.exhibition.id })
            assertEquals(1L, ready.runId)
            assertTrue(
                recommender.contexts
                    .single()
                    .bookmarkedExhibitionIds
                    .isEmpty(),
            )
            assertTrue(
                recommender.contexts
                    .single()
                    .visits
                    .isEmpty(),
            )
            assertTrue(
                recommender.contexts
                    .single()
                    .followedGalleries
                    .isEmpty(),
            )
            assertEquals(today, recommender.contexts.single().today)

            language.value = AppLanguage.EN
            assertEquals(AppLanguage.EN, viewModel.language.value)
        }

    @Test
    fun `bookmark visit and follow changes rerank on refresh through the same prepared index`() =
        runTest(dispatcher) {
            val a = exhibition("a")
            val b = exhibition("b")
            val c = exhibition("c")
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val visits = LocalDiscoveryVisitRepository()
            val follows = LocalDiscoveryFollowedGalleryRepository()
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(a, b, c),
                    bookmarks = bookmarks,
                    visits = visits,
                    follows = follows,
                    recommender = recommender,
                )
            advanceUntilIdle()
            val first = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            val prepared = recommender.returnedIndexes.single()

            bookmarks.value = setOf("a")
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()
            val afterBookmark = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("b", "c"), afterBookmark.items.map { it.exhibition.id })
            assertNotEquals(first.runId, afterBookmark.runId)
            assertSame(prepared, recommender.previousIndexes.last())
            assertEquals(1, recommender.createdIndexCount)

            visits.visits.value = listOf(visit(b))
            follows.followed.value = listOf(followedGallery("gallery-c"))
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()
            val afterAllSignals = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("c"), afterAllSignals.items.map { it.exhibition.id })
            assertEquals(RecommendationBasis(savedCount = 0, visitedCount = 0, followedCount = 0), first.basis)
            assertEquals(
                RecommendationBasis(savedCount = 1, visitedCount = 1, followedCount = 1),
                afterAllSignals.basis,
            )
            assertEquals(setOf("a"), recommender.contexts.last().bookmarkedExhibitionIds)
            assertEquals(
                listOf("b"),
                recommender.contexts
                    .last()
                    .visits
                    .map { it.exhibitionId },
            )
            assertEquals(
                listOf("gallery-c"),
                recommender.contexts
                    .last()
                    .followedGalleries
                    .map { it.galleryKey },
            )
            assertEquals(1, recommender.createdIndexCount)
        }

    @Test
    fun `a save while the list is showing leaves the list exactly as it is`() =
        runTest(dispatcher) {
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a"), exhibition("b"), exhibition("c")),
                    bookmarks = bookmarks,
                    recommender = recommender,
                )
            advanceUntilIdle()
            val shown = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)

            bookmarks.value = setOf("a")
            advanceUntilIdle()
            bookmarks.value = emptySet()
            advanceUntilIdle()
            bookmarks.value = setOf("a", "b")
            advanceUntilIdle()

            assertEquals(shown, viewModel.recommendationState.value)
            assertEquals(1, recommender.contexts.size)
        }

    @Test
    fun `a new taste pick reranks at once while a dismissal waits for a refresh`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a"), exhibition("b")),
                    recommender = recommender,
                    taste = taste,
                )
            advanceUntilIdle()
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(TasteSeed.EMPTY, recommender.contexts.last().tasteSeed)

            val seed = TasteSeed(termIds = setOf("style:minimalist", "medium:photography", "theme:city"))
            taste.saveSeed(seed)
            advanceUntilIdle()

            assertEquals(2, recommender.contexts.size)
            assertEquals(seed, recommender.contexts.last().tasteSeed)

            taste.dismiss(TasteDismissal(exhibitionId = "a", closingDate = LocalDate(2026, 9, 30)))
            advanceUntilIdle()
            assertEquals(2, recommender.contexts.size)

            viewModel.refreshRecommendations()
            advanceUntilIdle()

            assertEquals(3, recommender.contexts.size)
            assertEquals(
                setOf("a"),
                recommender.contexts
                    .last()
                    .dismissals.hiddenExhibitionIds,
            )
        }

    @Test
    fun `dismissing a card records its reviewed terms and artists and closing date`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val shown =
                exhibition(
                    "a",
                    artTerms = listOf(ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")),
                    artists = listOf(ExhibitionArtist("artist-park", "박서보", "Park Seo-Bo")),
                )
            val viewModel = createViewModel(exhibitions = listOf(shown), taste = taste)
            advanceUntilIdle()

            viewModel.dismiss(shown)
            advanceUntilIdle()

            assertEquals(
                listOf(TasteDismissal("a", LocalDate(2026, 9, 30), setOf("theme:city"), setOf("artist-park"))),
                taste.current.value.dismissals.records,
            )
        }

    @Test
    fun `undo brings a hidden card back`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val shown = exhibition("a")
            val viewModel = createViewModel(exhibitions = listOf(shown), taste = taste)
            advanceUntilIdle()

            viewModel.dismiss(shown)
            advanceUntilIdle()
            viewModel.undoDismissal(shown.id)
            advanceUntilIdle()

            assertTrue(
                taste.current.value.dismissals.records
                    .isEmpty(),
            )
        }

    @Test
    fun `saving or visiting or following the gallery of a hidden exhibition lifts its hide`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val visits = LocalDiscoveryVisitRepository()
            val follows = LocalDiscoveryFollowedGalleryRepository()
            val saved = exhibition("saved")
            val visited = exhibition("visited")
            val followed = exhibition("followed")
            val followedById = exhibition("followed-by-id")
            val kept = exhibition("kept")
            val viewModel =
                createViewModel(
                    exhibitions = listOf(saved, visited, followed, followedById, kept),
                    bookmarks = bookmarks,
                    visits = visits,
                    follows = follows,
                    taste = taste,
                )
            advanceUntilIdle()
            listOf(saved, visited, followed, followedById, kept).forEach(viewModel::dismiss)
            advanceUntilIdle()
            assertEquals(5, taste.current.value.dismissals.records.size)

            bookmarks.value = setOf(saved.id)
            visits.addVisits(listOf(visit(visited)))
            follows.followGalleries(listOf(followedGallery(galleryKey(followed.venueNameKo, followed.venueNameEn))))
            // This follow's key names another venue, so only its gallery id ties it to the exhibition.
            follows.followGalleries(listOf(followedGallery("renamed-venue", galleryId = followedById.galleryId)))
            advanceUntilIdle()

            assertEquals(setOf("kept"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `hiding a card from a gallery already followed keeps the hide`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val byKey = exhibition("by-key")
            val byId = exhibition("by-id")
            val follows = LocalDiscoveryFollowedGalleryRepository()
            follows.followGalleries(
                listOf(
                    followedGallery(galleryKey(byKey.venueNameKo, byKey.venueNameEn)),
                    followedGallery("renamed-venue", galleryId = byId.galleryId),
                ),
            )
            val viewModel =
                createViewModel(exhibitions = listOf(byKey, byId, exhibition("c")), follows = follows, taste = taste)
            advanceUntilIdle()

            viewModel.dismiss(byKey)
            viewModel.dismiss(byId)
            advanceUntilIdle()

            assertEquals(setOf("by-key", "by-id"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `hiding a card already saved or visited keeps the hide`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val saved = exhibition("saved")
            val visited = exhibition("visited")
            val visits = LocalDiscoveryVisitRepository()
            visits.addVisits(listOf(visit(visited)))
            val viewModel =
                createViewModel(
                    exhibitions = listOf(saved, visited, exhibition("c")),
                    bookmarks = MutableStateFlow(setOf(saved.id)),
                    visits = visits,
                    taste = taste,
                )
            advanceUntilIdle()

            viewModel.dismiss(saved)
            viewModel.dismiss(visited)
            advanceUntilIdle()

            assertEquals(setOf("saved", "visited"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `undo brings back a card that a refresh already removed`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val a = exhibition("a")
            val viewModel =
                createViewModel(
                    exhibitions = listOf(a, exhibition("b")),
                    recommender = RecordingRecommender(hidesDismissed = true),
                    taste = taste,
                )
            advanceUntilIdle()

            viewModel.dismiss(a)
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()
            val refreshed = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("b"), refreshed.items.map { it.exhibition.id })

            viewModel.undoDismissal(a.id)
            advanceUntilIdle()

            val restored = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("a", "b"), restored.items.map { it.exhibition.id })
        }

    @Test
    fun `undo after a save lifted the hide leaves the other hides alone`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")
            val lifted = exhibition("lifted")
            val other = exhibition("other", artTerms = listOf(city))
            val viewModel = createViewModel(exhibitions = listOf(lifted, other), bookmarks = bookmarks, taste = taste)
            advanceUntilIdle()
            viewModel.dismiss(lifted)
            viewModel.dismiss(other)
            advanceUntilIdle()
            val otherRecord =
                taste.current.value.dismissals.records
                    .single { it.exhibitionId == other.id }
            bookmarks.value = setOf(lifted.id)
            advanceUntilIdle()
            assertEquals(setOf(other.id), taste.current.value.dismissals.hiddenExhibitionIds)

            viewModel.undoDismissal(lifted.id)
            advanceUntilIdle()

            assertEquals(listOf(otherRecord), taste.current.value.dismissals.records)
        }

    // The fake's dismiss suspends before it writes: an undo written separately would finish first, and the
    // hide would land after it and stay.
    @Test
    fun `an undo sent right after the hide applies after it and leaves no record`() =
        runTest(dispatcher) {
            val taste = SlowDismissTasteRepository()
            val a = exhibition("a")
            val viewModel = createViewModel(exhibitions = listOf(a, exhibition("b")), taste = taste)
            advanceUntilIdle()

            viewModel.dismiss(a)
            viewModel.undoDismissal(a.id)
            advanceUntilIdle()

            assertTrue(
                taste.delegate.current.value.dismissals.records
                    .isEmpty(),
            )
        }

    @Test
    fun `follows that return after signing in again do not lift a hide`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val settled = MutableStateFlow(true)
            val shown = exhibition("g")
            val key = galleryKey(shown.venueNameKo, shown.venueNameEn)
            val follows = LocalDiscoveryFollowedGalleryRepository()
            follows.followGalleries(listOf(followedGallery(key)))
            val viewModel =
                createViewModel(
                    exhibitions = listOf(shown, exhibition("b")),
                    follows = follows,
                    taste = taste,
                    historySettled = settled,
                )
            advanceUntilIdle()
            viewModel.dismiss(shown)
            advanceUntilIdle()

            settled.value = false
            follows.unfollowGallery(key)
            advanceUntilIdle()
            follows.followGalleries(listOf(followedGallery(key)))
            advanceUntilIdle()
            settled.value = true
            advanceUntilIdle()

            assertEquals(setOf("g"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `a follow that only gains its gallery id is not a new follow`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val shown = exhibition("g")
            val key = galleryKey(shown.venueNameKo, shown.venueNameEn)
            val follows = LocalDiscoveryFollowedGalleryRepository()
            follows.followGalleries(listOf(followedGallery(key)))
            val viewModel =
                createViewModel(exhibitions = listOf(shown, exhibition("b")), follows = follows, taste = taste)
            advanceUntilIdle()
            viewModel.dismiss(shown)
            advanceUntilIdle()

            follows.followed.value = listOf(followedGallery(key, galleryId = shown.galleryId))
            advanceUntilIdle()

            assertEquals(setOf("g"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `a save made while the catalogue is loading still lifts the hide once the list arrives`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val catalogue = listOf(exhibition("a"), exhibition("b"))
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(catalogue))
            val viewModel =
                createViewModel(exhibitionsState = exhibitions, bookmarks = bookmarks, taste = taste)
            advanceUntilIdle()
            viewModel.dismiss(catalogue.first())
            advanceUntilIdle()
            assertEquals(setOf("a"), taste.current.value.dismissals.hiddenExhibitionIds)

            exhibitions.value = ExhibitionListState.Loading
            advanceUntilIdle()
            bookmarks.value = setOf("a")
            advanceUntilIdle()
            exhibitions.value = ExhibitionListState.Success(catalogue)
            advanceUntilIdle()

            assertTrue(
                taste.current.value.dismissals.hiddenExhibitionIds
                    .isEmpty(),
            )
        }

    @Test
    fun `a failing taste write leaves later writes working`() =
        runTest(dispatcher) {
            val taste = FailingOnceDismissTasteRepository()
            val a = exhibition("a")
            val b = exhibition("b")
            val viewModel = createViewModel(exhibitions = listOf(a, b), taste = taste)
            advanceUntilIdle()

            viewModel.dismiss(a)
            advanceUntilIdle()
            viewModel.dismiss(b)
            advanceUntilIdle()

            assertEquals(setOf("b"), taste.delegate.current.value.dismissals.hiddenExhibitionIds)
        }

    // The refresh leaves the hidden card off the list, so an undo still waiting would rerank it on the next save.
    @Test
    fun `undoing a hide that a save already lifted leaves the shown list held`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val a = exhibition("a")
            val recommender = RecordingRecommender(hidesDismissed = true)
            val viewModel =
                createViewModel(
                    exhibitions = listOf(a, exhibition("b"), exhibition("c")),
                    bookmarks = bookmarks,
                    recommender = recommender,
                    taste = taste,
                )
            advanceUntilIdle()
            viewModel.dismiss(a)
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()
            bookmarks.value = setOf(a.id)
            advanceUntilIdle()
            assertTrue(
                taste.current.value.dismissals.hiddenExhibitionIds
                    .isEmpty(),
            )
            val held = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("b", "c"), held.items.map { it.exhibition.id })
            val rankings = recommender.contexts.size

            viewModel.undoDismissal(a.id)
            advanceUntilIdle()
            bookmarks.value = setOf(a.id, "c")
            advanceUntilIdle()

            assertSame(held, viewModel.recommendationState.value)
            assertEquals(rankings, recommender.contexts.size)
        }

    @Test
    fun `history stays unknown until the account history has arrived`() =
        runTest(dispatcher) {
            val settled = MutableStateFlow(false)
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val viewModel =
                createViewModel(exhibitions = listOf(exhibition("a")), bookmarks = bookmarks, historySettled = settled)
            advanceUntilIdle()
            assertEquals(null, viewModel.hasHistory.value)

            settled.value = true
            advanceUntilIdle()
            assertEquals(false, viewModel.hasHistory.value)

            bookmarks.value = setOf("a")
            advanceUntilIdle()
            assertEquals(true, viewModel.hasHistory.value)

            settled.value = false
            advanceUntilIdle()
            assertEquals(null, viewModel.hasHistory.value)
        }

    @Test
    fun `hides of exhibitions that have already closed are dropped when the view model starts`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            taste.dismiss(TasteDismissal("closed", closingDate = LocalDate(2026, 8, 29), termIds = setOf("theme:city")))
            taste.dismiss(TasteDismissal("running", closingDate = LocalDate(2026, 9, 30)))
            createViewModel(exhibitions = listOf(exhibition("a")), taste = taste)
            advanceUntilIdle()

            assertEquals(setOf("running"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `a hide whose exhibition closes while the app is open is dropped when the list is read again`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            taste.dismiss(TasteDismissal("last-day", closingDate = LocalDate(2026, 8, 29)))
            taste.dismiss(TasteDismissal("running", closingDate = LocalDate(2026, 9, 30)))
            var day = LocalDate(2026, 8, 29)
            val viewModel =
                createViewModel(exhibitions = listOf(exhibition("a")), taste = taste, todayProvider = { day })
            advanceUntilIdle()
            assertEquals(setOf("last-day", "running"), taste.current.value.dismissals.hiddenExhibitionIds)

            day = LocalDate(2026, 8, 30)
            viewModel.refreshRecommendations()
            advanceUntilIdle()

            assertEquals(setOf("running"), taste.current.value.dismissals.hiddenExhibitionIds)
        }

    @Test
    fun `picks count in the basis and lead the taste tags and history is reported for the sheet`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a").copy(artTerms = listOf(city)), exhibition("b")),
                    bookmarks = bookmarks,
                    taste = taste,
                )
            advanceUntilIdle()
            assertEquals(false, viewModel.hasHistory.value)

            // "gone" left the catalogue: it can no longer be shown or unpicked, so it stops counting.
            taste.saveSeed(TasteSeed(termIds = setOf(city.id), exhibitionIds = setOf("b", "gone")))
            advanceUntilIdle()

            val ready = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(2, ready.basis.pickedCount)
            assertEquals(listOf(city.id), ready.tasteTerms.map { it.id })
            assertEquals(
                TasteSeed(termIds = setOf(city.id), exhibitionIds = setOf("b", "gone")),
                viewModel.tasteState.value.seed,
            )

            bookmarks.value = setOf("b")
            advanceUntilIdle()
            assertEquals(true, viewModel.hasHistory.value)
        }

    @Test
    fun `a picked exhibition's terms describe the taste like a save's do`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a").copy(artTerms = listOf(city)), exhibition("b")),
                    taste = taste,
                )
            advanceUntilIdle()

            taste.saveSeed(TasteSeed(exhibitionIds = setOf("a")))
            advanceUntilIdle()

            val ready = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf(city.id), ready.tasteTerms.map { it.id })
        }

    // Value: protects=a term carried by a 덜 보기 hide leaves Home's taste tags on the next read;
    // fails_when=the view model stops passing dismissed term ids to tasteTerms as excluded;
    // why_new=TasteProfileTest covers tasteTerms alone, not the view model wiring; seam=none;
    // no_claim=how the chips render or which tags replace the removed one
    @Test
    fun `a term the visitor sees less of leaves the taste tags once the list is read again`() =
        runTest(dispatcher) {
            val taste = FakeTastePreferenceRepository()
            val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")
            val saved = exhibition("saved", artTerms = listOf(city))
            val hidden = exhibition("hidden", artTerms = listOf(city))
            val viewModel =
                createViewModel(
                    exhibitions = listOf(saved, hidden),
                    bookmarks = MutableStateFlow(setOf(saved.id)),
                    taste = taste,
                )
            advanceUntilIdle()
            val before = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf(city.id), before.tasteTerms.map { it.id })

            viewModel.dismiss(hidden)
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()

            val after = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(emptyList(), after.tasteTerms.map { it.id })
        }

    // Value: protects=a DataStore write that throws during 덜 보기 is logged and the shown list stays put;
    // fails_when=updateTaste stops catching write failures so the coroutine fails as an uncaught exception;
    // why_new=every taste test uses a repository that never fails; seam=none;
    // no_claim=the log line's wording or any retry of the failed write
    @Test
    fun `a hide that fails to write is contained and leaves the list as it is`() =
        runTest(dispatcher) {
            val shown = exhibition("a")
            val viewModel =
                createViewModel(exhibitions = listOf(shown, exhibition("b")), taste = FailingDismissTasteRepository())
            advanceUntilIdle()
            val before = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)

            viewModel.dismiss(shown)
            advanceUntilIdle()

            assertSame(before, viewModel.recommendationState.value)
            assertTrue(
                viewModel.tasteState.value.dismissals.records
                    .isEmpty(),
            )
        }

    @Test
    fun `refreshing with nothing new keeps the same run`() =
        runTest(dispatcher) {
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(exhibitions = listOf(exhibition("a"), exhibition("b")), recommender = recommender)
            advanceUntilIdle()
            val shown = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)

            viewModel.refreshRecommendations()
            advanceUntilIdle()

            assertEquals(shown, viewModel.recommendationState.value)
            assertEquals(1, recommender.contexts.size)
        }

    @Test
    fun `a change that ends an empty list is applied straight away`() =
        runTest(dispatcher) {
            val bookmarks = MutableStateFlow(setOf("a"))
            val viewModel = createViewModel(exhibitions = listOf(exhibition("a")), bookmarks = bookmarks)
            advanceUntilIdle()
            assertEquals(RecommendationUiState.Empty, viewModel.recommendationState.value)

            bookmarks.value = emptySet()
            advanceUntilIdle()

            val ready = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("a"), ready.items.map { it.exhibition.id })
        }

    @Test
    fun `routes use a new save before the list is refreshed`() =
        runTest(dispatcher) {
            val bookmarks = MutableStateFlow(setOf("saved-a"))
            val viewModel =
                createViewModel(
                    exhibitions =
                        listOf(
                            exhibition("saved-a", latitude = 37.5705, longitude = 126.9805),
                            exhibition("saved-b", latitude = 37.5710, longitude = 126.9810),
                            exhibition("other", latitude = 37.5715, longitude = 126.9815),
                        ),
                    bookmarks = bookmarks,
                )
            advanceUntilIdle()
            val shown = viewModel.recommendationState.value

            bookmarks.value = setOf("saved-a", "saved-b")
            advanceUntilIdle()
            viewModel.beginRouteIfNeeded(GeoPoint(37.5700, 126.9800))
            viewModel.setRouteMode(RouteCurationMode.SAVED)
            viewModel.setStopCount(2)
            viewModel.buildRoute()
            advanceUntilIdle()

            val ready = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
            assertEquals(setOf("saved-a", "saved-b"), ready.estimate.stops.mapTo(mutableSetOf()) { it.id })
            assertEquals(shown, viewModel.recommendationState.value)
        }

    @Test
    fun `a planned route is walked along paths that arrive later without changing its stops`() =
        runTest(dispatcher) {
            val walking = FakeWalkingRouteRepository()
            val viewModel =
                createViewModel(
                    exhibitions =
                        listOf(
                            exhibition("saved-a", latitude = 37.5705, longitude = 126.9805),
                            exhibition("saved-b", latitude = 37.5710, longitude = 126.9810),
                        ),
                    bookmarks = MutableStateFlow(setOf("saved-a", "saved-b")),
                    routePlanner = NeighborhoodRoutePlanner(),
                    walking = walking,
                )
            advanceUntilIdle()
            viewModel.beginRouteIfNeeded(GeoPoint(37.5700, 126.9800))
            viewModel.setRouteMode(RouteCurationMode.SAVED)
            viewModel.setStopCount(2)
            viewModel.buildRoute()
            advanceUntilIdle()

            val planned = assertIs<RouteUiState.Ready>(viewModel.routeState.value).estimate
            val stopIds = planned.stops.map { it.id }
            assertEquals(listOf<List<String?>>(stopIds), walking.resolvedIds, "only the venues are asked for")
            val (from, to) = planned.stops.map { GeoPoint(it.latitude!!, it.longitude!!) }
            walking.publish(from, to, distanceMeters = 1_500)
            advanceUntilIdle()

            val walked = assertIs<RouteUiState.Ready>(viewModel.routeState.value).estimate
            assertEquals(stopIds, walked.stops.map { it.id })
            assertEquals(RouteLegQuality.APPROXIMATE, walked.legs[0].quality, "the leg from the start is an estimate")
            assertEquals(RouteLegQuality.ROUTED, walked.legs[1].quality)
            assertEquals(1_500, walked.legs[1].distanceMeters)
        }

    @Test
    fun `taste terms describe the saved and visited exhibitions and update with them`() =
        runTest(dispatcher) {
            val painting = exhibition("a").copy(descriptionKo = "추상 회화 연작")
            val sculpture = exhibition("b").copy(descriptionKo = "추상 조각")
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val visits = LocalDiscoveryVisitRepository()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(painting, sculpture, exhibition("c")),
                    bookmarks = bookmarks,
                    visits = visits,
                )
            advanceUntilIdle()
            val coldStart = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(emptyList(), coldStart.tasteTerms)

            bookmarks.value = setOf("a")
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()
            val saved = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("medium:painting", "style:abstract"), saved.tasteTerms.map { it.id })

            visits.visits.value = listOf(visit(sculpture))
            advanceUntilIdle()
            viewModel.refreshRecommendations()
            advanceUntilIdle()
            val savedAndVisited = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(
                listOf("style:abstract", "medium:painting", "medium:sculpture"),
                savedAndVisited.tasteTerms.map { it.id },
            )
        }

    @Test
    fun `catalogue loading empty and failure become explicit recommendation states`() =
        runTest(dispatcher) {
            val catalogue = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val viewModel = createViewModel(exhibitionsState = catalogue)

            assertIs<RecommendationUiState.Loading>(viewModel.recommendationState.value)
            catalogue.value = ExhibitionListState.Success(emptyList())
            advanceUntilIdle()
            assertIs<RecommendationUiState.Empty>(viewModel.recommendationState.value)

            catalogue.value = ExhibitionListState.Error("private backend detail")
            advanceUntilIdle()
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)
        }

    @Test
    fun `repository flow failure retries and recovers local recommendations`() =
        runTest(dispatcher) {
            val visits = RecoveringLocalDiscoveryVisitRepository()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    visits = visits,
                )

            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, visits.subscriptionCount)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `persistent repository failure stops bounded retries until explicit retry`() =
        runTest(dispatcher) {
            val visits = FailingLocalDiscoveryVisitRepository()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    visits = visits,
                )

            advanceUntilIdle()

            assertEquals(3, visits.subscriptionCount)
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, visits.subscriptionCount)

            viewModel.retryRecommendations()
            advanceUntilIdle()
            assertEquals(6, visits.subscriptionCount)
        }

    @Test
    fun `explicit retry reruns an equal catalogue after a computation failure`() =
        runTest(dispatcher) {
            val recommender = RecordingRecommender().apply { failingPrepareCalls = 1 }
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    recommender = recommender,
                )
            advanceUntilIdle()
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            viewModel.retryRecommendations()
            advanceUntilIdle()

            assertEquals(2, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `repeated retry taps coalesce behind one computation`() =
        runTest(dispatcher) {
            val backgroundDispatcher = StandardTestDispatcher(testScheduler)
            val recommender = RecordingRecommender().apply { failingPrepareCalls = 1 }
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    recommender = recommender,
                    backgroundDispatcher = backgroundDispatcher,
                )
            runCurrent()
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            viewModel.retryRecommendations()
            viewModel.retryRecommendations()
            runCurrent()

            assertEquals(2, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `every route mode plans from the latest organic snapshot`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val exhibitions =
                listOf(
                    exhibition("saved-a", latitude = 37.5705, longitude = 126.9805),
                    exhibition("saved-b", latitude = 37.5710, longitude = 126.9810),
                    exhibition("for-you-a", latitude = 37.5715, longitude = 126.9815),
                    exhibition("for-you-b", latitude = 37.5720, longitude = 126.9820),
                )
            val viewModel =
                createViewModel(
                    exhibitions = exhibitions,
                    bookmarks = MutableStateFlow(setOf("saved-a", "saved-b")),
                )
            advanceUntilIdle()
            viewModel.beginRouteIfNeeded(origin)

            RouteCurationMode.entries.forEach { mode ->
                viewModel.setRouteMode(mode)
                viewModel.setStopCount(2)
                viewModel.buildRoute()
                advanceUntilIdle()

                val ready = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
                assertEquals(mode, ready.request.mode)
                assertEquals(2, ready.estimate.stops.size)
                when (mode) {
                    RouteCurationMode.FOR_YOU -> {
                        assertEquals(
                            setOf("saved-a", "saved-b"),
                            ready.estimate.stops.mapTo(mutableSetOf()) { it.id },
                        )
                    }

                    RouteCurationMode.SAVED -> {
                        assertEquals(
                            setOf("saved-a", "saved-b"),
                            ready.estimate.stops.mapTo(mutableSetOf()) { it.id },
                        )
                    }

                    RouteCurationMode.NEIGHBORHOOD,
                    RouteCurationMode.CLOSING_SOON,
                    -> {
                        assertTrue(
                            ready.estimate.stops.all { stop ->
                                exhibitions.any { it.id == stop.id }
                            },
                        )
                    }
                }
            }
        }

    @Test
    fun `for you routes rank the whole catalogue from the request origin`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val exhibitions = (0 until 8).map { index -> exhibition("ex-$index", latitude = 37.5705 + index * 0.0005) }
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = exhibitions,
                    bookmarks = MutableStateFlow(setOf("ex-0")),
                    recommender = recommender,
                )
            advanceUntilIdle()

            val ready = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertTrue(ready.items.size <= 6)
            assertFalse(ready.items.any { it.exhibition.id == "ex-0" })
            assertTrue(recommender.routeContexts.isEmpty())

            viewModel.beginRouteIfNeeded(origin)
            viewModel.setRouteMode(RouteCurationMode.NEIGHBORHOOD)
            viewModel.buildRoute()
            advanceUntilIdle()
            assertTrue(recommender.routeContexts.isEmpty(), "non personal modes never rank route candidates")

            viewModel.setRouteMode(RouteCurationMode.FOR_YOU)
            viewModel.buildRoute()
            advanceUntilIdle()

            val context = recommender.routeContexts.single()
            assertEquals(origin, context.origin)
            assertEquals(5.0, context.maxDistanceKm)
            assertEquals(today, context.today)
            assertEquals(setOf("ex-0"), context.bookmarkedExhibitionIds)
            val route = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
            assertTrue("ex-0" in route.estimate.stops.map { it.id })
        }

    @Test
    fun `route builds are stamped with the current Korea date and time`() =
        runTest(dispatcher) {
            val viewModel =
                createViewModel(
                    exhibitions =
                        listOf(
                            exhibition("a", closingDate = LocalDate(2026, 12, 31)),
                            exhibition("b", latitude = 37.5710, closingDate = LocalDate(2026, 12, 31)),
                        ),
                    now = Instant.parse("2026-10-05T05:30:00Z"),
                )
            advanceUntilIdle()

            viewModel.beginRouteIfNeeded(GeoPoint(37.5700, 126.9800))
            viewModel.setStopCount(2)
            viewModel.buildRoute()
            advanceUntilIdle()

            val ready = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
            assertEquals(LocalDate(2026, 10, 5), ready.request.visitDate)
            assertEquals(LocalTime(14, 30), ready.request.startTime)
            val estimate = ready.estimate
            val firstArrivalSeconds = estimate.stopSchedules[0].arrival.toSecondOfDay()
            val firstWalkSeconds = estimate.legs[0].estimatedTravelMinutes * 60
            val departure = LocalTime.fromSecondOfDay(firstArrivalSeconds - firstWalkSeconds)
            assertEquals(ready.request.startTime, departure)
        }

    @Test
    fun `route shortage carries the closed venue count`() =
        runTest(dispatcher) {
            // 2026-10-05 is a Monday in Korea time.
            val viewModel =
                createViewModel(
                    exhibitions =
                        listOf(
                            exhibition(
                                id = "closed-a",
                                closingDate = LocalDate(2026, 12, 31),
                                hours = "10am - 6pm\nTuesday - Sunday",
                            ),
                            exhibition(
                                id = "closed-b",
                                latitude = 37.5710,
                                closingDate = LocalDate(2026, 12, 31),
                                hours = "11am - 6pm\nTuesday - Saturday",
                            ),
                            exhibition(
                                id = "open",
                                latitude = 37.5715,
                                closingDate = LocalDate(2026, 12, 31),
                                hours = "10am - 6pm\nMonday - Sunday",
                            ),
                        ),
                    now = Instant.parse("2026-10-05T05:30:00Z"),
                )
            advanceUntilIdle()

            viewModel.beginRouteIfNeeded(GeoPoint(37.5700, 126.9800))
            viewModel.setStopCount(2)
            viewModel.buildRoute()
            advanceUntilIdle()

            val insufficient = assertIs<RouteUiState.Insufficient>(viewModel.routeState.value)
            assertEquals(1, insufficient.available)
            assertEquals(2, insufficient.closedCount)
        }

    @Test
    fun `route shortage retains the request and available count for editing`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val viewModel = createViewModel(exhibitions = listOf(exhibition("only")))
            advanceUntilIdle()

            viewModel.beginRouteIfNeeded(origin)
            viewModel.setStopCount(3)
            viewModel.buildRoute()
            advanceUntilIdle()

            val insufficient = assertIs<RouteUiState.Insufficient>(viewModel.routeState.value)
            assertEquals(3, insufficient.request.stopCount)
            assertEquals(1, insufficient.available)
            assertEquals(origin, insufficient.request.origin)

            viewModel.setStopCount(2)
            val editing = assertIs<RouteUiState.Editing>(viewModel.routeState.value)
            assertEquals(2, editing.request.stopCount)
        }

    @Test
    fun `ready route and started status survive returning with the same origin`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val viewModel =
                createViewModel(
                    exhibitions =
                        listOf(
                            exhibition("a", latitude = 37.5705, longitude = 126.9805),
                            exhibition("b", latitude = 37.5710, longitude = 126.9810),
                        ),
                )
            advanceUntilIdle()
            viewModel.beginRouteIfNeeded(origin)
            viewModel.setStopCount(2)
            viewModel.buildRoute()
            advanceUntilIdle()
            val ready = assertIs<RouteUiState.Ready>(viewModel.routeState.value)

            assertTrue(viewModel.markRouteStarted())
            assertFalse(viewModel.markRouteStarted())
            val started = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
            assertEquals(ready.buildId, started.buildId)
            assertTrue(started.hasStarted)

            viewModel.beginRouteIfNeeded(origin)
            assertSame(started, viewModel.routeState.value)

            viewModel.beginRouteIfNeeded(
                origin = origin,
                initialMode = RouteCurationMode.SAVED,
                requestId = 2L,
            )
            val newSession = assertIs<RouteUiState.Editing>(viewModel.routeState.value)
            assertEquals(RouteCurationMode.SAVED, newSession.request.mode)

            val newOrigin = GeoPoint(37.5800, 126.9900)
            viewModel.beginRouteIfNeeded(newOrigin, RouteCurationMode.SAVED)
            val editing = assertIs<RouteUiState.Editing>(viewModel.routeState.value)
            assertEquals(newOrigin, editing.request.origin)
            assertEquals(RouteCurationMode.SAVED, editing.request.mode)
        }

    @Test
    fun `recommendation preparation is dispatched instead of running inline on Main`() =
        runTest(dispatcher) {
            val backgroundDispatcher = StandardTestDispatcher(testScheduler)
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    recommender = recommender,
                    backgroundDispatcher = backgroundDispatcher,
                )

            assertEquals(0, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Loading>(viewModel.recommendationState.value)

            runCurrent()

            assertEquals(1, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `route planning is dispatched instead of running inline on Main`() =
        runTest(dispatcher) {
            val backgroundDispatcher = StandardTestDispatcher(testScheduler)
            var estimateCalls = 0
            val routePlanner =
                NeighborhoodRoutePlanner(
                    RouteLegEstimator { _, _ ->
                        estimateCalls += 1
                        EstimatedLeg(distanceMeters = 100, travelMinutes = 2)
                    },
                )
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a"), exhibition("b")),
                    backgroundDispatcher = backgroundDispatcher,
                    routePlanner = routePlanner,
                )
            runCurrent()

            viewModel.beginRouteIfNeeded(GeoPoint(37.5700, 126.9800))
            viewModel.setStopCount(2)
            viewModel.buildRoute()

            assertEquals(0, estimateCalls)
            assertIs<RouteUiState.Planning>(viewModel.routeState.value)

            runCurrent()

            assertTrue(estimateCalls > 0)
            assertIs<RouteUiState.Ready>(viewModel.routeState.value)
        }

    private fun createViewModel(
        exhibitions: List<Exhibition> = emptyList(),
        exhibitionsState: StateFlowExhibitions = MutableStateFlow(ExhibitionListState.Success(exhibitions)),
        bookmarks: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        visits: VisitRepository = LocalDiscoveryVisitRepository(),
        follows: FollowedGalleryRepository = LocalDiscoveryFollowedGalleryRepository(),
        language: MutableStateFlow<AppLanguage> = MutableStateFlow(AppLanguage.KO),
        recommender: RecordingRecommender = RecordingRecommender(),
        backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = dispatcher,
        now: Instant = Instant.parse("2026-08-30T02:00:00Z"),
        routePlanner: NeighborhoodRoutePlanner =
            NeighborhoodRoutePlanner(
                RouteLegEstimator { _, _ -> EstimatedLeg(distanceMeters = 100, travelMinutes = 2) },
            ),
        walking: FakeWalkingRouteRepository? = null,
        taste: TastePreferenceRepository? = null,
        historySettled: Flow<Boolean> = flowOf(true),
        todayProvider: () -> LocalDate = { today },
    ) = LocalDiscoveryViewModel(
        exhibitionsState = exhibitionsState,
        bookmarkedIds = bookmarks,
        visitRepository = visits,
        followedGalleryRepository = follows,
        language = language,
        backgroundDispatcher = backgroundDispatcher,
        todayProvider = todayProvider,
        nowProvider = { now },
        recommender = recommender,
        routePlanner = routePlanner,
        walkingRoutes = walking,
        tastePreferences = taste,
        historySettled = historySettled,
    )

    private fun exhibition(
        id: String,
        latitude: Double = 37.5705,
        longitude: Double = 126.9805,
        closingDate: LocalDate = LocalDate(2026, 9, 30),
        hours: String? = null,
        artTerms: List<ArtTerm> = emptyList(),
        artists: List<ExhibitionArtist> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "장소 $id",
        venueNameEn = "Venue $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = closingDate,
        isFeatured = true,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "설명 $id",
        descriptionEn = "Description $id",
        addressKo = "주소 $id",
        addressEn = "Address $id",
        coverImageUrl = null,
        hours = hours,
        galleryId = "gallery-$id",
        artTerms = artTerms,
        artists = artists,
    )

    private fun visit(exhibition: Exhibition) =
        ExhibitionVisit(
            clientRecordId = "visit-${exhibition.id}",
            exhibitionId = exhibition.id,
            snapshot = ExhibitionVisitSnapshot.from(exhibition),
            createdAt = Instant.fromEpochMilliseconds(1),
        )

    private fun followedGallery(
        key: String,
        galleryId: String? = null,
    ) = FollowedGallery(
        galleryKey = key,
        snapshot =
            FollowedGallerySnapshot(
                nameKo = key,
                nameEn = key,
                cityKo = "서울",
                cityEn = "Seoul",
                regionKo = "종로구",
                regionEn = "Jongno-gu",
            ),
        knownExhibitionIds = emptySet(),
        followedAt = Instant.fromEpochMilliseconds(1),
        galleryId = galleryId,
    )
}

private typealias StateFlowExhibitions = kotlinx.coroutines.flow.StateFlow<ExhibitionListState>

/** Ranks the catalogue in order; with [hidesDismissed] it leaves out hidden exhibitions as the real ranker does. */
private class RecordingRecommender(
    private val hidesDismissed: Boolean = false,
) : ExhibitionRecommender {
    val prepareCalls = mutableListOf<List<Exhibition>>()
    val previousIndexes = mutableListOf<ExhibitionRecommendationIndex?>()
    val returnedIndexes = mutableListOf<ExhibitionRecommendationIndex>()
    val contexts = mutableListOf<RecommendationContext>()
    val routeContexts = mutableListOf<RouteRelevanceContext>()
    var createdIndexCount = 0
        private set
    var failingPrepareCalls = 0

    override fun prepare(
        catalogue: List<Exhibition>,
        previous: ExhibitionRecommendationIndex?,
    ): ExhibitionRecommendationIndex {
        prepareCalls += catalogue
        previousIndexes += previous
        if (failingPrepareCalls > 0) {
            failingPrepareCalls -= 1
            error("private recommendation detail")
        }
        val reusable = (previous as? RecordingIndex)?.takeIf { it.catalogue == catalogue }
        val result =
            reusable ?: RecordingIndex(catalogue.toList()).also {
                createdIndexCount += 1
                returnedIndexes += it
            }
        return result
    }

    private inner class RecordingIndex(
        val catalogue: List<Exhibition>,
    ) : ExhibitionRecommendationIndex {
        override fun recommend(context: RecommendationContext): List<ExhibitionRecommendation> {
            contexts += context
            val visitedIds = context.visits.mapTo(mutableSetOf()) { it.exhibitionId }
            val hiddenIds = if (hidesDismissed) context.dismissals.hiddenExhibitionIds else emptySet()
            return catalogue
                .filterNot { it.id in context.bookmarkedExhibitionIds || it.id in visitedIds || it.id in hiddenIds }
                .take(context.limit)
                .mapIndexed { index, exhibition ->
                    ExhibitionRecommendation(
                        exhibition = exhibition,
                        scoreBasisPoints = 10_000 - index,
                        evidence = listOf(RecommendationEvidence.Featured),
                    )
                }
        }

        override fun rankRouteCandidates(context: RouteRelevanceContext): List<RouteRelevance> {
            routeContexts += context
            val visitedIds = context.visits.mapTo(mutableSetOf()) { it.exhibitionId }
            return catalogue
                .filterNot { it.id in visitedIds }
                .mapIndexed { index, exhibition ->
                    val saved = exhibition.id in context.bookmarkedExhibitionIds
                    RouteRelevance(
                        exhibition = exhibition,
                        scoreBasisPoints = 10_000 - index,
                        evidence =
                            if (saved) {
                                listOf(RecommendationEvidence.Saved)
                            } else {
                                listOf(RecommendationEvidence.Featured)
                            },
                        hasPersonalEvidence = saved,
                    )
                }
        }
    }
}

private class FakeTastePreferenceRepository : TastePreferenceRepository {
    val current = MutableStateFlow(TastePickState.EMPTY)
    override val state: Flow<TastePickState> = current

    override suspend fun saveSeed(seed: TasteSeed) {
        current.value = current.value.copy(seed = seed, skipped = false)
    }

    override suspend fun markSkipped() {
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

/** Every write but a dismissal succeeds; the dismissal fails as a full disk would. */
private class FailingDismissTasteRepository(
    private val delegate: FakeTastePreferenceRepository = FakeTastePreferenceRepository(),
) : TastePreferenceRepository by delegate {
    override suspend fun dismiss(record: TasteDismissal): Unit = throw IllegalStateException("disk full")
}

/** The first hide fails as a full disk would; later hides and every other write succeed. */
private class FailingOnceDismissTasteRepository(
    val delegate: FakeTastePreferenceRepository = FakeTastePreferenceRepository(),
) : TastePreferenceRepository by delegate {
    private var failed = false

    override suspend fun dismiss(record: TasteDismissal) {
        if (!failed) {
            failed = true
            throw IllegalStateException("disk full")
        }
        delegate.dismiss(record)
    }
}

/** A hide takes a moment to write, as a disk write would; everything else is immediate. */
private class SlowDismissTasteRepository(
    val delegate: FakeTastePreferenceRepository = FakeTastePreferenceRepository(),
) : TastePreferenceRepository by delegate {
    override suspend fun dismiss(record: TasteDismissal) {
        delay(100)
        delegate.dismiss(record)
    }
}

private class LocalDiscoveryVisitRepository : VisitRepository {
    val visits = MutableStateFlow<List<ExhibitionVisit>>(emptyList())

    override fun observeVisits(): Flow<List<ExhibitionVisit>> = visits

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        this.visits.value = (this.visits.value + visits).distinctBy { it.exhibitionId }
    }

    override suspend fun removeVisit(exhibitionId: String) {
        visits.value = visits.value.filterNot { it.exhibitionId == exhibitionId }
    }
}

private class RecoveringLocalDiscoveryVisitRepository : VisitRepository {
    private val recoveredVisits = MutableStateFlow<List<ExhibitionVisit>>(emptyList())
    var subscriptionCount = 0
        private set

    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        flow {
            subscriptionCount += 1
            if (subscriptionCount == 1) error("private repository detail")
            emitAll(recoveredVisits)
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        recoveredVisits.value = visits
    }

    override suspend fun removeVisit(exhibitionId: String) {
        recoveredVisits.value = recoveredVisits.value.filterNot { it.exhibitionId == exhibitionId }
    }
}

private class FailingLocalDiscoveryVisitRepository : VisitRepository {
    var subscriptionCount = 0
        private set

    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        flow {
            subscriptionCount += 1
            error("private persistent repository detail")
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) = Unit

    override suspend fun removeVisit(exhibitionId: String) = Unit
}

private class LocalDiscoveryFollowedGalleryRepository : FollowedGalleryRepository {
    val followed = MutableStateFlow<List<FollowedGallery>>(emptyList())

    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = followed

    override suspend fun followGalleries(galleries: List<FollowedGallery>) {
        followed.value = (followed.value + galleries).distinctBy { it.galleryKey }
    }

    override suspend fun unfollowGallery(galleryKey: String) {
        followed.value = followed.value.filterNot { it.galleryKey == galleryKey }
    }

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) = Unit
}
