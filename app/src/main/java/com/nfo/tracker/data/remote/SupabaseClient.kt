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

object SupabaseClient {

    private const val TAG = "SupabaseClient"

    private const val BASE_URL = "https://rzivbeaqfhamlpsfaqov.supabase.co"
    private const val API_KEY = "sb_publishable_Kifo4X6qxs6nkv7yilHRkA_7RapeV4a"
    private const val TABLE_ENDPOINT = "$BASE_URL/rest/v1/nfo_status?on_conflict=username"

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    /**
     * Converts epoch millis (or seconds) to ISO-8601 UTC string.
     * Handles both epoch seconds (10 digits) and millis (13 digits).
     */
    private fun Long?.toIsoUtc(): String? {
        if (this == null) return null
        // If the value looks like epoch seconds (10 digits), convert to millis.
        val millis = if (this < 3_000_000_000L) this * 1000L else this
        return Instant.ofEpochMilli(millis).toString() // e.g. 2025-12-01T13:45:30Z
    }

    suspend fun syncHeartbeats(heartbeats: List<HeartbeatEntity>): Boolean {
        if (heartbeats.isEmpty()) return true

        val jsonArray = JSONArray()

        for (heartbeat in heartbeats) {
            val obj = JSONObject().apply {
                put("username", heartbeat.username)
                put("name", heartbeat.name ?: JSONObject.NULL)
                put("on_shift", heartbeat.onShift ?: JSONObject.NULL)
                put("status", heartbeat.status ?: JSONObject.NULL)
                put("activity", heartbeat.activity ?: JSONObject.NULL)
                put("site_id", heartbeat.siteId ?: JSONObject.NULL)
                put("work_order_id", heartbeat.workOrderId ?: JSONObject.NULL)
                put("lat", heartbeat.lat ?: JSONObject.NULL)
                put("lng", heartbeat.lng ?: JSONObject.NULL)

                // FIXED: convert epoch → ISO UTC
                put(
                    "updated_at",
                    heartbeat.updatedAt.toIsoUtc() ?: JSONObject.NULL
                )
                put("logged_in", heartbeat.loggedIn ?: JSONObject.NULL)
                put(
                    "last_ping",
                    heartbeat.lastPing.toIsoUtc() ?: JSONObject.NULL
                )
                put(
                    "last_active_source",
                    heartbeat.lastActiveSource ?: JSONObject.NULL
                )
                put(
                    "last_active_at",
                    heartbeat.lastActiveAt.toIsoUtc() ?: JSONObject.NULL
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
}
