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

private const val TAG = "DeviceHealthChecker"

data class DeviceHealthStatus(
    val locationPermissionOk: Boolean,
    val backgroundLocationOk: Boolean,
    val locationEnabled: Boolean,
    val batteryOptimizationOk: Boolean,
    val networkOk: Boolean
) {
    // CRITICAL: must be true to allow "Continue"
    val allCriticalOk: Boolean
        get() = locationPermissionOk && locationEnabled && networkOk

    // Nice-to-have: perfect configuration
    val isHealthy: Boolean
        get() = allCriticalOk && backgroundLocationOk && batteryOptimizationOk
}

object DeviceHealthChecker {

    fun getHealthStatus(context: Context): DeviceHealthStatus {
        val locationPermissionOk = hasLocationPermission(context)
        val backgroundLocationOk = hasBackgroundLocationPermission(context)
        val locationEnabled = isLocationEnabled(context)
        val batteryOk = isBatteryOptimizationOk(context)
        val networkOk = isNetworkAvailable(context)

        Log.d(
            TAG,
            "health: locPerm=$locationPermissionOk, bgLoc=$backgroundLocationOk, " +
                    "gps=$locationEnabled, batteryOk=$batteryOk, netOk=$networkOk"
        )

        return DeviceHealthStatus(
            locationPermissionOk = locationPermissionOk,
            backgroundLocationOk = backgroundLocationOk,
            locationEnabled = locationEnabled,
            batteryOptimizationOk = batteryOk,
            networkOk = networkOk
        )
    }

    // ----------- CHECKS -----------

    /**
     * Returns true if either ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION is granted.
     * This is PUBLIC so it can be used to guard foreground service starts.
     */
    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Location permission: fine=$fineGranted, coarse=$coarseGranted")

        // Treat either FINE or COARSE as OK
        return fineGranted || coarseGranted
    }

    private fun hasBackgroundLocationPermission(context: Context): Boolean {
        // Background location is "recommended", not required
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "Background location: SDK<29 → treated as OK")
            return true
        }

        // If the app does not even request ACCESS_BACKGROUND_LOCATION, treat as OK
        val hasBgInManifest = try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            pkgInfo.requestedPermissions?.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect manifest for background location", e)
            false
        }

        if (!hasBgInManifest) {
            Log.d(TAG, "Background location: not requested in manifest → treated as OK")
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
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        Log.d(TAG, "Location enabled: $enabled")
        return enabled
    }

    private fun isBatteryOptimizationOk(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        Log.d(TAG, "Battery optimization: ignoring=$ignoring")
        return ignoring
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Log.d(TAG, "Network available: $ok")
        return ok
    }

    // ----------- INTENTS TO OPEN SETTINGS -----------

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
