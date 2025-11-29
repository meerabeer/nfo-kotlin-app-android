package com.nfo.tracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nfo.tracker.data.local.HeartbeatDatabase
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
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = HeartbeatDatabase.getInstance(applicationContext)
        val dao = db.heartbeatDao()

        // For now we just read unsynced rows and log how many we found.
        // Later this will push to Supabase and mark them as synced.
        val unsynced = dao.getUnsynced(limit = 200)
        // TODO: Replace with real logging and network sync
        println("HeartbeatWorker: found ${unsynced.size} unsynced heartbeats")

        Result.success()
    }
}
