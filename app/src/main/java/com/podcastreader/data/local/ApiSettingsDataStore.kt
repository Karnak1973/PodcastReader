package com.podcastreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.apiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "api_settings"
)

enum class TtsProviderType {
    ANDROID_LOCAL,
    GEMINI_TTS,
    OPENAI_TTS
}

enum class AiProviderType {
    GEMINI,
    OPENAI_COMPATIBLE
}

data class AppSettings(
    val ttsProvider: TtsProviderType = TtsProviderType.ANDROID_LOCAL,
    val androidVoiceName: String = "",
    val cloudVoiceName: String = "Kore",
    val aiProvider: AiProviderType = AiProviderType.GEMINI,
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai/",
    val llmModel: String = "gemini-3.8-flash",
    val embeddingModel: String = "gemini-embedding-001"
)

class ApiSettingsDataStore(private val context: Context) {

    private object Keys {
        val TTS_PROVIDER = stringPreferencesKey("tts_provider")
        val ANDROID_VOICE_NAME = stringPreferencesKey("android_voice_name")
        val CLOUD_VOICE_NAME = stringPreferencesKey("cloud_voice_name")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
    }

    val settingsFlow: Flow<AppSettings> = context.apiSettingsDataStore.data.map { prefs ->
        val aiProvider = prefs[Keys.AI_PROVIDER]?.let {
            runCatching { AiProviderType.valueOf(it) }.getOrNull()
        } ?: AiProviderType.GEMINI

        val ttsProvider = prefs[Keys.TTS_PROVIDER]?.let {
            runCatching { TtsProviderType.valueOf(it) }.getOrNull()
        } ?: TtsProviderType.ANDROID_LOCAL

        val rawLlmModel = prefs[Keys.LLM_MODEL] ?: defaultLlmModel(aiProvider)
        val migratedLlmModel = if (aiProvider == AiProviderType.GEMINI && rawLlmModel == "gemini-2.5-flash") {
            "gemini-3.8-flash"
        } else {
            rawLlmModel
        }

        AppSettings(
            ttsProvider = ttsProvider,
            androidVoiceName = prefs[Keys.ANDROID_VOICE_NAME] ?: "",
            cloudVoiceName = prefs[Keys.CLOUD_VOICE_NAME] ?: "Kore",
            aiProvider = aiProvider,
            apiKey = (prefs[Keys.API_KEY] ?: "").replace(Regex("\\s+"), ""),
            baseUrl = prefs[Keys.BASE_URL] ?: defaultBaseUrl(aiProvider),
            llmModel = migratedLlmModel,
            embeddingModel = prefs[Keys.EMBEDDING_MODEL] ?: defaultEmbeddingModel(aiProvider)
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.apiSettingsDataStore.edit { prefs ->
            prefs[Keys.TTS_PROVIDER] = settings.ttsProvider.name
            prefs[Keys.ANDROID_VOICE_NAME] = settings.androidVoiceName
            prefs[Keys.CLOUD_VOICE_NAME] = settings.cloudVoiceName
            prefs[Keys.AI_PROVIDER] = settings.aiProvider.name
            prefs[Keys.API_KEY] = settings.apiKey.replace(Regex("\\s+"), "")
            prefs[Keys.BASE_URL] = settings.baseUrl.trim()
            prefs[Keys.LLM_MODEL] = settings.llmModel.trim()
            prefs[Keys.EMBEDDING_MODEL] = settings.embeddingModel.trim()
        }
    }

    companion object {
        fun defaultBaseUrl(provider: AiProviderType): String = when (provider) {
            AiProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/"
            AiProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1/"
        }

        fun defaultLlmModel(provider: AiProviderType): String = when (provider) {
            AiProviderType.GEMINI -> "gemini-3.8-flash"
            AiProviderType.OPENAI_COMPATIBLE -> "gpt-4o-mini"
        }

        fun defaultEmbeddingModel(provider: AiProviderType): String = when (provider) {
            AiProviderType.GEMINI -> "gemini-embedding-001"
            AiProviderType.OPENAI_COMPATIBLE -> "text-embedding-3-small"
        }
    }
}
