package com.gallr.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.leadingSelectionBar
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.thoughts.ThoughtReportReason

/**
 * Why the reader reports a 감상 (spec 103 US4): the public routes' report sheet (`RouteReportSheet`) with the 감상's
 * five fixed reasons as radio-style rows and a standard black 신고하기, enabled once a reason is chosen and reading
 * 보내는 중… while it sends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThoughtReportSheet(
    language: AppLanguage,
    busy: Boolean,
    onReport: (ThoughtReportReason) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = GallrShapeTokens.sheetTop,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        ThoughtReportSheetContent(language = language, busy = busy, onReport = onReport)
    }
}

@Composable
internal fun ThoughtReportSheetContent(
    language: AppLanguage,
    busy: Boolean,
    onReport: (ThoughtReportReason) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<ThoughtReportReason?>(null) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.lg),
    ) {
        Text(
            text = if (language == AppLanguage.KO) "이 감상을 신고하는 이유" else "WHY ARE YOU REPORTING THIS NOTE?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = GallrSpacing.sm).semantics { heading() },
        )
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            ThoughtReportReason.entries.forEachIndexed { index, reason ->
                Text(
                    text = thoughtReportReasonLabel(reason, language),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = ROW_MIN_HEIGHT)
                            .selectable(
                                selected = chosen == reason,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = { chosen = reason },
                            ).leadingSelectionBar(chosen == reason)
                            .padding(horizontal = GallrSpacing.md, vertical = GallrSpacing.md),
                )
                if (index < ThoughtReportReason.entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        val label = thoughtReportSendLabel(busy, language)
        Button(
            onClick = { chosen?.takeUnless { busy }?.let(onReport) },
            enabled = chosen != null,
            shape = MaterialTheme.shapes.small,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = GallrSpacing.md)
                    .heightIn(min = SEND_MIN_HEIGHT)
                    .semantics { if (busy) stateDescription = label },
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun thoughtReportReasonLabel(
    reason: ThoughtReportReason,
    language: AppLanguage,
): String {
    val ko = language == AppLanguage.KO
    return when (reason) {
        ThoughtReportReason.INAPPROPRIATE -> if (ko) "부적절한 내용" else "Inappropriate"
        ThoughtReportReason.PROMOTIONAL -> if (ko) "광고·홍보" else "Promotional"
        ThoughtReportReason.NOT_ABOUT_EXHIBITION -> if (ko) "전시와 관련 없음" else "Not about the exhibition"
        ThoughtReportReason.PERSONAL_INFORMATION -> if (ko) "개인정보 노출" else "Personal information"
        ThoughtReportReason.OTHER -> if (ko) "기타" else "Other"
    }
}

private fun thoughtReportSendLabel(
    busy: Boolean,
    language: AppLanguage,
): String =
    when {
        busy -> if (language == AppLanguage.KO) "보내는 중…" else "SENDING…"
        else -> if (language == AppLanguage.KO) "신고하기" else "REPORT"
    }

private val ROW_MIN_HEIGHT = 52.dp
private val SEND_MIN_HEIGHT = 52.dp
