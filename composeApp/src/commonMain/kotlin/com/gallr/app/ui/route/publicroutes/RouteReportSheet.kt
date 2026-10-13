package com.gallr.app.ui.route.publicroutes

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.leadingSelectionBar
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.RouteReportReason

/**
 * Why the reader reports a listed route (spec 089 US10, DD10): one fixed reason as radio-style option rows, and a
 * standard black "신고하기" enabled once a reason is chosen, reading "보내는 중…" while it sends (DD12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteReportSheet(
    language: AppLanguage,
    busy: Boolean,
    onReport: (RouteReportReason) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf<RouteReportReason?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.lg),
        ) {
            Text(
                text = reportSheetTitle(language),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = GallrSpacing.sm).semantics { heading() },
            )
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                RouteReportReason.entries.forEachIndexed { index, reason ->
                    ReasonRow(
                        label = reportReasonLabel(reason, language),
                        selected = chosen == reason,
                        enabled = !busy,
                        onClick = { chosen = reason },
                    )
                    if (index < RouteReportReason.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            val label = reportSendLabel(busy, language)
            Button(
                onClick = { chosen?.takeUnless { busy }?.let(onReport) },
                enabled = chosen != null,
                shape = RectangleShape,
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
}

@Composable
private fun ReasonRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .leadingSelectionBar(selected)
                .padding(horizontal = GallrSpacing.md, vertical = GallrSpacing.md),
    )
}

private val ROW_MIN_HEIGHT = 52.dp
private val SEND_MIN_HEIGHT = 52.dp
