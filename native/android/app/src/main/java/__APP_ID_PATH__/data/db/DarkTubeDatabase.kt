package __APP_ID__.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1. No destructive migration: when the schema changes a real Migration must be added, so
 * a user's download list is never silently wiped.
 */
@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class DarkTubeDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): DarkTubeDatabase =
            Room.databaseBuilder(context.applicationContext, DarkTubeDatabase::class.java, "darktube.db").build()
    }
}
