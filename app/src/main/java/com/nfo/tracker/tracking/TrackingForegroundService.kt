package com.nfo.tracker.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nfo.tracker.MainActivity
import com.nfo.tracker.R
import com.nfo.tracker.data.local.HeartbeatDatabase
import com.nfo.tracker.data.local.HeartbeatEntity
import com.nfo.tracker.data.remote.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nfo_tracking_channel"
        const val CHANNEL_NAME = "NFO Tracking"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nfo.tracker.action.START_TRACKING"
        const val ACTION_STOP = "com.nfo.tracker.action.STOP_TRACKING"
        const val ACTION_START_FROM_WATCHDOG = "com.nfo.tracker.action.START_FROM_WATCHDOG"

        fun start(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d("TrackingService", "Starting tracking (user initiated)")
                startForeground(NOTIFICATION_ID, createNotification())
                startLocationUpdates()
            }
            ACTION_START_FROM_WATCHDOG -> {
                Log.w("TrackingService", "Restarting tracking (watchdog initiated - service was stale)")
                startForeground(NOTIFICATION_ID, createNotification())
                startLocationUpdates()
            }
            ACTION_STOP -> {
                Log.d("TrackingService", "Stopping tracking")
                stopLocationUpdates()
                stopForeground(true)
                stopSelf()
            }
        }
        // If the system kills the service, try to recreate it later with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            // No permission – nothing to do, let the activity handle asking the user
            stopSelf()
            return
        }

        if (locationCallback != null) {
            // Already running
            return
        }

        // Simple request for now: update every 30 seconds with balanced power accuracy
        val request = LocationRequest.Builder(
            30_000L
        )
            .setMinUpdateIntervalMillis(15_000L)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                val now = System.currentTimeMillis()

                // Build a heartbeat row with all timestamp fields set
                val heartbeat = HeartbeatEntity(
                    username = "NFO_TEST", // TODO: replace with real logged-in username
                    name = null,
                    onShift = true,
                    status = "on-shift",
                    activity = "tracking",
                    siteId = null,
                    workOrderId = null,
                    lat = location.latitude,
                    lng = location.longitude,
                    updatedAt = now,
                    loggedIn = true,
                    lastPing = now,
                    lastActiveSource = "mobile-app-gps",
                    lastActiveAt = now,
                    homeLocation = null,
                    createdAtLocal = now,
                    synced = false
                )

                Log.d(
                    "TrackingService",
                    "Location heartbeat: lat=${location.latitude}, lng=${location.longitude}"
                )

                // Upsert into DB and immediately sync to Supabase (Uber-style)
                serviceScope.launch {
                    val db = HeartbeatDatabase.getInstance(applicationContext)
                    val dao = db.heartbeatDao()

                    // Upsert (replace if username exists) and get the generated local_id
                    val localId = dao.upsert(heartbeat)
                    val entityWithId = heartbeat.copy(localId = localId)

                    Log.d(
                        "TrackingService",
                        "Upserted heartbeat id=$localId, attempting immediate sync..."
                    )

                    // Immediately try to sync this single heartbeat to Supabase
                    val success = SupabaseClient.syncHeartbeats(listOf(entityWithId))

                    if (success) {
                        dao.markAsSynced(listOf(localId))
                        dao.deleteSynced()
                        Log.d("TrackingService", "Immediate sync success for id=$localId")
                    } else {
                        Log.w("TrackingService", "Immediate sync failed for id=$localId, worker will retry later")
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
        locationCallback = null
    }

    private fun createNotification(): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFO Tracker – tracking active")
            .setContentText("On shift • Tap to open")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
