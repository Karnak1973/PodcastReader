package com.podcastreader.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.podcastreader.domain.tts.TtsEngine
import com.podcastreader.domain.tts.TtsVoiceOption
import com.podcastreader.domain.tts.VoiceConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class AndroidTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    @Volatile
    override var isReady: Boolean = false
        private set

    private val initMutex = Mutex()
    private val synthesisMutex = Mutex()
    private val pendingUtterances = ConcurrentHashMap<String, PendingSynthesis>()

    private data class PendingSynthesis(
        val file: File,
        val continuation: CancellableContinuation<Result<File>>
    )

    override suspend fun initialize(): Boolean = initMutex.withLock {
        if (isReady && tts != null) return true

        suspendCancellableCoroutine { cont ->
            var engineRef: TextToSpeech? = null
            engineRef = TextToSpeech(context.applicationContext) { status ->
                val engine = engineRef
                val success = status == TextToSpeech.SUCCESS && engine != null
                if (success && engine != null) {
                    val locale = Locale.forLanguageTag("es-ES")
                    val langResult = engine.setLanguage(locale)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        engine.setLanguage(Locale.getDefault())
                    }
                    selectBestSpanishVoice(engine)?.let { bestVoice ->
                        engine.voice = bestVoice
                    }
                    engine.setOnUtteranceProgressListener(createProgressListener())
                    tts = engine
                    isReady = true
                } else {
                    isReady = false
                }
                if (cont.isActive) {
                    cont.resume(success)
                }
            }

            cont.invokeOnCancellation {
                if (!isReady) {
                    engineRef?.shutdown()
                }
            }
        }
    }

    fun getAvailableSpanishVoices(): List<TtsVoiceOption> {
        val engine = tts ?: return emptyList()
        return engine.voices
            ?.filter { it.locale.language.equals("es", ignoreCase = true) }
            ?.sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.name.contains("network", ignoreCase = true) }
                    .thenBy { it.locale.toLanguageTag() }
            )
            ?.map { voice ->
                val qualityLabel = when {
                    voice.quality >= Voice.QUALITY_VERY_HIGH -> "Muy alta"
                    voice.quality >= Voice.QUALITY_HIGH -> "Alta"
                    else -> "Normal"
                }
                val netLabel = if (voice.isNetworkConnectionRequired) "Red/Natural" else "Offline"
                TtsVoiceOption(
                    id = voice.name,
                    label = "${voice.locale.toLanguageTag()} · $qualityLabel ($netLabel) · ${voice.name.takeLast(14)}",
                    isNetwork = voice.isNetworkConnectionRequired,
                    quality = voice.quality
                )
            }
            ?: emptyList()
    }

    private fun selectBestSpanishVoice(engine: TextToSpeech): Voice? {
        val spanishVoices = engine.voices
            ?.filter { it.locale.language.equals("es", ignoreCase = true) }
            .orEmpty()
        if (spanishVoices.isEmpty()) return null

        return spanishVoices.maxWithOrNull(
            compareBy<Voice> { it.quality }
                .thenBy { it.locale.country.equals("ES", ignoreCase = true) }
                .thenBy {
                    it.name.contains("network", ignoreCase = true) ||
                        it.name.contains("neural", ignoreCase = true)
                }
        )
    }

    override suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voiceConfig: VoiceConfig
    ): Result<File> {
        if (!isReady && !initialize()) {
            return Result.failure(IllegalStateException("No se pudo inicializar Android TextToSpeech"))
        }

        val engine = tts
            ?: return Result.failure(IllegalStateException("Motor TTS no disponible"))

        outputFile.parentFile?.mkdirs()

        return synthesisMutex.withLock {
            suspendCancellableCoroutine { cont ->
                val utteranceId = UUID.randomUUID().toString()
                pendingUtterances[utteranceId] = PendingSynthesis(outputFile, cont)

                val requestedVoiceName = voiceConfig.voiceName
                if (!requestedVoiceName.isNullOrBlank()) {
                    engine.voices?.firstOrNull { it.name == requestedVoiceName }?.let {
                        engine.voice = it
                    }
                }

                engine.setPitch(voiceConfig.pitch)
                engine.setSpeechRate(voiceConfig.rate)

                val params = Bundle()
                val queueResult = engine.synthesizeToFile(text, params, outputFile, utteranceId)
                if (queueResult != TextToSpeech.SUCCESS) {
                    pendingUtterances.remove(utteranceId)
                    if (cont.isActive) {
                        cont.resume(
                            Result.failure(
                                IllegalStateException("synthesizeToFile falló con código $queueResult")
                            )
                        )
                    }
                }

                cont.invokeOnCancellation {
                    pendingUtterances.remove(utteranceId)
                }
            }
        }
    }

    private fun createProgressListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            val pending = utteranceId?.let { pendingUtterances.remove(it) } ?: return
            if (pending.continuation.isActive) {
                if (pending.file.exists() && pending.file.length() > 0L) {
                    pending.continuation.resume(Result.success(pending.file))
                } else {
                    pending.continuation.resume(
                        Result.failure(IllegalStateException("El archivo de audio generado está vacío"))
                    )
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val pending = utteranceId?.let { pendingUtterances.remove(it) } ?: return
            if (pending.continuation.isActive) {
                pending.continuation.resume(
                    Result.failure(IllegalStateException("Error durante la síntesis TTS"))
                )
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val pending = utteranceId?.let { pendingUtterances.remove(it) } ?: return
            if (pending.continuation.isActive) {
                pending.continuation.resume(
                    Result.failure(IllegalStateException("Error TTS (código $errorCode)"))
                )
            }
        }
    }

    override fun shutdown() {
        isReady = false
        pendingUtterances.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
