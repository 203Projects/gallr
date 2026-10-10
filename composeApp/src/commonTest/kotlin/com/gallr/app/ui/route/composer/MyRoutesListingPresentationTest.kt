package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.ListingRefusal
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteDeclineReason
import com.gallr.shared.route.RouteListingBlocker
import com.gallr.shared.route.RouteListingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Spec 089 US7: the 내 동선 row's listing lines, menu, consent dialog and messages (DD12–DD17, D24, D25). */
class MyRoutesListingPresentationTest {
    private val updated = Instant.parse("2026-10-08T04:00:00Z")

    @Test
    fun lineTwoGivesStopsLinkAndListingState() {
        val expected =
            mapOf(
                RouteListingState.Unlisted to ("3곳 · 링크 공개 · 목록 미등록" to "3 STOPS · LINK SHARED · NOT LISTED"),
                RouteListingState.Requested to ("3곳 · 링크 공개 · 목록 검토 중" to "3 STOPS · LINK SHARED · IN REVIEW"),
                RouteListingState.Approved to ("3곳 · 링크 공개 · 목록 승인됨" to "3 STOPS · LINK SHARED · APPROVED FOR LISTING"),
                RouteListingState.Declined to ("3곳 · 링크 공개 · 목록 반려됨" to "3 STOPS · LINK SHARED · DECLINED"),
                RouteListingState.Removed to ("3곳 · 링크 공개 · 목록에서 내려짐" to "3 STOPS · LINK SHARED · REMOVED FROM LIST"),
            )
        for ((state, labels) in expected) {
            val row = summary(published = true, state = state)
            assertEquals(labels.first, myRouteRowLabel(row, AppLanguage.KO))
            assertEquals(labels.second, myRouteRowLabel(row, AppLanguage.EN))
        }
        assertEquals("2곳 · 나만 보기 · 목록 미등록", myRouteRowLabel(summary(stops = 2), AppLanguage.KO))
        assertEquals("2 STOPS · ONLY YOU · NOT LISTED", myRouteRowLabel(summary(stops = 2), AppLanguage.EN))
    }

    @Test
    fun lineThreeGivesOneReasonInPrecedenceOrder() {
        val reasons =
            mapOf(
                RouteListingBlocker.Revoked to ("운영 정책으로 내려져 링크가 열리지 않아요" to "Taken down; the link no longer opens"),
                RouteListingBlocker.Removed to ("운영 정책으로 공개 목록에서 내려졌어요" to "Removed from the public list by gallr"),
                RouteListingBlocker.EndedStop to
                    ("종료된 전시가 있어 공개 목록에서 빠졌어요" to "A stop has ended, so it's off the list"),
                RouteListingBlocker.MissingStop to
                    ("볼 수 없는 전시가 있어 공개 목록에서 빠졌어요" to "A stop is no longer available, so it's off the list"),
                RouteListingBlocker.NoSharedDay to ("모든 전시를 함께 볼 수 있는 날이 없어요" to "There's no day to see every stop"),
            )
        for ((blocker, text) in reasons) {
            val row = summary(published = true, state = RouteListingState.Approved, blocker = blocker)
            assertEquals(listOf(text.first), myRouteListingReasons(row, AppLanguage.KO))
            assertEquals(listOf(text.second), myRouteListingReasons(row, AppLanguage.EN))
        }
        assertEquals(emptyList(), myRouteListingReasons(summary(published = true), AppLanguage.KO))
    }

    @Test
    fun declineReasonsMapToAuthorMessagesWithTheStaffNoteBelow() {
        fun declined(
            reason: RouteDeclineReason,
            note: String? = null,
        ) = summary(
            published = true,
            state = RouteListingState.Declined,
            blocker = RouteListingBlocker.Declined,
            reason = reason,
            note = note,
        )

        val nameFix = declined(RouteDeclineReason.NameOrDescription)
        assertEquals(listOf("이름을 고쳐 다시 요청해 주세요"), myRouteListingReasons(nameFix, AppLanguage.KO))
        assertEquals(
            listOf("홍보성 동선은 공개 목록에 올릴 수 없어요", "문구를 줄여 주세요"),
            myRouteListingReasons(declined(RouteDeclineReason.Promotional, "문구를 줄여 주세요"), AppLanguage.KO),
        )
        assertEquals(
            listOf("Change or remove stops and request again"),
            myRouteListingReasons(declined(RouteDeclineReason.Composition), AppLanguage.EN),
        )
        val otherWithNote = declined(RouteDeclineReason.Other, "사진 설명이 필요해요")
        assertEquals(listOf("사진 설명이 필요해요"), myRouteListingReasons(otherWithNote, AppLanguage.KO))
        val otherWithoutNote = declined(RouteDeclineReason.Other)
        assertEquals(listOf("This route can't be listed"), myRouteListingReasons(otherWithoutNote, AppLanguage.EN))
    }

