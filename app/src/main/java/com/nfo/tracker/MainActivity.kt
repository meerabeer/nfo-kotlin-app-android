package com.nfo.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** SharedPreferences file for tracking state (shared with HealthWatchdogWorker). */
private const val PREFS_NAME = "nfo_tracker_prefs"
private const val KEY_ON_SHIFT = "on_shift"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var onShift by remember { mutableStateOf(false) }

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
                    // Going OFF shift
                    onShift = false
                    saveOnShiftState(context, false)
                    TrackingForegroundService.stop(context)
                    HealthWatchdogScheduler.cancel(context)
                }
            }
        ) {
            Text(text = if (onShift) "Go Off Shift" else "Go On Shift")
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