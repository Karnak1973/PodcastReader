package com.podcastreader.data.tts

import android.content.Context
import com.podcastreader.data.local.ApiSettingsDataStore
import com.podcastreader.data.local.TtsProviderType
import com.podcastreader.domain.model.Chapter
import com.podcastreader.domain.tts.TtsEngine
import com.podcastreader.domain.tts.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class AudioChunkCacheManager(
    context: Context,
    private val ttsEngine: TtsEngine,
    private val settingsDataStore: ApiSettingsDataStore
) {
    private val rootCacheDir = File(context.cacheDir, "tts_chunks").apply { mkdirs() }
    private var prefetchJob: Job? = null

    fun getChunkFile(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        voiceTag: String
    ): File {
        val safeBookId = bookId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeTag = voiceTag.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
        val bookDir = File(rootCacheDir, safeBookId).apply { mkdirs() }
        return File(bookDir, "ch_${chapterIndex}_p_${paragraphIndex}_${safeTag}.wav")
    }

    suspend fun getOrSynthesizeParagraph(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        text: String,
        voiceConfig: VoiceConfig = VoiceConfig()
    ): Result<File> {
        val settings = settingsDataStore.settingsFlow.first()
        val activeTag = when (settings.ttsProvider) {
            TtsProviderType.ANDROID_LOCAL ->
                "local_${voiceConfig.voiceName ?: settings.androidVoiceName.ifBlank { "auto" }}"
            TtsProviderType.GEMINI_TTS ->
                "gemini_${voiceConfig.voiceName ?: settings.cloudVoiceName}"
            TtsProviderType.OPENAI_TTS ->
                "openai_${voiceConfig.voiceName ?: settings.cloudVoiceName}"
        }
        val targetFile = getChunkFile(bookId, chapterIndex, paragraphIndex, activeTag)
        if (targetFile.exists() && targetFile.length() > 44L) {
            return Result.success(targetFile)
        }
        return ttsEngine.synthesizeToFile(
            text = text,
            outputFile = targetFile,
            voiceConfig = voiceConfig
        )
    }

    fun prefetchAhead(
        scope: CoroutineScope,
        bookId: String,
        chapter: Chapter,
        fromParagraphIndex: Int,
        windowSize: Int = 3,
        onChunkReady: suspend (paragraphIndex: Int, file: File) -> Unit
    ) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            val endIndex = (fromParagraphIndex + windowSize).coerceAtMost(chapter.paragraphs.size - 1)
            for (pIdx in fromParagraphIndex..endIndex) {
                val paragraph = chapter.paragraphs[pIdx]
                val result = getOrSynthesizeParagraph(
                    bookId = bookId,
                    chapterIndex = chapter.index,
                    paragraphIndex = paragraph.index,
                    text = paragraph.text
                )
                result.getOrNull()?.let { file ->
                    onChunkReady(paragraph.index, file)
                }
            }
        }
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }
}
