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
    private val sherpaTtsEngine: SherpaOnnxTtsEngine,
    private val cloudTtsEngine: CloudTtsEngine,
    private val settingsDataStore: ApiSettingsDataStore
) : TtsEngine {

    override val isReady: Boolean
        get() = androidTtsEngine.isReady || sherpaTtsEngine.isReady

    override suspend fun initialize(): Boolean {
        return androidTtsEngine.initialize()
    }

    private suspend fun currentSettings(): AppSettings = settingsDataStore.settingsFlow.first()

    /**
     * Si el proveedor activo es Sherpa-ONNX y aún no se inicializó, se inicializa bajo demanda
     * (carga el modelo Piper desde filesDir). Así no se ralentiza el arranque de la app si no
     * se usa este motor.
     */
    private suspend fun ensureSherpaInitialized(): Boolean {
        if (sherpaTtsEngine.isReady) return true
        return sherpaTtsEngine.initialize()
    }

    override suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voiceConfig: VoiceConfig
    ): Result<File> {
        val settings = currentSettings()

        if (settings.ttsProvider == TtsProviderType.SHERPA_ONNX_LOCAL) {
            if (!ensureSherpaInitialized()) {
                return Result.failure(
                    IllegalStateException("No se pudo inicializar Sherpa-ONNX. Verifica que el modelo esté en app/src/main/assets/tts-model/")
                )
            }
            return sherpaTtsEngine.synthesizeToFile(text, outputFile, voiceConfig)
        }

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
        sherpaTtsEngine.shutdown()
    }
}
