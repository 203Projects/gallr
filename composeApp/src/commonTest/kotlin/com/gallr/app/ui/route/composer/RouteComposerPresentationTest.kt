package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.ComposerSaveStatus
import com.gallr.app.viewmodel.RouteActionError
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.EvaluatedStop
import com.gallr.shared.route.PersonalRouteEvaluation
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PlannedDayReason
import com.gallr.shared.route.RouteSaveProblem
import com.gallr.shared.route.RouteStopVerdict
import com.gallr.shared.route.UndoResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 089 composer copy (DR-D5–DR-D8, DR-D15, DR-D31, RO4). */
class RouteComposerPresentationTest {
    private val today = LocalDate(2026, 10, 8)

    @Test
    fun verdictLineSaysAllOpenOnlyWhenEveryStopIsOpen() {
        val open = evaluation(RouteStopVerdict.Open, RouteStopVerdict.Open)

        assertEquals("모두 열림", composerVerdictLine(open, AppLanguage.KO))
        assertEquals("ALL OPEN", composerVerdictLine(open, AppLanguage.EN))
    }

    @Test
    fun verdictLineCountsConflictsAndNamesTheFirst() {
        val oneConflict =
            evaluation(RouteStopVerdict.Open, RouteStopVerdict.HoursUnknown, RouteStopVerdict.ClosedOnPlannedDay)
        val twoConflicts =
            evaluation(RouteStopVerdict.Open, RouteStopVerdict.Ended, RouteStopVerdict.ClosedOnPlannedDay)

        assertEquals("1곳 시간 충돌 · 3번", composerVerdictLine(oneConflict, AppLanguage.KO))
        assertEquals("1 CONFLICT · STOP 3", composerVerdictLine(oneConflict, AppLanguage.EN))
        assertEquals("2곳 시간 충돌 · 2번", composerVerdictLine(twoConflicts, AppLanguage.KO))
        assertEquals("2 CONFLICTS · STOP 2", composerVerdictLine(twoConflicts, AppLanguage.EN))
    }

    @Test
    fun verdictLineNeverClaimsAllOpenWithUnconfirmedHours() {
        val unknown = evaluation(RouteStopVerdict.Open, RouteStopVerdict.HoursUnknown)

        assertEquals("충돌 없음 · 1곳 시간 미확인", composerVerdictLine(unknown, AppLanguage.KO))
        assertEquals("NO CONFLICTS · 1 WITH UNCONFIRMED HOURS", composerVerdictLine(unknown, AppLanguage.EN))
    }

    @Test
    fun summaryLineGivesDistanceTravelAndTotal() {
        val route =
            evaluation(RouteStopVerdict.Open, RouteStopVerdict.Open)
                .copy(totalDistanceMeters = 1_240, travelMinutes = 18, visitMinutes = 90, waitMinutes = 12)

        assertEquals("약 1.2 KM · 이동 약 18분 · 총 약 2시간", composerSummaryLine(route, AppLanguage.KO))
        assertEquals("~1.2 KM · ~18 MIN TRAVEL · ~2 HR TOTAL", composerSummaryLine(route, AppLanguage.EN))
    }

    @Test
    fun referenceDayNamesTheOpeningAndTheDeparture() {
        val tomorrow =
            evaluation(RouteStopVerdict.Open).copy(
                plannedDay = LocalDate(2026, 10, 9),
                plannedDayReason = PlannedDayReason.LATER_AT_OPENING,
                anchor = LocalTime(11, 0),
                departure = LocalTime(10, 41),
            )

        assertEquals("내일 11:00 개관 기준 · 10:41 출발", composerReferenceDayLine(tomorrow, today, AppLanguage.KO))
        assertEquals(
            "FROM TOMORROW'S 11:00 OPENING · LEAVE 10:41",
            composerReferenceDayLine(tomorrow, today, AppLanguage.EN),
        )
    }

