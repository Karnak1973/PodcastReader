package com.podcastreader.data.tts

import com.podcastreader.data.local.ApiSettingsDataStore
import com.podcastreader.data.local.AppSettings
import com.podcastreader.data.local.TtsProviderType
import com.podcastreader.domain.tts.TtsEngine
import com.podcastreader.domain.tts.VoiceConfig
import kotlinx.coroutines.flow.first
import java.io.File

class CompositeTtsEngine(
    private val androidTtsEngine: AndroidTtsEngine,
    private val cloudTtsEngine: CloudTtsEngine,
    private val settingsDataStore: ApiSettingsDataStore
) : TtsEngine {

    override val isReady: Boolean
        get() = androidTtsEngine.isReady

    override suspend fun initialize(): Boolean {
        return androidTtsEngine.initialize()
    }

    suspend fun currentSettings(): AppSettings = settingsDataStore.settingsFlow.first()

    override suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voiceConfig: VoiceConfig
    ): Result<File> {
        val settings = currentSettings()
        val useCloud = settings.ttsProvider != TtsProviderType.ANDROID_LOCAL &&
            settings.apiKey.isNotBlank()

        if (useCloud) {
            val cloudResult = cloudTtsEngine.synthesize(
                text = text,
                outputFile = outputFile,
                settings = settings,
                voiceOverride = voiceConfig.voiceName
            )
            if (cloudResult.isSuccess) {
                return cloudResult
            }
        }

        val localConfig = voiceConfig.copy(
            voiceName = voiceConfig.voiceName ?: settings.androidVoiceName.takeIf { it.isNotBlank() }
        )
        return androidTtsEngine.synthesizeToFile(text, outputFile, localConfig)
    }

    override fun shutdown() {
        androidTtsEngine.shutdown()
    }
}
