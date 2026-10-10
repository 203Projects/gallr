package com.gallr.app.ui.route.composer

import com.gallr.app.ui.route.estimatedDistanceLabel
import com.gallr.app.ui.route.estimatedDurationLabel
import com.gallr.app.viewmodel.ComposerSaveStatus
import com.gallr.app.viewmodel.RouteActionError
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.EvaluatedStop
import com.gallr.shared.route.MAX_ROUTE_NAME_LENGTH
import com.gallr.shared.route.PersonalRouteEvaluation
import com.gallr.shared.route.PlannedDayReason
import com.gallr.shared.route.RouteSaveProblem
import com.gallr.shared.route.RouteStopVerdict
import com.gallr.shared.route.UndoResult
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil

/**
 * The stop statuses a route can show, named as in `statusLabels` of the spec 089 parity file. The composer and the
 * public page word each status from the same table (re-review Section 2).
 */
internal enum class RouteStatusKind(
    val isBlocking: Boolean,
) {
    ClosedOnPlannedDay(isBlocking = true),
    ArrivesAfterClose(isBlocking = true),
    VisitCutShort(isBlocking = true),
    NotYetOpen(isBlocking = true),
    Ended(isBlocking = true),
    Unavailable(isBlocking = true),
    HoursUnknown(isBlocking = false),
}

/** The label template for [kind]; placeholders are `{arrival}`, `{closes}`, `{minutes}` and `{date}`. */
internal fun routeStatusTemplate(
    kind: RouteStatusKind,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (kind) {
                RouteStatusKind.ClosedOnPlannedDay -> "! 휴관일"
                RouteStatusKind.ArrivesAfterClose -> "! {arrival} 도착 · {closes} 마감"
                RouteStatusKind.VisitCutShort -> "! 관람 {minutes}분밖에 없어요"
                RouteStatusKind.NotYetOpen -> "! {date} 개막"
                RouteStatusKind.Ended -> "! 종료된 전시"
                RouteStatusKind.Unavailable -> "! 더 이상 볼 수 없는 전시"
                RouteStatusKind.HoursUnknown -> "운영 시간 미확인"
            }
        }

        AppLanguage.EN -> {
            when (kind) {
                RouteStatusKind.ClosedOnPlannedDay -> "! CLOSED THAT DAY"
                RouteStatusKind.ArrivesAfterClose -> "! ARRIVES {arrival} · CLOSES {closes}"
                RouteStatusKind.VisitCutShort -> "! ONLY {minutes} MIN TO VISIT"
                RouteStatusKind.NotYetOpen -> "! OPENS {date}"
                RouteStatusKind.Ended -> "! EXHIBITION ENDED"
                RouteStatusKind.Unavailable -> "! NO LONGER LISTED"
                RouteStatusKind.HoursUnknown -> "HOURS UNCONFIRMED"
            }
        }
    }

/** A stop's status line; blocking lines carry error semantics and the "!" prefix (DR-D8). */
internal data class StopStatusLine(
    val text: String,
    val isBlocking: Boolean,
)

/** The status line under a stop, or null when the stop is open for the whole visit. */
internal fun stopStatusLine(
    verdict: RouteStopVerdict,
    language: AppLanguage,
): StopStatusLine? {
    val kind =
        when (verdict) {
            RouteStopVerdict.Open -> return null
            RouteStopVerdict.ClosedOnPlannedDay -> RouteStatusKind.ClosedOnPlannedDay
            is RouteStopVerdict.ArrivesAfterClose -> RouteStatusKind.ArrivesAfterClose
            is RouteStopVerdict.VisitCutShort -> RouteStatusKind.VisitCutShort
            is RouteStopVerdict.NotYetOpen -> RouteStatusKind.NotYetOpen
            RouteStopVerdict.Ended -> RouteStatusKind.Ended
            RouteStopVerdict.Unavailable -> RouteStatusKind.Unavailable
            RouteStopVerdict.HoursUnknown -> RouteStatusKind.HoursUnknown
        }
    var text = routeStatusTemplate(kind, language)
    when (verdict) {
        is RouteStopVerdict.ArrivesAfterClose -> {
            val arrival =
                when {
                    !verdict.afterMidnight -> clock(verdict.arrival)
                    language == AppLanguage.KO -> "자정 이후"
                    else -> "AFTER MIDNIGHT"
                }
            text = text.replace("{arrival}", arrival).replace("{closes}", clock(verdict.closes))
        }

        is RouteStopVerdict.VisitCutShort -> {
            text = text.replace("{minutes}", verdict.minutes.toString())
        }

        is RouteStopVerdict.NotYetOpen -> {
            text = text.replace("{date}", monthDay(verdict.openingDate))
        }

        else -> {}
    }
    return StopStatusLine(text, kind.isBlocking)
}

