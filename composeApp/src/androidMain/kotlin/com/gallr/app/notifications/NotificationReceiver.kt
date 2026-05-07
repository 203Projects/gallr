package com.gallr.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gallr.app.R
import com.gallr.shared.notifications.NotificationConstants

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(NotificationConstants.EXTRA_NOTIFICATION_ID) ?: return
        val title = intent.getStringExtra(NotificationConstants.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(NotificationConstants.EXTRA_BODY) ?: return
        val deepLinkType = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE)
        val exhibitionId = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID)

        ensureChannel(context)

        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, "com.gallr.app.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE, deepLinkType)
            putExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID, exhibitionId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between schedule and fire — silent drop
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NotificationConstants.CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NotificationConstants.CHANNEL_ID,
                NotificationConstants.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)
        }
    }
}
