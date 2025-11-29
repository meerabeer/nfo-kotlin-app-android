package com.nfo.tracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local offline buffer row for nfo_status.
 * Column names are aligned with the Supabase nfo_status table
 * so mapping to JSON/body is straightforward.
 */
@Entity(tableName = "heartbeats")
data class HeartbeatEntity(

    // Local primary key (not sent to server)
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0L,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "name")
    val name: String? = null,

    @ColumnInfo(name = "on_shift")
    val onShift: Boolean? = null,

    @ColumnInfo(name = "status")
    val status: String? = null,

    @ColumnInfo(name = "activity")
    val activity: String? = null,

    @ColumnInfo(name = "site_id")
    val siteId: String? = null,

    @ColumnInfo(name = "work_order_id")
    val workOrderId: String? = null,

    @ColumnInfo(name = "lat")
    val lat: Double? = null,

    @ColumnInfo(name = "lng")
    val lng: Double? = null,

    // Timestamps stored as epoch millis; convert to timestamptz on server
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "logged_in")
    val loggedIn: Boolean? = null,

    @ColumnInfo(name = "last_ping")
    val lastPing: Long? = null,

    @ColumnInfo(name = "last_active_source")
    val lastActiveSource: String? = null,

    @ColumnInfo(name = "last_active_at")
    val lastActiveAt: Long? = null,

    @ColumnInfo(name = "home_location")
    val homeLocation: String? = null,

    // Local-only fields for sync management
    @ColumnInfo(name = "created_at_local")
    val createdAtLocal: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "synced")
    val synced: Boolean = false
)
