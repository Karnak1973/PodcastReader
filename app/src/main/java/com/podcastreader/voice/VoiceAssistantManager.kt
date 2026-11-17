package com.podcastreader.voice

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.podcastreader.domain.rag.AntiSpoilerRagEngine
import com.podcastreader.domain.rag.PlaybackBookmark
import com.podcastreader.domain.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class VoicePhase {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

data class VoiceAssistantUiState(
    val isDialogOpen: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    val transcript: String = "",
    val answer: String = "",
    val errorMessage: String? = null
)

class VoiceAssistantManager(
    private val context: Context,
    private val ragEngine: AntiSpoilerRagEngine,
    private val ttsEngine: TtsEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceAssistantUiState())
    val state: StateFlow<VoiceAssistantUiState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var replyPlayer: MediaPlayer? = null
    private var currentBookmark: PlaybackBookmark? = null

    fun openDialog(bookmark: PlaybackBookmark) {
        currentBookmark = bookmark
        _state.value = VoiceAssistantUiState(isDialogOpen = true, phase = VoicePhase.IDLE)
    }

    fun startListening(bookmark: PlaybackBookmark) {
        currentBookmark = bookmark
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.update {
                it.copy(
                    isDialogOpen = true,
                    phase = VoicePhase.ERROR,
                    errorMessage = "Reconocimiento de voz no disponible en este dispositivo. Puedes escribir tu pregunta abajo."
                )
            }
            return
        }

        stopReplyAudio()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        _state.update {
            it.copy(
                isDialogOpen = true,
                phase = VoicePhase.LISTENING,
                transcript = "",
                answer = "",
                errorMessage = null
            )
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun submitQuestion(question: String, bookmark: PlaybackBookmark) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        currentBookmark = bookmark
        processQuery(clean, bookmark)
    }

    private fun processQuery(question: String, bookmark: PlaybackBookmark) {
        scope.launch {
            _state.update {
                it.copy(
                    isDialogOpen = true,
                    phase = VoicePhase.THINKING,
                    transcript = question,
                    answer = "",
                    errorMessage = null
                )
            }

            val answerResult = runCatching {
                val targetChapterForSummary = detectSummaryChapterIndex(question, bookmark.currentChapterIndex)
                if (targetChapterForSummary != null) {
                    ragEngine.summarizeChapter(targetChapterForSummary, bookmark)
                } else {
                    ragEngine.askAboutBook(question, bookmark)
                }
            }

            answerResult.onFailure { err ->
                _state.update {
                    it.copy(
                        phase = VoicePhase.ERROR,
                        errorMessage = err.message ?: "Error al consultar el libro"
                    )
                }
                return@launch
            }

            val answerText = answerResult.getOrThrow()
            _state.update { it.copy(phase = VoicePhase.SPEAKING, answer = answerText) }

            val replyFile = File(context.cacheDir, "voice_reply.wav")
            val ttsResult = withContext(Dispatchers.IO) {
                ttsEngine.synthesizeToFile(answerText, replyFile)
            }

            ttsResult.onSuccess { file ->
                playReplyFile(file)
            }.onFailure {
                _state.update { it.copy(phase = VoicePhase.IDLE) }
            }
        }
    }

    internal fun detectSummaryChapterIndex(question: String, currentChapterIndex: Int): Int? {
        val lower = question.lowercase()
        val asksSummary = lower.contains("resum") || lower.contains("síntesis")
        if (!asksSummary) return null

        if (lower.contains("anterior")) {
            return (currentChapterIndex - 1).coerceAtLeast(0)
        }
        if (lower.contains("actual") || lower.contains("este capítulo")) {
            return currentChapterIndex
        }
        val numberMatch = Regex("cap[ií]tulo\\s+(\\d+)").find(lower)
        val explicitNumber = numberMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (explicitNumber != null && explicitNumber > 0) {
            explicitNumber - 1
        } else {
            currentChapterIndex
        }
    }

    private fun playReplyFile(file: File) {
        stopReplyAudio()
        replyPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                _state.update { it.copy(phase = VoicePhase.IDLE) }
            }
            setOnErrorListener { _, _, _ ->
                _state.update { it.copy(phase = VoicePhase.IDLE) }
                true
            }
            prepare()
            start()
        }
    }

    fun stopReplyAudio() {
        replyPlayer?.run {
            if (isPlaying) stop()
            release()
        }
        replyPlayer = null
    }

    fun closeDialog() {
        speechRecognizer?.cancel()
        stopReplyAudio()
        _state.value = VoiceAssistantUiState(isDialogOpen = false, phase = VoicePhase.IDLE)
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopReplyAudio()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                _state.update { it.copy(transcript = partial) }
            }
        }

        override fun onResults(results: Bundle?) {
            val finalQuery = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val bookmark = currentBookmark
            if (finalQuery.isNotBlank() && bookmark != null) {
                processQuery(finalQuery, bookmark)
            } else {
                _state.update {
                    it.copy(
                        phase = VoicePhase.ERROR,
                        errorMessage = "No se captó ninguna pregunta. Inténtalo de nuevo."
                    )
                }
            }
        }

        override fun onError(error: Int) {
            _state.update {
                it.copy(
                    phase = VoicePhase.ERROR,
                    errorMessage = "Error de micrófono (código $error). Puedes escribir tu pregunta."
                )
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
