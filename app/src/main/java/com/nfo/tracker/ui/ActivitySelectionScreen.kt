package com.nfo.tracker.ui

import android.util.Log
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nfo.tracker.data.remote.SupabaseClient
import com.nfo.tracker.work.ShiftStateHelper
import kotlinx.coroutines.launch

private const val TAG = "ActivitySelectionScreen"

/** Fixed list of activity types */
private val ACTIVITY_OPTIONS = listOf(
    "Outage",
    "Alarm",
    "Performance",
    "HO5",
    "PMR",
    "Audit",
    "Survey"
)

/**
 * Screen for selecting the current activity context:
 * - Activity type (from fixed list)
 * - Site (from Supabase Site_Coordinates)
 * - Via Warehouse flag
 * - Warehouse name (from Supabase warehouses, only if Via Warehouse is true)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySelectionScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Data loading state
    var isLoading by remember { mutableStateOf(true) }
    var sites by remember { mutableStateOf<List<SupabaseClient.SiteDto>>(emptyList()) }
    var warehouses by remember { mutableStateOf<List<SupabaseClient.WarehouseDto>>(emptyList()) }

    // Read initial context from ShiftStateHelper
    val initialActivity = remember { ShiftStateHelper.getCurrentActivity(context) }
    val initialSiteId = remember { ShiftStateHelper.getCurrentSiteId(context) }
    val initialViaWarehouse = remember { ShiftStateHelper.isViaWarehouse(context) }
    val initialWarehouseName = remember { ShiftStateHelper.getWarehouseName(context) }
    val username = remember { ShiftStateHelper.getUsername(context) ?: "Unknown" }

    // Form state
    var selectedActivity by remember { mutableStateOf(initialActivity ?: "") }
    var selectedSiteId by remember { mutableStateOf(initialSiteId ?: "") }
    var viaWarehouse by remember { mutableStateOf(initialViaWarehouse) }
    var selectedWarehouseName by remember { mutableStateOf(initialWarehouseName ?: "") }

    // Dropdown expanded states
    var activityDropdownExpanded by remember { mutableStateOf(false) }
    var siteDropdownExpanded by remember { mutableStateOf(false) }
    var warehouseDropdownExpanded by remember { mutableStateOf(false) }

    // Site search/filter state
    var siteSearchQuery by remember { mutableStateOf("") }
    // Track whether the user has typed in the search box (to avoid clearing initial selection on first render)
    var siteSearchHasBeenUsed by remember { mutableStateOf(false) }

    // When the site search query is cleared (after user has typed), reset the selected site.
    // This allows the user to reopen the dropdown and choose a new site.
    LaunchedEffect(siteSearchQuery) {
        if (siteSearchHasBeenUsed && siteSearchQuery.isBlank() && selectedSiteId.isNotBlank()) {
            // Only reset if user previously typed something and then cleared it
            Log.d(TAG, "Site search cleared – resetting selectedSiteId")
            selectedSiteId = ""
        }
    }

    // Filtered sites based on search query
    val filteredSites = sites.filter { site ->
        siteSearchQuery.isBlank() ||
        site.siteId.contains(siteSearchQuery, ignoreCase = true) ||
        (site.siteName?.contains(siteSearchQuery, ignoreCase = true) == true)
    }

    // Validation error
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Determine if there's an existing context
    val hasExistingContext = !initialActivity.isNullOrBlank() ||
            !initialSiteId.isNullOrBlank() ||
            initialViaWarehouse ||
            !initialWarehouseName.isNullOrBlank()

    // Load sites and warehouses on composition
    LaunchedEffect(Unit) {
        Log.d(TAG, "Loading sites and warehouses...")
        isLoading = true
        sites = SupabaseClient.fetchSites()
        warehouses = SupabaseClient.fetchWarehouses()
        isLoading = false
        Log.d(TAG, "Loaded ${sites.size} sites, ${warehouses.size} warehouses")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Current Activity")
                        Text(
                            text = "Logged in as $username",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Current context summary card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current Context",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val currentSummary = buildString {
                        append(if (selectedActivity.isNotBlank()) selectedActivity else "No activity")
                        append(" at ")
                        append(if (selectedSiteId.isNotBlank()) selectedSiteId else "-")
                        append(" via ")
                        append(
                            if (viaWarehouse && selectedWarehouseName.isNotBlank())
                                selectedWarehouseName
                            else
                                "Direct"
                        )
                    }
                    Text(
                        text = currentSummary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Activity selector
            Text(
                text = "Activity Type",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ExposedDropdownMenuBox(
                expanded = activityDropdownExpanded,
                onExpandedChange = { activityDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedActivity.ifBlank { "Select activity" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = activityDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = activityDropdownExpanded,
                    onDismissRequest = { activityDropdownExpanded = false }
                ) {
                    ACTIVITY_OPTIONS.forEach { activity ->
                        DropdownMenuItem(
                            text = { Text(activity) },
                            onClick = {
                                selectedActivity = activity
                                activityDropdownExpanded = false
                                errorMessage = null
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Site selector with search
            Text(
                text = "Site",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Site search TextField
            OutlinedTextField(
                value = siteSearchQuery,
                onValueChange = {
                    siteSearchQuery = it
                    // Mark that the user has interacted with the search box
                    siteSearchHasBeenUsed = true
                },
                label = { Text("Search Site") },
                placeholder = { Text("Type to filter by Site ID or name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                enabled = !isLoading && sites.isNotEmpty()
            )

            // Site dropdown (filtered)
            ExposedDropdownMenuBox(
                expanded = siteDropdownExpanded,
                onExpandedChange = { siteDropdownExpanded = it }
            ) {
                val displaySite = if (selectedSiteId.isNotBlank()) {
                    // Look in full sites list for display, not filtered
                    val site = sites.find { it.siteId == selectedSiteId }
                    if (site?.siteName != null) "${site.siteId} – ${site.siteName}" else selectedSiteId
                } else {
                    "Select site (${filteredSites.size} shown)"
                }
                OutlinedTextField(
                    value = displaySite,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    enabled = !isLoading && sites.isNotEmpty()
                )
                ExposedDropdownMenu(
                    expanded = siteDropdownExpanded,
                    onDismissRequest = { siteDropdownExpanded = false }
                ) {
                    // Use filteredSites instead of full sites list
                    filteredSites.forEach { site ->
                        val displayText = if (site.siteName != null) {
                            "${site.siteId} – ${site.siteName}"
                        } else {
                            site.siteId
                        }
                        DropdownMenuItem(
                            text = { Text(displayText) },
                            onClick = {
                                selectedSiteId = site.siteId
                                siteDropdownExpanded = false
                                errorMessage = null
                            }
                        )
                    }
                }
            }
            if (isLoading) {
                Text(
                    text = "Loading sites...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (sites.isEmpty()) {
                Text(
                    text = "No sites available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (filteredSites.isEmpty() && siteSearchQuery.isNotBlank()) {
                Text(
                    text = "No sites match \"$siteSearchQuery\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Via Warehouse switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Via Warehouse?",
                    style = MaterialTheme.typography.labelLarge
                )
                Switch(
                    checked = viaWarehouse,
                    onCheckedChange = {
                        viaWarehouse = it
                        if (!it) {
                            selectedWarehouseName = ""
                        }
                        errorMessage = null
                    }
                )
            }

            // Warehouse selector (only visible when viaWarehouse is true)
            if (viaWarehouse) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Warehouse",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = warehouseDropdownExpanded,
                    onExpandedChange = { warehouseDropdownExpanded = it }
                ) {
                    val displayWarehouse = if (selectedWarehouseName.isNotBlank()) {
                        val warehouse = warehouses.find { it.name == selectedWarehouseName }
                        if (warehouse?.region != null) {
                            "${warehouse.name} (${warehouse.region})"
                        } else {
                            selectedWarehouseName
                        }
                    } else {
                        "Select warehouse"
                    }
                    OutlinedTextField(
                        value = displayWarehouse,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = warehouseDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isLoading && warehouses.isNotEmpty()
                    )
                    ExposedDropdownMenu(
                        expanded = warehouseDropdownExpanded,
                        onDismissRequest = { warehouseDropdownExpanded = false }
                    ) {
                        warehouses.forEach { warehouse ->
                            val displayText = if (warehouse.region != null) {
                                "${warehouse.name} (${warehouse.region})"
                            } else {
                                warehouse.name
                            }
                            DropdownMenuItem(
                                text = { Text(displayText) },
                                onClick = {
                                    selectedWarehouseName = warehouse.name
                                    warehouseDropdownExpanded = false
                                    errorMessage = null
                                }
                            )
                        }
                    }
                }
                if (isLoading) {
                    Text(
                        text = "Loading warehouses...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (warehouses.isEmpty()) {
                    Text(
                        text = "No warehouses available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Close Activity button (only if there's existing context)
                if (hasExistingContext) {
                    TextButton(
                        onClick = {
                            Log.d(TAG, "Closing activity context")
                            ShiftStateHelper.clearCurrentActivityContext(context)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Activity cleared")
                            }
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Close Activity")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Start/Update Activity button
                Button(
                    onClick = {
                        // Validate
                        when {
                            selectedActivity.isBlank() -> {
                                errorMessage = "Please select an activity"
                            }
                            selectedSiteId.isBlank() -> {
                                errorMessage = "Please select a site"
                            }
                            viaWarehouse && selectedWarehouseName.isBlank() -> {
                                errorMessage = "Please select a warehouse"
                            }
                            else -> {
                                // Save to ShiftStateHelper
                                Log.d(
                                    TAG,
                                    "Saving activity context: activity=$selectedActivity, " +
                                            "siteId=$selectedSiteId, viaWarehouse=$viaWarehouse, " +
                                            "warehouseName=$selectedWarehouseName"
                                )
                                ShiftStateHelper.setCurrentActivityContext(
                                    context = context,
                                    activity = selectedActivity,
                                    siteId = selectedSiteId,
                                    viaWarehouse = viaWarehouse,
                                    warehouseName = if (viaWarehouse) selectedWarehouseName else null
                                )
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Activity saved")
                                }
                                onBack()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (hasExistingContext) "Update Activity" else "Start Activity")
                }
            }
        }
    }
}
