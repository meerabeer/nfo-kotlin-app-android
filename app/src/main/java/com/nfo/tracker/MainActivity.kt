package com.nfo.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.ui.theme.NfoKotlinAppTheme
import com.nfo.tracker.work.HeartbeatWorker
import com.nfo.tracker.work.HealthWatchdogScheduler
import com.nfo.tracker.work.ShiftStateHelper
import kotlinx.coroutines.launch

/** SharedPreferences file for tracking state (shared with HealthWatchdogWorker). */
private const val PREFS_NAME = "nfo_tracker_prefs"
private const val KEY_ON_SHIFT = "on_shift"

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Launcher for POST_NOTIFICATIONS permission (Android 13+)
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register notification permission launcher BEFORE setContent
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission GRANTED")
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission DENIED - notifications will be silent")
            }
        }

        // Request notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission...")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS permission already granted")
            }
        }

        enableEdgeToEdge()
        setContent {
            NfoKotlinAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TrackingScreen()
                }
            }
        }
    }
}

private fun enqueueImmediateHeartbeatSync(context: Context) {
    val workRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>().build()
    WorkManager.getInstance(context.applicationContext).enqueue(workRequest)
}

/**
 * Persists the on-shift state to SharedPreferences.
 * This is read by HealthWatchdogWorker to decide whether to check heartbeat freshness.
 */
private fun saveOnShiftState(context: Context, isOnShift: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
        putBoolean(KEY_ON_SHIFT, isOnShift)
    }
}

@Composable
fun TrackingScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var onShift by remember { mutableStateOf(false) }
    var isGoingOffShift by remember { mutableStateOf(false) }  // Loading state for off-shift

    // Launcher for requesting location permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            // Now we have location permission, start tracking
            saveOnShiftState(context, true)
            TrackingForegroundService.start(context)
            HeartbeatWorker.schedule(context)
            HealthWatchdogScheduler.schedule(context)
            enqueueImmediateHeartbeatSync(context)
        } else {
            // Permission denied – reset state
            saveOnShiftState(context, false)
            onShift = false
        }
    }

    val statusText = if (onShift) {
        "On shift – tracking active"
    } else {
        "Off shift – tracking stopped"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NFO Tracker (Kotlin)",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        Button(
            onClick = {
                if (!onShift) {
                    // Going ON shift
                    val fineGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val coarseGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (fineGranted || coarseGranted) {
                        // Already have permission – start tracking directly
                        onShift = true
                        saveOnShiftState(context, true)
                        TrackingForegroundService.start(context)
                        HeartbeatWorker.schedule(context)
                        HealthWatchdogScheduler.schedule(context)
                        enqueueImmediateHeartbeatSync(context)
                    } else {
                        // Ask for permission, onShift will be set in the callback if granted
                        onShift = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                } else {
                    // Going OFF shift - send final heartbeat before stopping
                    isGoingOffShift = true
                    coroutineScope.launch {
                        val username = ShiftStateHelper.getUsername(context)
                        val displayName = ShiftStateHelper.getDisplayName(context)

                        Log.d("MainActivity", "Going off shift, sending final heartbeat for $username")

                        // Send the off-shift heartbeat (waits for sync attempt)
                        val syncSuccess = ShiftStateHelper.sendOffShiftHeartbeat(
                            context = context,
                            username = username,
                            name = displayName
                        )

                        Log.d(
                            "MainActivity",
                            "Off-shift heartbeat sent, sync=${if (syncSuccess) "SUCCESS" else "PENDING"}. " +
                                "Stopping tracking service..."
                        )

                        // After heartbeat is written (and sync attempted), stop everything
                        onShift = false
                        saveOnShiftState(context, false)
                        TrackingForegroundService.stop(context)
                        HealthWatchdogScheduler.cancel(context)
                        // Note: HeartbeatWorker keeps running as backup to retry failed syncs

                        isGoingOffShift = false
                    }
                }
            },
            enabled = !isGoingOffShift  // Disable button while going off shift
        ) {
            if (isGoingOffShift) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Ending Shift...",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                Text(text = if (onShift) "Go Off Shift" else "Go On Shift")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TrackingScreenPreview() {
    NfoKotlinAppTheme {
        TrackingScreen()
    }
}