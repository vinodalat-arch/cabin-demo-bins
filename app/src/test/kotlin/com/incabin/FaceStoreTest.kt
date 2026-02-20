package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FaceStore.cosineSimilarity.
 * The FaceStore class itself requires Android context, so we test
 * the static cosine similarity function directly.
 */
class FaceStoreTest {

    @Test
    fun test_identical_vectors_similarity_1() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        val sim = FaceStore.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun test_orthogonal_vectors_similarity_0() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val sim = FaceStore.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun test_opposite_vectors_similarity_negative1() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        val sim = FaceStore.cosineSimilarity(a, b)
        assertEquals(-1.0f, sim, 0.001f)
    }

    @Test
    fun test_similar_vectors_above_threshold() {
        // Slightly different unit vectors should be close to 1.0
        val a = floatArrayOf(0.9f, 0.1f, 0.0f)
        val b = floatArrayOf(0.85f, 0.15f, 0.0f)
        val sim = FaceStore.cosineSimilarity(a, b)
        assertTrue("Similar vectors should have high similarity", sim > 0.9f)
    }

    @Test
    fun test_different_vectors_below_threshold() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 0f, 1f)
        val sim = FaceStore.cosineSimilarity(a, b)
        assertTrue("Orthogonal vectors should be below threshold 0.5", sim < 0.5f)
    }

    @Test
    fun test_empty_arrays_return_0() {
        val a = floatArrayOf()
        val b = floatArrayOf()
        val sim = FaceStore.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun test_mismatched_lengths_return_0() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        val sim = FaceStore.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun test_normalized_512dim_vectors() {
        // Simulate two L2-normalized 512-dim embeddings
        val a = FloatArray(512) { if (it == 0) 1f else 0f }
        val b = FloatArray(512) { if (it == 0) 1f else 0f }
        val sim = FaceStore.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }
}
