package com.gallr.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Invisible composable that briefly focuses a text field on first composition
 * to pre-warm the iOS keyboard. Uses 1x1 dp size to avoid BringIntoView crash.
 *
 * Place this once at the app root level.
 */
@Composable
fun KeyboardPrewarm() {
    var text by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    if (!done) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(focusRequester),
        )

        LaunchedEffect(Unit) {
            delay(500) // wait for layout to fully settle
            try {
                focusRequester.requestFocus()
                delay(150) // let keyboard initialize
                focusRequester.freeFocus()
            } catch (_: Exception) {
                // Silently handle if focus fails
            }
            done = true
        }
    }
}
