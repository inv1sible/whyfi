package com.whyfi.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PendingScanEntity::class, RecordingEntity::class], version = 2, exportSchema = false)
abstract class WhyfiDatabase : RoomDatabase() {
    abstract fun pendingScanDao(): PendingScanDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var instance: WhyfiDatabase? = null

        /** v2: recordings, and passes kept after upload (syncState) instead of deleted. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recordings` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`startedAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `syncAllowed` INTEGER NOT NULL, " +
                        "`publicIp` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("ALTER TABLE `pending_scans` ADD COLUMN `recordingId` TEXT")
                db.execSQL("ALTER TABLE `pending_scans` ADD COLUMN `syncState` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): WhyfiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WhyfiDatabase::class.java,
                    "whyfi.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
