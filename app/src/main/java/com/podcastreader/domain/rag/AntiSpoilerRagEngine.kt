package com.podcastreader.domain.rag

import com.podcastreader.data.local.BookChunkDao
import com.podcastreader.data.local.BookChunkEntity
import com.podcastreader.domain.chunking.BookSemanticChunker
import com.podcastreader.domain.chunking.RawChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlaybackBookmark(
    val bookId: String,
    val currentChapterIndex: Int,
    val currentParagraphIndex: Int
)

data class ScoredChunk(
    val chunk: BookChunkEntity,
    val score: Float
)

class AntiSpoilerRagEngine(
    private val dao: BookChunkDao,
    private val chunker: BookSemanticChunker,
    private val embeddingProvider: EmbeddingProvider,
    private val llmClient: LlmClient
) {
    suspend fun indexBookChapters(bookId: String, chapters: List<RawChapter>) =
        withContext(Dispatchers.Default) {
            for (chapter in chapters) {
                val textChunks = chunker.chunkChapter(chapter)
                if (textChunks.isEmpty()) continue

                val embeddings = embeddingProvider.embedBatch(textChunks.map { it.content })

                val entities = textChunks.zip(embeddings).map { (chunk, vector) ->
                    BookChunkEntity(
                        chunkId = "${bookId}_ch${chunk.chapterIndex}_ck${chunk.chunkOrder}",
                        bookId = bookId,
                        chapterIndex = chunk.chapterIndex,
                        chapterTitle = chunk.chapterTitle,
                        chunkOrderInChapter = chunk.chunkOrder,
                        startParagraphIndex = chunk.startParagraph,
                        endParagraphIndex = chunk.endParagraph,
                        textContent = chunk.content,
                        embedding = vector
                    )
                }
                dao.insertChunks(entities)
            }
        }

    suspend fun askAboutBook(
        question: String,
        bookmark: PlaybackBookmark,
        topK: Int = 5
    ): String = withContext(Dispatchers.Default) {
        val heardChunks = dao.getHeardChunksUpToPosition(
            bookId = bookmark.bookId,
            currentChapter = bookmark.currentChapterIndex,
            currentParagraph = bookmark.currentParagraphIndex
        )

        if (heardChunks.isEmpty()) {
            return@withContext "Aún estás al principio del libro y no hay suficiente contexto leído para responder sin hacer spoilers."
        }

        val queryVector = embeddingProvider.embedText(question)

        val topChunks = heardChunks
            .map { chunk ->
                val similarity = VectorMath.cosineSimilarity(queryVector, chunk.embedding)
                val recencyBoost =
                    (chunk.chapterIndex.toFloat() / (bookmark.currentChapterIndex + 1)) * 0.03f
                ScoredChunk(chunk, similarity + recencyBoost)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .sortedWith(compareBy({ it.chunk.chapterIndex }, { it.chunk.chunkOrderInChapter }))

        val contextBlock = topChunks.joinToString("\n---\n") {
            "Fragmento (Cap. ${it.chunk.chapterIndex + 1}, Párrafos ${it.chunk.startParagraphIndex}-${it.chunk.endParagraphIndex}):\n${it.chunk.textContent}"
        }

        val systemPrompt = """
            Eres un compañero de lectura narrando un audiolibro.
            El oyente va exactamente por el Capítulo ${bookmark.currentChapterIndex + 1}, párrafo ${bookmark.currentParagraphIndex}.
            
            REGLAS ESTRICTAS:
            1. Responde ÚNICAMENTE utilizando la información de los fragmentos proporcionados abajo.
            2. Incluso si conoces la obra literaria por tu entrenamiento previo, PROHIBIDO revelar, insinuar o adelantar sucesos, giros de trama o destinos de personajes que no aparezcan explícitamente en los fragmentos.
            3. Responde en un tono natural, conciso y conversacional (máximo 3-4 frases), ideal para ser leído en voz alta por un motor TTS.
        """.trimIndent()

        val userPrompt = """
            FRAGMENTOS LEÍDOS HASTA AHORA:
            $contextBlock
            
            PREGUNTA DEL OYENTE:
            $question
        """.trimIndent()

        llmClient.generateResponse(systemPrompt = systemPrompt, userPrompt = userPrompt)
    }

    suspend fun summarizeChapter(
        targetChapterIndex: Int,
        bookmark: PlaybackBookmark
    ): String = withContext(Dispatchers.Default) {
        if (targetChapterIndex > bookmark.currentChapterIndex) {
            return@withContext "Todavía vas por el capítulo ${bookmark.currentChapterIndex + 1}. No puedo resumirte el capítulo ${targetChapterIndex + 1} para no arruinarte la historia."
        }

        val chunks = dao.getFullChapterText(bookmark.bookId, targetChapterIndex)
        val fullText = chunks.joinToString("\n\n")

        val systemPrompt = """
            Eres un narrador de podcast literario. Resume de forma amena y hablada los eventos clave y la evolución de los personajes en este capítulo en unos 60-90 segundos de locución.
        """.trimIndent()

        llmClient.generateResponse(systemPrompt = systemPrompt, userPrompt = fullText)
    }
}
