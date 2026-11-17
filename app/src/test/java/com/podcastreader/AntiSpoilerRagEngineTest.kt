package com.podcastreader

import com.podcastreader.data.local.BookChunkDao
import com.podcastreader.data.local.BookChunkEntity
import com.podcastreader.data.rag.LocalHashEmbeddingProvider
import com.podcastreader.domain.chunking.BookSemanticChunker
import com.podcastreader.domain.chunking.RawChapter
import com.podcastreader.domain.rag.AntiSpoilerRagEngine
import com.podcastreader.domain.rag.LlmClient
import com.podcastreader.domain.rag.PlaybackBookmark
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiSpoilerRagEngineTest {

    private class FakeBookChunkDao : BookChunkDao {
        private val store = mutableListOf<BookChunkEntity>()

        override suspend fun insertChunks(chunks: List<BookChunkEntity>) {
            chunks.forEach { chunk ->
                store.removeAll { it.chunkId == chunk.chunkId }
                store.add(chunk)
            }
        }

        override suspend fun getHeardChunksUpToPosition(
            bookId: String,
            currentChapter: Int,
            currentParagraph: Int
        ): List<BookChunkEntity> {
            return store.filter {
                it.bookId == bookId && (
                    it.chapterIndex < currentChapter ||
                        (it.chapterIndex == currentChapter && it.startParagraphIndex <= currentParagraph)
                    )
            }
        }

        override suspend fun getFullChapterText(bookId: String, targetChapter: Int): List<String> {
            return store
                .filter { it.bookId == bookId && it.chapterIndex == targetChapter }
                .sortedBy { it.chunkOrderInChapter }
                .map { it.textContent }
        }

        override suspend fun deleteChunksForBook(bookId: String) {
            store.removeAll { it.bookId == bookId }
        }
    }

    @Test
    fun `askAboutBook never includes chunks from future chapters`() = runTest {
        val dao = FakeBookChunkDao()
        var capturedPrompt = ""
        val fakeLlm = object : LlmClient {
            override suspend fun generateResponse(
                systemPrompt: String,
                userPrompt: String,
                temperature: Float
            ): String {
                capturedPrompt = userPrompt
                return "Respuesta simulada"
            }
        }

        val engine = AntiSpoilerRagEngine(
            dao = dao,
            chunker = BookSemanticChunker(targetWordCount = 50, overlapParagraphs = 0),
            embeddingProvider = LocalHashEmbeddingProvider(),
            llmClient = fakeLlm
        )

        engine.indexBookChapters(
            bookId = "b1",
            chapters = listOf(
                RawChapter(0, "Cap 1", listOf("Mateo vive en Madrid y trabaja como relojero.")),
                RawChapter(1, "Cap 2", listOf("SPOILER_FUTURO Mateo descubre que es un robot."))
            )
        )

        engine.askAboutBook(
            question = "¿Quién es Mateo?",
            bookmark = PlaybackBookmark(bookId = "b1", currentChapterIndex = 0, currentParagraphIndex = 0)
        )

        assertTrue(capturedPrompt.contains("relojero"))
        assertFalse(capturedPrompt.contains("SPOILER_FUTURO"))
    }

    @Test
    fun `summarizeChapter blocks future chapter summaries`() = runTest {
        val dao = FakeBookChunkDao()
        val fakeLlm = object : LlmClient {
            override suspend fun generateResponse(
                systemPrompt: String,
                userPrompt: String,
                temperature: Float
            ): String = "Resumen"
        }
        val engine = AntiSpoilerRagEngine(
            dao = dao,
            chunker = BookSemanticChunker(),
            embeddingProvider = LocalHashEmbeddingProvider(),
            llmClient = fakeLlm
        )

        val response = engine.summarizeChapter(
            targetChapterIndex = 3,
            bookmark = PlaybackBookmark(bookId = "b1", currentChapterIndex = 1, currentParagraphIndex = 0)
        )

        assertTrue(response.contains("Todavía vas por el capítulo 2"))
    }
}