    @Test
    fun referenceDayUsesTheWeekdayBeyondTomorrowAndTodayForALaterOpening() {
        val saturday =
            evaluation(RouteStopVerdict.Open).copy(
                plannedDay = LocalDate(2026, 10, 10),
                plannedDayReason = PlannedDayReason.LATER_AT_OPENING,
                anchor = LocalTime(12, 0),
            )
        val nextThursday = saturday.copy(plannedDay = LocalDate(2026, 10, 15))
        val laterToday =
            evaluation(RouteStopVerdict.Open).copy(
                plannedDayReason = PlannedDayReason.TODAY_AT_OPENING,
                anchor = LocalTime(11, 0),
            )

        assertEquals("토요일 12:00 개관 기준", composerReferenceDayLine(saturday, today, AppLanguage.KO))
        assertEquals("FROM SATURDAY'S 12:00 OPENING", composerReferenceDayLine(saturday, today, AppLanguage.EN))
        assertEquals("다음 주 목요일 12:00 개관 기준", composerReferenceDayLine(nextThursday, today, AppLanguage.KO))
        assertEquals(
            "FROM NEXT THURSDAY'S 12:00 OPENING",
            composerReferenceDayLine(nextThursday, today, AppLanguage.EN),
        )
        assertEquals("오늘 11:00 개관 기준", composerReferenceDayLine(laterToday, today, AppLanguage.KO))
        assertEquals("FROM TODAY'S 11:00 OPENING", composerReferenceDayLine(laterToday, today, AppLanguage.EN))
    }

    @Test
    fun startingNowHasNoReferenceDayAndOnlyALaterDeparture() {
        val now = evaluation(RouteStopVerdict.Open)
        val leaveLater = now.copy(departure = LocalTime(14, 5))

        assertNull(composerReferenceDayLine(now, today, AppLanguage.KO))
        assertEquals("14:05 출발", composerReferenceDayLine(leaveLater, today, AppLanguage.KO))
        assertEquals("LEAVE 14:05", composerReferenceDayLine(leaveLater, today, AppLanguage.EN))
    }

    @Test
    fun statusLinesFollowTheSharedTableAndMarkBlockingOnes() {
        fun ko(verdict: RouteStopVerdict) = stopStatusLine(verdict, AppLanguage.KO)

        fun en(verdict: RouteStopVerdict) = stopStatusLine(verdict, AppLanguage.EN)

        assertNull(ko(RouteStopVerdict.Open))
        assertEquals(StopStatusLine("! 휴관일", isBlocking = true), ko(RouteStopVerdict.ClosedOnPlannedDay))
        assertEquals(
            StopStatusLine("! 17:50 도착 · 17:00 마감", isBlocking = true),
            ko(RouteStopVerdict.ArrivesAfterClose(LocalTime(17, 50), LocalTime(17, 0))),
        )
        assertEquals(
            StopStatusLine("! ARRIVES 17:50 · CLOSES 17:00", isBlocking = true),
            en(RouteStopVerdict.ArrivesAfterClose(LocalTime(17, 50), LocalTime(17, 0))),
        )
        assertEquals(
            StopStatusLine("! 자정 이후 도착 · 18:00 마감", isBlocking = true),
            ko(RouteStopVerdict.ArrivesAfterClose(LocalTime(23, 59), LocalTime(18, 0), afterMidnight = true)),
        )
        assertEquals(
            StopStatusLine("! ARRIVES AFTER MIDNIGHT · CLOSES 18:00", isBlocking = true),
            en(RouteStopVerdict.ArrivesAfterClose(LocalTime(23, 59), LocalTime(18, 0), afterMidnight = true)),
        )
        assertEquals(StopStatusLine("! 관람 20분밖에 없어요", isBlocking = true), ko(RouteStopVerdict.VisitCutShort(20)))
        assertEquals(
            StopStatusLine("! ONLY 20 MIN TO VISIT", isBlocking = true),
            en(RouteStopVerdict.VisitCutShort(20)),
        )
        assertEquals(
            StopStatusLine("! 10.24 개막", isBlocking = true),
            ko(RouteStopVerdict.NotYetOpen(LocalDate(2026, 10, 24))),
        )
        assertEquals(
            StopStatusLine("! OPENS 10.24", isBlocking = true),
            en(RouteStopVerdict.NotYetOpen(LocalDate(2026, 10, 24))),
        )
        assertEquals(StopStatusLine("! 종료된 전시", isBlocking = true), ko(RouteStopVerdict.Ended))
        assertEquals(StopStatusLine("! 더 이상 볼 수 없는 전시", isBlocking = true), ko(RouteStopVerdict.Unavailable))
        assertEquals(StopStatusLine("! NO LONGER LISTED", isBlocking = true), en(RouteStopVerdict.Unavailable))
        assertEquals(StopStatusLine("운영 시간 미확인", isBlocking = false), ko(RouteStopVerdict.HoursUnknown))
        assertEquals(StopStatusLine("HOURS UNCONFIRMED", isBlocking = false), en(RouteStopVerdict.HoursUnknown))
    }

