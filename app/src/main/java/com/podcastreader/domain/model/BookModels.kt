package com.podcastreader.domain.model

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val toc: List<TocItem>,
    val coverImageBytes: ByteArray? = null,
    val coverPath: String? = null
) {
    val totalParagraphs: Int
        get() = chapters.sumOf { it.paragraphs.size }

    fun progressFraction(chapterIndex: Int, paragraphIndex: Int): Float {
        val total = totalParagraphs
        if (total <= 0) return 0f
        var read = 0
        for (ch in chapters) {
            if (ch.index < chapterIndex) {
                read += ch.paragraphs.size
            } else if (ch.index == chapterIndex) {
                read += (paragraphIndex + 1).coerceIn(0, ch.paragraphs.size)
                break
            }
        }
        return (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}

data class Chapter(
    val index: Int,
    val id: String,
    val title: String,
    val paragraphs: List<Paragraph>
)

data class Paragraph(
    val index: Int,
    val text: String
)

data class TocItem(
    val title: String,
    val chapterIndex: Int,
    val level: Int = 0
)

data class ReadingProgress(
    val bookId: String,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
)

enum class SynthesisMode {
    LITERAL,
    PODCAST_DIALOGUE
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentChapterIndex: Int = 0,
    val currentParagraphIndex: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val synthesisMode: SynthesisMode = SynthesisMode.LITERAL,
    val errorMessage: String? = null
)
