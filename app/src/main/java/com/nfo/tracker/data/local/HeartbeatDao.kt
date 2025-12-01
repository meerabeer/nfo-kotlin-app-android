package com.nfo.tracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HeartbeatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(heartbeat: HeartbeatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(heartbeats: List<HeartbeatEntity>): List<Long>

    @Query("SELECT * FROM heartbeats WHERE synced = 0 ORDER BY created_at_local ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 100): List<HeartbeatEntity>

    @Query("UPDATE heartbeats SET synced = 1 WHERE local_id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM heartbeats WHERE synced = 1")
    suspend fun deleteSynced()
}
