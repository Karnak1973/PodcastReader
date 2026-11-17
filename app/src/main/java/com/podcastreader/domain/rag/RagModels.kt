package com.podcastreader.domain.rag

import kotlin.math.sqrt

interface EmbeddingProvider {
    suspend fun embedText(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}

interface LlmClient {
    suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float = 0.2f
    ): String
}

object VectorMath {
    fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        require(vecA.size == vecB.size) {
            "Dimensión de vectores incompatible: ${vecA.size} vs ${vecB.size}"
        }
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in vecA.indices) {
            val a = vecA[i]
            val b = vecB[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dotProduct / denom
    }
}
