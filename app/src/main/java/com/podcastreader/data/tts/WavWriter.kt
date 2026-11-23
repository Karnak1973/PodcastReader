package com.podcastreader.data.tts

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Escribe un arreglo de muestras PCM flotante (FloatArray) como archivo WAV
 * con codificación IEEE float (format tag 3), mono, sin compresión.
 *
 * El AudioTrack de la app y ExoPlayer/Media3 pueden consumir este formato
 * directamente, por lo que sirve tanto para reproducción en vivo como para
 * exportar capítulos a archivo (.wav) desde el motor Sherpa-ONNX.
 */
object WavWriter {

    @JvmName("writeFloatWav")
    fun write(file: File, sampleRate: Int, samples: FloatArray) {
        val bytesPerSample = 4 // float32
        val dataSize = samples.size * bytesPerSample
        val chunkSize = 36 + dataSize

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            // ---- RIFF chunk descriptor ----
            fos.write("RIFF".toByteArray(Charsets.UTF_8))
            writeIntLE(fos, chunkSize)
            fos.write("WAVE".toByteArray(Charsets.UTF_8))

            // ---- fmt sub-chunk ----
            fos.write("fmt ".toByteArray(Charsets.UTF_8))
            writeIntLE(fos, 16) // Subchunk1Size = 16 para PCM
            writeShortLE(fos, 3) // AudioFormat = 3 (IEEE_FLOAT)
            writeShortLE(fos, 1) // NumChannels = 1 (mono)
            writeIntLE(fos, sampleRate)
            writeIntLE(fos, sampleRate * bytesPerSample) // ByteRate
            writeShortLE(fos, bytesPerSample.toInt()) // BlockAlign
            writeShortLE(fos, 32) // BitsPerSample = 32 (float)

            // ---- data sub-chunk ----
            fos.write("data".toByteArray(Charsets.UTF_8))
            writeIntLE(fos, dataSize)

            val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                bb.putFloat(sample)
            }
            fos.write(bb.array())
        }
    }

    private fun writeIntLE(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
        fos.write((value shr 16) and 0xFF)
        fos.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
    }
}
