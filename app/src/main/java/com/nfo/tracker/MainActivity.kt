package com.nfo.tracker

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nfo.tracker.device.DeviceHealthChecker
import com.nfo.tracker.device.DeviceHealthStatus
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.ui.DeviceHealthScreen
import com.nfo.tracker.ui.DeviceSetupWizardScreen
import com.nfo.tracker.ui.DiagnosticsScreen
import com.nfo.tracker.ui.LoginScreen
import com.nfo.tracker.ui.PermissionGateScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            NfoKotlinAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppFlow()
                }
            }
        }
    }
}

/**
 * Main app flow that shows login if needed, then gates the user behind permission screen.
 * Once logged in and location permission is granted, shows the main tracking screen.
 * Uses NavHost for navigation to Login, Device Setup Wizard and Diagnostics screens.
 */
@Composable
fun MainAppFlow() {
    val context = LocalContext.current

    // Determine the start destination based on login state
    val isLoggedIn = remember { ShiftStateHelper.isLoggedIn(context) }
    val hasUsername = remember { !ShiftStateHelper.getUsername(context).isNullOrBlank() }

    val startDestination = if (isLoggedIn && hasUsername) {
        "permission_gate"
    } else {
        "login"
    }

    Log.d("MainActivity", "MainAppFlow: isLoggedIn=$isLoggedIn, hasUsername=$hasUsername, startDestination=$startDestination")

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    Log.d("MainActivity", "Login successful, navigating to permission_gate")
                    navController.navigate("permission_gate") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("permission_gate") {
            PermissionGateScreen(
                onAllPermissionsGranted = {
                    Log.d("MainActivity", "Permission gate passed, navigating to tracking")
                    navController.navigate("tracking") {
                        popUpTo("permission_gate") { inclusive = true }
                    }
                }
            )
        }

        composable("tracking") {
            TrackingScreen(
                onNavigateToDeviceSetup = {
                    navController.navigate("device_setup_wizard")
                },
                onNavigateToDiagnostics = {
                    navController.navigate("diagnostics")
                }
            )
        }

        composable("device_setup_wizard") {
            DeviceSetupWizardScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("diagnostics") {
            DiagnosticsScreen(
                onBack = { navController.popBackStack() }
            )
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

/**
 * Actually starts the shift and tracking services.
 * This should only be called after all device health checks pass.
 */
private fun actuallyStartShiftAndTracking(context: Context) {
    Log.d("MainActivity", "Starting shift and tracking services...")
    saveOnShiftState(context, true)
    TrackingForegroundService.start(context)
    HeartbeatWorker.schedule(context)
    HealthWatchdogScheduler.schedule(context)
    enqueueImmediateHeartbeatSync(context)
    Log.d("MainActivity", "Shift started, tracking active")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(
    onNavigateToDeviceSetup: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var onShift by remember { mutableStateOf(false) }
    var isGoingOffShift by remember { mutableStateOf(false) }  // Loading state for off-shift

    // Device health gate state
    var showDeviceHealthScreen by remember { mutableStateOf(false) }
    var deviceHealthStatus by remember { mutableStateOf<DeviceHealthStatus?>(null) }

    // Dropdown menu state
    var showMenu by remember { mutableStateOf(false) }

    // Location permission launcher - requests permission before starting foreground service
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        Log.d("MainActivity", "Location permission result: fine=$fineGranted, coarse=$coarseGranted")

        if (fineGranted || coarseGranted) {
            // Permission granted - now we can safely start tracking
            Log.d("MainActivity", "Location permission granted, starting shift...")
            onShift = true
            actuallyStartShiftAndTracking(context)
        } else {
            Log.w("MainActivity", "Location permission denied, cannot start tracking")
            // Reset state - user denied permission
            showDeviceHealthScreen = false
        }
    }

    /**
     * Actually starts the shift and tracking. Called when all health checks pass
     * AND location permission is confirmed granted.
     */
    fun startShift() {
        // Double-check permission before starting (defensive)
        if (!DeviceHealthChecker.hasLocationPermission(context)) {
            Log.d("MainActivity", "startShift: Permission not granted, requesting...")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        onShift = true
        actuallyStartShiftAndTracking(context)
    }

    /**
     * Handles the "Go On Shift" button click.
     * Checks device health and either starts tracking or shows the health screen.
     */
    fun handleGoOnShiftClicked() {
        Log.d("MainActivity", "Go On Shift clicked, checking device health...")

        val status = DeviceHealthChecker.getHealthStatus(context)
        deviceHealthStatus = status

        if (status.isHealthy) {
            Log.d("MainActivity", "Device is healthy, starting shift immediately")
            startShift()
        } else {
            Log.d("MainActivity", "Device health issues detected, showing health screen")
            showDeviceHealthScreen = true
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI: Show DeviceHealthScreen or main TrackingScreen
    // ═══════════════════════════════════════════════════════════════════════════

    if (showDeviceHealthScreen && deviceHealthStatus != null) {
        DeviceHealthScreen(
            status = deviceHealthStatus!!,
            onRefresh = {
                Log.d("MainActivity", "Health screen: Refresh clicked")
                val newStatus = DeviceHealthChecker.getHealthStatus(context)
                deviceHealthStatus = newStatus
            },
            onOpenLocationSettings = {
                Log.d("MainActivity", "Health screen: Opening location settings")
                DeviceHealthChecker.openLocationSettings(context)
            },
            onOpenBatterySettings = {
                Log.d("MainActivity", "Health screen: Opening battery settings")
                DeviceHealthChecker.openBatteryOptimizationSettings(context)
            },
            onOpenAppSettings = {
                Log.d("MainActivity", "Health screen: Opening app settings")
                DeviceHealthChecker.openAppSettings(context)
            },
            onContinue = {
                Log.d("MainActivity", "Health screen: Continue clicked (device is healthy)")
                showDeviceHealthScreen = false
                startShift()
            },
            onBack = {
                Log.d("MainActivity", "Health screen: Back clicked")
                showDeviceHealthScreen = false
                onShift = false
            },
            onNavigateToDeviceSetup = onNavigateToDeviceSetup
        )
    } else {
        // Main tracking screen
        val statusText = if (onShift) {
            "On shift – tracking active"
        } else {
            "Off shift – tracking stopped"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NFO Tracker") },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Device Setup") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToDeviceSetup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToDiagnostics()
                                }
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
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
                            // Going ON shift - check device health first
                            handleGoOnShiftClicked()
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

                Spacer(modifier = Modifier.height(32.dp))

                // Quick access buttons for Device Setup and Diagnostics
                // These are always visible below the main shift button
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedButton(
                        onClick = onNavigateToDeviceSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("Device Setup")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onNavigateToDiagnostics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("Diagnostics")
                    }
                }
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