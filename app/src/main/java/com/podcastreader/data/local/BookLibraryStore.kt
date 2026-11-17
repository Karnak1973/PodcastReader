package com.podcastreader.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryBookEntry(
    val bookId: String,
    val title: String,
    val author: String,
    val localPath: String,
    val coverPath: String? = null,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val progressPercent: Int = 0,
    val lastOpenedAt: Long = System.currentTimeMillis()
)

class BookLibraryStore(private val context: Context) {

    private val libraryFile = File(context.filesDir, "library.json")
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    suspend fun loadAll(): List<LibraryBookEntry> = withContext(Dispatchers.IO) {
        if (!libraryFile.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(libraryFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val path = obj.getString("localPath")
                    if (File(path).exists()) {
                        add(
                            LibraryBookEntry(
                                bookId = obj.getString("bookId"),
                                title = obj.getString("title"),
                                author = obj.getString("author"),
                                localPath = path,
                                coverPath = obj.optString("coverPath").takeIf { it.isNotBlank() },
                                chapterIndex = obj.optInt("chapterIndex", 0),
                                paragraphIndex = obj.optInt("paragraphIndex", 0),
                                progressPercent = obj.optInt("progressPercent", 0),
                                lastOpenedAt = obj.optLong("lastOpenedAt", 0L)
                            )
                        )
                    }
                }
            }.sortedByDescending { it.lastOpenedAt }
        }.getOrElse { emptyList() }
    }

    suspend fun saveCoverIfPresent(bookId: String, coverBytes: ByteArray?): String? =
        withContext(Dispatchers.IO) {
            if (coverBytes == null || coverBytes.isEmpty()) return@withContext null
            val file = File(coversDir, "$bookId.img")
            file.writeBytes(coverBytes)
            file.absolutePath
        }

    suspend fun upsertBook(entry: LibraryBookEntry) = withContext(Dispatchers.IO) {
        val current = loadAll().toMutableList()
        current.removeAll { it.bookId == entry.bookId }
        current.add(0, entry)
        writeList(current)
    }

    suspend fun updateProgress(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        progressPercent: Int
    ) = withContext(Dispatchers.IO) {
        val current = loadAll().toMutableList()
        val idx = current.indexOfFirst { it.bookId == bookId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                progressPercent = progressPercent,
                lastOpenedAt = System.currentTimeMillis()
            )
            writeList(current)
        }
    }

    private fun writeList(entries: List<LibraryBookEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("bookId", e.bookId)
                    put("title", e.title)
                    put("author", e.author)
                    put("localPath", e.localPath)
                    put("coverPath", e.coverPath ?: "")
                    put("chapterIndex", e.chapterIndex)
                    put("paragraphIndex", e.paragraphIndex)
                    put("progressPercent", e.progressPercent)
                    put("lastOpenedAt", e.lastOpenedAt)
                }
            )
        }
        libraryFile.writeText(array.toString())
    }
}
