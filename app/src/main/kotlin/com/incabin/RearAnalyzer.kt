package com.incabin

import android.content.res.AssetManager
import android.util.Log
import java.io.Closeable
import java.time.Instant

/**
 * Runs YOLO detection on rear-view camera frames to detect person/cat/dog.
 *
 * Creates its own [PoseAnalyzerBridge] instance (separate ONNX session) to avoid
 * contention with in-cabin inference. Uses 2 threads (not 4) to leave CPU headroom.
 *
 * Person detection comes from the pose model's person bbox output.
 * Cat/dog detection requires C++ changes to expose COCO classes 15/16 from the
 * detect model — currently returns false until that support is added.
 */
class RearAnalyzer(assets: AssetManager) : Closeable {

    companion object {
        private const val TAG = "RearAnalyzer"

        /**
         * Pure function: build a RearResult from pose analysis output.
         * Extracts person count from detected persons list.
         */
        fun buildResult(poseResult: PoseResult): RearResult {
            val personCount = poseResult.persons.size
            val personDetected = personCount > 0

            // Cat/dog detection: requires C++ detect model to expose COCO classes 15, 16.
            // For now, these come from PoseResult which only tracks people.
            val catDetected = false
            val dogDetected = false

            val risk = RearResult.computeRisk(personDetected, catDetected, dogDetected)

            return RearResult(
                timestamp = Instant.now().toString(),
                personDetected = personDetected,
                personCount = personCount,
                catDetected = catDetected,
                dogDetected = dogDetected,
                riskLevel = risk
            )
        }
    }

    private val poseAnalyzer = PoseAnalyzerBridge(assets, threadCount = 2, threadAffinity = "")

    /**
     * Analyze a rear camera BGR frame.
     * Runs full pose+detect pipeline (detect-only would need a separate JNI path).
     * Filters output to person/cat/dog only.
     */
    fun analyze(bgrData: ByteArray, width: Int, height: Int): RearResult {
        if (!poseAnalyzer.isInitialized) {
            Log.w(TAG, "PoseAnalyzer not initialized, returning default")
            return RearResult.default()
        }

        val poseResult = poseAnalyzer.analyze(bgrData, width, height, seatOnLeft = true)
        return buildResult(poseResult)
    }

    override fun close() {
        poseAnalyzer.close()
        Log.i(TAG, "RearAnalyzer closed")
    }
}
