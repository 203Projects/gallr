package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.gallr.app.ui.theme.GallrSpacing

/** The page title (DESIGN.md, Home tab): the greeting in `displayMedium`. */
@Composable
internal fun HomeHeader(
    greeting: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = greeting,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = GallrSpacing.screenMargin)
                .semantics { heading() },
    )
}
