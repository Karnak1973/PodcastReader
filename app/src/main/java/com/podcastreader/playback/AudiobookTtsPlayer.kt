package com.podcastreader.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.podcastreader.data.tts.SpanishIpaPhonemizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import java.util.Locale

/**
 * Reproductor de audio directo basado en [AudioTrack] para el motor Sherpa-ONNX.
 *
 * En lugar de entregar un capítulo entero, divide el texto por párrafos y frases. Cada frase
 * se sintetiza en muestras PCM flotante (ENCODING_PCM_FLOAT) y se escribe en el AudioTrack,
 * inyectando silencios controlados al milisegundo:
 *   - 450 ms tras cada punto y seguido (dentro de un párrafo).
 *   - 750 ms extra al finalizar cada párrafo (punto y aparte).
 *
 * Esto reproduce la entonación real de signos de puntuación porque el modelo VITS/Piper pasa
 * la frase completa por espeak-ng-data antes de la red neuronal, ajustando la curva melódica
 * de toda la oración antes de emitir la primera sílaba.
 *
 * Uso básico (bloqueante, una frase tras otra):
 *     player.reproducirCapitulo(textoCapitulo)
 *
 * Para latencia casi nula (productor-consumidor), lanzar [reproducirCapituloConPrefetch] en un
 * hilo de fondo con un [Channel]: se genera la frase N+1 mientras el AudioTrack reproduce la N,
 * así el usuario nunca nota tiempos de carga entre oraciones.
 */
class AudiobookTtsPlayer(private val tts: OfflineTts) {

    private val pausaPuntoSeguidoMs = 450
    private val pausaPuntoAparteMs = 750

