package com.nfo.tracker.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nfo.tracker.data.local.HeartbeatDatabase
import com.nfo.tracker.data.remote.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class HeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_NAME = "heartbeat_sync_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Cancel the periodic heartbeat worker.
         * Called when the user goes off shift to stop unnecessary sync attempts.
         */
        fun cancel(context: Context) {
            Log.d("HeartbeatWorker", "Cancelling periodic heartbeat sync worker")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Early exit if user is not logged in or not on shift
        // This prevents unnecessary sync attempts when the user is off shift
        val isLoggedIn = ShiftStateHelper.isLoggedIn(applicationContext)
        val isOnShift = ShiftStateHelper.isOnShift(applicationContext)
        
        if (!isLoggedIn || !isOnShift) {
            Log.d("HeartbeatWorker", "Skipping sync: isLoggedIn=$isLoggedIn, isOnShift=$isOnShift")
            return@withContext Result.success()
        }
        
        try {
            val db = HeartbeatDatabase.getInstance(applicationContext)
            val dao = db.heartbeatDao()

            // Pull a small batch of unsynced heartbeats
            val unsynced = dao.getUnsynced(limit = 100)

            if (unsynced.isEmpty()) {
                Log.d("HeartbeatWorker", "No unsynced heartbeats to sync")
                return@withContext Result.success()
            }

            // Deduplicate: keep only the latest heartbeat per username
            // (Supabase upsert cannot handle multiple rows with the same username in one batch)
            val dedupedHeartbeats = unsynced
                .groupBy { it.username }
                .mapValues { (_, heartbeats) -> heartbeats.maxByOrNull { it.createdAtLocal } }
                .values
                .filterNotNull()

            Log.d(
                "HeartbeatWorker",
                "Syncing ${dedupedHeartbeats.size} heartbeats (deduped from ${unsynced.size} unsynced rows) to Supabase"
            )

            val success = SupabaseClient.syncHeartbeats(dedupedHeartbeats)

            if (success) {
                // Mark ALL original unsynced heartbeats as synced (not just the deduped ones)
                val ids = unsynced.map { it.localId }
                dao.markAsSynced(ids)
                dao.deleteSynced()
                Log.d("HeartbeatWorker", "Successfully synced, marked ${ids.size} local rows as synced")
                Result.success()
            } else {
                Log.e("HeartbeatWorker", "Supabase sync failed, will retry later")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("HeartbeatWorker", "Error while syncing heartbeats", e)
            Result.retry()
        }
    }
}
