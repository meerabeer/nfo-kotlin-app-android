package com.nfo.tracker.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Helper object to trigger immediate heartbeat sync.
 *
 * Used by [HealthWatchdogWorker] to sync "device-silent" status to Supabase
 * when tracking becomes stale, and by other parts of the app that need
 * to trigger an immediate sync attempt.
 */
object HeartbeatSyncHelper {

    private const val TAG = "HeartbeatSyncHelper"
    private const val IMMEDIATE_SYNC_WORK_NAME = "heartbeat_immediate_sync"

    /**
     * Enqueues an immediate one-time sync of unsynced heartbeats.
     * Uses network constraint so it will run when network becomes available.
     *
     * @param context Application or Activity context.
     */
    fun enqueueImmediateSync(context: Context) {
        Log.d(TAG, "Enqueueing immediate heartbeat sync")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(constraints)
            .build()

        // Use REPLACE policy so we don't queue multiple immediate syncs
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Immediate sync work enqueued (will run when network available)")
    }
}
