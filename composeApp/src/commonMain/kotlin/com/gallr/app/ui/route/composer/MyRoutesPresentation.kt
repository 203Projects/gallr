package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.MyRoutesError
import com.gallr.app.viewmodel.MyRoutesUiState
import com.gallr.app.viewmodel.SavedRoutesState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteDeclineReason
import com.gallr.shared.route.RouteListingBlocker
import com.gallr.shared.route.RouteListingState

/** The actions in a saved route's ⋯ menu (DR-D10). */
internal enum class MyRouteAction { OPEN, SHARE, DELETE }

internal fun myRoutesTitle(language: AppLanguage): String = if (language == AppLanguage.KO) "내 동선" else "MY ROUTES"

/**
 * True when the list has nothing to show, signed out without a draft or signed in with no routes: the empty
 * state's CTA is then the only "new route" action (DR-D10).
 */
internal fun myRoutesShowsEmptyState(state: MyRoutesUiState): Boolean {
    if (state.draftRow != null) return false
    return when (val saved = state.saved) {
        SavedRoutesState.SignedOut -> true
        is SavedRoutesState.Loaded -> saved.routes.isEmpty()
        SavedRoutesState.Loading, SavedRoutesState.Error -> false
    }
}

/** The route sheet lists at most this many saved routes; the MY tab section lists them all (DD3). */
private const val PLANNER_ROUTE_LIMIT = 3

internal fun myRoutesPlannerRows(routes: List<PersonalRouteSummary>): List<PersonalRouteSummary> =
    routes.take(PLANNER_ROUTE_LIMIT)

internal fun myRoutesShowsSeeAll(routes: List<PersonalRouteSummary>): Boolean = routes.size > PLANNER_ROUTE_LIMIT

internal fun myRoutesSeeAllLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "모두 보기" else "SEE ALL"

/** The MY tab section beside 방문 and 팔로잉. */
internal fun myRoutesSectionLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "동선" else "ROUTES"

internal fun myRoutesNewRouteLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "+ 새 동선 만들기" else "+ NEW ROUTE"

internal fun myRouteDisplayName(
    name: String,
    language: AppLanguage,
): String = name.trim().ifEmpty { if (language == AppLanguage.KO) "이름 없는 동선" else "UNTITLED ROUTE" }

/** Line 2 of a saved route's row (DD13): "3곳 · 링크 공개 · 목록 검토 중" — stops, link and public-list state. */
internal fun myRouteRowLabel(
    route: PersonalRouteSummary,
    language: AppLanguage,
): String {
    val count = language.pick("${route.stopCount}곳", "${route.stopCount} STOPS")
    val linkOpen = route.isPublished && !route.isRevoked
    val link = if (linkOpen) language.pick("링크 공개", "LINK SHARED") else language.pick("나만 보기", "ONLY YOU")
    return "$count · $link · ${listingStateLabel(route.listingState, language)}"
}

private fun listingStateLabel(
    state: RouteListingState,
    language: AppLanguage,
): String =
    when (state) {
        RouteListingState.Unlisted -> language.pick("목록 미등록", "NOT LISTED")
        RouteListingState.Requested -> language.pick("목록 검토 중", "IN REVIEW")
        RouteListingState.Approved -> language.pick("목록 승인됨", "APPROVED FOR LISTING")
        RouteListingState.Declined -> language.pick("목록 반려됨", "DECLINED")
        RouteListingState.Removed -> language.pick("목록에서 내려짐", "REMOVED FROM LIST")
    }

/**
 * Line 3 of a saved route's row (DD13, DD16): the single reason it is not shown, by precedence, with a staff note
 * under a decline reason. Empty when nothing blocks the route. A revoked route always says it was taken down.
 */
internal fun myRouteListingReasons(
    route: PersonalRouteSummary,
    language: AppLanguage,
): List<String> {
    val blocker = if (route.isRevoked) RouteListingBlocker.Revoked else route.listingBlocker
    if (blocker == RouteListingBlocker.Declined) return declineLines(route, language)
    val reason = blocker?.let { blockerReason(it, language) } ?: return emptyList()
    return listOf(reason)
}

