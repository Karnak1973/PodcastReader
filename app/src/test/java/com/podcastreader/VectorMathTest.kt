package com.podcastreader

import com.podcastreader.domain.rag.VectorMath
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun `cosineSimilarity returns expected values for identical, orthogonal, and opposite vectors`() {
        val v1 = floatArrayOf(1f, 0f, 0f)
        val v2 = floatArrayOf(0f, 1f, 0f)
        val v3 = floatArrayOf(-1f, 0f, 0f)

        assertEquals(1.0f, VectorMath.cosineSimilarity(v1, v1), 1e-5f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(v1, v2), 1e-5f)
        assertEquals(-1.0f, VectorMath.cosineSimilarity(v1, v3), 1e-5f)
    }
}
