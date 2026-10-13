package com.gallr.app.viewmodel

import com.gallr.app.ui.route.composer.composerListingWarning
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteListingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Spec 089 US7 (DD15, R10): editing a listed route warns that saving sends it back to review. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerListingWarningTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-08T04:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun anApprovedRouteWarnsOnceItHasUnsavedChanges() =
        runTest(dispatcher) {
            val (drafts, composer) = composerFor(RouteListingState.Approved)

            assertFalse(composer.state.value.listingWarning, "a saved route has nothing to warn about")
            drafts.rename("새 이름")
            advanceUntilIdle()

            assertTrue(composer.state.value.listingWarning)
        }

    @Test
    fun aFailedFirstReadIsRetriedWhenTheRouteIsEdited() =
        runTest(dispatcher) {
            // The composer is created before sign-in is ready, so its first read of the row fails.
            val routesBox = mutableListOf<FakePersonalRouteRepository>()
            val (drafts, composer) =
                composerFor(RouteListingState.Approved) { routes ->
                    routes.listFailure = PersonalRouteFailure.Network
                    routesBox += routes
                }
            routesBox.single().listFailure = null

            drafts.rename("새 이름")
            advanceUntilIdle()

            assertTrue(composer.state.value.listingWarning)
        }

    @Test
    fun aRouteAlreadyEditedIsReadAgainOnceSignInIsReady() =
        runTest(dispatcher) {
            val auth = MutableStateFlow<AuthState>(AuthState.Loading)
            val routesBox = mutableListOf<FakePersonalRouteRepository>()
            val (drafts, composer) =
                composerFor(RouteListingState.Approved, auth = auth) { routes ->
                    routes.listFailure = PersonalRouteFailure.Network
                    routesBox += routes
                }
            drafts.rename("새 이름")
            advanceUntilIdle()
            assertFalse(composer.state.value.listingWarning)

            routesBox.single().listFailure = null
            auth.value = signedIn().value
            advanceUntilIdle()

            assertTrue(composer.state.value.listingWarning)
        }

    @Test
    fun aRouteWaitingForReviewWarnsToo() =
        runTest(dispatcher) {
            val (drafts, composer) = composerFor(RouteListingState.Requested)
            drafts.rename("새 이름")
            advanceUntilIdle()

            assertTrue(composer.state.value.listingWarning)
        }

    @Test
    fun editorsAndUnlistedRoutesAreNotWarned() =
        runTest(dispatcher) {
            for ((state, editor) in listOf(
                RouteListingState.Approved to true,
                RouteListingState.Unlisted to false,
                RouteListingState.Declined to false,
                RouteListingState.Removed to false,
            )) {
                val (drafts, composer) = composerFor(state, editor)
                drafts.rename("새 이름 $state")
                advanceUntilIdle()

                assertFalse(composer.state.value.listingWarning, "$state, editor $editor")
            }
        }

    @Test
    fun aNewDraftIsNotWarned() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            val drafts = FakePersonalRouteDraftRepository(stops(2))
            val composer = composer(drafts, routes)
            observe(composer)

            drafts.rename("새 동선")
            advanceUntilIdle()

            assertFalse(composer.state.value.listingWarning)
        }

    @Test
    fun theWarningWording() {
        assertEquals("저장하면 목록에서 빠지고 다시 검토를 받아요", composerListingWarning(AppLanguage.KO))
        val english = composerListingWarning(AppLanguage.EN)
        assertEquals("Saving removes it from the list until it's reviewed again", english)
    }

    private suspend fun TestScope.composerFor(
        state: RouteListingState,
        editor: Boolean = false,
        auth: MutableStateFlow<AuthState> = signedIn(),
        beforeComposer: (FakePersonalRouteRepository) -> Unit = {},
    ): Pair<FakePersonalRouteDraftRepository, PersonalRouteComposerViewModel> {
        val route = PersonalRoute(id = "route-a", name = "목록 동선", stops = stops(2), isPublished = true)
        val routes =
            FakePersonalRouteRepository().apply {
                summaries =
                    listOf(
                        PersonalRouteSummary(
                            id = "route-a",
                            name = "목록 동선",
                            stopCount = 2,
                            isPublished = true,
                            isRevoked = false,
                            updatedAt = now,
                            listingState = state,
                            authorIsEditor = editor,
                        ),
                    )
            }
        val drafts = FakePersonalRouteDraftRepository()
        drafts.replace(route, ownerAccountId = "account-1")
        val draft = drafts.draft.value
        drafts.acknowledgeSave(draft.draftId, draft.revision, route, "account-1")
        beforeComposer(routes)
        val composer = composer(drafts, routes, auth)
        observe(composer)
        return drafts to composer
    }

    private fun TestScope.observe(viewModel: PersonalRouteComposerViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun composer(
        drafts: FakePersonalRouteDraftRepository,
        routes: FakePersonalRouteRepository,
        auth: MutableStateFlow<AuthState> = signedIn(),
    ): PersonalRouteComposerViewModel {
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }
        return PersonalRouteComposerViewModel(
            draftRepository = drafts,
            exhibitionsState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(emptyList())),
            language = MutableStateFlow(AppLanguage.KO),
            authState = auth,
            shareOrchestrator = RouteShareOrchestrator(drafts, routes, clock),
            routeRepository = routes,
            backgroundDispatcher = dispatcher,
            clock = clock,
        )
    }

    private fun stops(count: Int) =
        (0 until count).map { index ->
            PersonalRouteStop(
                exhibitionId = "e$index",
                nameKo = "전시 $index",
                nameEn = "Show $index",
                venueNameKo = "공간 $index",
                venueNameEn = "Venue $index",
                point = GeoPoint(37.57 + index / 1_000.0, 126.98),
                regionKo = "종로구",
                regionEn = "Jongno-gu",
                cityKo = "서울",
            )
        }

    private fun signedIn(): MutableStateFlow<AuthState> =
        MutableStateFlow(AuthState.Authenticated(GallrUser("account-1", "하나", null)))
}