private fun blockerReason(
    blocker: RouteListingBlocker,
    language: AppLanguage,
): String? =
    when (blocker) {
        RouteListingBlocker.Revoked -> {
            language.pick("운영 정책으로 내려져 링크가 열리지 않아요", "Taken down; the link no longer opens")
        }

        RouteListingBlocker.Removed -> {
            language.pick("운영 정책으로 공개 목록에서 내려졌어요", "Removed from the public list by gallr")
        }

        RouteListingBlocker.EndedStop -> {
            language.pick("종료된 전시가 있어 공개 목록에서 빠졌어요", "A stop has ended, so it's off the list")
        }

        RouteListingBlocker.MissingStop -> {
            language.pick("볼 수 없는 전시가 있어 공개 목록에서 빠졌어요", "A stop is no longer available, so it's off the list")
        }

        RouteListingBlocker.NoSharedDay -> {
            language.pick("모든 전시를 함께 볼 수 있는 날이 없어요", "There's no day to see every stop")
        }

        RouteListingBlocker.Declined -> {
            null
        }
    }

private fun declineLines(
    route: PersonalRouteSummary,
    language: AppLanguage,
): List<String> {
    val note = route.declineNote?.trim()?.takeIf(String::isNotEmpty)
    val reason =
        when (route.declineReason) {
            RouteDeclineReason.NameOrDescription -> {
                language.pick("이름을 고쳐 다시 요청해 주세요", "Change the name and request again")
            }

            RouteDeclineReason.Promotional -> {
                language.pick("홍보성 동선은 공개 목록에 올릴 수 없어요", "Promotional routes can't be listed")
            }

            RouteDeclineReason.Composition -> {
                language.pick("전시를 바꾸거나 줄여 다시 요청해 주세요", "Change or remove stops and request again")
            }

            RouteDeclineReason.Other, null -> {
                note ?: language.pick("검토 결과 목록에 올릴 수 없어요", "This route can't be listed")
            }
        }
    return if (note == null || note == reason) listOf(reason) else listOf(reason, note)
}

/** The listing actions in a saved route's ⋯ menu (R6, DD16). */
internal enum class MyRouteListingAction { LIST, SHARE_AND_LIST, UNLIST, EDIT, REQUEST_AGAIN }

internal fun myRouteListingActions(route: PersonalRouteSummary): List<MyRouteListingAction> {
    if (route.isRevoked) return emptyList()
    return when (route.listingState) {
        RouteListingState.Unlisted -> {
            listOf(if (route.isPublished) MyRouteListingAction.LIST else MyRouteListingAction.SHARE_AND_LIST)
        }

        RouteListingState.Requested, RouteListingState.Approved -> {
            listOf(MyRouteListingAction.UNLIST)
        }

        RouteListingState.Declined -> {
            listOf(MyRouteListingAction.EDIT, MyRouteListingAction.REQUEST_AGAIN)
        }

        RouteListingState.Removed -> {
            emptyList()
        }
    }
}

internal fun myRouteListingActionLabel(
    action: MyRouteListingAction,
    language: AppLanguage,
): String =
    when (action) {
        MyRouteListingAction.LIST -> language.pick("공개 목록에 올리기", "LIST PUBLICLY")
        MyRouteListingAction.SHARE_AND_LIST -> language.pick("공개하고 목록에 올리기", "SHARE AND LIST")
        MyRouteListingAction.UNLIST -> language.pick("목록에서 내리기", "REMOVE FROM LIST")
        MyRouteListingAction.EDIT -> language.pick("수정하기", "EDIT")
        MyRouteListingAction.REQUEST_AGAIN -> language.pick("다시 요청", "REQUEST AGAIN")
    }

internal fun listingConsentTitle(language: AppLanguage): String =
    language.pick("공개 목록에 올릴까요?", "LIST THIS ROUTE PUBLICLY?")

/**
 * The consent dialog's body (DD17): what becomes visible and when; the publish-and-list option also says the link
 * becomes public and stays open even if the request is withdrawn or declined.
 */
