package com.podcastreader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [BookChunkEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(EmbeddingConverters::class)
abstract class BookDatabase : RoomDatabase() {
    abstract fun bookChunkDao(): BookChunkDao

    companion object {
        @Volatile
        private var INSTANCE: BookDatabase? = null

        fun getInstance(context: Context): BookDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "podcast_reader.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
