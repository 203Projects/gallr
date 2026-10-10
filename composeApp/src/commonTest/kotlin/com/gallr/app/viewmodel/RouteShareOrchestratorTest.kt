package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.RouteSaveProblem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Spec 089 US2: save, publish and share with sign-in only when needed (E-D8, E-D16, E-D18, RR1, RO2, RO3). */
class RouteShareOrchestratorTest {
    private val now = Instant.parse("2026-10-08T04:10:00Z")
    private val signedIn = AuthState.Authenticated(GallrUser("account-1", "hanshin", null))
    private val otherAccount = AuthState.Authenticated(GallrUser("account-2", "someone", null))

    @Test
    fun sharingAnUnsavedDraftSavesPublishesAndBuildsTheVersionedLink() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(3), name = " 삼청동 산책 ")
            val routes = FakePersonalRouteRepository()

            val outcome = assertIs<RouteActionOutcome.ReadyToShare>(orchestrator(drafts, routes).share(signedIn))

            assertEquals(listOf("route-1"), routes.saved.map { it.id })
            assertEquals(listOf("route-1"), routes.published)
            val revision = Instant.parse("2026-10-08T04:00:00Z").epochSeconds
            assertEquals("https://gallrmap.com/route/route-1?s=share&v=$revision", outcome.payload.link)
            assertEquals("삼청동 산책", outcome.payload.route.name)
            assertTrue(outcome.payload.route.isPublished)
            assertTrue(drafts.draft.value.isSaved)
            assertTrue(drafts.draft.value.route.isPublished)
        }

    @Test
    fun anAlreadyPublishedRouteIsOnlySaved() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository().apply { markAlreadyPublished("route-1") }

            assertIs<RouteActionOutcome.ReadyToShare>(orchestrator(drafts, routes).share(signedIn))

            assertEquals(1, routes.saved.size)
            assertTrue(routes.published.isEmpty())
        }

    @Test
    fun savingDoesNotPublish() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()

            val outcome = assertIs<RouteActionOutcome.Saved>(orchestrator(drafts, routes).save(signedIn))

            assertEquals("route-1", outcome.route.id)
            assertTrue(routes.published.isEmpty())
            assertEquals("account-1", drafts.draft.value.ownerAccountId)
        }

    @Test
    fun signedOutSetsAPendingActionAndAsksForSignIn() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()

            val outcome = orchestrator(drafts, routes).share(AuthState.Anonymous)

            assertEquals(RouteActionOutcome.SignInRequired, outcome)
            assertEquals(PendingKind.SHARE, drafts.pendingKind())
            assertTrue(routes.saved.isEmpty())
        }

    @Test
    fun aPendingSaveCompletesOnTheNextSignIn() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()
            val orchestrator = orchestrator(drafts, routes)
            orchestrator.save(AuthState.Anonymous)

            assertIs<RouteActionOutcome.Saved>(orchestrator.resumePending(signedIn))
            assertNull(drafts.draft.value.pendingAction)
            assertTrue(drafts.draft.value.isSaved)
        }

    @Test
    fun aPendingShareStopsAtReady() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()
            val orchestrator = orchestrator(drafts, routes)
            orchestrator.share(AuthState.Anonymous)

            val outcome = assertIs<RouteActionOutcome.ReadyToShare>(orchestrator.resumePending(signedIn))
            assertTrue(outcome.resumed)
            assertEquals(listOf("route-1"), routes.published)
        }

    @Test
    fun aPendingCopyIsLeftForThePublicRoutePreview() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()
            drafts.setPending(PendingKind.COPY, routeId = "p1")

            assertNull(orchestrator(drafts, routes).resumePending(signedIn))
            assertEquals(PendingKind.COPY, drafts.pendingKind())
            assertTrue(routes.saved.isEmpty())
        }

    @Test
    fun nothingRunsAfterAnEditACancelOrExpiry() =
        runTest {
            val routes = FakePersonalRouteRepository()

            val edited = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            orchestrator(edited, routes).share(AuthState.Anonymous)
            edited.rename("다른 이름")
            assertNull(orchestrator(edited, routes).resumePending(signedIn))

            val cancelled = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            orchestrator(cancelled, routes).share(AuthState.Anonymous)
            orchestrator(cancelled, routes).cancelPending()
            assertNull(orchestrator(cancelled, routes).resumePending(signedIn))

            val expired = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            expired.pendingCreatedAt = now - 31.minutes
            orchestrator(expired, routes).share(AuthState.Anonymous)
            assertNull(orchestrator(expired, routes).resumePending(signedIn))
            assertNull(expired.draft.value.pendingAction)

            assertTrue(routes.saved.isEmpty())
        }

    @Test
    fun aRouteOfAnotherAccountIsSavedAsANewRoute() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()
            orchestrator(drafts, routes).save(signedIn)

            assertIs<RouteActionOutcome.Saved>(orchestrator(drafts, routes).save(otherAccount))

            assertEquals(1, drafts.detachCount)
            assertNotEquals(routes.saved.first().id, routes.saved.last().id)
            assertEquals("account-2", drafts.draft.value.ownerAccountId)
        }

    @Test
    fun aNotOwnerRejectionDetachesAndSavesAsNew() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository().apply { saveFailures += PersonalRouteFailure.NotOwner }

            assertIs<RouteActionOutcome.Saved>(orchestrator(drafts, routes).save(signedIn))

            assertEquals(1, drafts.detachCount)
            assertEquals(listOf("route-1", "route-detached-1"), routes.saved.map { it.id })
        }

    @Test
    fun aRevokedRouteIsReportedAndForgotten() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository().apply { saveFailures += PersonalRouteFailure.Revoked }

            val outcome = assertIs<RouteActionOutcome.Failed>(orchestrator(drafts, routes).share(signedIn))

            assertEquals(RouteActionError.Revoked, outcome.error)
            assertEquals(1, drafts.detachCount)
            assertTrue(routes.published.isEmpty())
        }

    @Test
    fun unavailableStopsAreNamedForTheBlockedStopPrompt() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(3), name = "동선")
            val routes = FakePersonalRouteRepository()
            routes.saveFailures += PersonalRouteFailure.UnavailableStops(listOf("e1"))

            val outcome = assertIs<RouteActionOutcome.Failed>(orchestrator(drafts, routes).save(signedIn))

            assertEquals(RouteActionError.BlockedStops(listOf("e1")), outcome.error)
        }

    @Test
    fun missingLocationsAreAnError() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository()
            routes.saveFailures += PersonalRouteFailure.MissingLocation(listOf("e0"))

            val outcome = assertIs<RouteActionOutcome.Failed>(orchestrator(drafts, routes).save(signedIn))

            assertEquals(RouteActionError.MissingLocation(listOf("e0")), outcome.error)
        }

    @Test
    fun aNetworkFailureKeepsTheDraftAndProducesNoLink() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository().apply { saveFailures += PersonalRouteFailure.Network }

            val outcome = assertIs<RouteActionOutcome.Failed>(orchestrator(drafts, routes).share(signedIn))

            assertEquals(RouteActionError.SaveFailed, outcome.error)
            assertEquals(2, drafts.draft.value.route.stops.size)
            assertTrue(routes.published.isEmpty())
            assertTrue(!drafts.draft.value.isSaved)
        }

    @Test
    fun anExpiredSessionAsksForSignInAgain() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository().apply { saveFailures += PersonalRouteFailure.Unauthenticated }

            assertEquals(RouteActionOutcome.SignInRequired, orchestrator(drafts, routes).save(signedIn))
            assertEquals(PendingKind.SAVE, drafts.pendingKind())
        }

    @Test
    fun anEmptyNameIsCaughtBeforeTheServer() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "  ")
            val routes = FakePersonalRouteRepository()

            val outcome = assertIs<RouteActionOutcome.Failed>(orchestrator(drafts, routes).save(signedIn))

            assertEquals(RouteActionError.Invalid(RouteSaveProblem.NAME_EMPTY), outcome.error)
            assertTrue(routes.saved.isEmpty())
        }

    @Test
    fun aFirstPublishAndAnOpenedSheetAreCountedOnce() =
        runTest {
            val analytics = RecordingRouteAnalytics()
            val drafts = FakePersonalRouteDraftRepository(stops(3), name = "동선")
            val orchestrator = orchestrator(drafts, FakePersonalRouteRepository(), analytics)

            val first = assertIs<RouteActionOutcome.ReadyToShare>(orchestrator.share(signedIn))
            orchestrator.shareOpened(first.payload)
            val again = assertIs<RouteActionOutcome.ReadyToShare>(orchestrator.share(signedIn))
            orchestrator.shareOpened(again.payload)

            assertEquals(listOf("published:3", "shared:3", "shared:3"), analytics.events)
        }

    @Test
    fun savingAloneCountsNothing() =
        runTest {
            val analytics = RecordingRouteAnalytics()
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")

            orchestrator(drafts, FakePersonalRouteRepository(), analytics).save(signedIn)

            assertTrue(analytics.events.isEmpty())
        }

    @Test
    fun aSavedRouteFromTheListIsPublishedThenShared() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "다른 초안")
            val routes = FakePersonalRouteRepository()
            routes.save(PersonalRoute(id = "route-9", name = "저장된 동선", stops = stops(3)))

            val shared = orchestrator(drafts, routes).shareSaved("route-9", signedIn)
            val outcome = assertIs<RouteActionOutcome.ReadyToShare>(shared)

            assertEquals(listOf("route-9"), routes.published)
            assertTrue(outcome.payload.link.startsWith("https://gallrmap.com/route/route-9?s=share&v="))
            assertEquals("다른 초안", drafts.draft.value.route.name, "the open draft is left alone")
        }

    @Test
    fun anAlreadyPublishedSavedRouteIsReadNotPublishedAgain() =
        runTest {
            val routes = FakePersonalRouteRepository()
            routes.save(PersonalRoute(id = "route-9", name = "저장된 동선", stops = stops(3)))
            routes.markAlreadyPublished("route-9")

            val outcome =
                assertIs<RouteActionOutcome.ReadyToShare>(
                    orchestrator(FakePersonalRouteDraftRepository(), routes).shareSaved("route-9", signedIn),
                )

            assertTrue(routes.published.isEmpty())
            assertEquals(listOf("route-9"), routes.loaded)
            assertTrue(outcome.payload.route.isPublished)
        }

    @Test
    fun sharingFromTheListWhileSignedOutAsksForSignIn() =
        runTest {
            val routes = FakePersonalRouteRepository()

            assertEquals(
                RouteActionOutcome.SignInRequired,
                orchestrator(FakePersonalRouteDraftRepository(), routes).shareSaved("route-9", AuthState.Anonymous),
            )
        }

    @Test
    fun editsMadeDuringTheSaveStayUnsaved() =
        runTest {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = EditingDuringSave(drafts)

            assertIs<RouteActionOutcome.Saved>(orchestrator(drafts, routes.repository).save(signedIn))

            assertEquals("새 이름", drafts.draft.value.route.name)
            assertTrue(!drafts.draft.value.isSaved)
            assertEquals("account-1", drafts.draft.value.ownerAccountId)
        }

    /** Renames the draft while the save request is in flight. */
    private class EditingDuringSave(
        private val drafts: FakePersonalRouteDraftRepository,
        private val delegate: FakePersonalRouteRepository = FakePersonalRouteRepository(),
    ) : PersonalRouteRepository by delegate {
        val repository: PersonalRouteRepository get() = this

        override suspend fun save(route: PersonalRoute): Result<PersonalRoute> =
            delegate.save(route).also { drafts.rename("새 이름") }
    }

    private fun FakePersonalRouteDraftRepository.pendingKind(): PendingKind? {
        val pending = draft.value.pendingAction ?: return null
        return pending.kind
    }

    private fun orchestrator(
        drafts: FakePersonalRouteDraftRepository,
        routes: PersonalRouteRepository,
        analytics: RouteAnalytics = RouteAnalytics.None,
    ) = RouteShareOrchestrator(
        draftRepository = drafts,
        routeRepository = routes,
        analytics = analytics,
        clock =
            object : Clock {
                override fun now(): Instant = now
            },
    )

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
}
