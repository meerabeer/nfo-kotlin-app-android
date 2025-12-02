package com.nfo.tracker.ui

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nfo.tracker.device.DeviceHealthStatus
import com.nfo.tracker.ui.theme.NfoKotlinAppTheme

/**
 * Screen shown when device health checks fail before going on shift.
 * Displays each health check status and provides buttons to fix issues.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHealthScreen(
    status: DeviceHealthStatus,
    onRefresh: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Setup") },
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "Device setup required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Fix these items before going on shift.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Health check items
            HealthCheckItem(
                label = "Location Permission",
                isOk = status.hasFineLocationPermission,
                onFix = onOpenAppSettings
            )

            HealthCheckItem(
                label = "Background Location",
                isOk = status.hasBackgroundLocationPermission,
                onFix = onOpenAppSettings
            )

            HealthCheckItem(
                label = "Location Enabled",
                isOk = status.isLocationEnabled,
                onFix = onOpenLocationSettings
            )

            HealthCheckItem(
                label = "Battery Optimization",
                description = if (status.isIgnoringBatteryOptimizations) "Unrestricted" else "Restricted",
                isOk = status.isIgnoringBatteryOptimizations,
                onFix = onOpenBatterySettings
            )

            HealthCheckItem(
                label = "Network Connection",
                isOk = status.hasNetworkConnection,
                onFix = onRefresh,
                fixButtonText = "Retry"
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }

                Button(
                    onClick = onContinue,
                    enabled = status.isHealthy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Continue")
                }
            }

            if (!status.isHealthy) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Fix all issues above to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * A single health check row with status indicator and optional fix button.
 */
@Composable
private fun HealthCheckItem(
    label: String,
    isOk: Boolean,
    onFix: () -> Unit,
    description: String? = null,
    fixButtonText: String = "Fix"
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOk) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Text(
                text = if (isOk) "✅" else "⚠️",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Label and description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description ?: if (isOk) "OK" else "Fix required",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOk) {
                        Color(0xFF4CAF50) // Green
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            // Fix button (only shown when not OK)
            if (!isOk) {
                OutlinedButton(
                    onClick = onFix
                ) {
                    Text(fixButtonText)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceHealthScreenPreview_AllOk() {
    NfoKotlinAppTheme {
        DeviceHealthScreen(
            status = DeviceHealthStatus(
                hasFineLocationPermission = true,
                hasBackgroundLocationPermission = true,
                isLocationEnabled = true,
                isIgnoringBatteryOptimizations = true,
                hasNetworkConnection = true
            ),
            onRefresh = {},
            onOpenLocationSettings = {},
            onOpenBatterySettings = {},
            onOpenAppSettings = {},
            onContinue = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceHealthScreenPreview_SomeIssues() {
    NfoKotlinAppTheme {
        DeviceHealthScreen(
            status = DeviceHealthStatus(
                hasFineLocationPermission = true,
                hasBackgroundLocationPermission = false,
                isLocationEnabled = true,
                isIgnoringBatteryOptimizations = false,
                hasNetworkConnection = true
            ),
            onRefresh = {},
            onOpenLocationSettings = {},
            onOpenBatterySettings = {},
            onOpenAppSettings = {},
            onContinue = {},
            onBack = {}
        )
    }
}
