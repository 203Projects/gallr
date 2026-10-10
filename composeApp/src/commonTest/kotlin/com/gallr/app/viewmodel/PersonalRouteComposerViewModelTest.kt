package com.gallr.app.viewmodel

import com.gallr.app.ui.tabs.map.LocationPermissionStatus
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.CopyIntoDraftResult
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PlannedDayReason
import com.gallr.shared.route.RouteStopVerdict
import com.gallr.shared.route.UndoResult
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Spec 089 US1: the composer renders the observed draft and re-evaluates it after every edit (RR3, RO4, RO6). */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalRouteComposerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Thursday 2026-10-08, 13:00 in Seoul. */
    private val thursdayAfternoon = Instant.parse("2026-10-08T04:00:00Z")

    private val openDaily = exhibition("open", latitude = 37.570, hours = "10am - 6pm")
    private val secondOpen = exhibition("second", latitude = 37.575, hours = "10am - 6pm")
    private val closedThursday = exhibition("closed", latitude = 37.580, hours = "Friday - Wednesday 10am - 6pm")
    private val catalogue = listOf(openDaily, secondOpen, closedThursday)
    private val signedIn = AuthState.Authenticated(GallrUser("account-1", "hanshin", null))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun rendersTheObservedDraftIncludingAppendsMadeElsewhere() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(name = "토요일 삼청동")
            val viewModel = composer(drafts)
            observe(viewModel)

            drafts.append(openDaily)
            drafts.append(secondOpen)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("토요일 삼청동", state.name)
            assertEquals(listOf("open", "second"), state.stops.map { it.exhibitionId })
            assertEquals(2, state.evaluation?.stops?.size)
        }

    @Test
    fun everyEditReEvaluatesWithTheInjectedClockInSeoul() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(closedThursday)))
            val viewModel = composer(drafts)
            observe(viewModel)

            val before = requireNotNull(viewModel.state.value.evaluation)
            assertEquals(LocalDate(2026, 10, 8), before.plannedDay)
            assertEquals(RouteStopVerdict.ClosedOnPlannedDay, before.stops[1].verdict)

            viewModel.move(from = 1, to = 0)
            advanceUntilIdle()

            val after = requireNotNull(viewModel.state.value.evaluation)
            assertEquals(listOf("closed", "open"), viewModel.stopIds())
            assertEquals(PlannedDayReason.LATER_AT_OPENING, after.plannedDayReason)
            assertEquals(LocalDate(2026, 10, 9), after.plannedDay)
            assertEquals(0, after.conflictCount)
        }

    @Test
    fun theCalendarDayIsSeoulsNotUtcs() =
        runTest(dispatcher) {
            // 15:30 UTC on Thursday is 00:30 on Friday in Seoul.
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)))
            val viewModel = composer(drafts, now = Instant.parse("2026-10-08T15:30:00Z"))
            observe(viewModel)

            val evaluation = requireNotNull(viewModel.state.value.evaluation)
            assertEquals(LocalDate(2026, 10, 9), evaluation.plannedDay)
            assertEquals(PlannedDayReason.TODAY_AT_OPENING, evaluation.plannedDayReason)
            assertEquals(LocalDate(2026, 10, 9), viewModel.state.value.today)
        }

    @Test
    fun pickedExhibitionsAreAppendedInOrderThroughTheRepository() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily)))
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.append(listOf(closedThursday, secondOpen))
            advanceUntilIdle()

            assertEquals(listOf("open", "closed", "second"), viewModel.stopIds())
        }

    @Test
    fun aCopiedRouteShowsTheCopiedNoteUntilItIsEdited() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = composer(drafts)
            observe(viewModel)
            val copied = drafts.copyIntoDraft("한남 산책", listOf(stop(openDaily), stop(secondOpen)), "draft-1", 0)
            val draftId = assertIs<CopyIntoDraftResult.Applied>(copied).draftId

            viewModel.noteCopied(draftId)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.copiedNote)

            viewModel.rename("한남 산책 2")
            advanceUntilIdle()
            assertFalse(viewModel.state.value.copiedNote)
        }

    @Test
    fun theCopiedNoteEndsWithTheFirstSave() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = composer(drafts)
            observe(viewModel)
            val copied = drafts.copyIntoDraft("한남 산책", listOf(stop(openDaily), stop(secondOpen)), "draft-1", 0)
            viewModel.noteCopied(assertIs<CopyIntoDraftResult.Applied>(copied).draftId)
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isSaved)
            assertFalse(viewModel.state.value.copiedNote)
        }

    @Test
    fun aNoteForAnotherDraftIsIgnored() =
        runTest(dispatcher) {
            val viewModel = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily)), name = "동선"))
            observe(viewModel)

            viewModel.noteCopied("draft-copy-9")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.copiedNote)
        }

    @Test
    fun aPlannerRouteSeedsANewDraftInItsOrder() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily)), name = "이전 동선")
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.startFromPlanner(listOf(secondOpen, closedThursday))
            advanceUntilIdle()

            assertEquals(listOf("second", "closed"), viewModel.stopIds())
            // A new draft identity lets the name field drop what was typed for the previous draft.
            assertEquals("", viewModel.state.value.name)
            assertTrue(viewModel.state.value.draftId != "draft-1")
        }

    @Test
    fun newDraftsAndOpenedSheetsAreCounted() =
        runTest(dispatcher) {
            val analytics = RecordingRouteAnalytics()
            val drafts = FakePersonalRouteDraftRepository(name = "동선")
            val viewModel = composer(drafts, analytics = analytics)
            observe(viewModel)

            viewModel.append(listOf(openDaily, secondOpen))
            advanceUntilIdle()
            viewModel.startFromPlanner(listOf(secondOpen, closedThursday))
            advanceUntilIdle()
            viewModel.rename("동선")
            viewModel.share()
            advanceUntilIdle()
            viewModel.onShareSheetShown()
            advanceUntilIdle()

            assertEquals(listOf("draft_started", "draft_started", "published:2", "shared:2"), analytics.events)
        }

    @Test
    fun replacingAnUnsavedDraftNeedsConfirmation() =
        runTest(dispatcher) {
            val empty = composer(FakePersonalRouteDraftRepository())
            val unsaved = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily))))
            observe(empty)
            observe(unsaved)

            assertFalse(empty.state.value.replacingNeedsConfirmation)
            assertTrue(unsaved.state.value.replacingNeedsConfirmation)
        }

    @Test
    fun aNewRouteCarriesThePublicNoteWithTheAuthorsName() =
        runTest(dispatcher) {
            val viewModel = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen))))
            observe(viewModel)

            assertEquals("hanshin", viewModel.state.value.authorName)
            assertFalse(viewModel.state.value.isPublished)
            assertNull(viewModel.state.value.saveStatus)
        }

    @Test
    fun savingShowsProgressThenSavedThenUnsavedAfterAnEdit() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            val routes = GatedRouteRepository()
            val viewModel = composer(drafts, routes = routes)
            observe(viewModel)

            viewModel.save()
            assertTrue(viewModel.state.value.isSaving)
            assertFalse(viewModel.state.value.canSave)

            routes.release()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isSaving)
            assertEquals(ComposerSaveStatus.SAVED, viewModel.state.value.saveStatus)

            viewModel.rename("새 이름")
            advanceUntilIdle()
            assertEquals(ComposerSaveStatus.UNSAVED, viewModel.state.value.saveStatus)
        }

    @Test
    fun sharingPublishesAndOpensTheSheetWithTheLink() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.share()
            advanceUntilIdle()

            val payload = requireNotNull(viewModel.state.value.sharePayload)
            assertTrue(payload.link.startsWith("https://gallrmap.com/route/route-1?s=share&v="))
            assertTrue(viewModel.state.value.isPublished)
            assertEquals(ComposerSaveStatus.SAVED, viewModel.state.value.saveStatus)

            viewModel.onShareSheetShown()
            assertNull(viewModel.state.value.sharePayload)
        }

    @Test
    fun signedOutActionsAskForSignInAndResumeAfterIt() =
        runTest(dispatcher) {
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            drafts.pendingCreatedAt = thursdayAfternoon
            val viewModel = composer(drafts, auth = auth)
            observe(viewModel)

            viewModel.share()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.signInRequested)
            viewModel.onSignInRequestHandled()
            assertFalse(viewModel.state.value.signInRequested)

            auth.value = signedIn
            advanceUntilIdle()

            assertTrue(viewModel.state.value.shareReady)
            assertNull(viewModel.state.value.sharePayload)
            assertTrue(viewModel.state.value.isPublished)
        }

    @Test
    fun aPendingSaveResumesWhenTheAppStartsSignedIn() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            drafts.pendingCreatedAt = thursdayAfternoon
            drafts.setPending(com.gallr.shared.route.PendingKind.SAVE)

            val viewModel = composer(drafts)
            observe(viewModel)

            assertEquals(ComposerSaveStatus.SAVED, viewModel.state.value.saveStatus)
            assertFalse(viewModel.state.value.shareReady)
        }

    @Test
    fun saveFailuresAreShownAndTheDraftIsKept() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { saveFailures += PersonalRouteFailure.Network }
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            val viewModel = composer(drafts, routes = routes)
            observe(viewModel)

            viewModel.share()
            advanceUntilIdle()

            assertEquals(RouteActionError.SaveFailed, viewModel.state.value.actionError)
            assertNull(viewModel.state.value.sharePayload)
            assertEquals(2, viewModel.state.value.stops.size)
            viewModel.dismissActionError()
            assertNull(viewModel.state.value.actionError)
        }

    @Test
    fun aRevokedRouteIsExplained() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { saveFailures += PersonalRouteFailure.Revoked }
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            val viewModel = composer(drafts, routes = routes)
            observe(viewModel)

            viewModel.save()
            advanceUntilIdle()

            assertEquals(RouteActionError.Revoked, viewModel.state.value.actionError)
            assertFalse(viewModel.state.value.hasRemoteRoute)
        }

    @Test
    fun blockedStopsAreMarkedAndCanBeRemovedBeforeSaving() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            routes.saveFailures += PersonalRouteFailure.UnavailableStops(listOf("second"))
            val drafts =
                FakePersonalRouteDraftRepository(
                    listOf(stop(openDaily), stop(secondOpen), stop(closedThursday)),
                    name = "동선",
                )
            val viewModel = composer(drafts, routes = routes)
            observe(viewModel)

            viewModel.save()
            advanceUntilIdle()
            assertEquals(RouteActionError.BlockedStops(listOf("second")), viewModel.state.value.actionError)
            assertEquals(setOf("second"), viewModel.state.value.blockedStopIds)

            viewModel.removeBlockedStopsAndSave()
            advanceUntilIdle()

            assertEquals(listOf("open", "closed"), viewModel.stopIds())
            assertNull(viewModel.state.value.actionError)
            assertEquals(ComposerSaveStatus.SAVED, viewModel.state.value.saveStatus)
        }

    @Test
    fun anEmptyNameShowsTheNameError() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "")
            val viewModel = composer(drafts)
            observe(viewModel)
            assertFalse(viewModel.state.value.showNameError)

            viewModel.save()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.showNameError)
            assertNull(viewModel.state.value.actionError)
        }

    @Test
    fun saveAndShareNeedTwoStops() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily)))
            val viewModel = composer(drafts)
            observe(viewModel)

            assertFalse(viewModel.state.value.canSave)
            assertFalse(viewModel.state.value.canShare)

            drafts.append(secondOpen)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.canSave)
            assertTrue(viewModel.state.value.canShare)
        }

    @Test
    fun noEvaluationUntilTheCatalogueHasLoaded() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)))
            val viewModel = composer(drafts, exhibitions = exhibitions)
            observe(viewModel)

            assertNull(viewModel.state.value.evaluation)
            assertEquals(2, viewModel.state.value.stops.size)

            exhibitions.value = ExhibitionListState.Success(catalogue)
            advanceUntilIdle()

            assertEquals(2, viewModel.evaluatedStopCount())
        }

    @Test
    fun removeOffersUndoAndUndoRestores() =
        runTest(dispatcher) {
            val drafts =
                FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen), stop(closedThursday)))
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.remove(position = 1)
            advanceUntilIdle()
            assertIs<ComposerMessage.Removed>(viewModel.state.value.message)
            assertEquals(listOf("open", "closed"), viewModel.stopIds())

            viewModel.undo()
            advanceUntilIdle()
            assertNull(viewModel.state.value.message)
            assertEquals(listOf("open", "second", "closed"), viewModel.stopIds())
        }

    @Test
    fun undoFailuresAreSurfaced() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)))
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.remove(position = 0)
            drafts.forcedUndoResult = UndoResult.Full
            viewModel.undo()
            advanceUntilIdle()
            assertEquals(ComposerMessage.UndoFailed(UndoResult.Full), viewModel.state.value.message)

            viewModel.remove(position = 0)
            drafts.forcedUndoResult = UndoResult.Duplicate
            viewModel.undo()
            advanceUntilIdle()
            assertEquals(ComposerMessage.UndoFailed(UndoResult.Duplicate), viewModel.state.value.message)

            viewModel.dismissMessage()
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun anExpiredUndoClearsTheMessageQuietly() =
        runTest(dispatcher) {
            val drafts =
                FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen), stop(closedThursday)))
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.remove(position = 2)
            viewModel.rename("새 이름")
            viewModel.undo()
            advanceUntilIdle()

            assertNull(viewModel.state.value.message)
            assertEquals(2, viewModel.state.value.stops.size)
        }

    @Test
    fun aDragCommitsOneMoveOnDrop() =
        runTest(dispatcher) {
            val drafts =
                FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen), stop(closedThursday)))
            val viewModel = composer(drafts)
            observe(viewModel)

            viewModel.move(from = 0, to = 2)
            advanceUntilIdle()

            assertEquals(listOf(0 to 2), drafts.moves)
            assertEquals(listOf("second", "closed", "open"), viewModel.stopIds())
        }

    @Test
    fun locationIsUnusedUntilTheScreenReportsIt() =
        runTest(dispatcher) {
            val viewModel = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen))))
            observe(viewModel)

            val state = viewModel.state.value
            assertFalse(state.showStartFromLocation)
            assertFalse(state.usesDeviceOrigin)
            assertEquals(1, state.evaluation?.legs?.size)
        }

    @Test
    fun theLocationButtonShowsOnlyWhileTheSystemCanStillAsk() =
        runTest(dispatcher) {
            val viewModel = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen))))
            observe(viewModel)

            viewModel.updateLocation(LocationPermissionStatus.CAN_ASK, origin = null)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.showStartFromLocation)

            viewModel.updateLocation(LocationPermissionStatus.DENIED_PERMANENTLY, origin = null)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.showStartFromLocation)
            assertFalse(viewModel.state.value.usesDeviceOrigin)
        }

    @Test
    fun aGrantedLocationBecomesTheOrigin() =
        runTest(dispatcher) {
            val viewModel = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen))))
            observe(viewModel)

            viewModel.updateLocation(LocationPermissionStatus.GRANTED, origin = GeoPoint(37.56, 126.97))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.showStartFromLocation)
            assertTrue(state.usesDeviceOrigin)
            assertEquals(2, state.evaluation?.legs?.size)
        }

    @Test
    fun aDeviceLocationWithoutPermissionIsIgnored() =
        runTest(dispatcher) {
            val viewModel = composer(FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen))))
            observe(viewModel)

            viewModel.updateLocation(LocationPermissionStatus.CAN_ASK, origin = GeoPoint(37.56, 126.97))
            advanceUntilIdle()

            assertFalse(viewModel.state.value.usesDeviceOrigin)
            assertEquals(1, viewModel.legCount())
        }

    @Test
    fun headerAndSaveStateFollowTheDraft() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(openDaily), stop(secondOpen)), name = "동선")
            val viewModel = composer(drafts)
            observe(viewModel)
            assertFalse(viewModel.state.value.hasRemoteRoute)
            assertFalse(viewModel.state.value.isPublished)

            val sent = drafts.draft.value
            drafts.acknowledgeSave(sent.draftId, sent.revision, sent.route, ownerAccountId = "account-1")
            drafts.markPublished(sent.draftId, sent.route.copy(isPublished = true))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.hasRemoteRoute)
            assertTrue(viewModel.state.value.isPublished)
            assertTrue(viewModel.state.value.isSaved)
        }

    private fun PersonalRouteComposerViewModel.stopIds() = state.value.stops.map { it.exhibitionId }

    private fun PersonalRouteComposerViewModel.evaluatedStopCount(): Int? {
        val evaluation = state.value.evaluation ?: return null
        return evaluation.stops.size
    }

    private fun PersonalRouteComposerViewModel.legCount(): Int? {
        val evaluation = state.value.evaluation ?: return null
        return evaluation.legs.size
    }

    private fun TestScope.observe(viewModel: PersonalRouteComposerViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun composer(
        drafts: FakePersonalRouteDraftRepository,
        now: Instant = thursdayAfternoon,
        exhibitions: MutableStateFlow<ExhibitionListState> =
            MutableStateFlow(ExhibitionListState.Success(catalogue)),
        routes: PersonalRouteRepository = FakePersonalRouteRepository(),
        auth: MutableStateFlow<AuthState> = MutableStateFlow(signedIn),
        analytics: RouteAnalytics = RouteAnalytics.None,
    ) = PersonalRouteComposerViewModel(
        draftRepository = drafts,
        exhibitionsState = exhibitions,
        language = MutableStateFlow(AppLanguage.KO),
        authState = auth,
        shareOrchestrator = RouteShareOrchestrator(drafts, routes, FixedClock(now), analytics),
        analytics = analytics,
        backgroundDispatcher = dispatcher,
        clock = FixedClock(now),
    )

    /** Holds every save until [release], so the in-flight state can be observed. */
    private class GatedRouteRepository(
        private val delegate: FakePersonalRouteRepository = FakePersonalRouteRepository(),
    ) : PersonalRouteRepository by delegate {
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun save(route: PersonalRoute): Result<PersonalRoute> {
            gate.await()
            return delegate.save(route)
        }
    }

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private fun stop(exhibition: Exhibition) = requireNotNull(exhibition.toRouteStop())

    private fun exhibition(
        id: String,
        latitude: Double,
        hours: String,
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
        openingDate = LocalDate(2026, 9, 1),
        closingDate = LocalDate(2026, 11, 30),
        isFeatured = false,
        latitude = latitude,
        longitude = 126.98,
        descriptionKo = "설명 $id",
        descriptionEn = "Description $id",
        addressKo = "주소 $id",
        addressEn = "Address $id",
        coverImageUrl = null,
        hours = hours,
        galleryId = "gallery-$id",
    )
}