    @Test
    fun headerSaysNewUntilTheRouteExistsOnTheServer() {
        assertEquals("새 동선", composerHeaderTitle(hasRemoteRoute = false, language = AppLanguage.KO))
        assertEquals("동선 편집", composerHeaderTitle(hasRemoteRoute = true, language = AppLanguage.KO))
        assertEquals("NEW ROUTE", composerHeaderTitle(hasRemoteRoute = false, language = AppLanguage.EN))
        assertEquals("EDIT ROUTE", composerHeaderTitle(hasRemoteRoute = true, language = AppLanguage.EN))
    }

    @Test
    fun emptyAndOneStopStatesExplainTheTwoStopMinimum() {
        assertEquals("보고 싶은 전시를 2곳 이상 담아 보세요", composerEmptyMessage(AppLanguage.KO))
        assertEquals("ADD AT LEAST TWO EXHIBITIONS YOU WANT TO SEE", composerEmptyMessage(AppLanguage.EN))
        assertEquals("1곳 더 추가하면 저장할 수 있어요", composerOneStopMessage(AppLanguage.KO))
        assertEquals("ADD ONE MORE TO SAVE", composerOneStopMessage(AppLanguage.EN))
        assertEquals("+ 전시 추가", composerAddExhibitionsLabel(AppLanguage.KO))
        assertEquals("+ ADD EXHIBITIONS", composerAddExhibitionsLabel(AppLanguage.EN))
    }

    @Test
    fun saveLabelChangesOncePublished() {
        assertEquals("저장", composerSaveLabel(isPublished = false, language = AppLanguage.KO))
        assertEquals("변경사항 저장", composerSaveLabel(isPublished = true, language = AppLanguage.KO))
        assertEquals("SAVE", composerSaveLabel(isPublished = false, language = AppLanguage.EN))
        assertEquals("SAVE CHANGES", composerSaveLabel(isPublished = true, language = AppLanguage.EN))
    }

    @Test
    fun nameErrorsCoverOnlyTheNameProblems() {
        assertEquals("! 이름을 입력하세요", composerNameError(RouteSaveProblem.NAME_EMPTY, AppLanguage.KO))
        assertEquals("! 60자 이내", composerNameError(RouteSaveProblem.NAME_TOO_LONG, AppLanguage.KO))
        assertEquals("! ENTER A NAME", composerNameError(RouteSaveProblem.NAME_EMPTY, AppLanguage.EN))
        assertEquals("! 60 CHARACTERS MAX", composerNameError(RouteSaveProblem.NAME_TOO_LONG, AppLanguage.EN))
        assertNull(composerNameError(RouteSaveProblem.TOO_FEW_STOPS, AppLanguage.KO))
        assertNull(composerNameError(null, AppLanguage.KO))
    }

    @Test
    fun removalAndUndoMessages() {
        assertEquals("삭제했어요", composerRemovedMessage(AppLanguage.KO))
        assertEquals("되돌리기", composerUndoLabel(AppLanguage.KO))
        assertEquals("REMOVED", composerRemovedMessage(AppLanguage.EN))
        assertEquals("UNDO", composerUndoLabel(AppLanguage.EN))
        assertEquals("되돌릴 수 없어요 · 동선이 가득 찼어요", composerUndoFailure(UndoResult.Full, AppLanguage.KO))
        assertEquals("이미 동선에 있어요", composerUndoFailure(UndoResult.Duplicate, AppLanguage.KO))
        assertEquals("CAN’T UNDO · ROUTE IS FULL", composerUndoFailure(UndoResult.Full, AppLanguage.EN))
        assertEquals("ALREADY IN YOUR ROUTE", composerUndoFailure(UndoResult.Duplicate, AppLanguage.EN))
        assertNull(composerUndoFailure(UndoResult.Restored, AppLanguage.KO))
        assertNull(composerUndoFailure(UndoResult.Expired, AppLanguage.KO))
    }

    @Test
    fun locationButtonLabel() {
        assertEquals("현재 위치에서 출발", composerStartFromLocationLabel(AppLanguage.KO))
        assertEquals("START FROM MY LOCATION", composerStartFromLocationLabel(AppLanguage.EN))
    }

