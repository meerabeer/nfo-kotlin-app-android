package com.nfo.tracker.work

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Scheduler utility for the Health Watchdog Worker.
 *
 * Provides methods to schedule and cancel the watchdog that monitors
 * heartbeat freshness while the user is on shift. Uses a self-rescheduling
 * OneTimeWorkRequest pattern to achieve ~1 minute check intervals
 * (since PeriodicWorkRequest minimum is 15 minutes).
 *
 * Usage:
 * - Call [schedule] when tracking starts (user goes on shift).
 * - Call [cancel] when tracking stops (user goes off shift or logs out).
 */
object HealthWatchdogScheduler {

    private const val TAG = "HealthWatchdogScheduler"

    /**
     * Schedules the health watchdog to start checking after an initial delay.
     * Call this when the user goes on shift and tracking starts.
     *
     * @param context Application or Activity context.
     */
    fun schedule(context: Context) {
        Log.d(TAG, "Scheduling health watchdog (initial delay: ${HealthWatchdogWorker.WATCHDOG_INTERVAL_MINUTES}m)")

        val workRequest = OneTimeWorkRequestBuilder<HealthWatchdogWorker>()
            .setInitialDelay(
                HealthWatchdogWorker.WATCHDOG_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            HealthWatchdogWorker.WATCHDOG_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Schedules the next watchdog check (called internally by the worker).
     * Uses REPLACE policy so we don't accumulate duplicate work.
     *
     * @param context Application context.
     */
    internal fun scheduleOneTime(context: Context) {
        Log.d(TAG, "Rescheduling watchdog for next check in ${HealthWatchdogWorker.WATCHDOG_INTERVAL_MINUTES}m")

        val workRequest = OneTimeWorkRequestBuilder<HealthWatchdogWorker>()
            .setInitialDelay(
                HealthWatchdogWorker.WATCHDOG_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            HealthWatchdogWorker.WATCHDOG_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Cancels the health watchdog.
     * Call this when the user goes off shift, logs out, or tracking stops.
     *
     * @param context Application or Activity context.
     */
    fun cancel(context: Context) {
        Log.d(TAG, "Cancelling health watchdog")
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(HealthWatchdogWorker.WATCHDOG_UNIQUE_WORK_NAME)
    }
}
