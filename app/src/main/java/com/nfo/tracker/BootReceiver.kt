package com.nfo.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nfo.tracker.work.HealthWatchdogScheduler

/**
 * BroadcastReceiver that handles device boot completion.
 *
 * If the NFO was "on shift" when the device rebooted, this receiver
 * schedules the health watchdog to detect the stale state and notify the user.
 *
 * NOTE: We intentionally do NOT start the TrackingForegroundService from here.
 * Android 13+ restricts starting foreground services with type=location from
 * background contexts like BOOT_COMPLETED. Instead, the watchdog will:
 * - Mark the NFO as "device-silent" in Room/Supabase
 * - Show a notification prompting the user to reopen the app
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

        // User was on shift when device rebooted.
        // We cannot start a foreground service with type=location from BOOT_COMPLETED
        // on Android 13+ (SecurityException). Instead, schedule the watchdog which will:
        // 1. Detect stale heartbeat
        // 2. Mark status as "device-silent" in Room
        // 3. Sync to Supabase
        // 4. Show notification prompting user to reopen app
        Log.w(TAG, "BootReceiver: Device rebooted while on shift → scheduling watchdog only (no auto tracking restart)")

        HealthWatchdogScheduler.scheduleOneTime(context)

        Log.d(TAG, "BootReceiver: Watchdog scheduled. User must reopen app to resume tracking.")

        // TODO: If we want to show a non-FGS notification from boot (e.g., "Tracking paused after reboot"),
        // we can do that here safely since regular notifications don't have the same restrictions.
    }
}