    @Test
    fun theMenuOffersOnlyTheListingActionsThatApply() {
        assertEquals(listOf(MyRouteListingAction.LIST), myRouteListingActions(summary(published = true)))
        assertEquals(listOf(MyRouteListingAction.SHARE_AND_LIST), myRouteListingActions(summary()))
        assertEquals(
            listOf(MyRouteListingAction.UNLIST),
            myRouteListingActions(summary(published = true, state = RouteListingState.Requested)),
        )
        assertEquals(
            listOf(MyRouteListingAction.UNLIST),
            myRouteListingActions(summary(published = true, state = RouteListingState.Approved)),
        )
        assertEquals(
            listOf(MyRouteListingAction.EDIT, MyRouteListingAction.REQUEST_AGAIN),
            myRouteListingActions(summary(published = true, state = RouteListingState.Declined)),
        )
        assertEquals(emptyList(), myRouteListingActions(summary(published = true, state = RouteListingState.Removed)))
        assertEquals(emptyList(), myRouteListingActions(summary(published = true, revoked = true)))
        assertEquals(
            listOf("공개 목록에 올리기", "공개하고 목록에 올리기", "목록에서 내리기", "수정하기", "다시 요청"),
            MyRouteListingAction.entries.map { myRouteListingActionLabel(it, AppLanguage.KO) },
        )
        assertEquals(
            listOf("LIST PUBLICLY", "SHARE AND LIST", "REMOVE FROM LIST", "EDIT", "REQUEST AGAIN"),
            MyRouteListingAction.entries.map { myRouteListingActionLabel(it, AppLanguage.EN) },
        )
    }

    @Test
    fun theConsentDialogSaysWhatBecomesVisible() {
        assertEquals("공개 목록에 올릴까요?", listingConsentTitle(AppLanguage.KO))
        assertEquals("LIST THIS ROUTE PUBLICLY?", listingConsentTitle(AppLanguage.EN))
        assertEquals(
            "검토를 거치면 앱의 추천 동선에 이 동선과 작성자 이름(하나)이 보여요.",
            listingConsentBody(authorName = "하나", isEditor = false, alsoPublishes = false, language = AppLanguage.KO),
        )
        assertEquals(
            "바로 앱의 추천 동선에 이 동선과 작성자 이름(하나)이 보여요.",
            listingConsentBody(authorName = "하나", isEditor = true, alsoPublishes = false, language = AppLanguage.KO),
        )
        assertEquals(
            "검토를 거치면 앱의 추천 동선에 이 동선과 작성자 이름이 보여요. 링크도 함께 공개돼요. 요청을 취소하거나 반려돼도 링크는 계속 열려요.",
            listingConsentBody(authorName = null, isEditor = false, alsoPublishes = true, language = AppLanguage.KO),
        )
        assertEquals(
            "Once reviewed, this route and your name (Hana) appear in Popular routes. " +
                "The link becomes public too, and stays open even if you withdraw or it's declined.",
            listingConsentBody(authorName = "Hana", isEditor = false, alsoPublishes = true, language = AppLanguage.EN),
        )
        assertEquals(
            "This route and your name (Hana) appear in Popular routes right away.",
            listingConsentBody(authorName = "Hana", isEditor = true, alsoPublishes = false, language = AppLanguage.EN),
        )
        assertEquals("올리기" to "취소", listingConsentConfirmLabel(AppLanguage.KO) to myRoutesCancelLabel(AppLanguage.KO))
        assertEquals("LIST", listingConsentConfirmLabel(AppLanguage.EN))
    }

    @Test
    fun listingMessages() {
        assertEquals("검토를 요청했어요 · 보통 하루 안에 처리돼요", listingRequestedMessage(isEditor = false, AppLanguage.KO))
        val sent = listingRequestedMessage(isEditor = false, AppLanguage.EN)
        assertEquals("SENT FOR REVIEW · USUALLY WITHIN A DAY", sent)
        assertEquals("목록에 올렸어요", listingRequestedMessage(isEditor = true, AppLanguage.KO))
        assertEquals("LISTED", listingRequestedMessage(isEditor = true, AppLanguage.EN))
        assertEquals("요청하지 못했어요", listingRequestFailedMessage(AppLanguage.KO))
        assertEquals("COULDN’T SEND THE REQUEST", listingRequestFailedMessage(AppLanguage.EN))
    }

    @Test
    fun refusedCallsAreExplainedWithTheErrorPrefixAndNoRetry() {
        val expected =
            mapOf(
                ListingRefusal.STATE_CHANGED to ("! 동선 상태가 바뀌어 처리하지 못했어요" to "! THE ROUTE’S STATUS CHANGED"),
                ListingRefusal.NOT_PUBLISHED to ("! 링크를 먼저 공개해야 목록에 올릴 수 있어요" to "! SHARE THE LINK BEFORE LISTING"),
                ListingRefusal.REVOKED to
                    ("! 운영 정책으로 내려진 동선은 올릴 수 없어요" to "! THIS ROUTE WAS TAKEN DOWN AND CAN’T BE LISTED"),
            )
        for ((reason, text) in expected) {
            assertEquals(text.first, listingRefusedMessage(reason, AppLanguage.KO))
            assertEquals(text.second, listingRefusedMessage(reason, AppLanguage.EN))
        }
    }

    @Test
    fun aRevokedRouteSaysOnlyThatItWasTakenDown() {
        val row = summary(published = true, revoked = true, blocker = RouteListingBlocker.Revoked)

        assertEquals("3곳 · 나만 보기 · 목록 미등록", myRouteRowLabel(row, AppLanguage.KO))
        assertEquals(listOf("운영 정책으로 내려져 링크가 열리지 않아요"), myRouteListingReasons(row, AppLanguage.KO))
        assertNull(myRouteListingActions(row).firstOrNull())
    }

    private fun summary(
        published: Boolean = false,
        revoked: Boolean = false,
        stops: Int = 3,
        state: RouteListingState = RouteListingState.Unlisted,
        blocker: RouteListingBlocker? = null,
        reason: RouteDeclineReason? = null,
        note: String? = null,
    ) = PersonalRouteSummary(
        id = "route-1",
        name = "동선 1",
        stopCount = stops,
        isPublished = published,
        isRevoked = revoked,
        updatedAt = updated,
        listingState = state,
        declineReason = reason,
        declineNote = note,
        listingBlocker = blocker,
    )
}
