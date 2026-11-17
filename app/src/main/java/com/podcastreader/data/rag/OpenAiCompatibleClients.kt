package com.podcastreader.data.rag

import com.podcastreader.data.local.ApiSettingsDataStore
import com.podcastreader.domain.rag.EmbeddingProvider
import com.podcastreader.domain.rag.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ConfigurableEmbeddingProvider(
    private val settingsDataStore: ApiSettingsDataStore,
    private val fallbackLocal: LocalHashEmbeddingProvider = LocalHashEmbeddingProvider()
) : EmbeddingProvider {

    override suspend fun embedText(text: String): FloatArray {
        return embedBatch(listOf(text)).first()
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            val settings = settingsDataStore.settingsFlow.first()
            val apiKey = settings.apiKey.replace(Regex("\\s+"), "")
            if (apiKey.isEmpty()) {
                return@withContext fallbackLocal.embedBatch(texts)
            }

            runCatching {
                val cleanBase = settings.baseUrl.trimEnd('/')
                val url = URL("$cleanBase/embeddings")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("model", settings.embeddingModel)
                    put("input", JSONArray(texts))
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    error("Embeddings HTTP $code: $body")
                }

                val data = JSONObject(body).getJSONArray("data")
                val result = MutableList(texts.size) { FloatArray(0) }
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val index = item.optInt("index", i)
                    val embArray = item.getJSONArray("embedding")
                    val vec = FloatArray(embArray.length()) { j ->
                        embArray.getDouble(j).toFloat()
                    }
                    if (index in result.indices) {
                        result[index] = vec
                    }
                }
                result
            }.getOrElse {
                fallbackLocal.embedBatch(texts)
            }
        }
}

class ConfigurableLlmClient(
    private val settingsDataStore: ApiSettingsDataStore
) : LlmClient {

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float
    ): String = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.settingsFlow.first()
        val apiKey = settings.apiKey.replace(Regex("\\s+"), "")
        if (apiKey.isEmpty()) {
            return@withContext "Configura tu API Key en Ajustes (icono de engranaje) para activar las respuestas de IA y el modo Podcast Dual."
        }

        runCatching {
            val cleanBase = settings.baseUrl.trimEnd('/')
            val url = URL("$cleanBase/chat/completions")
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            }
            val payload = JSONObject().apply {
                put("model", settings.llmModel)
                put("temperature", temperature.toDouble())
                put("messages", messages)
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
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(payload) }

                lastCode = conn.responseCode
                lastBody = (if (lastCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (lastCode in 200..299) {
                    val json = JSONObject(lastBody)
                    return@runCatching json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                }

                if ((lastCode == 503 || lastCode == 429 || lastCode == 500) && attempt < maxAttempts) {
                    delay(attempt * 1_500L)
                    continue
                }
                break
            }

            error("HTTP $lastCode (${settings.llmModel}): Servidor saturado o modelo no disponible. Prueba otro modelo de la lista en Ajustes.")
        }.getOrElse { err ->
            "No se pudo conectar con la IA (${err.message ?: "error desconocido"})."
        }
    }
}