    /**
     * Reproduce todo el capítulo de forma secuencial. Cada frase se genera y se escribe en el
     * AudioTrack antes de pasar a la siguiente (el write en modo STREAM bloquea hasta que hay
     * espacio en el buffer, lo que ya da un comportamiento productor-consumidor implícito).
     */
    suspend fun reproducirCapitulo(textoCapitulo: String) = withContext(Dispatchers.Default) {
        val sampleRate = tts.sampleRate()
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).takeIf { it > 0 } ?: 4096

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()
        try {
            // 1. Dividimos primero por párrafos (punto y aparte)
            val parrafos = textoCapitulo.split(Regex("\\n+")).filter { it.isNotBlank() }

            for (parrafo in parrafos) {
                // 2. Dividimos cada párrafo en frases usando BreakIterator en español
                val iterator = BreakIterator.getSentenceInstance(Locale("es", "ES"))
                iterator.setText(parrafo)
                var start = iterator.first()
                var end = iterator.next()

                while (end != BreakIterator.DONE) {
                    val frase = parrafo.substring(start, end).trim()
                    if (frase.isNotEmpty()) {
                        val codepoints = SpanishIpaPhonemizer.textToIpaCodepoints(frase)
                        if (codepoints.isNotEmpty()) {
                            val genConfig = GenerationConfig(
                                sid = 0,
                                speed = 1.0f,
                                phonemeCodepoints = arrayOf(codepoints)
                            )
                            val audio = tts.generateWithConfig(frase, genConfig)
                            audioTrack.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)

                            // Pausa de punto y seguido (450 ms de silencio exacto)
                            escribirSilencio(audioTrack, sampleRate, duracionMs = pausaPuntoSeguidoMs)
                        }
                    }
                    start = end
                    end = iterator.next()
                }

                // Pausa extra de punto y aparte al terminar el párrafo (750 ms adicionales)
                escribirSilencio(audioTrack, sampleRate, duracionMs = pausaPuntoAparteMs)
            }
        } finally {
            audioTrack.stop()
            audioTrack.release()
        }
    }

    /**
     * Variante con prefetch real (productor-consumidor) mediante [Channel].
     *
     * Se lanza una coroutine productora que sintetiza la frase N+1 y la envía por el canal,
     * mientras la coroutine consumidora lee del canal y escribe en el AudioTrack la frase N.
     * Como [OfflineTts.generate] es síncrono y relativamente costoso (red neuronal), el productor
     * se adelanta generando audio mientras el consumidor lo reproduce, ocultando la latencia.
     *
     * @param scope scope donde se lanzan las coroutines (por ejemplo, un ViewModel o el scope de
     *              reproducción de la app).
     * @param textoCapitulo texto completo del capítulo a reproducir.
     * @param onError callback invocado si ocurre un error durante la síntesis o reproducción.
     */
    fun reproducirCapituloConPrefetch(
        scope: CoroutineScope,
        textoCapitulo: String,
        onError: (Throwable) -> Unit = {}
    ) {
        scope.launch {
            try {
                reproducirCapituloConPrefetchInterno(textoCapitulo, onError)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private suspend fun reproducirCapituloConPrefetchInterno(
        textoCapitulo: String,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.Default) {
        val sampleRate = tts.sampleRate()
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).takeIf { it > 0 } ?: 4096

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()
        try {
            // Canal productor-consumidor. Capacidad ilimitada: el productor se adelanta
            // generando frases con la red neuronal mientras el consumidor escribe en el
            // AudioTrack. Como AudioTrack.write bloquea cuando el buffer se llena, el
            // productor se autorregula y nunca se acumulan demasiados fragmentos en memoria.
            val canal = Channel<AudioYResultado>()

            // --- Productor: sintetiza frases (red neuronal) y las mete al canal ---
            val productora = launch {
                try {
                    for (fp in frasesYPausas(textoCapitulo)) {
                        val muestras = if (fp.frase.isBlank()) {
                            // Marcador de fin de párrafo: emitimos silencio directamente
                            // sin llamar a la red neuronal.
                            FloatArray((sampleRate * fp.pausaMs).toInt() / 1000)
                        } else {
                            val codepoints = SpanishIpaPhonemizer.textToIpaCodepoints(fp.frase)
                            if (codepoints.isEmpty()) {
                                FloatArray(0)
                            } else {
                                val genConfig = GenerationConfig(
                                    sid = 0,
                                    speed = 1.0f,
                                    phonemeCodepoints = arrayOf(codepoints)
                                )
                                tts.generateWithConfig(fp.frase, genConfig).samples
                            }
                        }
                        canal.send(AudioYResultado(muestras))
                    }
                    canal.close()
                } catch (e: Exception) {
                    canal.close(e)
                }
            }

            // --- Consumidor: lee muestras ya generadas y las escribe en el AudioTrack ---
            for (ayr in canal) {
                audioTrack.write(ayr.muestras, 0, ayr.muestras.size, AudioTrack.WRITE_BLOCKING)
            }

            productora.join()
        } catch (e: Exception) {
            onError(e)
        } finally {
            audioTrack.stop()
            audioTrack.release()
        }
    }

    /** Plan de reproducción: una frase de texto junto con la pausa (ms) que le sigue. */
    private data class FrasePausa(
        val frase: String,
        val pausaMs: Int
    )

    /** Muestras PCM flotante ya generadas (audio o silencio) listas para el AudioTrack. */
    private data class AudioYResultado(
        val muestras: FloatArray
    )

    /**
     * Genera el plan de (frase, pausaMs) para un capítulo:
     * - Cada frase va seguida de pausa de 450 ms (punto y seguido).
     * - Al cerrar cada párrafo se añade una pausa extra de 750 ms (punto y aparte).
     */
    private fun frasesYPausas(textoCapitulo: String): List<FrasePausa> {
        val resultado = mutableListOf<FrasePausa>()
        val parrafos = textoCapitulo.split(Regex("\\n+")).filter { it.isNotBlank() }

        for (parrafo in parrafos) {
            val iterator = BreakIterator.getSentenceInstance(Locale("es", "ES"))
            iterator.setText(parrafo)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val frase = parrafo.substring(start, end).trim()
                if (frase.isNotEmpty()) {
                    resultado += FrasePausa(frase, pausaPuntoSeguidoMs)
                }
                start = end
                end = iterator.next()
            }
            // Pausa de punto y aparte al finalizar el párrafo
            resultado += FrasePausa("", pausaPuntoAparteMs)
        }
        return resultado
    }

    private fun escribirSilencio(audioTrack: AudioTrack, sampleRate: Int, duracionMs: Int) {
        val numSamples = (sampleRate * duracionMs) / 1000
        val silencio = FloatArray(numSamples) // inicializado a 0.0f por defecto
        audioTrack.write(silencio, 0, silencio.size, AudioTrack.WRITE_BLOCKING)
    }
}
