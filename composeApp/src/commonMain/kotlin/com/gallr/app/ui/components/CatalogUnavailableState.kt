package com.gallr.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/** A warm, actionable terminal state used only after automatic catalogue retries are exhausted. */
@Composable
fun CatalogUnavailableState(
    isNetworkError: Boolean,
    lang: AppLanguage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when {
        isNetworkError && lang == AppLanguage.KO ->
            "전시를 불러오는 데 시간이 걸리고 있어요.\n연결을 확인하고 다시 시도해주세요."
        isNetworkError ->
            "Exhibitions are taking a little longer.\nCheck your connection and try again."
        lang == AppLanguage.KO ->
            "전시를 불러오는 데 시간이 걸리고 있어요.\n잠시 후 다시 시도해주세요."
        else ->
            "Exhibitions are taking a little longer.\nPlease try again in a moment."
    }

    Column(
        modifier = modifier
            .padding(horizontal = GallrSpacing.xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(GallrSpacing.md))
        OutlinedButton(
            onClick = onRetry,
            shape = RectangleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "다시 시도" else "RETRY",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
