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
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nfo.tracker.MainActivity
import com.nfo.tracker.R
import com.nfo.tracker.data.local.HeartbeatDatabase
import com.nfo.tracker.tracking.TrackingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Health Watchdog Worker for Uber-style reliability.
 *
 * This worker runs periodically while the user is "on shift" and monitors
 * the heartbeat stream. If no heartbeat has been recorded for more than
 * [STALE_THRESHOLD_MILLIS] (3 minutes), it assumes the tracking service has died.
 *
 * Behaviour:
 * - If not on shift → no-op, returns success (no reschedule).
 * - If on shift and heartbeat is fresh → logs HEALTHY, sets tracking_healthy=true.
 * - If on shift and heartbeat is stale → marks NFO as "device-silent" in local DB,
 *   triggers sync to Supabase, sets tracking_healthy=false, and shows a notification
 *   prompting the user to reopen the app.
 *
 * NOTE: The watchdog intentionally does NOT restart the TrackingForegroundService.
 * Android 13+ restricts starting foreground services with type=location from
 * background contexts like WorkManager. The user must manually reopen the app
 * to resume tracking.
 *
 * The "device-silent" status will be synced to Supabase when network is available,
 * ensuring the manager dashboard never sees a stale NFO as "available".
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

        /** Stale threshold in milliseconds (3 minutes). */
        private const val STALE_THRESHOLD_MILLIS = 3 * 60 * 1000L

        /** Cooldown before showing another stale notification (10 minutes). */
        private const val STALE_NOTIFICATION_COOLDOWN_MILLIS = 10 * 60 * 1000L

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
            Log.d(TAG, "Watchdog: check started")

            // 1. Read on_shift from nfo_tracker_prefs
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isOnShift = prefs.getBoolean(KEY_ON_SHIFT, false)

            if (!isOnShift) {
                Log.d(TAG, "Watchdog: user not on shift → skipping check")
                // Don't reschedule if not on shift; let scheduler decide when to start again
                return@withContext Result.success()
            }

            // 2. Read health watchdog state
            val healthPrefs = applicationContext.getSharedPreferences(HEALTH_PREFS_NAME, Context.MODE_PRIVATE)
            val wasHealthy = healthPrefs.getBoolean(KEY_TRACKING_HEALTHY, true)
            val lastReactionAt = healthPrefs.getLong(KEY_LAST_STALE_REACTION_AT, 0L)
            val now = System.currentTimeMillis()

            // 3. Query the latest heartbeat from Room
            val db = HeartbeatDatabase.getInstance(applicationContext)
            val dao = db.heartbeatDao()
            val lastHeartbeat = dao.getLastHeartbeat()

            if (lastHeartbeat == null) {
                Log.w(TAG, "Watchdog: no heartbeat found → cannot evaluate, skipping")
                // Mark as unhealthy since we have no data, but don't trigger full stale reaction
                healthPrefs.edit { putBoolean(KEY_TRACKING_HEALTHY, false) }
                // NOTE: We do NOT attempt to start the service here.
                // Android 13+ restricts starting FGS with type=location from background.
                // User must reopen app to start tracking.
                rescheduleWatchdog()
                return@withContext Result.success()
            }

            // 4. Compute age of last heartbeat
            val ageMillis = now - lastHeartbeat.createdAtLocal
            val isHealthy = ageMillis < STALE_THRESHOLD_MILLIS

            Log.d(
                TAG,
                "Watchdog: lastHeartbeat id=${lastHeartbeat.localId}, username=${lastHeartbeat.username}, " +
                    "ageMillis=$ageMillis, threshold=$STALE_THRESHOLD_MILLIS, synced=${lastHeartbeat.synced}"
            )

            if (isHealthy) {
                // ═══════════════════════════════════════════════════════════════
                // HEALTHY PATH
                // ═══════════════════════════════════════════════════════════════
                healthPrefs.edit { putBoolean(KEY_TRACKING_HEALTHY, true) }
                Log.d(TAG, "Watchdog: HEALTHY; age=${ageMillis}ms")
                // Do NOT modify heartbeat or service
            } else {
                // ═══════════════════════════════════════════════════════════════
                // STALE PATH
                // ═══════════════════════════════════════════════════════════════
                healthPrefs.edit { putBoolean(KEY_TRACKING_HEALTHY, false) }
                Log.w(TAG, "Watchdog: STALE; age=${ageMillis}ms, attempting recovery")

                // 4a. Mark device-silent in Room
                val updated = lastHeartbeat.copy(
                    status = "device-silent",
                    lastActiveSource = "watchdog",
                    lastActiveAt = now,
                    updatedAt = now,
                    synced = false  // Mark as unsynced so HeartbeatWorker will pick it up
                )
                dao.upsert(updated)
                Log.d(TAG, "Watchdog: updated heartbeat to status='device-silent', synced=false")

                // 4b. Trigger immediate sync to Supabase
                HeartbeatSyncHelper.enqueueImmediateSync(applicationContext)
                Log.d(TAG, "Watchdog: enqueued immediate sync for device-silent status")

                // NOTE: We do NOT restart the TrackingForegroundService here.
                // Android 13+ restricts starting FGS with type=location from WorkManager.
                // User must reopen app to resume tracking.

                // 4c. Show notification if cooldown has passed
                val canShowNotification = (now - lastReactionAt) > STALE_NOTIFICATION_COOLDOWN_MILLIS
                if (canShowNotification) {
                    showStaleNotification()
                    healthPrefs.edit { putLong(KEY_LAST_STALE_REACTION_AT, now) }
                    Log.w(TAG, "Watchdog: Showing stale notification to user")
                } else {
                    val cooldownRemainingMs = STALE_NOTIFICATION_COOLDOWN_MILLIS - (now - lastReactionAt)
                    Log.d(TAG, "Watchdog: Stale but notification cooldown active; skipping alert (${cooldownRemainingMs}ms remaining)")
                }
            }

            // 5. Reschedule watchdog for next check
            rescheduleWatchdog()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: error during check: ${e.message}", e)
            // Still reschedule even on error, but return success to avoid retry hammering
            try {
                rescheduleWatchdog()
            } catch (re: Exception) {
                Log.e(TAG, "Watchdog: failed to reschedule: ${re.message}", re)
            }
            Result.success()
        }
    }

    /**
     * Shows a notification to the NFO when tracking becomes stale.
     * Prompts user to reopen the app to resume tracking.
     *
     * Uses the same notification channel as [TrackingForegroundService].
     */
    private fun showStaleNotification() {
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
                Log.d(TAG, "Watchdog: created notification channel ${TrackingForegroundService.CHANNEL_ID}")
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
            Log.d(TAG, "Watchdog: stale notification shown successfully (id=$STALE_NOTIFICATION_ID)")
        } catch (e: SecurityException) {
            // Notification permission might be denied on Android 13+
            Log.w(TAG, "Watchdog: could not show notification (permission denied): ${e.message}")
        }
    }

    /**
     * Reschedules this worker to run again after [WATCHDOG_INTERVAL_MINUTES].
     * Uses OneTimeWorkRequest pattern to achieve sub-15-minute intervals.
     */
    private fun rescheduleWatchdog() {
        HealthWatchdogScheduler.scheduleOneTime(applicationContext)
    }
}