    @Test
    fun visitWindowShowsWhenTheStopWasTimed() {
        val timed =
            EvaluatedStop(
                stop = stop(0),
                verdict = RouteStopVerdict.Open,
                arrival = LocalTime(13, 5),
                visitStart = LocalTime(13, 10),
                visitEnd = LocalTime(13, 55),
            )
        val untimed = timed.copy(visitStart = null, visitEnd = null)

        assertEquals("관람 13:10–13:55", composerVisitWindow(timed, AppLanguage.KO))
        assertEquals("VISIT 13:10–13:55", composerVisitWindow(timed, AppLanguage.EN))
        assertNull(composerVisitWindow(untimed, AppLanguage.KO))
    }

    @Test
    fun reorderActionsAndTheirAnnouncement() {
        assertEquals(
            listOf("위로", "아래로", "맨 위로", "삭제"),
            ComposerStopAction.entries.map { composerStopActionLabel(it, AppLanguage.KO) },
        )
        assertEquals(
            listOf("MOVE UP", "MOVE DOWN", "MOVE TO TOP", "REMOVE"),
            ComposerStopAction.entries.map { composerStopActionLabel(it, AppLanguage.EN) },
        )
        assertEquals("3번으로 이동했어요", composerMovedAnnouncement(position = 2, language = AppLanguage.KO))
        assertEquals("MOVED TO STOP 3", composerMovedAnnouncement(position = 2, language = AppLanguage.EN))
        assertEquals(1, ComposerStopAction.MOVE_UP.targetFor(position = 2, stopCount = 4))
        assertEquals(3, ComposerStopAction.MOVE_DOWN.targetFor(position = 2, stopCount = 4))
        assertEquals(0, ComposerStopAction.MOVE_TO_TOP.targetFor(position = 2, stopCount = 4))
        assertNull(ComposerStopAction.MOVE_UP.targetFor(position = 0, stopCount = 4))
        assertNull(ComposerStopAction.MOVE_DOWN.targetFor(position = 3, stopCount = 4))
        assertNull(ComposerStopAction.MOVE_TO_TOP.targetFor(position = 0, stopCount = 4))
        assertNull(ComposerStopAction.REMOVE.targetFor(position = 1, stopCount = 4))
    }

    @Test
    fun plannerEntryPointsAndTheReplaceDraftDialog() {
        assertEquals("내 동선 만들기", createPersonalRouteLabel(AppLanguage.KO))
        assertEquals("CREATE MY ROUTE", createPersonalRouteLabel(AppLanguage.EN))
        assertEquals("내 동선으로 복사", copyToPersonalRouteLabel(AppLanguage.KO))
        assertEquals("COPY TO MY ROUTE", copyToPersonalRouteLabel(AppLanguage.EN))
        assertEquals("저장하지 않은 변경사항이 있어요", replaceDraftTitle(AppLanguage.KO))
        assertEquals("버리기", replaceDraftDiscardLabel(AppLanguage.KO))
        assertEquals("계속 편집", replaceDraftKeepLabel(AppLanguage.KO))
        assertEquals("YOU HAVE UNSAVED CHANGES", replaceDraftTitle(AppLanguage.EN))
        assertEquals("DISCARD", replaceDraftDiscardLabel(AppLanguage.EN))
        assertEquals("KEEP EDITING", replaceDraftKeepLabel(AppLanguage.EN))
    }

    @Test
    fun publicStateNotes() {
        assertEquals(
            "공유하면 링크를 가진 누구나 이 동선과 작성자 이름(hanshin)을 볼 수 있어요",
            composerPublicNote(isPublished = false, isSaved = false, authorName = "hanshin", language = AppLanguage.KO),
        )
        assertEquals(
            "공유하면 링크를 가진 누구나 이 동선과 작성자 이름을 볼 수 있어요",
            composerPublicNote(isPublished = false, isSaved = false, authorName = null, language = AppLanguage.KO),
        )
        assertEquals(
            "저장됨 · 공개 페이지에 반영",
            composerPublicNote(isPublished = true, isSaved = true, authorName = "hanshin", language = AppLanguage.KO),
        )
        assertNull(
            composerPublicNote(isPublished = true, isSaved = false, authorName = null, language = AppLanguage.KO),
        )
        assertEquals(
            "ANYONE WITH THE LINK CAN SEE THIS ROUTE AND YOUR NAME (hanshin)",
            composerPublicNote(isPublished = false, isSaved = false, authorName = "hanshin", language = AppLanguage.EN),
        )
        assertEquals(
            "SAVED · LIVE ON THE PUBLIC PAGE",
            composerPublicNote(isPublished = true, isSaved = true, authorName = null, language = AppLanguage.EN),
        )
    }

