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
import android.content.pm.ServiceInfo
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
import com.nfo.tracker.work.ShiftStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingForegroundService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        const val CHANNEL_ID = "nfo_tracking_channel"
        const val CHANNEL_NAME = "NFO Tracking"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nfo.tracker.action.START_TRACKING"
        const val ACTION_STOP = "com.nfo.tracker.action.STOP_TRACKING"
        const val ACTION_START_FROM_WATCHDOG = "com.nfo.tracker.action.START_FROM_WATCHDOG"
        const val ACTION_START_FROM_BOOT = "com.nfo.tracker.action.START_FROM_BOOT"

        /**
         * Read-only flag indicating whether the tracking service is currently running.
         * Used by DiagnosticsScreen to display service status.
         */
        @Volatile
        private var isRunningInternal: Boolean = false

        /**
         * Returns true if the tracking service is currently running.
         */
        fun isRunning(): Boolean = isRunningInternal

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
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    /**
     * Creates the notification channel for foreground service.
     * Must be called before startForeground() on Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when NFO location tracking is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // User-initiated start (e.g., tapped "Go On Shift" in MainActivity)
                // This is the ONLY path that should start location tracking.
                Log.d(TAG, "Starting tracking (user initiated)")
                startForegroundWithType()
                startLocationUpdates()
            }
            ACTION_START_FROM_WATCHDOG -> {
                // NO-OP: Android 13+ restricts starting FGS with type=location from WorkManager.
                // The watchdog now only marks device-silent and shows a notification.
                Log.w(TAG, "ACTION_START_FROM_WATCHDOG received but ignored (Android 13+ restriction). User must reopen app.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_FROM_BOOT -> {
                // NO-OP: Android 13+ restricts starting FGS with type=location from BOOT_COMPLETED.
                // BootReceiver now only schedules the watchdog.
                Log.w(TAG, "ACTION_START_FROM_BOOT received but ignored (Android 13+ restriction). User must reopen app.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping tracking")
                // Mark service as stopped immediately
                isRunningInternal = false
                stopLocationUpdates()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}, starting foreground anyway")
                startForegroundWithType()
            }
        }
        // If the system kills the service, try to recreate it later with a null intent
        return START_STICKY
    }

    /**
     * Checks if we have location permission (required before starting FGS with type=location).
     */
    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    /**
     * Starts foreground with the correct service type for Android Q+ (API 29+).
     * Must be called within a few seconds of service start.
     *
     * This wrapper checks for location permission first and catches SecurityException
     * to prevent crashes on Android 14/15 with targetSdk 34+.
     */
    private fun startForegroundWithType() {
        // DEFENSIVE: Check permission before calling startForeground with type=location
        if (!hasLocationPermission()) {
            Log.e(TAG, "Missing location permission, cannot start foreground service with type=location")
            stopSelf()
            return
        }

        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                Log.d(TAG, "startForeground called with FOREGROUND_SERVICE_TYPE_LOCATION")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "startForeground called (pre-Q)")
            }
            // Mark service as running after successful startForeground
            isRunningInternal = true
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException when starting location foreground service", se)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Mark service as stopped
        isRunningInternal = false
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

                // Get logged-in user info from ShiftStateHelper
                val username = ShiftStateHelper.getUsername(applicationContext)
                val displayName = ShiftStateHelper.getDisplayName(applicationContext)
                val homeLocation = ShiftStateHelper.getHomeLocation(applicationContext)

                // Get current activity context from ShiftStateHelper
                val currentActivity = ShiftStateHelper.getCurrentActivity(applicationContext)
                val currentSiteId = ShiftStateHelper.getCurrentSiteId(applicationContext)
                val currentViaWarehouse = ShiftStateHelper.isViaWarehouse(applicationContext)
                val currentWarehouseName = ShiftStateHelper.getWarehouseName(applicationContext)

                if (username.isNullOrBlank()) {
                    Log.w(TAG, "No logged-in user found! Username is null/blank. Heartbeat will use 'UNKNOWN'.")
                }

                val effectiveUsername = username ?: "UNKNOWN"

                // Build a heartbeat row with all timestamp fields set
                val heartbeat = HeartbeatEntity(
                    username = effectiveUsername,
                    name = displayName,
                    onShift = true,
                    status = "on-shift",
                    activity = currentActivity ?: "tracking",
                    siteId = currentSiteId,
                    workOrderId = null,
                    viaWarehouse = if (currentViaWarehouse) true else null,
                    warehouseName = currentWarehouseName,
                    lat = location.latitude,
                    lng = location.longitude,
                    updatedAt = now,
                    loggedIn = true,
                    lastPing = now,
                    lastActiveSource = "mobile-app-gps",
                    lastActiveAt = now,
                    homeLocation = homeLocation,
                    createdAtLocal = now,
                    synced = false
                )

                Log.d(
                    TAG,
                    "Location heartbeat: username=$effectiveUsername, lat=${location.latitude}, lng=${location.longitude}"
                )

                // Upsert into DB and immediately sync to Supabase (Uber-style)
                serviceScope.launch {
                    val db = HeartbeatDatabase.getInstance(applicationContext)
                    val dao = db.heartbeatDao()

                    // Upsert (replace if username exists) and get the generated local_id
                    val localId = dao.upsert(heartbeat)
                    val entityWithId = heartbeat.copy(localId = localId)

                    Log.d(
                        TAG,
                        "Upserted heartbeat id=$localId, attempting immediate sync..."
                    )

                    // Immediately try to sync this single heartbeat to Supabase
                    val success = SupabaseClient.syncHeartbeats(listOf(entityWithId))

                    if (success) {
                        dao.markAsSynced(listOf(localId))
                        // NOTE: Do NOT call deleteSynced() here!
                        // We keep the row so HealthWatchdogWorker can read it via getLastHeartbeat().
                        // The row will be replaced on the next upsert anyway (unique index on username).
                        Log.d(TAG, "Immediate sync success for id=$localId")
                    } else {
                        Log.w(TAG, "Immediate sync failed for id=$localId, worker will retry later")
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
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFO Tracker – tracking active")
            .setContentText("On shift • Tap to open")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
