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
 */
data class DeviceHealthStatus(
    val hasLocationPermission: Boolean,
    val hasBackgroundLocationPermission: Boolean,
    val isLocationEnabled: Boolean,
    val isBatteryOptimizationOk: Boolean,
    val isNetworkOk: Boolean
) {
    /**
     * Critical checks that MUST pass to start tracking:
     * - Location permission (fine or coarse)
     * - GPS/Location enabled
     * - Network connection
     *
     * Background location and battery are RECOMMENDED but NOT required.
     */
    val allCriticalOk: Boolean
        get() = hasLocationPermission && isLocationEnabled && isNetworkOk

    /**
     * Ideal state: all critical + recommended checks pass.
     */
    val isHealthy: Boolean
        get() = allCriticalOk && isBatteryOptimizationOk && hasBackgroundLocationPermission
}

/**
 * Utility object for checking device health and opening system settings.
 *
 * Used to gate the "Go On Shift" action - NFO cannot start tracking
 * unless critical device health checks pass.
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
        val hasLocationPermission = hasLocationPermission(context)
        val hasBackgroundLocationPermission = hasBackgroundLocationPermission(context)
        val isLocationEnabled = isLocationEnabled(context)
        val isBatteryOptimizationOk = isBatteryOptimizationOk(context)
        val isNetworkOk = isNetworkAvailable(context)

        val status = DeviceHealthStatus(
            hasLocationPermission = hasLocationPermission,
            hasBackgroundLocationPermission = hasBackgroundLocationPermission,
            isLocationEnabled = isLocationEnabled,
            isBatteryOptimizationOk = isBatteryOptimizationOk,
            isNetworkOk = isNetworkOk
        )

        Log.d(
            TAG,
            "Health check: " +
                "locationPerm=$hasLocationPermission, " +
                "bgLocationPerm=$hasBackgroundLocationPermission, " +
                "locationEnabled=$isLocationEnabled, " +
                "batteryOk=$isBatteryOptimizationOk, " +
                "networkOk=$isNetworkOk, " +
                "allCriticalOk=${status.allCriticalOk}, " +
                "isHealthy=${status.isHealthy}"
        )

        return status
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Permission check methods
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Location permission: fine=$fineGranted, coarse=$coarseGranted")
        return fineGranted || coarseGranted
    }

    private fun hasBackgroundLocationPermission(context: Context): Boolean {
        // For Android 9 and below there is no separate background permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "Background location: SDK < Q, auto-granted")
            return true
        }

        // Check if the app even requested ACCESS_BACKGROUND_LOCATION in the manifest.
        val hasBgInManifest = try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            info.requestedPermissions?.contains(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking manifest for background location: ${e.message}")
            false
        }

        // If not requested in manifest, treat as OK (recommended only).
        if (!hasBgInManifest) {
            Log.d(TAG, "Background location: not in manifest, treating as OK")
            return true
        }

        val bgGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Background location permission: granted=$bgGranted")
        return bgGranted
    }

    private fun isLocationEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            Log.d(TAG, "Location enabled: GPS=$gpsEnabled, Network=$networkEnabled")
            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location enabled: ${e.message}")
            false
        }
    }

    private fun isBatteryOptimizationOk(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            Log.d(TAG, "Battery optimization ignored: $ignoring")
            ignoring
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization: ${e.message}")
            false
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            Log.d(TAG, "Network available: cellular=$hasCellular, wifi=$hasWifi")
            hasCellular || hasWifi
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings openers
    // ═══════════════════════════════════════════════════════════════════════════

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
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened battery optimization settings")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS not available: ${e.message}")
            openAppSettings(context)
        }
    }
}
