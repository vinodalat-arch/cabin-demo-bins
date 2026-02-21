package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow integration tests for face recognition logic and driver name integration
 * into OutputResult schema validation and the merge+smooth pipeline.
 */
class FlowFaceRecognitionTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeResult(
        driverName: String? = null,
        phone: Boolean = false,
        eyes: Boolean = false,
        ear: Float? = 0.25f,
        mar: Float? = 0.2f,
        headYaw: Float? = 0f,
        headPitch: Float? = 0f
    ): OutputResult = OutputResult(
        timestamp = "2026-01-01T00:00:00Z",
        passengerCount = 1,
        driverUsingPhone = phone,
        driverEyesClosed = eyes,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "low",
        earValue = ear,
        marValue = mar,
        headYaw = headYaw,
        headPitch = headPitch,
        distractionDurationS = 0,
        driverName = driverName
    )

    // -------------------------------------------------------------------------
    // Schema Validation with Driver Name (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_driver_name_in_output_validates_schema() {
        val result = makeResult(driverName = "Vinod")
        val errors = OutputResult.validate(result.toMap())
        assertTrue("Schema should validate with driver_name: $errors", errors.isEmpty())
        assertEquals("Vinod", result.driverName)
    }

    @Test
    fun test_null_driver_name_validates_schema() {
        val result = makeResult(driverName = null)
        val errors = OutputResult.validate(result.toMap())
        assertTrue("Schema should validate with null driver_name: $errors", errors.isEmpty())
        assertNull(result.driverName)
    }

    // -------------------------------------------------------------------------
    // Cosine Similarity Matching (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_cosine_similarity_identical_embeddings() {
        val embedding = FloatArray(Config.FACE_EMBEDDING_DIM) { 1.0f / Config.FACE_EMBEDDING_DIM }
        val sim = FaceStore.cosineSimilarity(embedding, embedding)
        assertTrue("Identical embeddings should have similarity ~1.0", sim > 0.99f)
    }

    @Test
    fun test_cosine_similarity_below_threshold_rejects() {
        // Orthogonal vectors → cosine similarity ≈ 0
        val a = FloatArray(Config.FACE_EMBEDDING_DIM) { if (it < 256) 1f else 0f }
        val b = FloatArray(Config.FACE_EMBEDDING_DIM) { if (it >= 256) 1f else 0f }
        val sim = FaceStore.cosineSimilarity(a, b)
        assertTrue("Orthogonal embeddings should be below threshold", sim < Config.FACE_RECOGNITION_THRESHOLD)
    }

    @Test
    fun test_best_match_selection_from_multiple_faces() {
        // Re-implement findBestMatch logic since FaceStore needs Context
        val query = FloatArray(Config.FACE_EMBEDDING_DIM) { 1f }
        val faceA = FloatArray(Config.FACE_EMBEDDING_DIM) { if (it < 256) 1f else 0f }
        val faceB = FloatArray(Config.FACE_EMBEDDING_DIM) { 1f } // identical to query

        val simA = FaceStore.cosineSimilarity(query, faceA)
        val simB = FaceStore.cosineSimilarity(query, faceB)

        assertTrue("FaceB should match better than FaceA", simB > simA)
        assertTrue("FaceB should exceed threshold", simB >= Config.FACE_RECOGNITION_THRESHOLD)
    }

    // -------------------------------------------------------------------------
    // Driver Name Through Pipeline (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_driver_name_survives_merge_smooth_pipeline() {
        // Merge produces result, then inject driver name, then smooth
        val face = FaceResult(earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(passengerCount = 1)
        val merged = mergeResults(face, pose)

        // Driver name is injected after merge (by InCabinService), simulate that
        val withName = merged.copy(driverName = "Vinod")

        val smoother = TemporalSmoother()
        val smoothed = smoother.smooth(withName)

        // TemporalSmoother.smooth() uses result.copy() → driverName is preserved
        assertEquals("Vinod", smoothed.driverName)
    }

    @Test
    fun test_output_json_roundtrip_with_driver_name() {
        val result = makeResult(driverName = "Vinod")
        val json = result.toJson()
        val parsed = OutputResult.fromJson(json)
        assertEquals("Vinod", parsed.driverName)
        assertEquals(result.passengerCount, parsed.passengerCount)
        assertEquals(result.riskLevel, parsed.riskLevel)
    }

    @Test
    fun test_driver_name_json_escaping_special_chars() {
        // Names with quotes or backslashes must produce valid JSON
        val result = makeResult(driverName = "John\"Smith")
        val json = result.toJson()
        // Verify the escaped JSON round-trips correctly through Gson
        val parsed = OutputResult.fromJson(json)
        assertEquals("John\"Smith", parsed.driverName)
    }
}
