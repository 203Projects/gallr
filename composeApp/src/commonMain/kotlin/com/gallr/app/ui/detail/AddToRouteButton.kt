package com.gallr.app.ui.detail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.route.composer.addToRouteLabel
import com.gallr.app.ui.route.composer.addedToRouteAction
import com.gallr.app.ui.route.composer.addedToRouteMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.AddToRouteAvailability
import com.gallr.app.viewmodel.AddToRouteUiState
import com.gallr.shared.data.model.AppLanguage

/** The detail page's route control: its state and the intents it emits to its ViewModel (DR-D16). */
data class AddToRouteControl(
    val state: AddToRouteUiState,
    val onAdd: () -> Unit,
    val onOpenRoute: () -> Unit,
    val onMessageShown: () -> Unit,
)

/** Outlined 44dp "동선에 추가"; "동선에 있음" opens the composer and a full route disables it. */
@Composable
internal fun AddToRouteButton(
    control: AddToRouteControl,
    language: AppLanguage,
) {
    val availability = control.state.availability
    val label = addToRouteLabel(availability, language) ?: return
    Spacer(Modifier.height(GallrSpacing.sm))
    OutlinedButton(
        onClick = if (availability == AddToRouteAvailability.IN_ROUTE) control.onOpenRoute else control.onAdd,
        enabled = availability != AddToRouteAvailability.FULL,
        shape = RectangleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Text(label)
    }
}

/** "동선에 추가했어요 (3/10) · 열기" after an add. */
@Composable
internal fun AddedToRouteSnackbar(
    control: AddToRouteControl,
    hostState: SnackbarHostState,
    language: AppLanguage,
) {
    val added = control.state.message
    LaunchedEffect(added) {
        if (added == null) return@LaunchedEffect
        val result =
            hostState.showSnackbar(
                message = addedToRouteMessage(added.stopCount, language),
                actionLabel = addedToRouteAction(language),
                duration = SnackbarDuration.Short,
            )
        control.onMessageShown()
        if (result == SnackbarResult.ActionPerformed) control.onOpenRoute()
    }
}
