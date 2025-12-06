package com.nfo.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.work.HealthWatchdogScheduler
import com.nfo.tracker.work.ShiftStateHelper

/**
 * BroadcastReceiver that handles device boot completion.
 *
 * If the NFO was "on shift" when the device rebooted, this receiver:
 * 1. Shows a normal notification prompting the user to reopen the app.
 * 2. Schedules the health watchdog to detect stale state and sync "device-silent" to Supabase.
 *
 * NOTE: We intentionally do NOT start the TrackingForegroundService from here.
 * Android 13+ restricts starting foreground services with type=location from
 * background contexts like BOOT_COMPLETED.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission in AndroidManifest.xml.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /** Notification ID for reboot alert (distinct from FGS=1001 and stale=1002). */
        private const val REBOOT_NOTIFICATION_ID = 1003

        /** Intent extra to indicate MainActivity was opened from boot notification. */
        const val EXTRA_FROM_BOOT = "from_boot_receiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "BootReceiver: Ignoring non-BOOT_COMPLETED intent: ${intent?.action}")
            return
        }

        Log.d(TAG, "BootReceiver: BOOT_COMPLETED received, checking shift and login state...")

        // Read on_shift and is_logged_in using ShiftStateHelper
        val isOnShift = ShiftStateHelper.isOnShift(context)
        val isLoggedIn = ShiftStateHelper.isLoggedIn(context)

        Log.d(TAG, "BootReceiver: isOnShift=$isOnShift, isLoggedIn=$isLoggedIn")

        if (!isOnShift || !isLoggedIn) {
            Log.d(TAG, "BootReceiver: User not on shift or not logged in → nothing to do")
            return
        }

        // User was on shift when device rebooted.
        Log.w(TAG, "BootReceiver: Device rebooted while on shift → posting reboot notification and scheduling watchdog")

        // 1. Show a normal notification prompting user to reopen app
        showRebootNotification(context)

        // 2. Schedule the watchdog to detect stale heartbeat and sync "device-silent" to Supabase
        HealthWatchdogScheduler.scheduleOneTime(context)

        Log.d(TAG, "BootReceiver: Reboot notification posted (id=$REBOOT_NOTIFICATION_ID) and watchdog scheduled")
    }

    /**
     * Shows a normal (non-foreground) notification informing the user that tracking
     * paused after reboot and they need to reopen the app.
     *
     * Uses the same channel as [TrackingForegroundService] to keep notifications grouped.
     */
    private fun showRebootNotification(context: Context) {
        // Ensure notification channel exists (required for Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existingChannel = notificationManager.getNotificationChannel(TrackingForegroundService.CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    TrackingForegroundService.CHANNEL_ID,
                    TrackingForegroundService.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "BootReceiver: Created notification channel ${TrackingForegroundService.CHANNEL_ID}")
            }
        }

        // Intent to open MainActivity when user taps the notification
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_FROM_BOOT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TrackingForegroundService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NFO Tracker – Resume shift")
            .setContentText("Tap to reopen app and resume tracking.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(REBOOT_NOTIFICATION_ID, notification)
            Log.d(TAG, "BootReceiver: Reboot notification shown successfully")
        } catch (e: SecurityException) {
            // Notification permission might be denied on Android 13+
            Log.w(TAG, "BootReceiver: Could not show notification (permission denied): ${e.message}")
        }
    }
}
