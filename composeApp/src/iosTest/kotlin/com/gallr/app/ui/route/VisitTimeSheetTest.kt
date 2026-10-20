package com.gallr.app.ui.route

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.RouteVisitTime
import com.gallr.shared.route.visitTimeOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 096 contracts/visit-time-control.md: choosing now, or a day and then a time. Thursday 2026-10-08, 13:05. */
@OptIn(ExperimentalTestApi::class)
class VisitTimeSheetTest {
    private val thursday = LocalDate(2026, 10, 8)
    private val options = visitTimeOptions(LocalDateTime(thursday, LocalTime(13, 5)))

    @Test
    fun choosingNowReportsNoVisitTime() =
        runComposeUiTest {
            val chosen = mutableListOf<RouteVisitTime?>()
            setContent {
                VisitTimeSheetContent(options, RouteVisitTime(thursday, LocalTime(15, 0)), thursday, AppLanguage.KO) {
                    chosen += it
                }
            }

            onNodeWithText("지금").performClick()

            assertEquals(listOf<RouteVisitTime?>(null), chosen)
        }

    @Test
    fun aDayThenATimeReportsThatDayAndTime() =
        runComposeUiTest {
            val chosen = mutableListOf<RouteVisitTime?>()
            setContent { VisitTimeSheetContent(options, null, thursday, AppLanguage.KO) { chosen += it } }

            onNodeWithText("내일").performClick()
            assertTrue(chosen.isEmpty(), "choosing a day alone reports nothing")
            onNodeWithText("13:00").performClick()

            assertEquals(listOf<RouteVisitTime?>(RouteVisitTime(LocalDate(2026, 10, 9), LocalTime(13, 0))), chosen)
        }

    @Test
    fun theCurrentChoiceIsMarkedSelected() =
        runComposeUiTest {
            setContent {
                VisitTimeSheetContent(
                    options,
                    RouteVisitTime(LocalDate(2026, 10, 10), LocalTime(13, 0)),
                    thursday,
                    AppLanguage.KO,
                ) {}
            }

            onNodeWithText("토 10.10").assertIsSelected()
            onNodeWithText("13:00").assertIsSelected()
        }

    @Test
    fun aDayWithNoTimesLeftSaysSo() =
        runComposeUiTest {
            val lateOptions = visitTimeOptions(LocalDateTime(thursday, LocalTime(21, 30)))
            setContent { VisitTimeSheetContent(lateOptions, null, thursday, AppLanguage.KO) {} }

            onNodeWithText("오늘은 더 고를 시간이 없어요").assertExists()
        }
}
