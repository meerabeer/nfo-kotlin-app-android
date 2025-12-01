package com.nfo.tracker.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nfo.tracker.MainActivity
import com.nfo.tracker.R
import com.nfo.tracker.data.local.HeartbeatDatabase
import com.nfo.tracker.tracking.TrackingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Health Watchdog Worker for Uber-style reliability.
 *
 * This worker runs periodically while the user is "on shift" and monitors
 * the heartbeat stream. If no heartbeat has been recorded for more than
 * [WATCHDOG_STALE_MINUTES], it assumes the tracking service has died
 * and attempts to restart it.
 *
 * Behaviour:
 * - If not on shift → no-op, returns success.
 * - If on shift and heartbeat is fresh → logs healthy, sets tracking_healthy=true.
 * - If on shift and heartbeat is stale → marks NFO as "device-silent" in local DB,
 *   triggers sync, restarts [TrackingForegroundService], sets tracking_healthy=false.
 * - On transition from healthy→stale (with cooldown): shows a notification to the user
 *   prompting them to reopen the app.
 *
 * The "device-silent" status will be synced to Supabase when network is available,
 * allowing the manager dashboard to see that the NFO's device went silent.
 *
 * This uses a self-rescheduling OneTimeWorkRequest pattern to achieve
 * ~1 minute check intervals (WorkManager's PeriodicWork minimum is 15 min).
 */
class HealthWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "HealthWatchdogWorker"

        /** How often we want to check (used for self-rescheduling delay). */
        const val WATCHDOG_INTERVAL_MINUTES = 1L

        /** If no heartbeat for this long (in minutes), treat tracking as stale. */
        const val WATCHDOG_STALE_MINUTES = 3L

        /** Cooldown before showing another stale notification (10 minutes). */
        private const val REACTION_COOLDOWN_MILLIS = 10 * 60 * 1000L

        /** Unique work name for WorkManager. */
        const val WATCHDOG_UNIQUE_WORK_NAME = "health_watchdog_work"

        /** SharedPreferences file for tracking state. */
        private const val PREFS_NAME = "nfo_tracker_prefs"
        private const val KEY_ON_SHIFT = "on_shift"

        /** SharedPreferences file for health watchdog state. */
        private const val HEALTH_PREFS_NAME = "health_watchdog"
        private const val KEY_TRACKING_HEALTHY = "tracking_healthy"
        private const val KEY_LAST_STALE_REACTION_AT = "last_stale_reaction_at"

        /** Notification ID for stale tracking alert (distinct from foreground service notification). */
        private const val STALE_NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Watchdog check started")

            // 1. Check if user is on shift
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isOnShift = prefs.getBoolean(KEY_ON_SHIFT, false)

            if (!isOnShift) {
                Log.d(TAG, "Not on shift, skipping watchdog check")
                // Don't reschedule if not on shift
                return@withContext Result.success()
            }

            // 2. Query the latest heartbeat from Room
            val db = HeartbeatDatabase.getInstance(applicationContext)
            val dao = db.heartbeatDao()
            val lastHeartbeat = dao.getLastHeartbeat()

            val healthPrefs = applicationContext.getSharedPreferences(HEALTH_PREFS_NAME, Context.MODE_PRIVATE)

            // Read previous state for transition detection
            val wasHealthy = healthPrefs.getBoolean(KEY_TRACKING_HEALTHY, true)
            val lastReactionAt = healthPrefs.getLong(KEY_LAST_STALE_REACTION_AT, 0L)
            val now = System.currentTimeMillis()

            if (lastHeartbeat == null) {
                Log.w(TAG, "On shift but no heartbeat found in DB. Service may not have started yet.")

                // Check for healthy→stale transition and react if cooldown passed
                val canReact = (now - lastReactionAt) > REACTION_COOLDOWN_MILLIS
                if (wasHealthy && canReact) {
                    Log.d(TAG, "Transition: healthy → stale (no heartbeat). Triggering reaction.")
                    showStaleNotification()
                    healthPrefs.edit { putLong(KEY_LAST_STALE_REACTION_AT, now) }
                }

                // Mark as unhealthy since we have no heartbeat data
                healthPrefs.edit { putBoolean(KEY_TRACKING_HEALTHY, false) }

                // Attempt to start service just in case
                restartTrackingService()
                rescheduleWatchdog()
                Log.d(TAG, "Watchdog check finished: UNHEALTHY (no heartbeat in DB)")
                return@withContext Result.success()
            }

            // 3. Compute time difference
            val lastHeartbeatTime = lastHeartbeat.createdAtLocal
            val diffMillis = now - lastHeartbeatTime
            val diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
            val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(diffMillis)
            val thresholdMillis = WATCHDOG_STALE_MINUTES * 60_000L

            Log.d(
                TAG,
                "Last heartbeat: id=${lastHeartbeat.localId}, username=${lastHeartbeat.username}, " +
                    "age=${diffMinutes}m ${diffSeconds % 60}s, synced=${lastHeartbeat.synced}, " +
                    "threshold=${WATCHDOG_STALE_MINUTES}m"
            )

            // 4. Check if stale
            val isHealthy = diffMillis < thresholdMillis

            if (!isHealthy) {
                Log.w(
                    TAG,
                    "Heartbeat STALE! Last seen ${diffMinutes}m ${diffSeconds % 60}s ago. " +
                        "Marking as device-silent and restarting tracking service..."
                )

                // Check for healthy→stale transition and react if cooldown passed
                val canReact = (now - lastReactionAt) > REACTION_COOLDOWN_MILLIS
                if (wasHealthy && canReact) {
                    Log.d(TAG, "Transition: healthy → stale. Triggering reaction (notification + restart).")
                    showStaleNotification()
                    healthPrefs.edit { putLong(KEY_LAST_STALE_REACTION_AT, now) }
                } else if (!canReact) {
                    val cooldownRemaining = TimeUnit.MILLISECONDS.toMinutes(REACTION_COOLDOWN_MILLIS - (now - lastReactionAt))
                    Log.d(TAG, "Still stale but within cooldown (${cooldownRemaining}m remaining). Skipping notification.")
                }

                // Mark tracking as unhealthy
                healthPrefs.edit { putBoolean(KEY_TRACKING_HEALTHY, false) }

                // Update the heartbeat row to "device-silent" status so it gets synced to Supabase
                val staleHeartbeat = lastHeartbeat.copy(
                    status = "device-silent",
                    lastActiveSource = "watchdog",
                    lastActiveAt = now,
                    updatedAt = now,
                    synced = false  // Mark as unsynced so HeartbeatWorker will pick it up
                )
                dao.upsert(staleHeartbeat)
                Log.d(TAG, "Updated heartbeat to device-silent status, synced=false")

                // Trigger immediate sync attempt (will run when network available)
                HeartbeatSyncHelper.enqueueImmediateSync(applicationContext)

                // Attempt to restart the tracking service
                restartTrackingService()

                Log.d(TAG, "Watchdog check finished: UNHEALTHY (stale heartbeat, marked device-silent)")
            } else {
                Log.d(
                    TAG,
                    "Heartbeat healthy (age=${diffSeconds}s, threshold=${WATCHDOG_STALE_MINUTES * 60}s). Tracking is alive."
                )

                // Mark tracking as healthy
                healthPrefs.edit { putBoolean(KEY_TRACKING_HEALTHY, true) }

                Log.d(TAG, "Watchdog check finished: HEALTHY")
            }

            // 5. Reschedule self for next check (only if still on shift)
            rescheduleWatchdog()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog error: ${e.message}", e)
            // Still reschedule even on error, but return success to avoid retry hammering
            try {
                rescheduleWatchdog()
            } catch (re: Exception) {
                Log.e(TAG, "Failed to reschedule watchdog: ${re.message}", re)
            }
            Result.success()
        }
    }

    /**
     * Shows a notification to the NFO when tracking becomes stale.
     * Prompts user to reopen the app to resume tracking.
     *
     * Reuses the same notification channel as [TrackingForegroundService].
     */
    private fun showStaleNotification() {
        Log.d(TAG, "Showing stale tracking notification")

        val context = applicationContext

        // Ensure notification channel exists (required for Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existingChannel = notificationManager.getNotificationChannel(TrackingForegroundService.CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    TrackingForegroundService.CHANNEL_ID,
                    TrackingForegroundService.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH  // Higher importance for alert
                )
                notificationManager.createNotificationChannel(channel)
            }
        }

        // Intent to open the app when user taps the notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TrackingForegroundService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NFO Tracker")
            .setContentText("Tracking stopped. Please open NFO Tracker again.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(STALE_NOTIFICATION_ID, notification)
            Log.d(TAG, "Stale notification shown successfully")
        } catch (e: SecurityException) {
            // Notification permission might be denied on Android 13+
            Log.w(TAG, "Could not show notification: ${e.message}")
        }
    }

    /**
     * Restarts the [TrackingForegroundService] using a dedicated action
     * so the service can log and handle watchdog-initiated restarts.
     */
    private fun restartTrackingService() {
        val appContext = applicationContext
        val intent = Intent(appContext, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_START_FROM_WATCHDOG
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
        Log.d(TAG, "Sent restart intent to TrackingForegroundService")
    }

    /**
     * Reschedules this worker to run again after [WATCHDOG_INTERVAL_MINUTES].
     * Uses OneTimeWorkRequest pattern to achieve sub-15-minute intervals.
     */
    private fun rescheduleWatchdog() {
        HealthWatchdogScheduler.scheduleOneTime(applicationContext)
    }
}
