package com.gallr.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/**
 * The whole screen while the installed build is below the minimum (spec 098 US1): one heading, one line, one button.
 * [onUpdate] opens the store and says whether it could; when it could not, a line says to update from the store and
 * the screen stays. Back does nothing here, so the app cannot be used around it.
 */
@Composable
internal fun RequiredUpdateScreen(
    language: AppLanguage,
    onUpdate: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var storeFailed by remember { mutableStateOf(false) }
    PlatformBackHandler(onBack = {})
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(horizontal = GallrSpacing.screenMargin),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = requiredUpdateTitle(language),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = requiredUpdateMessage(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GallrSpacing.sm),
        )
        Button(
            onClick = { storeFailed = !onUpdate() },
            shape = MaterialTheme.shapes.small,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            modifier =
                Modifier
                    .padding(top = GallrSpacing.lg)
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
        ) {
            Text(updateActionLabel(language), style = MaterialTheme.typography.bodyMedium)
        }
        if (storeFailed) {
            Text(
                text = storeFallbackMessage(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GallrSpacing.sm),
            )
        }
    }
}
