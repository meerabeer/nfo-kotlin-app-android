package com.nfo.tracker.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nfo.tracker.data.local.HeartbeatDatabase
import com.nfo.tracker.data.local.HeartbeatEntity
import com.nfo.tracker.device.DeviceHealthChecker
import com.nfo.tracker.device.DeviceHealthStatus
import com.nfo.tracker.tracking.TrackingForegroundService
import com.nfo.tracker.ui.theme.NfoKotlinAppTheme
import com.nfo.tracker.work.ShiftStateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics screen for debugging field issues.
 * Shows current shift state, last heartbeat, device health, and tracking service status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Diagnostics state
    var diagnosticsData by remember { mutableStateOf<DiagnosticsData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Function to load diagnostics data
    suspend fun loadDiagnostics(): DiagnosticsData {
        return withContext(Dispatchers.IO) {
            val db = HeartbeatDatabase.getInstance(context)
            val lastHeartbeat = db.heartbeatDao().getLastHeartbeat()

            DiagnosticsData(
                username = ShiftStateHelper.getUsername(context),
                displayName = ShiftStateHelper.getDisplayName(context),
                isOnShift = isOnShiftFromPrefs(context),
                lastHeartbeat = lastHeartbeat,
                healthStatus = DeviceHealthChecker.getHealthStatus(context),
                isTrackingServiceRunning = TrackingForegroundService.isRunning()
            )
        }
    }

    // Load on first composition
    LaunchedEffect(Unit) {
        diagnosticsData = loadDiagnostics()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (isLoading) {
                Text(
                    text = "Loading diagnostics...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                diagnosticsData?.let { data ->
                    // Section 1: Shift State
                    DiagnosticsSection(title = "Shift State") {
                        DiagnosticsRow(label = "Username", value = data.username)
                        DiagnosticsRow(
                            label = "Display Name",
                            value = data.displayName ?: "(not set)"
                        )
                        DiagnosticsRow(
                            label = "On Shift",
                            value = if (data.isOnShift) "Yes" else "No",
                            valueColor = if (data.isOnShift) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Section 2: Last Heartbeat
                    DiagnosticsSection(title = "Last Heartbeat") {
                        if (data.lastHeartbeat != null) {
                            val heartbeat = data.lastHeartbeat
                            DiagnosticsRow(
                                label = "Timestamp",
                                value = formatTimestamp(heartbeat.createdAtLocal)
                            )
                            DiagnosticsRow(
                                label = "On Shift",
                                value = if (heartbeat.onShift) "Yes" else "No"
                            )
                            DiagnosticsRow(
                                label = "Status",
                                value = heartbeat.status ?: "(null)"
                            )
                            DiagnosticsRow(
                                label = "Location",
                                value = if (heartbeat.lat != null && heartbeat.lng != null) {
                                    "%.6f, %.6f".format(heartbeat.lat, heartbeat.lng)
                                } else {
                                    "(no location)"
                                }
                            )
                            DiagnosticsRow(
                                label = "Synced",
                                value = if (heartbeat.synced) "Yes" else "No (pending)",
                                valueColor = if (heartbeat.synced) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                        } else {
                            Text(
                                text = "No heartbeat recorded yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Section 3: Device Health
                    DiagnosticsSection(title = "Device Health") {
                        val status = data.healthStatus
                        HealthStatusRow(
                            label = "Location Permission",
                            isOk = status.locationPermissionOk
                        )
                        HealthStatusRow(
                            label = "Location Enabled",
                            isOk = status.locationEnabled
                        )
                        HealthStatusRow(
                            label = "Network",
                            isOk = status.networkOk
                        )
                        HealthStatusRow(
                            label = "Background Location",
                            isOk = status.backgroundLocationOk,
                            isRecommended = true
                        )
                        HealthStatusRow(
                            label = "Battery Optimization",
                            isOk = status.batteryOptimizationOk,
                            isRecommended = true
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Section 4: Tracking Service
                    DiagnosticsSection(title = "Tracking Service") {
                        DiagnosticsRow(
                            label = "Service Status",
                            value = if (data.isTrackingServiceRunning) "RUNNING" else "STOPPED",
                            valueColor = if (data.isTrackingServiceRunning) {
                                Color(0xFF4CAF50)
                            } else {
                                Color(0xFFFF9800)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Run Diagnostics button
                    Button(
                        onClick = {
                            isLoading = true
                            coroutineScope.launch {
                                diagnosticsData = loadDiagnostics()
                                isLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run Diagnostics")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Data class holding all diagnostic information.
 */
private data class DiagnosticsData(
    val username: String,
    val displayName: String?,
    val isOnShift: Boolean,
    val lastHeartbeat: HeartbeatEntity?,
    val healthStatus: DeviceHealthStatus,
    val isTrackingServiceRunning: Boolean
)

/**
 * Reads on-shift state from SharedPreferences.
 */
private fun isOnShiftFromPrefs(context: Context): Boolean {
    val prefs = context.getSharedPreferences("nfo_tracker_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("on_shift", false)
}

/**
 * Formats a timestamp to human-readable string.
 */
private fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        timestamp.toString()
    }
}

@Composable
private fun DiagnosticsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticsRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun HealthStatusRow(
    label: String,
    isOk: Boolean,
    isRecommended: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = when {
                isOk -> "✅ OK"
                isRecommended -> "⚠️ Recommended"
                else -> "❌ NOT OK"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when {
                isOk -> Color(0xFF4CAF50)
                isRecommended -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.error
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DiagnosticsScreenPreview() {
    NfoKotlinAppTheme {
        DiagnosticsScreen(onBack = {})
    }
}
