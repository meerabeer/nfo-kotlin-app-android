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
    onBack: () -> Unit,
    onNavigateToDeviceSetup: () -> Unit = {}
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

            // Health check items - CRITICAL (required)
            HealthCheckItem(
                label = "Location Permission",
                isOk = status.locationPermissionOk,
                isCritical = true,
                onFix = onOpenAppSettings
            )

            HealthCheckItem(
                label = "Location Enabled",
                isOk = status.locationEnabled,
                isCritical = true,
                onFix = onOpenLocationSettings
            )

            HealthCheckItem(
                label = "Network Connection",
                isOk = status.networkOk,
                isCritical = true,
                onFix = onRefresh,
                fixButtonText = "Retry"
            )

            // RECOMMENDED (not required)
            HealthCheckItem(
                label = "Background Location",
                description = if (status.backgroundLocationOk) "OK" else "Fix recommended",
                isOk = status.backgroundLocationOk,
                isCritical = false,  // Recommended, not required
                onFix = onOpenAppSettings
            )

            HealthCheckItem(
                label = "Battery Optimization",
                description = if (status.batteryOptimizationOk) "Unrestricted" else "Fix recommended",
                isOk = status.batteryOptimizationOk,
                isCritical = false,  // Recommended, not required
                onFix = onOpenBatterySettings
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
                    enabled = status.allCriticalOk,  // Only critical items block Continue
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Continue")
                }
            }

            if (!status.allCriticalOk) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Fix required items above to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!status.batteryOptimizationOk || !status.backgroundLocationOk) {
                // All critical OK but some recommended items not fixed
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recommended: Fix battery / background location for best reliability",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Device Setup Wizard link - always visible
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onNavigateToDeviceSetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("Open Device Setup Wizard")
            }
            Text(
                text = "Get step-by-step instructions for your device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * A single health check row with status indicator and optional fix button.
 *
 * @param label The name of the health check
 * @param isOk Whether the check passes
 * @param onFix Action to open settings to fix the issue
 * @param description Custom description text (optional)
 * @param isCritical If true, shows as error when not OK; if false, shows as warning/recommended
 * @param fixButtonText Text for the fix button
 */
@Composable
private fun HealthCheckItem(
    label: String,
    isOk: Boolean,
    onFix: () -> Unit,
    description: String? = null,
    isCritical: Boolean = true,
    fixButtonText: String = "Fix"
) {
    // Non-critical items that aren't OK show as warning (amber), not error (red)
    val showAsWarning = !isOk && !isCritical
    val warningColor = Color(0xFFFF9800) // Amber/Orange

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isOk -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                showAsWarning -> Color(0xFFFFF3E0).copy(alpha = 0.5f) // Light amber
                else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
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
                text = when {
                    isOk -> "✅"
                    showAsWarning -> "⚠️"  // Warning for non-critical
                    else -> "❌"  // Error for critical
                },
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
                    text = description ?: if (isOk) "OK" else if (isCritical) "Fix required" else "Fix recommended",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isOk -> Color(0xFF4CAF50) // Green
                        showAsWarning -> warningColor // Amber
                        else -> MaterialTheme.colorScheme.error // Red
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
                locationPermissionOk = true,
                backgroundLocationOk = true,
                locationEnabled = true,
                batteryOptimizationOk = true,
                networkOk = true
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
                locationPermissionOk = true,
                backgroundLocationOk = false,
                locationEnabled = true,
                batteryOptimizationOk = false,
                networkOk = true
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
