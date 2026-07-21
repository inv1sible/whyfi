package com.whyfi.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingScanEntity::class], version = 1, exportSchema = false)
abstract class WhyfiDatabase : RoomDatabase() {
    abstract fun pendingScanDao(): PendingScanDao

    companion object {
        @Volatile
        private var instance: WhyfiDatabase? = null

        fun getInstance(context: Context): WhyfiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WhyfiDatabase::class.java,
                    "whyfi.db",
                ).build().also { instance = it }
            }
    }
}
