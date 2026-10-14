package com.gallr.app.ui.route.publicroutes

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.viewmodel.BrowseListState
import com.gallr.app.viewmodel.PublicRouteBrowseUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.BrowsedPublicRoute
import com.gallr.shared.route.PublicRouteBrowseQuery
import com.gallr.shared.route.PublicRouteDistrict
import com.gallr.shared.route.PublicRouteSort
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Spec 092: the 동선 둘러보기 screen renders its state and reports the reader's actions. */
@OptIn(ExperimentalTestApi::class)
class PublicRouteBrowseScreenTest {
    private val today = LocalDate(2026, 10, 14)
    private val districts =
        listOf(
            PublicRouteDistrict(nameKo = "성수동", nameEn = "Seongsu-dong", routeCount = 2),
            PublicRouteDistrict(nameKo = "한남동", nameEn = "Hannam-dong", routeCount = 1),
        )

    // US1

    @Test
    fun aRowIsOneButtonThatOpensItsRoute() =
        runComposeUiTest {
            val events = render(loaded(routes(3)))
            val description = publicRouteBrowseRowDescription(summary("r2"), today, AppLanguage.KO)

            onNodeWithContentDescription(description).performClick()

            assertEquals(listOf("open r2"), events)
        }

    @Test
    fun reachingTheEndAsksForMore() =
        runComposeUiTest {
            val events = render(loaded(routes(30), hasMore = true))

            assertEquals(emptyList<String>(), events, "the first rows do not reach the end")
            onNodeWithTag(BROWSE_LIST_TAG).performScrollToIndex(30)
            waitForIdle()

            assertEquals(listOf("load more"), events)
        }

    @Test
    fun theLastPageNeverAsksForMore() =
        runComposeUiTest {
            val events = render(loaded(routes(30), hasMore = false))

            onNodeWithTag(BROWSE_LIST_TAG).performScrollToIndex(30)
            waitForIdle()

            assertEquals(emptyList<String>(), events)
        }

    @Test
    fun backLeavesTheScreen() =
        runComposeUiTest {
            val events = render(loaded(routes(1)))

            onNodeWithContentDescription("뒤로").performClick()

            assertEquals(listOf("back"), events)
        }

    // US2

    @Test
    fun oneSortIsSelectedAndTheOtherCanBeChosen() =
        runComposeUiTest {
            val events = render(loaded(routes(1)))

            onNodeWithText("인기").assertIsSelected()
            onNodeWithText("최신").assertIsNotSelected()
            onNodeWithText("최신").performClick()

            assertEquals(listOf("sort NEWEST"), events)
        }

    // US3

    @Test
    fun theDistrictRowStartsWithAllAndChoosesByTheKoreanName() =
        runComposeUiTest {
            val events = render(loaded(routes(1)).copy(districts = districts), language = AppLanguage.EN)

            onNodeWithText("ALL").assertIsSelected()
            onNodeWithText("Seongsu-dong").assertIsNotSelected()
            onNodeWithText("Seongsu-dong").performClick()
            onNodeWithText("ALL").performClick()

            assertEquals(listOf("district 성수동", "clear district"), events)
        }

    @Test
    fun theChosenDistrictIsSelected() =
        runComposeUiTest {
            val chosen = PublicRouteBrowseQuery(districtKo = "한남동")
            render(loaded(routes(1)).copy(districts = districts, query = chosen))

            onNodeWithText("한남동").assertIsSelected()
            onNodeWithText("전체").assertIsNotSelected()
        }

    // US4

    @Test
    fun aFailedFirstPageOffersARetry() =
        runComposeUiTest {
            val events = render(PublicRouteBrowseUiState(list = BrowseListState.FirstPageError, today = today))

            onNodeWithText("다시 시도").performClick()

            assertEquals(listOf("retry"), events)
        }

    @Test
    fun aFailedLaterPageOffersARetryAtTheEnd() =
        runComposeUiTest {
            val failed = BrowseListState.Loaded(routes(2), hasMore = true, appendFailed = true)
            val events = render(loaded(routes(2)).copy(list = failed))

            onNodeWithText("! ${publicRouteLoadMoreFailedMessage(AppLanguage.KO)}").assertExists()
            onNodeWithText("다시 시도").performClick()

            assertEquals(listOf("retry more"), events)
        }

    @Test
    fun nothingListedInvitesTheReaderToMakeARoute() =
        runComposeUiTest {
            val empty = PublicRouteBrowseUiState(list = BrowseListState.Empty(filtered = false), today = today)
            val events = render(empty)

            onNodeWithText("아직 공유된 동선이 없어요").assertExists()
            onNodeWithText("내 동선 만들기").performClick()

            assertEquals(listOf("make route"), events)
        }

    @Test
    fun anEmptyDistrictOffersToClearTheFilter() =
        runComposeUiTest {
            val state =
                PublicRouteBrowseUiState(
                    query = PublicRouteBrowseQuery(districtKo = "성수동"),
                    districts = districts,
                    list = BrowseListState.Empty(filtered = true),
                    today = today,
                )
            val events = render(state)

            onNodeWithText("성수동을 지나는 동선이 없어요").assertExists()
            onNodeWithText("필터 지우기").performClick()

            assertEquals(listOf("clear district"), events)
        }

    private fun ComposeUiTest.render(
        state: PublicRouteBrowseUiState,
        language: AppLanguage = AppLanguage.KO,
    ): List<String> {
        val events = mutableListOf<String>()
        setContent {
            PublicRouteBrowseScreen(
                state = state,
                language = language,
                onOpenRoute = { events += "open ${it.id}" },
                onLoadMore = { events += "load more" },
                onRetry = { events += "retry" },
                onRetryMore = { events += "retry more" },
                onSelectSort = { events += "sort $it" },
                onSelectDistrict = { events += "district $it" },
                onClearDistrict = { events += "clear district" },
                onMakeRoute = { events += "make route" },
                onScrollAnchor = { _, _ -> },
                onBack = { events += "back" },
            )
        }
        waitForIdle()
        return events
    }

    private fun loaded(
        routes: List<BrowsedPublicRoute>,
        hasMore: Boolean = false,
    ) = PublicRouteBrowseUiState(
        query = PublicRouteBrowseQuery(sort = PublicRouteSort.POPULAR),
        list = BrowseListState.Loaded(routes, hasMore),
        today = today,
    )

    private fun routes(count: Int) = (1..count).map { BrowsedPublicRoute(summary("r$it"), emptyList()) }

    private fun summary(id: String) =
        PublicRouteSummary(
            id = id,
            name = "동선 $id",
            stopCount = 2,
            firstDistrictKo = "한남동",
            firstDistrictEn = "Hannam-dong",
            lastDistrictKo = "이태원동",
            lastDistrictEn = "Itaewon-dong",
            authorDisplayName = "작가",
            isEditor = false,
            copyCount30d = 1,
            firstSharedDay = today,
            approvedAt = Instant.parse("2026-10-12T00:00:00Z"),
        )
}
