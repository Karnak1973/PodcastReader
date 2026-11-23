package com.podcastreader.data.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.podcastreader.domain.tts.TtsEngine
import com.podcastreader.domain.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.BreakIterator
import java.util.Locale

/**
 * Motor de TTS offline basado en Sherpa-ONNX con un modelo de voz Piper en español
 * (por defecto `vits-piper-es_ES-davefx-medium`: narrador tipo audiolibro, español de España).
 *
 * Características:
 * - Inicialización en [Dispatchers.IO] para no bloquear el hilo principal.
 * - Sherpa-ONNX necesita rutas reales del sistema de archivos, así que los assets empaquetados
 *   en `app/src/main/assets/tts-model/` se copian a `context.filesDir/tts-model/` al iniciar.
 * - Genera audio neuronal frase por frase y une todo con pausas de estilo audiolibro:
 *   450 ms de silencio tras cada punto y seguido, 750 ms extra al final de cada párrafo.
 * - Implementa [TtsEngine] para poder encajarse en el [CompositeTtsEngine] existente y
 *   reutilizar el caché de fragmentos y la canalización de reproducción (Media3/ExoPlayer).
 */
class SherpaOnnxTtsEngine(private val context: Context) : TtsEngine {

    /** Nombre del paquete de voz dentro de assets/ . */
    private val ASSETS_MODEL_DIR = "tts-model"
    /** Nombre del modelo neuronal (ajustar si se descarga otro paquete de voz). */
    private val MODEL_FILE = "es_ES-davefx-medium.onnx"
    private val TOKENS_FILE = "tokens.txt"

    private val modelDir: File = File(context.filesDir, "tts-model")
    private val initMutex = Mutex()
    private val synthesisMutex = Mutex()

    private val _isReadyFlow = MutableStateFlow(false)
    val isReadyFlow: StateFlow<Boolean> = _isReadyFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private val _lastErrorFlow = MutableStateFlow<String?>(null)
    val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

    @Volatile
    override var isReady: Boolean = false
        private set

    /** Último error de inicialización, para mostrar en la UI de diagnóstico. */
    @Volatile
    var lastInitError: String? = null
        private set

    private var tts: OfflineTts? = null

