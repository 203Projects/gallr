package com.gallr.app.notifications

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSyncService
import kotlinx.coroutines.launch

/**
 * Mount once near the top of App(). Shows a contextual rationale dialog the
 * first time `bookmarkMutationCount` increments while permission is unknown
 * and the user has never been prompted.
 *
 * The host wires `bookmarkMutationCount` via an effect that increments on
 * every BookmarkRepository mutation.
 */
@Composable
fun NotificationPermissionHandler(
    scheduler: NotificationScheduler,
    syncService: NotificationSyncService,
    bookmarkMutationCount: Int,
    permissionPrompted: Boolean?,  // null = DataStore not yet yielded
    onPrompted: () -> Unit,
    language: AppLanguage,
) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookmarkMutationCount, permissionPrompted) {
        if (bookmarkMutationCount == 0) return@LaunchedEffect
        if (permissionPrompted != false) return@LaunchedEffect  // null OR true → don't prompt
        if (scheduler.hasPermission()) return@LaunchedEffect
        showDialog = true
    }

    if (showDialog) {
        val s = remember(language) { notificationPermissionStrings(language) }
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                onPrompted()
            },
            title = { Text(s.title) },
            text = { Text(s.body) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val granted = scheduler.requestPermission()
                        if (granted) syncService.sync()
                        showDialog = false
                        onPrompted()
                    }
                }) { Text(s.confirm) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    onPrompted()
                }) { Text(s.dismiss) }
            },
        )
    }
}
