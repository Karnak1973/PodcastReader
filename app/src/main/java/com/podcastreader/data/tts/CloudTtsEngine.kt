package com.podcastreader.data.tts

import android.util.Base64
import com.podcastreader.data.local.AppSettings
import com.podcastreader.data.local.TtsProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class CloudTtsEngine {

    suspend fun synthesize(
        text: String,
        outputFile: File,
        settings: AppSettings,
        voiceOverride: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settings.apiKey.replace(Regex("\\s+"), "")
            require(apiKey.isNotEmpty()) { "API Key no configurada para TTS Cloud" }
            outputFile.parentFile?.mkdirs()

            when (settings.ttsProvider) {
                TtsProviderType.GEMINI_TTS -> synthesizeGemini(
                    text = text,
                    outputFile = outputFile,
                    apiKey = apiKey,
                    voiceName = voiceOverride ?: settings.cloudVoiceName.ifBlank { "Kore" }
                )

                TtsProviderType.OPENAI_TTS -> synthesizeOpenAi(
                    text = text,
                    outputFile = outputFile,
                    apiKey = apiKey,
                    baseUrl = settings.baseUrl,
                    voiceName = voiceOverride ?: settings.cloudVoiceName.ifBlank { "nova" }
                )

                TtsProviderType.ANDROID_LOCAL -> error("CloudTtsEngine llamado en modo local")
            }
        }
    }

    private suspend fun synthesizeGemini(
        text: String,
        outputFile: File,
        apiKey: String,
        voiceName: String
    ): File {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/interactions")
        val payloadBytes = JSONObject().apply {
            put("model", "gemini-3.8-flash-tts")
            put(
                "input",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "user_input")
                        put(
                            "content",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", text)
                                    put(
                                        "annotations",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("type", "speech_metadata")
                                                put("style", "warm, natural Spanish audiobook narrator")
                                            }
                                        )
                                    )
                                }
                            )
                        )
                    }
                )
            )
            put("response_format", JSONObject().put("type", "audio"))
            put(
                "generation_config",
                JSONObject().put(
                    "speech_config",
                    JSONArray().put(JSONObject().put("voice", voiceName))
                )
            )
        }.toString().toByteArray(Charsets.UTF_8)

        var lastCode = 0
        var lastBody = ""
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payloadBytes) }

            lastCode = conn.responseCode
            lastBody = (if (lastCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (lastCode in 200..299) {
                val json = JSONObject(lastBody)
                val outputs = json.optJSONArray("outputs") ?: error("Gemini TTS sin outputs")
                var base64Audio: String? = null
                for (i in 0 until outputs.length()) {
                    val item = outputs.optJSONObject(i) ?: continue
                    if (item.optString("type") == "audio" && item.has("data")) {
                        base64Audio = item.optString("data")
                        break
                    }
                }
                val audioData = base64Audio?.takeIf { it.isNotBlank() }
                    ?: error("Gemini TTS no devolvió datos de audio")

                val bytes = Base64.decode(audioData, Base64.DEFAULT)
                outputFile.writeBytes(bytes)
                return outputFile
            }

            if ((lastCode == 503 || lastCode == 429 || lastCode == 500) && attempt < maxAttempts) {
                delay(attempt * 1_500L)
                continue
            }
            break
        }

        error("Gemini TTS error HTTP $lastCode: $lastBody")
    }

    private fun synthesizeOpenAi(
        text: String,
        outputFile: File,
        apiKey: String,
        baseUrl: String,
        voiceName: String
    ): File {
        val effectiveBase = if (baseUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)) {
            "https://api.openai.com/v1"
        } else {
            baseUrl.trimEnd('/')
        }
        val url = URL("$effectiveBase/audio/speech")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        val payload = JSONObject().apply {
            put("model", "tts-1-hd")
            put("input", text)
            put("voice", voiceName)
            put("response_format", "wav")
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("OpenAI TTS error HTTP $code: $err")
        }

        conn.inputStream.use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outputFile
    }
}
