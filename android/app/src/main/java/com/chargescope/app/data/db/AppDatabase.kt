package com.chargescope.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        SnapshotEntity::class,
        EvidenceEntity::class,
        DeviceProfileEntity::class,
    ],
    version = SCHEMA_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun deviceProfileDao(): DeviceProfileDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "chargescope.db",
            )
                // MVP has no released schema yet; destructive migration avoids
                // crash loops during development. Replace before P6 release.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
