package com.gallr.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Minimalist loading indicator: a 1dp-height indeterminate black line spanning full width.
 * Replaces CircularProgressIndicator across all screens (FR-008).
 */
@Composable
fun GallrLoadingState(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier =
            modifier
                .fillMaxWidth()
                .height(1.dp),
        color = MaterialTheme.colorScheme.onBackground,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
    )
}
