package com.nfo.tracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HeartbeatEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HeartbeatDatabase : RoomDatabase() {

    abstract fun heartbeatDao(): HeartbeatDao

    companion object {
        @Volatile
        private var INSTANCE: HeartbeatDatabase? = null

        fun getInstance(context: Context): HeartbeatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HeartbeatDatabase::class.java,
                    "heartbeat.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