/** Line 1 of the composer summary: "모두 열림" only when every stop is open (RO1), else the conflict count. */
internal fun composerVerdictLine(
    evaluation: PersonalRouteEvaluation,
    language: AppLanguage,
): String {
    val conflicts = evaluation.conflictCount
    val firstConflict = evaluation.firstConflictIndex
    if (conflicts > 0 && firstConflict != null) {
        val stopNumber = firstConflict + 1
        return when (language) {
            AppLanguage.KO -> "${conflicts}곳 시간 충돌 · ${stopNumber}번"
            AppLanguage.EN -> "$conflicts ${if (conflicts == 1) "CONFLICT" else "CONFLICTS"} · STOP $stopNumber"
        }
    }
    val unknown = evaluation.stops.count { it.verdict == RouteStopVerdict.HoursUnknown }
    if (unknown == 0) return if (language == AppLanguage.KO) "모두 열림" else "ALL OPEN"
    return when (language) {
        AppLanguage.KO -> "충돌 없음 · ${unknown}곳 시간 미확인"
        AppLanguage.EN -> "NO CONFLICTS · $unknown WITH UNCONFIRMED HOURS"
    }
}

/** Line 2 of the composer summary: estimated distance, travel and total time with visits. */
internal fun composerSummaryLine(
    evaluation: PersonalRouteEvaluation,
    language: AppLanguage,
): String {
    val distance = estimatedDistanceLabel(evaluation.totalDistanceMeters, language)
    val travel = estimatedDurationLabel(evaluation.travelMinutes, language)
    val total = estimatedDurationLabel(evaluation.totalMinutes, language)
    return when (language) {
        AppLanguage.KO -> "$distance · 이동 $travel · 총 $total"
        AppLanguage.EN -> "$distance · $travel TRAVEL · $total TOTAL"
    }
}

/**
 * Line 3 of the composer summary: the opening the plan is based on, when it does not start now, and the time to
 * leave, when a starting location is known and leaving is later than now (DR-D31). Null when neither applies.
 */
internal fun composerReferenceDayLine(
    evaluation: PersonalRouteEvaluation,
    today: LocalDate,
    language: AppLanguage,
): String? {
    val reference =
        if (evaluation.plannedDayReason == PlannedDayReason.TODAY_NOW) {
            null
        } else {
            openingReference(evaluation.anchor, today.daysUntil(evaluation.plannedDay), evaluation.plannedDay, language)
        }
    val departure =
        evaluation.departure?.let {
            if (language == AppLanguage.KO) "${clock(it)} 출발" else "LEAVE ${clock(it)}"
        }
    return listOfNotNull(reference, departure).joinToString(" · ").ifEmpty { null }
}

private fun openingReference(
    opening: LocalTime,
    daysAhead: Int,
    day: LocalDate,
    language: AppLanguage,
): String {
    val dayLabel =
        when (daysAhead) {
            0 -> if (language == AppLanguage.KO) "오늘" else "TODAY"
            1 -> if (language == AppLanguage.KO) "내일" else "TOMORROW"
            in 2 until DAYS_PER_WEEK -> weekdayName(day.dayOfWeek, language)
            else -> nextWeekday(day.dayOfWeek, language)
        }
    return when (language) {
        AppLanguage.KO -> "$dayLabel ${clock(opening)} 개관 기준"
        AppLanguage.EN -> "FROM $dayLabel'S ${clock(opening)} OPENING"
    }
}

private fun nextWeekday(
    day: DayOfWeek,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "다음 주 ${weekdayName(day, language)}"
        AppLanguage.EN -> "NEXT ${weekdayName(day, language)}"
    }

private fun weekdayName(
    day: DayOfWeek,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (day) {
                DayOfWeek.MONDAY -> "월요일"
                DayOfWeek.TUESDAY -> "화요일"
                DayOfWeek.WEDNESDAY -> "수요일"
                DayOfWeek.THURSDAY -> "목요일"
                DayOfWeek.FRIDAY -> "금요일"
                DayOfWeek.SATURDAY -> "토요일"
                DayOfWeek.SUNDAY -> "일요일"
            }
        }

        AppLanguage.EN -> {
            day.name
        }
    }

/** The stop's planned visit, e.g. "관람 13:10–13:55", or null when it could not be timed. */
internal fun composerVisitWindow(
    stop: EvaluatedStop,
    language: AppLanguage,
): String? {
    val start = stop.visitStart ?: return null
    val end = stop.visitEnd ?: return null
    val window = "${clock(start)}–${clock(end)}"
    return if (language == AppLanguage.KO) "관람 $window" else "VISIT $window"
}

