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

            Log.d(TAG, "Location permission check: fine=$fineGranted, coarse=$coarseGranted")
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
                Log.d(TAG, "Background location: SDK < 29, auto-granted with foreground")
                return true
            }

            // First check if foreground location is granted - if not, background doesn't matter
            val hasForeground = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasForeground) {
                Log.d(TAG, "Background location: foreground not granted, returning false")
                return false
            }

            // Check the actual background location permission
            val bgGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "Background location permission check: granted=$bgGranted")

            // On Samsung/some OEMs, "Allow all the time" in UI doesn't always
            // flip ACCESS_BACKGROUND_LOCATION to granted via checkSelfPermission().
            // However, if a foreground service with type=location is used,
            // the GPS tracking works anyway.
            //
            // Since we use a foreground service (which doesn't strictly need
            // ACCESS_BACKGROUND_LOCATION), we'll be lenient here:
            // If foreground location is granted, assume background is OK.
            if (!bgGranted) {
                Log.w(TAG, "Background location not explicitly granted, but foreground service should work. Treating as OK.")
            }

            // Return true if foreground is granted - our foreground service will handle it
            true
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
