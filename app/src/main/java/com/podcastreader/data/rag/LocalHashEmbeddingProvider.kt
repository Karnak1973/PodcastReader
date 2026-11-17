package com.podcastreader.data.rag

import com.podcastreader.domain.rag.EmbeddingProvider
import kotlin.math.sqrt

/**
 * Proveedor de embeddings determinista offline (feature hashing sobre palabras y bigramas).
 * Permite probar el motor RAG y la similitud coseno sin conexión o sin API Key configurada.
 */
class LocalHashEmbeddingProvider(private val dimensions: Int = 256) : EmbeddingProvider {

    override suspend fun embedText(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val tokens = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        for (i in tokens.indices) {
            addToken(vector, tokens[i], 1.0f)
            if (i + 1 < tokens.size) {
                addToken(vector, "${tokens[i]}_${tokens[i + 1]}", 0.7f)
            }
        }

        var norm = 0f
        for (v in vector) norm += v * v
        val denom = sqrt(norm)
        if (denom > 0f) {
            for (i in vector.indices) vector[i] /= denom
        }
        return vector
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embedText(it) }
    }

    private fun addToken(vector: FloatArray, token: String, weight: Float) {
        val hash = token.hashCode()
        val idx = (hash and 0x7FFFFFFF) % dimensions
        val sign = if ((hash and 1) == 0) 1f else -1f
        vector[idx] += sign * weight
    }
}
