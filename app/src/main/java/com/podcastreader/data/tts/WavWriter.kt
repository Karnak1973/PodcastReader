package com.podcastreader.data.tts

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Escribe un arreglo de muestras flotantes normalizadas [-1.0f, 1.0f] como archivo WAV
 * PCM lineal de 16 bits (AudioFormat = 1), mono, sin compresión.
 *
 * El formato PCM de 16 bits es universalmente compatible con ExoPlayer/Media3
 * (incluso con `enableFloatOutput = false` por defecto) y con `MediaPlayer` de Android.
 */
object WavWriter {

    @JvmName("writeFloatWav")
    fun write(file: File, sampleRate: Int, samples: FloatArray) {
        val bytesPerSample = 2 // 16-bit PCM signed integer
        val dataSize = samples.size * bytesPerSample
        val chunkSize = 36 + dataSize

        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            // ---- RIFF chunk descriptor ----
            fos.write("RIFF".toByteArray(Charsets.UTF_8))
            writeIntLE(fos, chunkSize)
            fos.write("WAVE".toByteArray(Charsets.UTF_8))

            // ---- fmt sub-chunk ----
            fos.write("fmt ".toByteArray(Charsets.UTF_8))
            writeIntLE(fos, 16) // Subchunk1Size = 16 para PCM
            writeShortLE(fos, 1) // AudioFormat = 1 (PCM estándar 16-bit)
            writeShortLE(fos, 1) // NumChannels = 1 (mono)
            writeIntLE(fos, sampleRate)
            writeIntLE(fos, sampleRate * bytesPerSample) // ByteRate
            writeShortLE(fos, bytesPerSample) // BlockAlign
            writeShortLE(fos, 16) // BitsPerSample = 16

            // ---- data sub-chunk ----
            fos.write("data".toByteArray(Charsets.UTF_8))
            writeIntLE(fos, dataSize)

            val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                val pcm16 = (clamped * 32767.0f).toInt().toShort()
                bb.putShort(pcm16)
            }
            fos.write(bb.array())
        }
        if (file.exists()) {
            file.delete()
        }
        tempFile.renameTo(file)
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
