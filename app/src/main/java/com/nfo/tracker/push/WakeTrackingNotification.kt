package com.nfo.tracker.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nfo.tracker.MainActivity
import com.nfo.tracker.R

/**
 * Helper object to show a high-priority notification when the server detects
 * that tracking has stopped (device silent).
 *
 * This notification prompts the NFO to reopen the app and resume tracking.
 * It is triggered by a WAKE_TRACKING FCM push message from the server.
 */
object WakeTrackingNotification {

    private const val CHANNEL_ID = "nfo_wake_tracking"
    private const val CHANNEL_NAME = "NFO Tracking Alerts"
    private const val NOTIF_ID = 2001

    /**
     * Shows a high-priority notification prompting the user to reopen the app.
     *
     * @param context Application context (typically from FirebaseMessagingService)
     */
    fun show(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when tracking has stopped or device is silent"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open MainActivity when user taps the notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NFO Tracker – Action Required")
            .setContentText("Tracking stopped. Tap to reopen the app and resume.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        try {
            notificationManager.notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission might be denied on Android 13+
            android.util.Log.w("WakeTrackingNotification", "Could not show notification: ${e.message}")
        }
    }
}