    @Test
    fun saveStatusAndProgress() {
        assertEquals("저장됨", composerSaveStatusLabel(ComposerSaveStatus.SAVED, AppLanguage.KO))
        assertEquals("저장 안 됨", composerSaveStatusLabel(ComposerSaveStatus.UNSAVED, AppLanguage.KO))
        assertEquals("SAVED", composerSaveStatusLabel(ComposerSaveStatus.SAVED, AppLanguage.EN))
        assertEquals("UNSAVED", composerSaveStatusLabel(ComposerSaveStatus.UNSAVED, AppLanguage.EN))
        assertEquals("저장 중…", composerSavingLabel(AppLanguage.KO))
        assertEquals("SAVING…", composerSavingLabel(AppLanguage.EN))
        assertEquals("공유 준비됐어요", composerShareReadyMessage(AppLanguage.KO))
        assertEquals("READY TO SHARE", composerShareReadyMessage(AppLanguage.EN))
    }

    @Test
    fun aCopiedRouteSaysHowToKeepIt() {
        assertEquals("복사했어요 · 저장하면 내 동선에 남아요", composerCopiedNote(AppLanguage.KO))
        assertEquals("COPIED · SAVE TO KEEP IT IN MY ROUTES", composerCopiedNote(AppLanguage.EN))
    }

    @Test
    fun actionErrorsAndTheirFixes() {
        assertEquals("! 저장하지 못했어요 · 다시 시도", composerActionErrorMessage(RouteActionError.SaveFailed, AppLanguage.KO))
        assertEquals(
            "! COULDN’T SAVE · TRY AGAIN",
            composerActionErrorMessage(RouteActionError.SaveFailed, AppLanguage.EN),
        )
        assertEquals(
            "! 이 동선은 운영 정책에 따라 내려졌어요 · 저장하면 새 동선이 돼요",
            composerActionErrorMessage(RouteActionError.Revoked, AppLanguage.KO),
        )
        assertEquals(
            "! 더 이상 볼 수 없는 전시 2개를 빼야 새 동선으로 저장할 수 있어요",
            composerActionErrorMessage(RouteActionError.BlockedStops(listOf("a", "b")), AppLanguage.KO),
        )
        assertEquals(
            "! 위치 정보가 없는 전시 1개를 빼야 저장할 수 있어요",
            composerActionErrorMessage(RouteActionError.MissingLocation(listOf("a")), AppLanguage.KO),
        )
        assertEquals(
            "! REMOVE 1 EXHIBITION WITHOUT A LOCATION TO SAVE",
            composerActionErrorMessage(RouteActionError.MissingLocation(listOf("a")), AppLanguage.EN),
        )
        assertEquals(
            "! REMOVE 2 EXHIBITIONS NO LONGER LISTED TO SAVE",
            composerActionErrorMessage(RouteActionError.BlockedStops(listOf("a", "b")), AppLanguage.EN),
        )
        assertEquals("빼고 저장", composerRemoveAndSaveLabel(AppLanguage.KO))
        assertEquals("REMOVE AND SAVE", composerRemoveAndSaveLabel(AppLanguage.EN))
        assertNull(composerActionErrorMessage(RouteActionError.Invalid(RouteSaveProblem.NAME_EMPTY), AppLanguage.KO))
    }

    @Test
    fun everyStatusKindHasBothLanguages() {
        RouteStatusKind.entries.forEach { kind ->
            assertTrue(routeStatusTemplate(kind, AppLanguage.KO).isNotBlank())
            assertTrue(routeStatusTemplate(kind, AppLanguage.EN).isNotBlank())
        }
        assertFalse(RouteStatusKind.HoursUnknown.isBlocking)
    }

    private fun evaluation(vararg verdicts: RouteStopVerdict) =
        PersonalRouteEvaluation(
            plannedDay = today,
            plannedDayReason = PlannedDayReason.TODAY_NOW,
            anchor = LocalTime(13, 0),
            departure = null,
            stops =
                verdicts.mapIndexed { index, verdict ->
                    EvaluatedStop(stop(index), verdict, arrival = null, visitStart = null, visitEnd = null)
                },
            legs = emptyList(),
            totalDistanceMeters = 0,
            travelMinutes = 0,
            visitMinutes = 0,
            waitMinutes = 0,
        )

    private fun stop(index: Int) =
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
