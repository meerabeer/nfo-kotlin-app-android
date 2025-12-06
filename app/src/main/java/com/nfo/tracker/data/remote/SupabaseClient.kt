package com.nfo.tracker.data.remote

import android.util.Log
import com.nfo.tracker.data.local.HeartbeatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

object SupabaseClient {

    private const val TAG = "SupabaseClient"

    private const val BASE_URL = "https://rzivbeaqfhamlpsfaqov.supabase.co"
    private const val API_KEY = "sb_publishable_Kifo4X6qxs6nkv7yilHRkA_7RapeV4a"
    private const val TABLE_ENDPOINT = "$BASE_URL/rest/v1/nfo_status?on_conflict=username"
    private const val NFOUSERS_ENDPOINT = "$BASE_URL/rest/v1/NFOusers"

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    // Use Asia/Riyadh (UTC+3) so Supabase shows same clock time as mobile device
    private val riyadhZone: ZoneId = ZoneId.of("Asia/Riyadh")

    /**
     * Data class representing a user from the NFOUsers table.
     */
    data class NfoUserDto(
        val username: String,
        val name: String?,
        val homeLocation: String?
    )

    /**
     * Data class representing a site from the Site_Coordinates table.
     */
    data class SiteDto(
        val siteId: String,
        val siteName: String?
    )

    /**
     * Data class representing a warehouse from the warehouses table.
     */
    data class WarehouseDto(
        val id: Long,
        val name: String,
        val region: String?
    )

    /**
     * Fetches all sites from the Site_Coordinates table in Supabase.
     * Uses pagination (offset/limit) to retrieve more than the default 1000 row limit.
     *
     * @return List of SiteDto, or empty list on error.
     */
    suspend fun fetchSites(): List<SiteDto> {
        return withContext(Dispatchers.IO) {
            val allSites = mutableListOf<SiteDto>()
            val pageSize = 1000
            var offset = 0
            val maxRows = 5000  // Safety cap to avoid infinite loops
            var keepFetching = true

            Log.d(TAG, "fetchSites() starting paginated fetch (pageSize=$pageSize, maxRows=$maxRows)")

            while (keepFetching && offset < maxRows) {
                try {
                    val url = okhttp3.HttpUrl.Builder()
                        .scheme("https")
                        .host("rzivbeaqfhamlpsfaqov.supabase.co")
                        .addPathSegments("rest/v1/Site_Coordinates")
                        .addQueryParameter("select", "site_id,site_name")
                        .addQueryParameter("limit", pageSize.toString())
                        .addQueryParameter("offset", offset.toString())
                        .build()

                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("apikey", API_KEY)
                        .addHeader("Authorization", "Bearer $API_KEY")
                        .addHeader("Content-Type", "application/json")
                        .build()

                    Log.d(TAG, "fetchSites() page request: offset=$offset, limit=$pageSize, URL=$url")

                    val response = client.newCall(request).execute()
                    val code = response.code
                    val responseBody = response.body?.string()
                    response.close()

                    if (code == 200 && responseBody != null) {
                        val jsonArray = JSONArray(responseBody)
                        val pageSites = mutableListOf<SiteDto>()

                        for (i in 0 until jsonArray.length()) {
                            val row = jsonArray.getJSONObject(i)
                            val siteId = row.optString("site_id", "")
                            if (siteId.isNotEmpty()) {
                                pageSites.add(
                                    SiteDto(
                                        siteId = siteId,
                                        siteName = row.optString("site_name", null).takeIf { it.isNotEmpty() }
                                    )
                                )
                            }
                        }

                        Log.d(TAG, "fetchSites() page offset=$offset returned ${pageSites.size} rows")
                        allSites.addAll(pageSites)

                        // If we got fewer rows than pageSize, we've reached the end
                        if (pageSites.size < pageSize) {
                            Log.d(TAG, "fetchSites() reached end of data (got ${pageSites.size} < $pageSize)")
                            keepFetching = false
                        } else {
                            offset += pageSize
                        }
                    } else {
                        Log.e(TAG, "fetchSites() page request failed: HTTP $code, body=$responseBody")
                        keepFetching = false  // Stop pagination on error, return what we have
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "fetchSites() page request error: ${e.message}", e)
                    keepFetching = false  // Stop pagination on error, return what we have
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSites() unexpected error: ${e.message}", e)
                    keepFetching = false  // Stop pagination on error, return what we have
                }
            }

            Log.d(TAG, "fetchSites() returned ${allSites.size} rows total (paged)")
            allSites
        }
    }

