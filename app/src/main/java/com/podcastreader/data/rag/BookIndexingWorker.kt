package com.podcastreader.data.rag

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.podcastreader.PodcastReaderApplication
import com.podcastreader.domain.chunking.RawChapter
import com.podcastreader.domain.model.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class IndexingProgress(
    val bookId: String,
    val indexedChapters: Int,
    val totalChapters: Int
)

object BookIndexingRegistry {
    private val pendingChapters = ConcurrentHashMap<String, List<RawChapter>>()
    private val _progress = MutableStateFlow<IndexingProgress?>(null)
    val progress: StateFlow<IndexingProgress?> = _progress.asStateFlow()

    fun registerBook(book: Book): List<RawChapter> {
        val raw = book.chapters.map { ch ->
            RawChapter(
                index = ch.index,
                title = ch.title,
                paragraphs = ch.paragraphs.map { it.text }
            )
        }
        pendingChapters[book.id] = raw
        _progress.value = IndexingProgress(book.id, 0, raw.size)
        return raw
    }

    fun getChapters(bookId: String): List<RawChapter> = pendingChapters[bookId].orEmpty()

    fun updateProgress(bookId: String, indexed: Int, total: Int) {
        _progress.value = IndexingProgress(bookId, indexed, total)
    }

    fun enqueueRemainingIndexing(context: Context, bookId: String, priorityChapterIndex: Int) {
        val request = OneTimeWorkRequestBuilder<BookIndexingWorker>()
            .setInputData(
                workDataOf(
                    BookIndexingWorker.KEY_BOOK_ID to bookId,
                    BookIndexingWorker.KEY_PRIORITY_CHAPTER to priorityChapterIndex
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "index_book_$bookId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class BookIndexingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val priorityChapter = inputData.getInt(KEY_PRIORITY_CHAPTER, 0)
        val app = applicationContext as PodcastReaderApplication
        val chapters = BookIndexingRegistry.getChapters(bookId)
        if (chapters.isEmpty()) return Result.success()

        val ordered = buildList {
            chapters.getOrNull(priorityChapter)?.let { add(it) }
            chapters.forEach { if (it.index != priorityChapter) add(it) }
        }

        var completed = 0
        for (chapter in ordered) {
            if (isStopped) break
            app.ragEngine.indexBookChapters(bookId, listOf(chapter))
            completed++
            BookIndexingRegistry.updateProgress(bookId, completed, chapters.size)
        }
        return Result.success()
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_PRIORITY_CHAPTER = "priority_chapter"
    }
}
