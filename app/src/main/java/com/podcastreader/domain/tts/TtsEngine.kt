package com.podcastreader.domain.tts

import java.io.File

data class VoiceConfig(
    val languageTag: String = "es-ES",
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f,
    val voiceName: String? = null
)

data class TtsVoiceOption(
    val id: String,
    val label: String,
    val isNetwork: Boolean,
    val quality: Int
)

interface TtsEngine {
    val isReady: Boolean
    suspend fun initialize(): Boolean
    suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voiceConfig: VoiceConfig = VoiceConfig()
    ): Result<File>
    fun shutdown()
}
