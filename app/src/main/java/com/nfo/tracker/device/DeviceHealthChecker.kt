package com.nfo.tracker.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Represents the current health status of the device for NFO tracking.
 * All checks must pass for the NFO to go on shift.
 */
data class DeviceHealthStatus(
    val hasFineLocationPermission: Boolean,
    val hasBackgroundLocationPermission: Boolean,
    val isLocationEnabled: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val hasNetworkConnection: Boolean
) {
    /**
     * Returns true if all CRITICAL checks pass (excluding battery optimization).
     * Battery optimization is recommended but not required to start tracking.
     */
    val allCriticalOk: Boolean
        get() = hasFineLocationPermission &&
                hasBackgroundLocationPermission &&
                isLocationEnabled &&
                hasNetworkConnection

    /**
     * Returns true if the device is fully configured for reliable tracking.
     * Includes battery optimization (ideal state).
     */
    val isHealthy: Boolean
        get() = allCriticalOk && isIgnoringBatteryOptimizations
}

/**
 * Utility object for checking device health and opening system settings.
 *
 * Used to gate the "Go On Shift" action - NFO cannot start tracking
 * unless all device health checks pass.
 */
object DeviceHealthChecker {

    private const val TAG = "DeviceHealthChecker"

    /**
     * Performs all device health checks and returns the current status.
     *
     * @param context Application or Activity context.
     * @return [DeviceHealthStatus] with the result of each check.
     */
    fun getHealthStatus(context: Context): DeviceHealthStatus {
        val status = DeviceHealthStatus(
            hasFineLocationPermission = checkFineLocationPermission(context),
            hasBackgroundLocationPermission = checkBackgroundLocationPermission(context),
            isLocationEnabled = checkLocationEnabled(context),
            isIgnoringBatteryOptimizations = checkBatteryOptimization(context),
            hasNetworkConnection = checkNetworkConnection(context)
        )

        Log.d(
            TAG,
            "Health check: fine=${status.hasFineLocationPermission}, " +
                "background=${status.hasBackgroundLocationPermission}, " +
                "locationEnabled=${status.isLocationEnabled}, " +
                "batteryOptIgnored=${status.isIgnoringBatteryOptimizations}, " +
                "network=${status.hasNetworkConnection}, " +
                "isHealthy=${status.isHealthy}"
        )

        return status
    }

    /**
     * Opens the app details settings page where user can grant permissions.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened app settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}", e)
        }
    }

    /**
     * Opens the system location settings page.
     */
    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened location settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open location settings: ${e.message}", e)
        }
    }

    /**
     * Opens the battery optimization settings page.
     * Falls back to app settings if the specific intent is not available.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            // Try to open the battery optimization settings directly
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened battery optimization settings")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS not available, trying app settings: ${e.message}")
            // Fall back to app details settings
            openAppSettings(context)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private check methods
    // ═══════════════════════════════════════════════════════════════════════════

    private fun checkFineLocationPermission(context: Context): Boolean {
        return try {
            // Accept either fine OR coarse location permission
            val fineGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            fineGranted || coarseGranted
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location permission: ${e.message}", e)
            false
        }
    }

    private fun checkBackgroundLocationPermission(context: Context): Boolean {
        return try {
            // Background location permission only exists on Android 10+ (API 29+)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // On older Android, background location is granted with foreground location
                return true
            }

            // Check if ACCESS_BACKGROUND_LOCATION is even requested in the manifest
            // If not requested, don't fail on it
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val requestedPermissions = info.requestedPermissions?.toList().orEmpty()
            val requestsBackgroundLocation = Manifest.permission.ACCESS_BACKGROUND_LOCATION in requestedPermissions

            if (!requestsBackgroundLocation) {
                Log.d(TAG, "ACCESS_BACKGROUND_LOCATION not in manifest, skipping check")
                return true
            }

            // Check if the permission is granted
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking background location permission: ${e.message}", e)
            false
        }
    }

    private fun checkLocationEnabled(context: Context): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null) {
                Log.w(TAG, "LocationManager not available")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+: Use isLocationEnabled()
                locationManager.isLocationEnabled
            } else {
                // Older APIs: Check if GPS or Network provider is enabled
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location enabled: ${e.message}", e)
            false
        }
    }

    private fun checkBatteryOptimization(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager == null) {
                Log.w(TAG, "PowerManager not available")
                return false
            }

            // Returns true if the app is on the battery optimization whitelist
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization: ${e.message}", e)
            false
        }
    }

    private fun checkNetworkConnection(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager not available")
                return false
            }

            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            // Check for Wi-Fi or cellular connectivity
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network connection: ${e.message}", e)
            false
        }
    }
}
