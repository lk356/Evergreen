package com.example.evergreen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Stores [EndReason] as its name rather than its position in the enum. */
class Converters {
    @TypeConverter
    fun toEndReason(value: String?): EndReason? =
        value?.let { name -> runCatching { EndReason.valueOf(name) }.getOrDefault(EndReason.UNKNOWN) }

    @TypeConverter
    fun fromEndReason(reason: EndReason?): String? = reason?.name
}

/**
 * The app's local database, and the source of truth for everything Evergreen
 * knows. Streaming services are sync targets, never the authority.
 *
 * `exportSchema = true` from version 1 onwards: each build writes a JSON
 * description of this schema to `app/schemas/`, which is committed. Future
 * migrations can then be generated and tested against the real previous schema
 * instead of being hand-written from memory.
 *
 * The database file is excluded from cloud backup and device-to-device transfer
 * by `data_extraction_rules.xml`; nothing here may leave the device.
 */
@Database(
    entities = [
        TrackEntity::class,
        ServiceTrackEntity::class,
        ListeningSegmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EvergreenDatabase : RoomDatabase() {

    abstract fun dao(): EvergreenDao

    companion object {
        private const val DB_NAME = "evergreen.db"

        @Volatile
        private var instance: EvergreenDatabase? = null

        /**
         * One instance per process. The capture service and the UI both reach
         * for this, and Room requires them to share a single handle.
         */
        fun get(context: Context): EvergreenDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): EvergreenDatabase =
            Room.databaseBuilder(context, EvergreenDatabase::class.java, DB_NAME)
                // Write-ahead logging lets the debug screen keep reading while
                // the capture service writes, instead of the two blocking each
                // other. Room enables foreign-key enforcement on its own.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
