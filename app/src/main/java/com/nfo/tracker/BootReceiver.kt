package com.nfo.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.work.HealthWatchdogScheduler

/**
 * BroadcastReceiver that handles device boot completion.
 *
 * If the NFO was "on shift" when the device rebooted, this receiver
 * automatically restarts the tracking foreground service and reschedules
 * the health watchdog, providing Uber-style reliability.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission in AndroidManifest.xml.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /** SharedPreferences file for tracking state (same as MainActivity). */
        private const val PREFS_NAME = "nfo_tracker_prefs"
        private const val KEY_ON_SHIFT = "on_shift"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "BootReceiver: Ignoring non-BOOT_COMPLETED intent: ${intent?.action}")
            return
        }

        Log.d(TAG, "BootReceiver: BOOT_COMPLETED received, checking on_shift state...")

        // Read on_shift from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val onShift = prefs.getBoolean(KEY_ON_SHIFT, false)

        if (!onShift) {
            Log.d(TAG, "BootReceiver: User not on shift at boot → nothing to do")
            return
        }

        Log.w(TAG, "BootReceiver: Device rebooted while on shift → restarting tracking")

        // 1) Start foreground tracking service with boot-specific action
        val serviceIntent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_START_FROM_BOOT
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        // 2) Reschedule the health watchdog
        HealthWatchdogScheduler.scheduleOneTime(context)

        Log.d(TAG, "BootReceiver: Tracking service and watchdog rescheduled successfully")
    }
}
