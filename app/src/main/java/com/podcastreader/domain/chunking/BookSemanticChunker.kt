package com.podcastreader.domain.chunking

data class RawChapter(
    val index: Int,
    val title: String,
    val paragraphs: List<String>
)

data class TextChunk(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chunkOrder: Int,
    val startParagraph: Int,
    val endParagraph: Int,
    val content: String
)

class BookSemanticChunker(
    private val targetWordCount: Int = 350,
    private val overlapParagraphs: Int = 1
) {
    fun chunkChapter(chapter: RawChapter): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        val paragraphs = chapter.paragraphs
        if (paragraphs.isEmpty()) return emptyList()

        var currentParaIndex = 0
        var chunkOrder = 0

        while (currentParaIndex < paragraphs.size) {
            val startPara = currentParaIndex
            val builder = StringBuilder()
            var wordCount = 0

            builder.append("[Capítulo ${chapter.index + 1}: ${chapter.title}]\n")

            while (currentParaIndex < paragraphs.size && wordCount < targetWordCount) {
                val para = paragraphs[currentParaIndex].trim()
                if (para.isNotEmpty()) {
                    builder.append(para).append("\n\n")
                    wordCount += para.split(Regex("\\s+")).size
                }
                currentParaIndex++
            }

            val endPara = (currentParaIndex - 1).coerceAtLeast(startPara)

            chunks.add(
                TextChunk(
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    chunkOrder = chunkOrder++,
                    startParagraph = startPara,
                    endParagraph = endPara,
                    content = builder.toString().trim()
                )
            )

            if (currentParaIndex < paragraphs.size) {
                currentParaIndex = (currentParaIndex - overlapParagraphs).coerceAtLeast(startPara + 1)
            }
        }
        return chunks
    }
}
