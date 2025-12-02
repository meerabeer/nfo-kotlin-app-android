package com.nfo.tracker.ui

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nfo.tracker.device.DeviceHealthChecker
import com.nfo.tracker.ui.theme.NfoKotlinAppTheme

private const val TAG = "PermissionGateScreen"

/**
 * A gate screen shown at app launch to request Location (mandatory) and
 * Notification (optional) permissions before letting the user into the main app.
 *
 * This ensures we have location permission BEFORE any foreground service is started,
 * which is required on Android 14+ (targetSdk 34+) with FOREGROUND_SERVICE_TYPE_LOCATION.
 *
 * @param onAllPermissionsGranted Called when location permission is granted and user can proceed.
 */
@Composable
fun PermissionGateScreen(
    onAllPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current

    // Notification permission launcher (optional, Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Notification permission result: granted=$granted")
        // We don't block on this - notification is optional
    }

    // Location permission launcher (mandatory)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        Log.d(TAG, "Location permission result: fine=$fineGranted, coarse=$coarseGranted")

        if (fineGranted || coarseGranted) {
            // Location is granted – proceed to the main app
            Log.d(TAG, "Location permission granted, proceeding to main app")
            onAllPermissionsGranted()
        } else {
            // User denied - they stay on this screen
            Log.w(TAG, "Location permission denied, user must grant to continue")
        }
    }

    // Check if location permission is already granted
    val alreadyHasLocation = DeviceHealthChecker.hasLocationPermission(context)

    // If user already granted location earlier, skip this gate
    LaunchedEffect(alreadyHasLocation) {
        if (alreadyHasLocation) {
            Log.d(TAG, "Location permission already granted, skipping gate")
            onAllPermissionsGranted()
        }
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to NFO Tracker",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "To track your shift and location reliably, please allow Location and Notifications permissions.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Location permission is required to start tracking.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                Log.d(TAG, "Enable & Continue clicked")

                // 1) Request notification permission on Android 13+ (optional, non-blocking)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Log.d(TAG, "Requesting POST_NOTIFICATIONS permission...")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                // 2) Request location permissions (mandatory)
                if (!DeviceHealthChecker.hasLocationPermission(context)) {
                    Log.d(TAG, "Requesting location permissions...")
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else {
                    // Already has location permission
                    Log.d(TAG, "Location permission already granted, proceeding")
                    onAllPermissionsGranted()
                }
            }
        ) {
            Text("Enable & Continue")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PermissionGateScreenPreview() {
    NfoKotlinAppTheme {
        PermissionGateScreen(
            onAllPermissionsGranted = {}
        )
    }
}
