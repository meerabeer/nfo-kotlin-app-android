package com.nfo.tracker.work

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
 * - If on shift and heartbeat is fresh → logs healthy, returns success.
 * - If on shift and heartbeat is stale → restarts [TrackingForegroundService].
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

        /** Unique work name for WorkManager. */
        const val WATCHDOG_UNIQUE_WORK_NAME = "health_watchdog_work"

        /** SharedPreferences file for tracking state. */
        private const val PREFS_NAME = "nfo_tracker_prefs"
        private const val KEY_ON_SHIFT = "on_shift"
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

            if (lastHeartbeat == null) {
                Log.w(TAG, "On shift but no heartbeat found in DB. Service may not have started yet.")
                // Attempt to start service just in case
                restartTrackingService()
                rescheduleWatchdog()
                return@withContext Result.success()
            }

            // 3. Compute time difference
            val now = System.currentTimeMillis()
            val lastHeartbeatTime = lastHeartbeat.createdAtLocal
            val diffMillis = now - lastHeartbeatTime
            val diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
            val diffSeconds = TimeUnit.MILLISECONDS.toSeconds(diffMillis)

            Log.d(
                TAG,
                "Last heartbeat: id=${lastHeartbeat.localId}, username=${lastHeartbeat.username}, " +
                    "age=${diffMinutes}m ${diffSeconds % 60}s, synced=${lastHeartbeat.synced}, " +
                    "threshold=${WATCHDOG_STALE_MINUTES}m"
            )

            // 4. Check if stale
            if (diffMinutes >= WATCHDOG_STALE_MINUTES) {
                Log.w(
                    TAG,
                    "Heartbeat STALE! Last seen ${diffMinutes}m ago. Restarting tracking service..."
                )
                restartTrackingService()
                // TODO: Optionally insert an interruption event row here for backend analytics
            } else {
                Log.d(TAG, "Heartbeat healthy. Tracking is alive.")
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
