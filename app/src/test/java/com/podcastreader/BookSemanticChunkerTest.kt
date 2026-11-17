package com.podcastreader

import com.podcastreader.domain.chunking.BookSemanticChunker
import com.podcastreader.domain.chunking.RawChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSemanticChunkerTest {

    @Test
    fun `chunkChapter groups paragraphs with overlap and preserves bounds`() {
        val paragraphs = (0 until 10).map { idx ->
            "Párrafo $idx " + "palabra ".repeat(25)
        }
        val chunker = BookSemanticChunker(targetWordCount = 60, overlapParagraphs = 1)
        val chunks = chunker.chunkChapter(
            RawChapter(index = 0, title = "Prólogo", paragraphs = paragraphs)
        )

        assertTrue(chunks.size > 1)
        assertEquals(0, chunks.first().startParagraph)
        assertEquals(9, chunks.last().endParagraph)
        // Verifica solapamiento: el segundo chunk empieza en o antes del endParagraph del primero
        assertTrue(chunks[1].startParagraph <= chunks[0].endParagraph)
    }
}
