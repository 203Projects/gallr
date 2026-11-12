package com.gallr.app.ui.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.thoughts.ThoughtReportReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 103 US4: the report sheet's fixed reasons and its send button (contracts/diary-ui.md "Report sheet"). */
@OptIn(ExperimentalTestApi::class)
class ThoughtReportSheetTest {
    @Test
    fun fiveReasonsAndTheSendButtonWaitsForOne() =
        runComposeUiTest {
            val sent = mutableListOf<ThoughtReportReason>()
            setContent {
                ThoughtReportSheetContent(language = AppLanguage.KO, busy = false, onReport = { sent += it })
            }

            onNodeWithText("이 감상을 신고하는 이유").assertExists()
            listOf("부적절한 내용", "광고·홍보", "전시와 관련 없음", "개인정보 노출", "기타").forEach {
                onNodeWithText(it).assertExists()
            }
            onNodeWithText("신고하기").assertIsNotEnabled()

            onNodeWithText("광고·홍보").performClick()
            onNodeWithText("광고·홍보").assertIsSelected()
            onNodeWithText("신고하기").assertIsEnabled().performClick()

            assertEquals(listOf(ThoughtReportReason.PROMOTIONAL), sent)
        }

    @Test
    fun whileSendingTheButtonReadsSendingAndSendsNothingMore() =
        runComposeUiTest {
            val sent = mutableListOf<ThoughtReportReason>()
            setContent {
                ThoughtReportSheetContent(language = AppLanguage.KO, busy = true, onReport = { sent += it })
            }

            onNodeWithText("보내는 중…").assertExists()
            onNodeWithText("기타").assertIsNotEnabled()
            onNodeWithText("보내는 중…").performClick()

            assertEquals(emptyList(), sent)
        }

    @Test
    fun englishCopy() =
        runComposeUiTest {
            setContent { ThoughtReportSheetContent(language = AppLanguage.EN, busy = false, onReport = {}) }

            onNodeWithText("WHY ARE YOU REPORTING THIS NOTE?").assertExists()
            val reasons =
                listOf("Inappropriate", "Promotional", "Not about the exhibition", "Personal information", "Other")
            reasons.forEach { onNodeWithText(it).assertExists() }
            onNodeWithText("REPORT").assertIsNotEnabled()
        }
}
