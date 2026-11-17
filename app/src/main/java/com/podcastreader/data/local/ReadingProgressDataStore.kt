package com.podcastreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.podcastreader.domain.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reading_progress"
)

class ReadingProgressDataStore(private val context: Context) {

    private object Keys {
        val BOOK_ID = stringPreferencesKey("last_book_id")
        val BOOK_URI = stringPreferencesKey("last_book_uri")
        val BOOK_LOCAL_PATH = stringPreferencesKey("last_book_local_path")
        val CHAPTER_INDEX = intPreferencesKey("chapter_index")
        val PARAGRAPH_INDEX = intPreferencesKey("paragraph_index")
        val POSITION_MS = longPreferencesKey("position_ms")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    }

    val progressFlow: Flow<ReadingProgress?> = context.readingProgressDataStore.data.map { prefs ->
        val bookId = prefs[Keys.BOOK_ID] ?: return@map null
        ReadingProgress(
            bookId = bookId,
            chapterIndex = prefs[Keys.CHAPTER_INDEX] ?: 0,
            paragraphIndex = prefs[Keys.PARAGRAPH_INDEX] ?: 0,
            positionMs = prefs[Keys.POSITION_MS] ?: 0L,
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f
        )
    }

    val lastBookUriFlow: Flow<String?> = context.readingProgressDataStore.data.map { prefs ->
        prefs[Keys.BOOK_URI]
    }

    val lastBookLocalPathFlow: Flow<String?> = context.readingProgressDataStore.data.map { prefs ->
        prefs[Keys.BOOK_LOCAL_PATH]
    }

    suspend fun saveProgress(
        progress: ReadingProgress,
        bookUri: String? = null,
        localPath: String? = null
    ) {
        context.readingProgressDataStore.edit { prefs ->
            prefs[Keys.BOOK_ID] = progress.bookId
            if (bookUri != null) {
                prefs[Keys.BOOK_URI] = bookUri
            }
            if (localPath != null) {
                prefs[Keys.BOOK_LOCAL_PATH] = localPath
            }
            prefs[Keys.CHAPTER_INDEX] = progress.chapterIndex
            prefs[Keys.PARAGRAPH_INDEX] = progress.paragraphIndex
            prefs[Keys.POSITION_MS] = progress.positionMs
            prefs[Keys.PLAYBACK_SPEED] = progress.playbackSpeed
        }
    }

    suspend fun saveSpeed(speed: Float) {
        context.readingProgressDataStore.edit { prefs ->
            prefs[Keys.PLAYBACK_SPEED] = speed
        }
    }
}
