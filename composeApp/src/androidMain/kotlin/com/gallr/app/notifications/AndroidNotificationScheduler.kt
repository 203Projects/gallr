package com.gallr.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationConstants
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSpec
import com.gallr.shared.notifications.ScheduledIdIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val WINDOW_MS = 10 * 60 * 1000L  // 10-minute window

class AndroidNotificationScheduler(
    private val context: Context,
    private val index: ScheduledIdIndex,
    private val permissionRequester: NotificationPermissionRequester,
) : NotificationScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return permissionRequester.request()
    }

    override suspend fun schedule(spec: NotificationSpec) {
        val pi = buildPendingIntent(spec)
        val triggerAtMs = spec.triggerAt.toEpochMilliseconds()
        // setWindow is inexact — avoids SCHEDULE_EXACT_ALARM grant on Android 14+.
        // Window: [triggerAt - 5min, triggerAt + 5min]; ±5min on a 9am calendar
        // reminder is acceptable.
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs - WINDOW_MS / 2,
            WINDOW_MS,
            pi,
        )
        index.add(spec.id)
    }

    override suspend fun cancel(id: String) {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra(NotificationConstants.EXTRA_NOTIFICATION_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pi)
        index.remove(id)
    }

    override suspend fun cancelAll() {
        for (id in index.getAll()) {
            cancel(id)
        }
        index.clear()
    }

    override suspend fun scheduledIds(): Set<String> = index.getAll()

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink

    override fun setPendingDeepLink(link: DeepLink) { _pendingDeepLink.value = link }
    override fun consumePendingDeepLink() { _pendingDeepLink.value = null }

    private fun buildPendingIntent(spec: NotificationSpec): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra(NotificationConstants.EXTRA_NOTIFICATION_ID, spec.id)
            putExtra(NotificationConstants.EXTRA_TITLE, spec.title)
            putExtra(NotificationConstants.EXTRA_BODY, spec.body)
            when (val link = spec.deepLink) {
                is DeepLink.Exhibition -> {
                    putExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE, NotificationConstants.DEEPLINK_EXHIBITION)
                    putExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID, link.id)
                }
                is DeepLink.MyList -> {
                    putExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE, NotificationConstants.DEEPLINK_MYLIST)
                }
            }
        }
        return PendingIntent.getBroadcast(
            context, spec.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

interface NotificationPermissionRequester {
    suspend fun request(): Boolean
}
