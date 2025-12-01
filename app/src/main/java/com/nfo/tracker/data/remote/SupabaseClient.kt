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
                put("updated_at", Instant.ofEpochMilli(heartbeat.updatedAt).toString())
                put("logged_in", heartbeat.loggedIn ?: JSONObject.NULL)
                put("last_ping", heartbeat.lastPing?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                put("last_active_source", heartbeat.lastActiveSource ?: JSONObject.NULL)
                put("last_active_at", heartbeat.lastActiveAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
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
                    if (code in 200..299) {
                        true
                    } else if (code == 409) {
                        // Conflict on upsert – treat as success (row already exists)
                        Log.w(TAG, "Upsert conflict (409), treating as success")
                        true
                    } else {
                        val errorBody = try {
                            response.body?.string()
                        } catch (e: Exception) {
                            "error reading body: ${e.message}"
                        }

                        Log.e(
                            TAG,
                            "Sync failed: $code - $errorBody"
                        )
                        false
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Sync error: ${e.message}", e)
                false
            }
        }
    }
}
