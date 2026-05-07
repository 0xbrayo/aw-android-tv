package net.activitywatch.tv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Bucket::class, Event::class], version = 1, exportSchema = false)
abstract class AWDatabase : RoomDatabase() {
    abstract fun bucketDao(): BucketDao
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: AWDatabase? = null

        fun getDatabase(context: Context): AWDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AWDatabase::class.java,
                    "activitywatch_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