    // Pausas de estilo audiolibro (milissegundos).
    private val pausaPuntoSeguidoMs = 450
    private val pausaPuntoAparteMs = 750

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isReady && tts != null) return@withLock true

            _isLoadingFlow.value = true
            _lastErrorFlow.value = null

            runCatching {
                Log.d("SherpaTTS", "Inicializando Sherpa-ONNX. modelDir=$modelDir")
                ensureModelFilesCopied()

                val onnxAfter = File(modelDir, MODEL_FILE)
                val tokensAfter = File(modelDir, TOKENS_FILE)
                Log.d("SherpaTTS", "Después de copiar - ONNX isFile=${onnxAfter.isFile}, size=${onnxAfter.length()}")
                Log.d("SherpaTTS", "Después de copiar - tokens isFile=${tokensAfter.isFile}, size=${tokensAfter.length()}")

                require(onnxAfter.isFile && onnxAfter.length() > 1_000_000L) {
                    "El modelo $MODEL_FILE no se copió correctamente (size=${onnxAfter.length()})."
                }
                require(tokensAfter.isFile && tokensAfter.length() > 0L) {
                    "El archivo $TOKENS_FILE no se copió correctamente."
                }

                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = "${modelDir.absolutePath}/$MODEL_FILE",
                    tokens = "${modelDir.absolutePath}/$TOKENS_FILE",
                    dataDir = "", // En Sherpa-ONNX >= 2.0.0 dataDir está deprecado; se usan phonemeCodepoints IPA
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.05f // > 1.0 hace la lectura un poco más pausada, ideal para libros
                )

                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = 2, // 2 hilos en CPU bastan para generar más rápido que el tiempo real
                    debug = false,
                    provider = "cpu"
                )

                val config = OfflineTtsConfig(model = modelConfig)
                tts = OfflineTts(assetManager = null, config = config) // assetManager = null: lee de filesDir
                lastInitError = null
                _lastErrorFlow.value = null
                isReady = true
                _isReadyFlow.value = true
                _isLoadingFlow.value = false
                Log.d("SherpaTTS", "Sherpa-ONNX inicializado correctamente")
                true
            }.getOrElse { error ->
                isReady = false
                _isReadyFlow.value = false
                _isLoadingFlow.value = false
                val msg = error.message ?: error.toString()
                lastInitError = msg
                _lastErrorFlow.value = msg
                Log.e("SherpaTTS", "Error inicializando Sherpa-ONNX: $lastInitError", error)
                false
            }
        }
    }

    override suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voiceConfig: VoiceConfig
    ): Result<File> = withContext(Dispatchers.IO) {
        synthesisMutex.withLock {
            runCatching {
                val engine = tts
                    ?: throw IllegalStateException("Sherpa-ONNX no está inicializado. Llama a initialize() antes de sintetizar.")
                outputFile.parentFile?.mkdirs()

                val sampleRate = engine.sampleRate()
                val samples = buildParagraphSamples(text, engine, sampleRate, voiceConfig)
                WavWriter.write(outputFile, sampleRate, samples)
                outputFile
            }
        }
    }

    override fun shutdown() {
        isReady = false
        _isReadyFlow.value = false
        _isLoadingFlow.value = false
        tts = null
    }

    /**
     * Divide el texto en frases con BreakIterator en español, convierte cada frase a fonemas IPA
     * con [SpanishIpaPhonemizer] (requerido por Sherpa-ONNX >= 2.0.0) y concatena el audio
     * neuronal inyectando silencios: 450 ms entre frases y 750 ms al cerrar el párrafo.
     */
    private fun buildParagraphSamples(
        text: String,
        engine: OfflineTts,
        sampleRate: Int,
        voiceConfig: VoiceConfig
    ): FloatArray {
        val iterator = BreakIterator.getSentenceInstance(Locale("es", "ES"))
        iterator.setText(text)

        val pausaCorto = silencioMuestra(sampleRate, pausaPuntoSeguidoMs)
        val pausaLargo = silencioMuestra(sampleRate, pausaPuntoAparteMs)

        val partes = mutableListOf<FloatArray>()
        var generatedVoiceSamples = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val frase = text.substring(start, end).trim()
            if (frase.isNotEmpty()) {
                val codepoints = SpanishIpaPhonemizer.textToIpaCodepoints(frase)
                if (codepoints.isNotEmpty()) {
                    val genConfig = GenerationConfig(
                        sid = 0,
                        speed = voiceConfig.rate.coerceIn(0.5f, 2.0f),
                        phonemeCodepoints = arrayOf(codepoints)
                    )
                    val audio = engine.generateWithConfig(frase, genConfig)
                    if (audio.samples.isNotEmpty()) {
                        partes.add(audio.samples)
                        partes.add(pausaCorto)
                        generatedVoiceSamples += audio.samples.size
                    }
                }
            }
            start = end
            end = iterator.next()
        }

        require(generatedVoiceSamples > 0) {
            "Sherpa-ONNX no generó muestras de voz para el texto proporcionado."
        }

        // Pausa de punto y aparte al terminar el párrafo
        partes.add(pausaLargo)

        val totalSize = partes.sumOf { it.size }
        val salida = FloatArray(totalSize)
        var pos = 0
        for (parte in partes) {
            java.lang.System.arraycopy(parte, 0, salida, pos, parte.size)
            pos += parte.size
        }
        return salida
    }

    private fun silencioMuestra(sampleRate: Int, duracionMs: Int): FloatArray =
        FloatArray((sampleRate * duracionMs).toInt() / 1000) // inicializado a 0.0f (silencio)

    /**
     * Copia los archivos del modelo (`es_ES-davefx-medium.onnx` y `tokens.txt`) desde
     * `app/src/main/assets/tts-model/` a `context.filesDir/tts-model/`.
     */
    private fun ensureModelFilesCopied() {
        val onnxFile = File(modelDir, MODEL_FILE)
        val tokensFile = File(modelDir, TOKENS_FILE)

        val validCopyExists = onnxFile.isFile && onnxFile.length() > 1_000_000L &&
            tokensFile.isFile && tokensFile.length() > 0L

        if (validCopyExists) return

        if (modelDir.exists()) {
            Log.d("SherpaTTS", "Limpiando directorio previo corrupto o incompleto: $modelDir")
            modelDir.deleteRecursively()
        }

        modelDir.mkdirs()
        copyAssetFile("$ASSETS_MODEL_DIR/$MODEL_FILE", onnxFile)
        copyAssetFile("$ASSETS_MODEL_DIR/$TOKENS_FILE", tokensFile)
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        if (destFile.isDirectory) {
            destFile.deleteRecursively()
        }
        destFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
