package com.nfo.tracker.work

import android.content.Context
import android.util.Log
import com.nfo.tracker.data.local.HeartbeatDatabase
import com.nfo.tracker.data.local.HeartbeatEntity
import com.nfo.tracker.data.remote.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper object to send "final" heartbeats when the user ends their shift or logs out.
 *
 * These heartbeats immediately update Supabase so the manager dashboard reflects
 * the real state (off-shift or logged-out) as soon as the NFO takes action.
 *
 * Usage:
 * - Call [sendOffShiftHeartbeat] when the user goes off shift (still logged in).
 * - Call [sendLogoutHeartbeat] when the user logs out entirely (future feature).
 */
object ShiftStateHelper {

    private const val TAG = "ShiftStateHelper"

    /** SharedPreferences file used by the app to store user identity. */
    private const val PREFS_NAME = "nfo_tracker_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_HOME_LOCATION = "home_location"

    // Activity context keys
    private const val KEY_CURRENT_ACTIVITY = "current_activity"
    private const val KEY_CURRENT_SITE_ID = "current_site_id"
    private const val KEY_VIA_WAREHOUSE = "via_warehouse"
    private const val KEY_WAREHOUSE_NAME = "warehouse_name_current"

    /**
     * Returns the current username from SharedPreferences.
     * Returns null if no user is logged in.
     */
    fun getUsername(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, null)
    }

    /**
     * Returns the current display name from SharedPreferences.
     * May be null if not set.
     */
    fun getDisplayName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISPLAY_NAME, null)
    }

    /**
     * Returns true if the user is logged in.
     */
    fun isLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    /**
     * Returns the home location of the logged-in user.
     * May be null if not set.
     */
    fun getHomeLocation(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOME_LOCATION, null)
    }

    /**
     * Saves the logged-in user's information to SharedPreferences.
     *
     * @param context Application or Activity context.
     * @param username The user's unique identifier (from NFOUsers.Username).
     * @param displayName The user's display name (from NFOUsers.name), may be null.
     * @param homeLocation The user's home location (from NFOUsers.home_location), may be null.
     */
    fun setLoggedInUser(
        context: Context,
        username: String,
        displayName: String?,
        homeLocation: String?
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_HOME_LOCATION, homeLocation)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        Log.d(TAG, "User logged in: username=$username, displayName=$displayName, homeLocation=$homeLocation")
    }

    /**
     * Clears the user's login state from SharedPreferences.
     * Sets is_logged_in = false and removes username, display name, and home location.
     */
    fun clearUser(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_HOME_LOCATION)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
        Log.d(TAG, "User cleared from SharedPreferences")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Activity Context Methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Saves the current activity context to SharedPreferences.
     *
     * @param context Application or Activity context.
     * @param activity The current activity type (e.g., "Normal Tracking", "PMR Visit").
     * @param siteId The current site ID.
     * @param viaWarehouse Whether the NFO is going via a warehouse.
     * @param warehouseName The warehouse name if viaWarehouse is true.
     */
    fun setCurrentActivityContext(
        context: Context,
        activity: String?,
        siteId: String?,
        viaWarehouse: Boolean,
        warehouseName: String?
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CURRENT_ACTIVITY, activity)
            .putString(KEY_CURRENT_SITE_ID, siteId)
            .putBoolean(KEY_VIA_WAREHOUSE, viaWarehouse)
            .putString(KEY_WAREHOUSE_NAME, warehouseName)
            .apply()
        Log.d(TAG, "Activity context saved: activity=$activity, siteId=$siteId, viaWarehouse=$viaWarehouse, warehouseName=$warehouseName")
    }

    /**
     * Returns the current activity type from SharedPreferences.
     * May be null if not set.
     */
    fun getCurrentActivity(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_ACTIVITY, null)
    }

    /**
     * Returns the current site ID from SharedPreferences.
     * May be null if not set.
     */
    fun getCurrentSiteId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_SITE_ID, null)
    }

    /**
     * Returns whether the NFO is currently going via a warehouse.
     */
    fun isViaWarehouse(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VIA_WAREHOUSE, false)
    }

    /**
     * Returns the current warehouse name from SharedPreferences.
     * May be null if not set or if viaWarehouse is false.
     */
    fun getWarehouseName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_WAREHOUSE_NAME, null)
    }

    /**
     * Clears the current activity context but keeps the logged-in user.
     * Resets activity, siteId, viaWarehouse, and warehouseName to defaults.
     */
    fun clearCurrentActivityContext(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CURRENT_ACTIVITY)
            .remove(KEY_CURRENT_SITE_ID)
            .remove(KEY_VIA_WAREHOUSE)
            .remove(KEY_WAREHOUSE_NAME)
            .apply()
        Log.d(TAG, "Activity context cleared")
    }

    /**
     * Sends a final "off-shift" heartbeat to Room and Supabase.
     *
     * This should be called when the user goes off shift but remains logged in.
     * The heartbeat will have:
     * - on_shift = false
     * - status = "off-shift"
     * - activity = null
     * - site_id = null
     * - logged_in = true
     * - last_active_source = "manual-shift-toggle"
     *
     * @param context Application or Activity context.
     * @param username The NFO's username (unique identifier in nfo_status).
     * @param name The NFO's display name (optional).
     * @return true if sync succeeded, false if it failed (row still in Room for retry).
     */
    suspend fun sendOffShiftHeartbeat(
        context: Context,
        username: String,
        name: String?
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending off-shift heartbeat for username=$username")

        val db = HeartbeatDatabase.getInstance(context)
        val dao = db.heartbeatDao()

        val now = System.currentTimeMillis()

        // Try to get last known location from the most recent heartbeat
        val lastHeartbeat = dao.getLastHeartbeat()
        val lat = lastHeartbeat?.lat
        val lng = lastHeartbeat?.lng

        // Build the final off-shift heartbeat
        val offShiftHeartbeat = HeartbeatEntity(
            username = username,
            name = name,
            onShift = false,
            status = "off-shift",
            activity = null,
            siteId = null,
            workOrderId = null,
            lat = lat,
            lng = lng,
            updatedAt = now,
            loggedIn = true,  // Still logged in, just off duty
            lastPing = now,
            lastActiveSource = "manual-shift-toggle",
            lastActiveAt = now,
            homeLocation = lastHeartbeat?.homeLocation,
            createdAtLocal = now,
            synced = false
        )

        // Upsert into Room (replaces existing row for this username)
        val localId = dao.upsert(offShiftHeartbeat)
        val entityWithId = offShiftHeartbeat.copy(localId = localId)

        Log.d(TAG, "Upserted off-shift heartbeat id=$localId, attempting immediate sync...")

        // Immediately try to sync to Supabase
        val success = SupabaseClient.syncHeartbeats(listOf(entityWithId))

        if (success) {
            dao.markAsSynced(listOf(localId))
            Log.d(TAG, "Off-shift heartbeat sync SUCCESS for username=$username")
        } else {
            Log.w(
                TAG,
                "Off-shift heartbeat sync FAILED for username=$username. " +
                    "Row saved locally (synced=false), HeartbeatWorker will retry."
            )
        }

        // Clear local activity context so next shift starts clean
        clearCurrentActivityContext(context)
        Log.d(TAG, "Cleared activity context after off-shift heartbeat")

        success
    }

    /**
     * Sends a final "logout" heartbeat to Room and Supabase.
     *
     * This should be called when the user logs out entirely.
     * The heartbeat will have:
     * - on_shift = false
     * - status = "off-shift" (or "logged-out" if we use that)
     * - activity = null
     * - site_id = null
     * - logged_in = false
     * - last_active_source = "manual-shift-toggle"
     *
     * @param context Application or Activity context.
     * @param username The NFO's username (unique identifier in nfo_status).
     * @param name The NFO's display name (optional).
     * @return true if sync succeeded, false if it failed (row still in Room for retry).
     */
    suspend fun sendLogoutHeartbeat(
        context: Context,
        username: String,
        name: String?
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending logout heartbeat for username=$username")

        val db = HeartbeatDatabase.getInstance(context)
        val dao = db.heartbeatDao()

        val now = System.currentTimeMillis()

        // Try to get last known location from the most recent heartbeat
        val lastHeartbeat = dao.getLastHeartbeat()
        val lat = lastHeartbeat?.lat
        val lng = lastHeartbeat?.lng

        // Build the final logout heartbeat
        val logoutHeartbeat = HeartbeatEntity(
            username = username,
            name = name,
            onShift = false,
            status = "off-shift",  // Could be "logged-out" if we have that status
            activity = null,
            siteId = null,
            workOrderId = null,
            lat = lat,
            lng = lng,
            updatedAt = now,
            loggedIn = false,  // Logged out
            lastPing = now,
            lastActiveSource = "manual-shift-toggle",
            lastActiveAt = now,
            homeLocation = lastHeartbeat?.homeLocation,
            createdAtLocal = now,
            synced = false
        )

        // Upsert into Room (replaces existing row for this username)
        val localId = dao.upsert(logoutHeartbeat)
        val entityWithId = logoutHeartbeat.copy(localId = localId)

        Log.d(TAG, "Upserted logout heartbeat id=$localId, attempting immediate sync...")

        // Immediately try to sync to Supabase
        val success = SupabaseClient.syncHeartbeats(listOf(entityWithId))

        if (success) {
            dao.markAsSynced(listOf(localId))
            Log.d(TAG, "Logout heartbeat sync SUCCESS for username=$username")
        } else {
            Log.w(
                TAG,
                "Logout heartbeat sync FAILED for username=$username. " +
                    "Row saved locally (synced=false), HeartbeatWorker will retry."
            )
        }

        // Clear local activity context before clearing user
        clearCurrentActivityContext(context)
        Log.d(TAG, "Cleared activity context after logout heartbeat")

        success
    }
}