/** The actions in a stop's ⋯ menu, also offered to screen readers as custom actions (DR-D26). */
internal enum class ComposerStopAction {
    MOVE_UP,
    MOVE_DOWN,
    MOVE_TO_TOP,
    REMOVE,
    ;

    /** Where the stop at [position] moves to, or null when this action does not move it or cannot. */
    fun targetFor(
        position: Int,
        stopCount: Int,
    ): Int? =
        when (this) {
            MOVE_UP, MOVE_TO_TOP -> if (position > 0) (if (this == MOVE_UP) position - 1 else 0) else null
            MOVE_DOWN -> if (position < stopCount - 1) position + 1 else null
            REMOVE -> null
        }
}

internal fun composerStopActionLabel(
    action: ComposerStopAction,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (action) {
                ComposerStopAction.MOVE_UP -> "위로"
                ComposerStopAction.MOVE_DOWN -> "아래로"
                ComposerStopAction.MOVE_TO_TOP -> "맨 위로"
                ComposerStopAction.REMOVE -> "삭제"
            }
        }

        AppLanguage.EN -> {
            when (action) {
                ComposerStopAction.MOVE_UP -> "MOVE UP"
                ComposerStopAction.MOVE_DOWN -> "MOVE DOWN"
                ComposerStopAction.MOVE_TO_TOP -> "MOVE TO TOP"
                ComposerStopAction.REMOVE -> "REMOVE"
            }
        }
    }

/** Spoken after a reorder; [position] is zero-based. */
internal fun composerMovedAnnouncement(
    position: Int,
    language: AppLanguage,
): String = if (language == AppLanguage.KO) "${position + 1}번으로 이동했어요" else "MOVED TO STOP ${position + 1}"

internal fun composerHeaderTitle(
    hasRemoteRoute: Boolean,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> if (hasRemoteRoute) "동선 편집" else "새 동선"
        AppLanguage.EN -> if (hasRemoteRoute) "EDIT ROUTE" else "NEW ROUTE"
    }

internal fun composerEmptyMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "보고 싶은 전시를 2곳 이상 담아 보세요" else "ADD AT LEAST TWO EXHIBITIONS YOU WANT TO SEE"

internal fun composerOneStopMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "1곳 더 추가하면 저장할 수 있어요" else "ADD ONE MORE TO SAVE"

internal fun composerAddExhibitionsLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "+ 전시 추가" else "+ ADD EXHIBITIONS"

internal fun composerSaveLabel(
    isPublished: Boolean,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> if (isPublished) "변경사항 저장" else "저장"
        AppLanguage.EN -> if (isPublished) "SAVE CHANGES" else "SAVE"
    }

internal fun composerShareLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "공유" else "SHARE"

internal fun composerNameLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "동선 이름" else "ROUTE NAME"

/** The name field's error for [problem], or null when the problem is not about the name. */
internal fun composerNameError(
    problem: RouteSaveProblem?,
    language: AppLanguage,
): String? =
    when (problem) {
        RouteSaveProblem.NAME_EMPTY -> {
            if (language == AppLanguage.KO) "! 이름을 입력하세요" else "! ENTER A NAME"
        }

        RouteSaveProblem.NAME_TOO_LONG -> {
            if (language == AppLanguage.KO) {
                "! ${MAX_ROUTE_NAME_LENGTH}자 이내"
            } else {
                "! $MAX_ROUTE_NAME_LENGTH CHARACTERS MAX"
            }
        }

        else -> {
            null
        }
    }

internal fun composerRemovedMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "삭제했어요" else "REMOVED"

internal fun composerUndoLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "되돌리기" else "UNDO"

/** Why an undo could not restore the stop (RO4), or null when there is nothing to tell. */
internal fun composerUndoFailure(
    result: UndoResult,
    language: AppLanguage,
): String? =
    when (result) {
        UndoResult.Full -> if (language == AppLanguage.KO) "되돌릴 수 없어요 · 동선이 가득 찼어요" else "CAN’T UNDO · ROUTE IS FULL"
        UndoResult.Duplicate -> if (language == AppLanguage.KO) "이미 동선에 있어요" else "ALREADY IN YOUR ROUTE"
        UndoResult.Restored, UndoResult.Expired -> null
    }

/**
 * The line above the bottom bar (DR-D15): before the first share, who can see a shared route; once public and
 * saved, that the public page shows this version. Null otherwise.
 */
