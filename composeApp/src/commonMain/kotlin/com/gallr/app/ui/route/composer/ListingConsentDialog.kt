package com.gallr.app.ui.route.composer

import androidx.compose.foundation.focusable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.gallr.app.viewmodel.ListingConsent
import com.gallr.shared.data.model.AppLanguage

/**
 * Asked before every listing request (spec 089 US7, DD17): what becomes visible to everyone in 추천 동선, and for
 * the publish-and-list option that the link becomes public for good. Focus moves to the title when it opens (D26).
 */
@Composable
fun ListingConsentDialog(
    consent: ListingConsent,
    language: AppLanguage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(consent) { runCatching { titleFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        title = {
            Text(
                text = listingConsentTitle(language),
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier
                        .focusRequester(titleFocus)
                        .focusable()
                        .semantics { heading() },
            )
        },
        text = {
            Text(
                text =
                    listingConsentBody(
                        authorName = consent.authorName,
                        isEditor = consent.isEditor,
                        alsoPublishes = consent.alsoPublishes,
                        language = language,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, shape = RectangleShape) {
                Text(listingConsentConfirmLabel(language), color = MaterialTheme.colorScheme.onBackground)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RectangleShape) {
                Text(myRoutesCancelLabel(language), color = MaterialTheme.colorScheme.onBackground)
            }
        },
    )
}
