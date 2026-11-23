package com.podcastreader.data.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.podcastreader.domain.tts.TtsEngine
import com.podcastreader.domain.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
        runCatching {
            Log.d("SherpaTTS", "Inicializando Sherpa-ONNX. modelDir=$modelDir")
            Log.d("SherpaTTS", "ONNX existe: ${File(modelDir, MODEL_FILE).exists()}, size=${File(modelDir, MODEL_FILE).length()}")
            Log.d("SherpaTTS", "tokens existe: ${File(modelDir, TOKENS_FILE).exists()}, size=${File(modelDir, TOKENS_FILE).length()}")
            Log.d("SherpaTTS", "espeak-ng-data existe: ${File(modelDir, "espeak-ng-data").exists()}")
            if (File(modelDir, "espeak-ng-data").exists()) {
                Log.d("SherpaTTS", "espeak-ng-data archivos: ${File(modelDir, "espeak-ng-data").list()?.size ?: 0}")
            }

            ensureModelFilesCopied()

            Log.d("SherpaTTS", "Después de copiar - ONNX existe: ${File(modelDir, MODEL_FILE).exists()}, size=${File(modelDir, MODEL_FILE).length()}")
            Log.d("SherpaTTS", "Después de copiar - tokens existe: ${File(modelDir, TOKENS_FILE).exists()}, size=${File(modelDir, TOKENS_FILE).length()}")
            Log.d("SherpaTTS", "Después de copiar - espeak-ng-data existe: ${File(modelDir, "espeak-ng-data").exists()}")

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = "${modelDir.absolutePath}/$MODEL_FILE",
                tokens = "${modelDir.absolutePath}/$TOKENS_FILE",
                dataDir = "${modelDir.absolutePath}/espeak-ng-data",
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
            isReady = true
            Log.d("SherpaTTS", "Sherpa-ONNX inicializado correctamente")
            true
        }.getOrElse { error ->
            isReady = false
            lastInitError = error.message ?: error.toString()
            Log.e("SherpaTTS", "Error inicializando Sherpa-ONNX: $lastInitError")
            error.printStackTrace()
            false
        }
    }

    override suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voiceConfig: VoiceConfig
    ): Result<File> = withContext(Dispatchers.IO) {
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

    override fun shutdown() {
        isReady = false
        tts = null
    }

    /**
     * Divide el texto en frases con BreakIterator en español, genera el audio neuronal de cada
     * frase y concatena todo inyectando silencios: 450 ms entre frases (punto y seguido) y
     * 750 ms extra al cerrar el párrafo (punto y aparte).
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
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val frase = text.substring(start, end).trim()
            if (frase.isNotEmpty()) {
                // sid = 0 (modelo de un solo altavoz), speed desde VoiceConfig.rate
                val audio = engine.generate(
                    text = frase,
                    sid = 0,
                    speed = voiceConfig.rate.coerceIn(0.5f, 2.0f)
                )
                partes.add(audio.samples)
                partes.add(pausaCorto)
            }
            start = end
            end = iterator.next()
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
     * Copia los assets empaquetados en `app/src/main/assets/tts-model/` a
     * `context.filesDir/tts-model/` si aún no existen allí. Sherpa-ONNX requiere
     * rutas reales del sistema de archivos para leer el modelo y espeak-ng-data.
     */
    private fun ensureModelFilesCopied() {
        val onnxFile = File(modelDir, MODEL_FILE)
        if (onnxFile.exists() && onnxFile.length() > 0L) return // ya copiado previamente

        modelDir.mkdirs()
        copyAssetsRecursively(context.assets, ASSETS_MODEL_DIR, modelDir)
    }

    private fun copyAssetsRecursively(assetManager: AssetManager, assetPath: String, destDir: File) {
        val entries = assetManager.list(assetPath) ?: return
        destDir.mkdirs()
        Log.d("SherpaTTS", "Copiando assets: $assetPath -> ${entries.size} entradas")
        for (entry in entries) {
            val childAssetPath = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            val childDest = File(destDir, entry)
            // Si assetManager.list devuelve un arreglo (aunque vacío), es un directorio.
            if (assetManager.list(childAssetPath) != null) {
                copyAssetsRecursively(assetManager, childAssetPath, childDest)
            } else {
                Log.d("SherpaTTS", "  Archivo: $childAssetPath")
                assetManager.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
