package com.nfo.tracker.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nfo.tracker.ui.theme.NfoKotlinAppTheme

/**
 * OEM-specific Device Setup Wizard that provides step-by-step guidance
 * for configuring battery and background settings on different device brands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSetupWizardScreen(
    onBack: () -> Unit
) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val model = Build.MODEL

    // Determine OEM category
    val oemType = when {
        manufacturer.contains("samsung") -> OemType.SAMSUNG
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> OemType.XIAOMI
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> OemType.HUAWEI
        else -> OemType.OTHER
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Setup – ${oemType.brandName}") },
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
            // Device info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Your Device",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$manufacturer $model".replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Why this matters
            Text(
                text = "Why configure these settings?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "NFO Tracker needs to run continuously in the background to send your location " +
                        "to the operations dashboard. Some phones aggressively stop background apps " +
                        "to save battery, which can interrupt tracking during your shift.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // OEM-specific instructions
            Text(
                text = "Steps to configure your ${oemType.brandName} device",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Show steps based on OEM type
            when (oemType) {
                OemType.SAMSUNG -> SamsungSetupSteps()
                OemType.XIAOMI -> XiaomiSetupSteps()
                OemType.HUAWEI -> HuaweiSetupSteps()
                OemType.OTHER -> GenericSetupSteps()
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Footer note
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ℹ️ Note",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "If any step is not available on your phone, skip it and continue. " +
                                "Menu names may vary slightly depending on your software version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private enum class OemType(val brandName: String) {
    SAMSUNG("Samsung"),
    XIAOMI("Xiaomi"),
    HUAWEI("Huawei"),
    OTHER("Your Device")
}

@Composable
private fun SamsungSetupSteps() {
    SetupStepsList(
        steps = listOf(
            SetupStep(
                title = "1. Disable battery optimization",
                instructions = listOf(
                    "Open Settings → Apps → NFO Tracker",
                    "Tap Battery",
                    "Select \"Unrestricted\" (not \"Optimized\")"
                )
            ),
            SetupStep(
                title = "2. Add to \"Never sleeping apps\"",
                instructions = listOf(
                    "Open Settings → Battery and device care → Battery",
                    "Tap \"Background usage limits\"",
                    "Tap \"Never sleeping apps\"",
                    "Add NFO Tracker to the list"
                )
            ),
            SetupStep(
                title = "3. Disable \"Put unused apps to sleep\"",
                instructions = listOf(
                    "In Settings → Battery and device care → Battery",
                    "Tap \"Background usage limits\"",
                    "Turn OFF \"Put unused apps to sleep\""
                )
            ),
            SetupStep(
                title = "4. Allow background location",
                instructions = listOf(
                    "Open Settings → Apps → NFO Tracker → Permissions",
                    "Tap Location",
                    "Select \"Allow all the time\""
                )
            )
        )
    )
}

@Composable
private fun XiaomiSetupSteps() {
    SetupStepsList(
        steps = listOf(
            SetupStep(
                title = "1. Enable Autostart",
                instructions = listOf(
                    "Open Settings → Apps → Manage apps",
                    "Find and tap NFO Tracker",
                    "Tap \"Autostart\" and enable it"
                )
            ),
            SetupStep(
                title = "2. Disable battery saver for the app",
                instructions = listOf(
                    "In the same app settings page",
                    "Tap \"Battery saver\"",
                    "Select \"No restrictions\""
                )
            ),
            SetupStep(
                title = "3. Lock the app in recent apps",
                instructions = listOf(
                    "Open NFO Tracker",
                    "Open recent apps (swipe up and hold)",
                    "Long-press on NFO Tracker card",
                    "Tap the lock icon to prevent it from being killed"
                )
            ),
            SetupStep(
                title = "4. Allow background location",
                instructions = listOf(
                    "Open Settings → Apps → Manage apps → NFO Tracker",
                    "Tap Permissions → Location",
                    "Select \"Allow all the time\""
                )
            ),
            SetupStep(
                title = "5. Disable MIUI battery optimization",
                instructions = listOf(
                    "Open Settings → Battery & performance",
                    "Tap \"Choose apps\" under Battery Saver",
                    "Find NFO Tracker",
                    "Select \"No restrictions\""
                )
            )
        )
    )
}

@Composable
private fun HuaweiSetupSteps() {
    SetupStepsList(
        steps = listOf(
            SetupStep(
                title = "1. Enable app launch management",
                instructions = listOf(
                    "Open Settings → Apps → Apps",
                    "Find and tap NFO Tracker",
                    "Tap \"Launch\" or \"App launch\"",
                    "Disable \"Manage automatically\"",
                    "Enable all toggles: Auto-launch, Secondary launch, Run in background"
                )
            ),
            SetupStep(
                title = "2. Disable battery optimization",
                instructions = listOf(
                    "Open Settings → Battery → App launch",
                    "Find NFO Tracker",
                    "Set to \"Manage manually\" and enable all options"
                )
            ),
            SetupStep(
                title = "3. Ignore battery optimization",
                instructions = listOf(
                    "Open Settings → Apps → Apps → NFO Tracker",
                    "Tap Battery",
                    "Tap \"App launch\" and enable everything",
                    "Also check \"Ignore optimizations\" if available"
                )
            ),
            SetupStep(
                title = "4. Allow background location",
                instructions = listOf(
                    "Open Settings → Apps → Apps → NFO Tracker",
                    "Tap Permissions → Location",
                    "Select \"Allow all the time\""
                )
            ),
            SetupStep(
                title = "5. Lock app in recent tasks",
                instructions = listOf(
                    "Open NFO Tracker",
                    "Open recent apps",
                    "Swipe down on NFO Tracker card to lock it"
                )
            )
        )
    )
}

@Composable
private fun GenericSetupSteps() {
    SetupStepsList(
        steps = listOf(
            SetupStep(
                title = "1. Disable battery optimization",
                instructions = listOf(
                    "Open Settings → Apps → NFO Tracker",
                    "Find Battery or Power settings",
                    "Select \"Unrestricted\" or \"Don't optimize\""
                )
            ),
            SetupStep(
                title = "2. Allow background activity",
                instructions = listOf(
                    "In app settings, look for \"Background activity\" or similar",
                    "Ensure background activity is allowed/enabled"
                )
            ),
            SetupStep(
                title = "3. Allow background location",
                instructions = listOf(
                    "Open Settings → Apps → NFO Tracker → Permissions",
                    "Tap Location",
                    "Select \"Allow all the time\" (not just \"While using\")"
                )
            ),
            SetupStep(
                title = "4. Check for \"sleeping apps\" feature",
                instructions = listOf(
                    "Some phones have a \"sleeping apps\" or \"standby apps\" feature",
                    "Make sure NFO Tracker is excluded from this list"
                )
            ),
            SetupStep(
                title = "5. Disable auto-start restrictions",
                instructions = listOf(
                    "If your phone has an \"Autostart\" manager",
                    "Enable autostart for NFO Tracker"
                )
            )
        )
    )
}

private data class SetupStep(
    val title: String,
    val instructions: List<String>
)

@Composable
private fun SetupStepsList(steps: List<SetupStep>) {
    steps.forEach { step ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                step.instructions.forEach { instruction ->
                    Text(
                        text = "• $instruction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceSetupWizardScreenPreview() {
    NfoKotlinAppTheme {
        DeviceSetupWizardScreen(onBack = {})
    }
}
