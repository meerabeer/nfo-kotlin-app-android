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
import androidx.core.content.ContextCompat
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.work.HealthWatchdogScheduler
import com.nfo.tracker.work.ShiftStateHelper

/**
 * BroadcastReceiver that handles device boot completion.
 *
 * If the NFO was "on shift" and logged in when the device rebooted, this receiver:
 * 1. Starts TrackingForegroundService to auto-resume location tracking and heartbeats.
 * 2. Shows a notification informing the user tracking has resumed.
 * 3. Schedules the health watchdog to monitor heartbeat freshness.
 *
 * This ensures heartbeats continue automatically after reboot without requiring
 * the NFO to manually reopen the app.
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
        Log.w(TAG, "BootReceiver: Device rebooted while on shift → starting tracking service, posting notification, scheduling watchdog")

        // 1. Start TrackingForegroundService to auto-resume heartbeats
        // This is allowed even on Android 13+ because:
        // - We have FOREGROUND_SERVICE_LOCATION permission in manifest
        // - User granted location permission before going on shift
        // - Service calls startForeground() immediately
        startTrackingService(context)

        // 2. Show a notification informing user tracking has resumed
        showRebootNotification(context)

        // 3. Schedule the watchdog to detect stale heartbeat and sync "device-silent" to Supabase
        HealthWatchdogScheduler.scheduleOneTime(context)

        Log.d(TAG, "BootReceiver: Service started, notification posted (id=$REBOOT_NOTIFICATION_ID), watchdog scheduled")
    }

    /**
     * Starts the TrackingForegroundService after device reboot.
     * Uses ACTION_START_FROM_BOOT to indicate boot-initiated start.
     */
    private fun startTrackingService(context: Context) {
        Log.d(TAG, "BootReceiver: Starting TrackingForegroundService after reboot (on_shift & logged_in)")

        val serviceIntent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_START_FROM_BOOT
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "BootReceiver: TrackingForegroundService start requested successfully")
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver: Failed to start TrackingForegroundService: ${e.message}", e)
        }
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
            .setContentTitle("NFO Tracker – Tracking resumed")
            .setContentText("Tracking auto-resumed after reboot. Tap to open app.")
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