    /**
     * Fetches active warehouses from the warehouses table in Supabase.
     *
     * @return List of WarehouseDto, or empty list on error.
     */
    suspend fun fetchWarehouses(): List<WarehouseDto> {
        return withContext(Dispatchers.IO) {
            try {
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("rzivbeaqfhamlpsfaqov.supabase.co")
                    .addPathSegments("rest/v1/warehouses")
                    .addQueryParameter("select", "id,name,region,is_active")
                    .addQueryParameter("is_active", "eq.true")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .build()

                Log.d(TAG, "Fetching warehouses")

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val responseBody = response.body?.string()

                    if (code == 200 && responseBody != null) {
                        val jsonArray = JSONArray(responseBody)
                        val warehouses = mutableListOf<WarehouseDto>()

                        for (i in 0 until jsonArray.length()) {
                            val row = jsonArray.getJSONObject(i)
                            val id = row.optLong("id", -1L)
                            val name = row.optString("name", "")

                            if (id >= 0 && name.isNotEmpty()) {
                                warehouses.add(
                                    WarehouseDto(
                                        id = id,
                                        name = name,
                                        region = row.optString("region", null).takeIf { it.isNotEmpty() }
                                    )
                                )
                            }
                        }

                        Log.d(TAG, "Fetched ${warehouses.size} warehouses")
                        return@withContext warehouses
                    } else {
                        Log.e(TAG, "Failed to fetch warehouses: HTTP $code, body=$responseBody")
                        return@withContext emptyList()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error fetching warehouses: ${e.message}", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching warehouses: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * Authenticates a user against the NFOUsers table in Supabase.
     *
     * @param username The username to authenticate.
     * @param password The password to authenticate.
     * @return NfoUserDto if credentials are valid, null otherwise.
     */
    suspend fun loginNfoUser(username: String, password: String): NfoUserDto? {
        return withContext(Dispatchers.IO) {
            try {
                // Build URL with query parameters
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("rzivbeaqfhamlpsfaqov.supabase.co")
                    .addPathSegments("rest/v1/NFOusers")
                    .addQueryParameter("Username", "eq.$username")
                    .addQueryParameter("Password", "eq.$password")
                    .addQueryParameter("select", "Username,name,home_location")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .build()

                Log.d(TAG, "Attempting login for username=$username")

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val responseBody = try {
                        response.body?.string()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading login response body", e)
                        null
                    }

                    if (code == 200 && responseBody != null) {
                        val jsonArray = JSONArray(responseBody)
                        Log.d(TAG, "Login response: ${jsonArray.length()} rows returned")

                        if (jsonArray.length() == 1) {
                            val row = jsonArray.getJSONObject(0)
                            val user = NfoUserDto(
                                username = row.optString("Username", ""),
                                name = row.optString("name", null).takeIf { it.isNotEmpty() },
                                homeLocation = row.optString("home_location", null).takeIf { it.isNotEmpty() }
                            )
                            Log.d(TAG, "Login SUCCESS for username=$username, name=${user.name}")
                            return@withContext user
                        } else if (jsonArray.length() == 0) {
                            Log.w(TAG, "Login FAILED: Invalid credentials for username=$username")
                            return@withContext null
                        } else {
                            Log.w(TAG, "Login FAILED: Multiple users found for username=$username (unexpected)")
                            return@withContext null
                        }
                    } else {
                        Log.e(TAG, "Login FAILED: HTTP $code, body=$responseBody")
                        return@withContext null
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Login error: ${e.message}", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Login unexpected error: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Converts epoch millis (or seconds) to ISO-8601 string in Asia/Riyadh timezone.
     * Handles both epoch seconds (10 digits) and millis (13 digits).
     * Output example: 2025-12-01T21:10:30+03:00
     */
    private fun Long?.toRiyadhTime(): String? {
        if (this == null) return null
        // If the value looks like epoch seconds (10 digits), convert to millis.
        val millis = if (this < 3_000_000_000L) this * 1000L else this
        val instant = Instant.ofEpochMilli(millis)
        return OffsetDateTime.ofInstant(instant, riyadhZone).toString()
    }

    suspend fun syncHeartbeats(heartbeats: List<HeartbeatEntity>): Boolean {
        if (heartbeats.isEmpty()) return true

        val jsonArray = JSONArray()

        for (heartbeat in heartbeats) {
            // Debug log for heartbeat payload
            Log.d(
                TAG,
                "Sending heartbeat: username=${heartbeat.username}, activity=${heartbeat.activity}, " +
                    "site_id=${heartbeat.siteId}, via_warehouse=${heartbeat.viaWarehouse}, " +
                    "warehouse_name=${heartbeat.warehouseName}"
            )

            val obj = JSONObject().apply {
                put("username", heartbeat.username)
                put("name", heartbeat.name ?: JSONObject.NULL)
                put("on_shift", heartbeat.onShift ?: JSONObject.NULL)
                put("status", heartbeat.status ?: JSONObject.NULL)
                put("activity", heartbeat.activity ?: JSONObject.NULL)
                put("site_id", heartbeat.siteId ?: JSONObject.NULL)
                put("via_warehouse", heartbeat.viaWarehouse ?: JSONObject.NULL)
                put("warehouse_name", heartbeat.warehouseName ?: JSONObject.NULL)
                put("lat", heartbeat.lat ?: JSONObject.NULL)
                put("lng", heartbeat.lng ?: JSONObject.NULL)

                // Convert epoch → ISO with Asia/Riyadh (+03:00) so Supabase shows local KSA time
                put(
                    "updated_at",
                    heartbeat.updatedAt.toRiyadhTime() ?: JSONObject.NULL
                )
                put("logged_in", heartbeat.loggedIn ?: JSONObject.NULL)
                put(
                    "last_ping",
                    heartbeat.lastPing.toRiyadhTime() ?: JSONObject.NULL
                )
                put(
                    "last_active_source",
                    heartbeat.lastActiveSource ?: JSONObject.NULL
                )
                put(
                    "last_active_at",
                    heartbeat.lastActiveAt.toRiyadhTime() ?: JSONObject.NULL
                )
                put("home_location", heartbeat.homeLocation ?: JSONObject.NULL)
            }
            jsonArray.put(obj)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonArray.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(TABLE_ENDPOINT)
            .post(requestBody)
            .addHeader("apikey", API_KEY)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal,resolution=merge-duplicates")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val responseBody = try {
                        response.body?.string()
                    } catch (e: Exception) {
                        "error reading body: ${e.message}"
                    }

                    if (code in 200..299) {
                        Log.d(TAG, "Sync success: code=$code, rows=${heartbeats.size}")
                        return@withContext true
                    } else if (code == 409) {
                        Log.w(TAG, "Upsert conflict (409), treating as success; rows=${heartbeats.size}")
                        return@withContext true
                    } else {
                        Log.e(
                            TAG,
                            "Sync failed: code=$code, body=$responseBody, rows=${heartbeats.size}"
                        )
                        return@withContext false
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Sync error: ${e.message}", e)
                false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Device Registration (FCM Token Upload)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Upserts a device record to the nfo_devices table.
     * Used to register/update the FCM token for push notifications.
     *
     * The table uses (username, device_id) as a composite unique key.
     * This allows one user to have multiple devices, each with its own FCM token.
     *
     * @param username The NFO's username.
     * @param deviceId The unique Android device ID (ANDROID_ID).
     * @param fcmToken The Firebase Cloud Messaging token for this device.
     * @param osVersion The Android version string (e.g., "Android 14 (SDK 34)").
     * @param deviceName The device manufacturer and model (e.g., "Samsung SM-G998B").
     * @return true if the upsert succeeded, false otherwise.
     */
    suspend fun upsertDeviceAsync(
        username: String,
        deviceId: String,
        fcmToken: String,
        osVersion: String,
        deviceName: String
    ): Boolean {
        val endpoint = "$BASE_URL/rest/v1/nfo_devices?on_conflict=username,device_id"

        val now = OffsetDateTime.now(riyadhZone).toString()

        val jsonBody = JSONObject().apply {
            put("username", username)
            put("device_id", deviceId)
            put("fcm_token", fcmToken)
            put("platform", "android")
            put("os_version", osVersion)
            put("device_name", deviceName)
            put("last_token_update", now)
            put("last_seen_at", now)
        }

        Log.d(TAG, "upsertDeviceAsync: username=$username, deviceId=$deviceId, " +
            "fcmToken=${fcmToken.take(20)}..., device=$deviceName")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .addHeader("apikey", API_KEY)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal,resolution=merge-duplicates")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val responseBody = try {
                        response.body?.string()
                    } catch (e: Exception) {
                        "error reading body: ${e.message}"
                    }

                    if (code in 200..299) {
                        Log.d(TAG, "upsertDeviceAsync success: code=$code")
                        return@withContext true
                    } else if (code == 409) {
                        Log.w(TAG, "upsertDeviceAsync conflict (409), treating as success")
                        return@withContext true
                    } else {
                        Log.e(TAG, "upsertDeviceAsync failed: code=$code, body=$responseBody")
                        return@withContext false
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "upsertDeviceAsync error: ${e.message}", e)
                false
            }
        }
    }
}
