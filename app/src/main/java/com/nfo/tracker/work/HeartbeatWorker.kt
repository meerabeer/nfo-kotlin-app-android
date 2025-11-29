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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = HeartbeatDatabase.getInstance(applicationContext)
            val dao = db.heartbeatDao()

            // Pull a small batch of unsynced heartbeats
            val unsynced = dao.getUnsynced(limit = 100)

            if (unsynced.isEmpty()) {
                Log.d("HeartbeatWorker", "No unsynced heartbeats to sync")
                return@withContext Result.success()
            }

            Log.d("HeartbeatWorker", "Syncing ${unsynced.size} heartbeats to Supabase")

            val success = SupabaseClient.syncHeartbeats(unsynced)

            if (success) {
                val ids = unsynced.map { it.localId }
                dao.markAsSynced(ids)
                dao.deleteSynced()
                Log.d("HeartbeatWorker", "Successfully synced ${ids.size} heartbeats")
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