internal fun composerPublicNote(
    isPublished: Boolean,
    isSaved: Boolean,
    authorName: String?,
    language: AppLanguage,
): String? {
    if (isPublished) {
        if (!isSaved) return null
        return if (language == AppLanguage.KO) "저장됨 · 공개 페이지에 반영" else "SAVED · LIVE ON THE PUBLIC PAGE"
    }
    val name = authorName?.takeIf(String::isNotBlank)
    return when (language) {
        AppLanguage.KO -> {
            val who = if (name == null) "작성자 이름" else "작성자 이름($name)"
            "공유하면 링크를 가진 누구나 이 동선과 ${who}을 볼 수 있어요"
        }

        AppLanguage.EN -> {
            val who = if (name == null) "YOUR NAME" else "YOUR NAME ($name)"
            "ANYONE WITH THE LINK CAN SEE THIS ROUTE AND $who"
        }
    }
}

internal fun composerSaveStatusLabel(
    status: ComposerSaveStatus,
    language: AppLanguage,
): String =
    when (status) {
        ComposerSaveStatus.SAVED -> if (language == AppLanguage.KO) "저장됨" else "SAVED"
        ComposerSaveStatus.UNSAVED -> if (language == AppLanguage.KO) "저장 안 됨" else "UNSAVED"
    }

internal fun composerSavingLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "저장 중…" else "SAVING…"

/** The line above the bottom bar while a listed route has unsaved changes (DD15); replaces the copied note. */
internal fun composerListingWarning(language: AppLanguage): String =
    if (language == AppLanguage.KO) {
        "저장하면 목록에서 빠지고 다시 검토를 받아요"
    } else {
        "Saving removes it from the list until it's reviewed again"
    }

/** After copying a listed route, until the first save or edit (DD9). */
internal fun composerCopiedNote(language: AppLanguage): String =
    if (language == AppLanguage.KO) "복사했어요 · 저장하면 내 동선에 남아요" else "COPIED · SAVE TO KEEP IT IN MY ROUTES"

internal fun composerShareReadyMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "공유 준비됐어요" else "READY TO SHARE"

/** The line shown above the bottom bar after a failed save or share; name problems show on the field instead. */
internal fun composerActionErrorMessage(
    error: RouteActionError,
    language: AppLanguage,
): String? =
    when (error) {
        RouteActionError.SaveFailed -> {
            if (language == AppLanguage.KO) "! 저장하지 못했어요 · 다시 시도" else "! COULDN’T SAVE · TRY AGAIN"
        }

        RouteActionError.Revoked -> {
            if (language == AppLanguage.KO) {
                "! 이 동선은 운영 정책에 따라 내려졌어요 · 저장하면 새 동선이 돼요"
            } else {
                "! THIS ROUTE WAS TAKEN DOWN · SAVING CREATES A NEW ROUTE"
            }
        }

        is RouteActionError.BlockedStops -> {
            val count = error.exhibitionIds.size
            if (language == AppLanguage.KO) {
                "! 더 이상 볼 수 없는 전시 ${count}개를 빼야 새 동선으로 저장할 수 있어요"
            } else {
                "! REMOVE $count ${exhibitionsWord(count)} NO LONGER LISTED TO SAVE"
            }
        }

        is RouteActionError.MissingLocation -> {
            val count = error.exhibitionIds.size
            if (language == AppLanguage.KO) {
                "! 위치 정보가 없는 전시 ${count}개를 빼야 저장할 수 있어요"
            } else {
                "! REMOVE $count ${exhibitionsWord(count)} WITHOUT A LOCATION TO SAVE"
            }
        }

        is RouteActionError.Invalid -> {
            null
        }
    }

internal fun composerRemoveAndSaveLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "빼고 저장" else "REMOVE AND SAVE"

internal fun createPersonalRouteLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "내 동선 만들기" else "CREATE MY ROUTE"

internal fun copyToPersonalRouteLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "내 동선으로 복사" else "COPY TO MY ROUTE"

internal fun replaceDraftTitle(language: AppLanguage): String =
    if (language == AppLanguage.KO) "저장하지 않은 변경사항이 있어요" else "YOU HAVE UNSAVED CHANGES"

internal fun replaceDraftDiscardLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "버리기" else "DISCARD"

internal fun replaceDraftKeepLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "계속 편집" else "KEEP EDITING"

internal fun composerStartFromLocationLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "현재 위치에서 출발" else "START FROM MY LOCATION"

private fun clock(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

/** "10.24": month and day as the status lines print them in both languages. */
private fun monthDay(date: LocalDate): String = "${date.month.ordinal + 1}.${date.day}"

private const val DAYS_PER_WEEK = 7

private fun exhibitionsWord(count: Int): String = if (count == 1) "EXHIBITION" else "EXHIBITIONS"
