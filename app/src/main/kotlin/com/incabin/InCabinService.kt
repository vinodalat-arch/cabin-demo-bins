package com.incabin

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.util.Log
import org.opencv.android.OpenCVLoader

class InCabinService : Service() {

    companion object {
        private const val TAG = "InCabin"
        private const val CHANNEL_ID = "incabin_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.incabin.START"
        const val ACTION_STOP = "com.incabin.STOP"

        private const val STATS_INTERVAL = 30

        /**
         * Distraction fields used for the duration counter.
         * Note: dangerous_posture, child_present, child_slouching are NOT included.
         */
        private val DISTRACTION_FIELDS_CHECK: (OutputResult) -> Boolean = { result ->
            result.driverUsingPhone || result.driverEyesClosed ||
                result.driverYawning || result.driverDistracted ||
                result.driverEatingDrinking
        }

        private val ASSET_FILES = listOf(
            "yolov8n-pose.onnx",
            "yolov8n.onnx",
            "face_landmarker.task"
        )
    }

    // --- Core components ---
    private val nativeLib = NativeLib()
    private var cameraManager: CameraManager? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var poseAnalyzer: PoseAnalyzerBridge? = null
    private var smoother: TemporalSmoother? = null
    private var audioAlerter: AudioAlerter? = null

    // --- Distraction duration counter ---
    @Volatile
    private var distractionDurationS: Int = 0

    // --- Performance stats ---
    private var frameCount: Int = 0
    private val recentFrameTimes = mutableListOf<Long>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        initializeComponents()
        startCamera()
        Log.i(TAG, "Service started")

