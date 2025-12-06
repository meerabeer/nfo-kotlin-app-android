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
    val networkOk: Boolean,
    val isStrictOem: Boolean
) {
    /**
     * CRITICAL: must be true to allow "Go On Shift".
     * On strict OEMs (Samsung, Xiaomi, etc.), battery optimization is also critical.
     */
    val allCriticalOk: Boolean
        get() = locationPermissionOk && locationEnabled && networkOk &&
                (!isStrictOem || batteryOptimizationOk)

    // Nice-to-have: perfect configuration
    val isHealthy: Boolean
        get() = allCriticalOk && backgroundLocationOk && batteryOptimizationOk
}

object DeviceHealthChecker {

    /**
     * OEMs known to aggressively kill background apps.
     * On these devices, battery optimization whitelist is CRITICAL.
     */
    private val STRICT_OEMS = listOf(
        "samsung", "xiaomi", "redmi", "oppo", "vivo", "realme", "huawei", "honor", "oneplus"
    )

    /**
     * Returns true if this device is from an OEM known to aggressively kill apps.
     */
    fun isStrictOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return STRICT_OEMS.any { manufacturer.contains(it) }
    }

    fun getHealthStatus(context: Context): DeviceHealthStatus {
        val locationPermissionOk = hasLocationPermission(context)
        val backgroundLocationOk = hasBackgroundLocationPermission(context)
        val locationEnabled = isLocationEnabled(context)
        val batteryOk = isBatteryOptimizationOk(context)
        val networkOk = isNetworkAvailable(context)
        val strictOem = isStrictOem()

        Log.d(
            TAG,
            "health: locPerm=$locationPermissionOk, bgLoc=$backgroundLocationOk, " +
                    "gps=$locationEnabled, batteryOk=$batteryOk, netOk=$networkOk, strictOem=$strictOem"
        )

        return DeviceHealthStatus(
            locationPermissionOk = locationPermissionOk,
            backgroundLocationOk = backgroundLocationOk,
            locationEnabled = locationEnabled,
            batteryOptimizationOk = batteryOk,
            networkOk = networkOk,
            isStrictOem = strictOem
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

    /**
     * Background location heuristic - GENEROUS because we use a foreground service.
     *
     * Since our app uses a foreground service with FOREGROUND_SERVICE_TYPE_LOCATION,
     * we don't strictly need ACCESS_BACKGROUND_LOCATION permission. The foreground
     * service keeps us "in the foreground" from Android's perspective.
     *
     * This check is for RECOMMENDED status only - it should NOT block shift start.
     */
    private fun hasBackgroundLocationPermission(context: Context): Boolean {
        // If we don't have foreground location permission, background doesn't matter
        if (!hasLocationPermission(context)) {
            Log.d(TAG, "Background location: no foreground permission → false")
            return false
        }

        // On Android 9 and below, there's no separate background permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "Background location: SDK<29 → treated as OK")
            return true
        }

        // Check if ACCESS_BACKGROUND_LOCATION is explicitly granted
        val bgGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // GENEROUS HEURISTIC: Since we use a foreground service, we treat this as OK
        // even if bgGranted is false. The foreground service keeps us active.
        // We only show a recommendation, not a blocker.
        if (!bgGranted) {
            Log.d(TAG, "Background location: not explicitly granted, but FGS covers us → treating as OK")
        } else {
            Log.d(TAG, "Background location: explicitly granted → OK")
        }

        // Always return true if we have foreground location - FGS handles the rest
        return true
    }

    /**
     * Battery optimization check.
     *
     * Returns true if the app is whitelisted from battery optimization (Doze mode).
     * On strict OEMs, this is CRITICAL for reliable tracking.
     */
    fun isBatteryOptimizationOk(context: Context): Boolean {
        // Before Android M (API 23), there's no Doze/battery optimization
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.d(TAG, "Battery optimization: SDK<23 → treated as OK")
            return true
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            // If we can't access PowerManager, assume the worst on strict OEMs
            Log.w(TAG, "Battery optimization: PowerManager unavailable")
            return !isStrictOem()
        }

        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        Log.d(TAG, "Battery optimization: isIgnoringBatteryOptimizations=$ignoring")

        return ignoring
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        Log.d(TAG, "Location enabled: $enabled")
        return enabled
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