internal fun listingConsentBody(
    authorName: String?,
    isEditor: Boolean,
    alsoPublishes: Boolean,
    language: AppLanguage,
): String {
    val name = authorName?.trim()?.takeIf(String::isNotEmpty)
    return when (language) {
        AppLanguage.KO -> {
            val who = if (name == null) "작성자 이름" else "작성자 이름($name)"
            val lead = if (isEditor) "바로 앱의 추천 동선에" else "검토를 거치면 앱의 추천 동선에"
            val link = if (alsoPublishes) " $LINK_STAYS_OPEN_KO" else ""
            "$lead 이 동선과 ${who}이 보여요.$link"
        }

        AppLanguage.EN -> {
            val who = if (name == null) "your name" else "your name ($name)"
            val sentence =
                if (isEditor) {
                    "This route and $who appear in Popular routes right away."
                } else {
                    "Once reviewed, this route and $who appear in Popular routes."
                }
            val link = if (alsoPublishes) " $LINK_STAYS_OPEN_EN" else ""
            "$sentence$link"
        }
    }
}

private const val LINK_STAYS_OPEN_KO = "링크도 함께 공개돼요. 요청을 취소하거나 반려돼도 링크는 계속 열려요."
private const val LINK_STAYS_OPEN_EN =
    "The link becomes public too, and stays open even if you withdraw or it's declined."

internal fun listingConsentConfirmLabel(language: AppLanguage): String = language.pick("올리기", "LIST")

/** The snackbar after a successful listing request (DD14). */
internal fun listingRequestedMessage(
    isEditor: Boolean,
    language: AppLanguage,
): String =
    if (isEditor) {
        language.pick("목록에 올렸어요", "LISTED")
    } else {
        language.pick("검토를 요청했어요 · 보통 하루 안에 처리돼요", "SENT FOR REVIEW · USUALLY WITHIN A DAY")
    }

internal fun listingRequestFailedMessage(language: AppLanguage): String =
    language.pick("요청하지 못했어요", "COULDN’T SEND THE REQUEST")

private fun AppLanguage.pick(
    korean: String,
    english: String,
): String = if (this == AppLanguage.KO) korean else english

internal fun myRoutesDraftLabel(
    stopCount: Int,
    language: AppLanguage,
): String = if (language == AppLanguage.KO) "작성 중 · ${stopCount}곳" else "DRAFT · $stopCount STOPS"

internal fun myRoutesEmptyMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "첫 동선을 만들어 친구에게 보내 보세요" else "Make your first route and send it to a friend"

internal fun myRoutesSignInLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "로그인하고 저장한 동선 보기" else "SIGN IN TO SEE SAVED ROUTES"

internal fun myRouteActionLabel(
    action: MyRouteAction,
    language: AppLanguage,
): String =
    when (action) {
        MyRouteAction.OPEN -> if (language == AppLanguage.KO) "열기" else "OPEN"
        MyRouteAction.SHARE -> if (language == AppLanguage.KO) "공유" else "SHARE"
        MyRouteAction.DELETE -> if (language == AppLanguage.KO) "삭제" else "DELETE"
    }

/**
 * The delete confirm's body under the route's name as the title; a public route also warns that its shared link
 * stops working (DR-D10).
 */
internal fun myRouteDeleteMessage(
    route: PersonalRouteSummary,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            val question = "이 동선을 삭제할까요?"
            if (route.isPublished) "$question 공유한 링크도 더 이상 열리지 않아요" else question
        }

        AppLanguage.EN -> {
            val question = "Delete this route?"
            if (route.isPublished) "$question The shared link will stop working too." else question
        }
    }

internal fun myRoutesCancelLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "취소" else "CANCEL"

internal fun myRoutesErrorMessage(
    error: MyRoutesError,
    language: AppLanguage,
): String =
    when (error) {
        MyRoutesError.OPEN_FAILED -> {
            if (language == AppLanguage.KO) "! 동선을 열지 못했어요 · 다시 시도" else "! COULDN’T OPEN · TRY AGAIN"
        }

        MyRoutesError.SHARE_FAILED -> {
            if (language == AppLanguage.KO) "! 공유하지 못했어요 · 다시 시도" else "! COULDN’T SHARE · TRY AGAIN"
        }

        MyRoutesError.DELETE_FAILED -> {
            if (language == AppLanguage.KO) "! 삭제하지 못했어요 · 다시 시도" else "! COULDN’T DELETE · TRY AGAIN"
        }
    }

internal fun myRoutesLoadFailedMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) "! 내 동선을 불러오지 못했어요" else "! COULDN’T LOAD YOUR ROUTES"

internal fun myRoutesRetryLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "다시 시도" else "RETRY"