        return START_STICKY
    }

    override fun onDestroy() {
        cameraManager?.stop()
        cameraManager = null

        faceAnalyzer?.close()
        faceAnalyzer = null

        poseAnalyzer?.close()
        poseAnalyzer = null

        audioAlerter?.close()
        audioAlerter = null

        smoother = null
        distractionDurationS = 0

        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    private fun initializeComponents() {
        // --- Device Info ---
        Log.i(TAG, "=== Device Info ===")
        Log.i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        val activityManager = getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val processHeapMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
        Log.i(TAG, "CPUs: ${Runtime.getRuntime().availableProcessors()}, RAM: ${totalRamMb} MB, Process heap: ${processHeapMb} MB")

        // --- Asset Verification ---
        Log.i(TAG, "=== Asset Verification ===")
        for (assetName in ASSET_FILES) {
            try {
                val size = assets.open(assetName).use { it.available().toLong() }
                Log.i(TAG, "Asset $assetName: $size bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Asset $assetName: MISSING or unreadable", e)
            }
        }

        // --- Component Init ---
        Log.i(TAG, "=== Component Init ===")

        // Initialize OpenCV (required for FaceAnalyzer solvePnP)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialization failed")
        } else {
            Log.i(TAG, "OpenCV ${org.opencv.core.Core.VERSION} initialized")
        }

        // FaceAnalyzer (MediaPipe FaceLandmarker)
        try {
            val t0 = System.currentTimeMillis()
            faceAnalyzer = FaceAnalyzer(this)
            Log.i(TAG, "FaceAnalyzer initialized (${System.currentTimeMillis() - t0}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "FaceAnalyzer initialization failed", e)
        }

        // PoseAnalyzerBridge (ONNX Runtime via JNI)
        try {
            val t0 = System.currentTimeMillis()
            poseAnalyzer = PoseAnalyzerBridge(assets)
            Log.i(TAG, "PoseAnalyzerBridge initialized (${System.currentTimeMillis() - t0}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "PoseAnalyzerBridge initialization failed", e)
        }

        // TemporalSmoother (uses Config defaults: window=5, threshold=0.6)
        smoother = TemporalSmoother()
        Log.i(TAG, "TemporalSmoother initialized")

        // AudioAlerter (TextToSpeech)
        try {
            audioAlerter = AudioAlerter(this)
            Log.i(TAG, "AudioAlerter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "AudioAlerter initialization failed", e)
        }

        // Reset counters
        distractionDurationS = 0
        frameCount = 0
        recentFrameTimes.clear()

        Log.i(TAG, "=== Service Ready ===")
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    private fun startCamera() {
        cameraManager = CameraManager(this, ::onFrame)
        cameraManager?.start()
    }

    // -------------------------------------------------------------------------
    // Per-Frame Pipeline
    // -------------------------------------------------------------------------

    private fun onFrame(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int
    ) {
        val frameStartMs = System.currentTimeMillis()

        // Step 1: YUV -> BGR conversion
        val bgrData = nativeLib.nativeYuvToBgr(
            y, u, v, yRowStride, uvRowStride, uvPixelStride, width, height
        )

        if (bgrData == null) {
            Log.e(TAG, "YUV->BGR conversion failed")
            return
        }

        val yuvElapsed = System.currentTimeMillis() - frameStartMs

        try {
            // Step 2: Run PoseAnalyzer (C++/JNI)
            val poseStartMs = System.currentTimeMillis()
            val poseResult = poseAnalyzer?.analyze(bgrData, width, height) ?: PoseResult()
            val poseElapsed = System.currentTimeMillis() - poseStartMs

            // Step 3: BGR -> Bitmap conversion for FaceAnalyzer
            val faceStartMs = System.currentTimeMillis()
            val bitmap = bgrToBitmap(bgrData, width, height)
            val faceResult = faceAnalyzer?.analyze(bitmap, width, height) ?: FaceResult.NO_FACE
            bitmap.recycle()
            val faceElapsed = System.currentTimeMillis() - faceStartMs

            // Step 4: Merge results
            val merged = mergeResults(faceResult, poseResult)

            // Step 5: Temporal smoothing
            val smoothed = smoother?.smooth(merged) ?: merged

            // Step 6: Update distraction duration counter
            if (DISTRACTION_FIELDS_CHECK(smoothed)) {
                distractionDurationS += 1
            } else {
                distractionDurationS = 0
            }

            // Step 7: Inject distraction_duration_s into result
            val finalResult = smoothed.copy(distractionDurationS = distractionDurationS)

            // Step 8: Audio alerter
            audioAlerter?.checkAndAnnounce(finalResult)

            // Step 9: Log JSON output
            Log.i(TAG, finalResult.toJson())

            // Timing summary
            val totalElapsed = System.currentTimeMillis() - frameStartMs
            Log.d(
                TAG,
                "Frame timing: YUV=${yuvElapsed}ms, Pose=${poseElapsed}ms, " +
                    "Face=${faceElapsed}ms, Total=${totalElapsed}ms"
            )

            // Periodic performance stats
            frameCount++
            recentFrameTimes.add(totalElapsed)
            if (frameCount % STATS_INTERVAL == 0) {
                val avg = recentFrameTimes.average().toLong()
                val min = recentFrameTimes.min()
                val max = recentFrameTimes.max()
                val javaHeapMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
                val nativeHeapMb = Debug.getNativeHeapSize() / (1024 * 1024)
                Log.i(
                    TAG,
                    "[Stats @$frameCount] frames=$frameCount, avg=${avg}ms, min=${min}ms, " +
                        "max=${max}ms, heap=${javaHeapMb}MB, native=${nativeHeapMb}MB, " +
                        "distraction=${finalResult.distractionDurationS}s"
                )
                recentFrameTimes.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference pipeline error", e)
        }
    }

    // -------------------------------------------------------------------------
    // BGR -> Bitmap conversion
    // -------------------------------------------------------------------------

    /**
     * Convert BGR byte array to an ARGB_8888 Bitmap for MediaPipe FaceAnalyzer.
     * Swaps B and R channels during the conversion so the Bitmap contains RGB data.
     */
    private fun bgrToBitmap(bgrData: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val b = bgrData[i * 3].toInt() and 0xFF
            val g = bgrData[i * 3 + 1].toInt() and 0xFF
            val r = bgrData[i * 3 + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "In-cabin monitoring service"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
