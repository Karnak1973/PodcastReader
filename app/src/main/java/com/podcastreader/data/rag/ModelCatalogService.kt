package com.podcastreader.data.rag

import com.podcastreader.data.local.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FetchedModels(
    val llmModels: List<String> = emptyList(),
    val embeddingModels: List<String> = emptyList()
)

object ModelCatalogService {

    suspend fun fetchModels(
        aiProvider: AiProviderType,
        apiKey: String,
        baseUrl: String
    ): Result<FetchedModels> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.replace(Regex("\\s+"), "")
            require(cleanKey.isNotEmpty()) { "Introduce tu API Key primero" }

            when (aiProvider) {
                AiProviderType.GEMINI -> fetchGeminiModels(cleanKey)
                AiProviderType.OPENAI_COMPATIBLE -> fetchOpenAiModels(cleanKey, baseUrl)
            }
        }
    }

    private fun fetchGeminiModels(apiKey: String): FetchedModels {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            error("HTTP $code al listar modelos de Gemini")
        }

        val modelsArray = JSONObject(body).optJSONArray("models")
            ?: return FetchedModels()

        val llmList = mutableListOf<String>()
        val embList = mutableListOf<String>()

        for (i in 0 until modelsArray.length()) {
            val obj = modelsArray.optJSONObject(i) ?: continue
            val rawName = obj.optString("name").removePrefix("models/")
            if (rawName.isBlank()) continue

            val methods = obj.optJSONArray("supportedGenerationMethods")
            val methodSet = buildSet {
                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        add(methods.optString(j))
                    }
                }
            }

            if (methodSet.contains("embedContent") || rawName.contains("embedding", ignoreCase = true)) {
                embList.add(rawName)
            } else if (methodSet.contains("generateContent") &&
                !rawName.contains("image", ignoreCase = true) &&
                !rawName.contains("tts", ignoreCase = true)
            ) {
                llmList.add(rawName)
            }
        }

        return FetchedModels(
            llmModels = llmList.distinct().sorted(),
            embeddingModels = embList.distinct().sorted()
        )
    }

    private fun fetchOpenAiModels(apiKey: String, baseUrl: String): FetchedModels {
        val cleanBase = baseUrl.trimEnd('/')
        val url = URL("$cleanBase/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            error("HTTP $code al listar modelos")
        }

        val dataArray = JSONObject(body).optJSONArray("data")
            ?: return FetchedModels()

        val llmList = mutableListOf<String>()
        val embList = mutableListOf<String>()

        for (i in 0 until dataArray.length()) {
            val id = dataArray.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isBlank()) continue
            if (id.contains("embed", ignoreCase = true)) {
                embList.add(id)
            } else {
                llmList.add(id)
            }
        }

        return FetchedModels(
            llmModels = llmList.distinct().sorted(),
            embeddingModels = embList.distinct().sorted()
        )
    }
}
