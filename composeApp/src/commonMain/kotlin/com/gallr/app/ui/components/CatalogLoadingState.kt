package com.gallr.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.delay

private const val SLOW_LOADING_MESSAGE_DELAY_MILLIS = 2_500L

/**
 * Calm catalogue placeholder: skeletons appear immediately, while explanatory copy is reserved
 * for loads long enough that silence could feel broken. The fixed message slot prevents reflow.
 */
@Composable
fun CatalogLoadingState(
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    var showSlowLoadingMessage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(SLOW_LOADING_MESSAGE_DELAY_MILLIS)
        showSlowLoadingMessage = true
    }

    Column(
        modifier = modifier.padding(horizontal = GallrSpacing.md),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(GallrSpacing.xl),
        ) {
            if (showSlowLoadingMessage) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "전시를 준비하고 있어요…"
                            AppLanguage.EN -> "Preparing exhibitions…"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
        }

        repeat(3) {
            SkeletonCard(modifier = Modifier.padding(bottom = GallrSpacing.md))
        }
    }
}
