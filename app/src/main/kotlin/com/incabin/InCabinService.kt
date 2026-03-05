package com.incabin

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import org.opencv.android.OpenCVLoader

class InCabinService : Service() {

    companion object {
        private const val TAG = "InCabin"
        private const val CHANNEL_ID = "incabin_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.incabin.START"
        const val ACTION_STOP = "com.incabin.STOP"
        const val ACTION_REVERSE_ON = "com.incabin.REVERSE_ON"
        const val ACTION_REVERSE_OFF = "com.incabin.REVERSE_OFF"

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
            "yolov8n-pose.onnx", "yolov8n-pose-fp16.onnx",
            "yolov8n.onnx", "yolov8n-fp16.onnx",
            "face_landmarker.task",
            "mobilefacenet-fp16.onnx"
        )

        /** Pure threshold check: should we reinitialize after N consecutive errors? */
        fun shouldReinitialize(errorCount: Int, threshold: Int): Boolean {
            return errorCount >= threshold
        }
    }

    // --- Platform profile (detected once at startup) ---
    private lateinit var platformProfile: PlatformProfile

    // --- Core components ---
    private val nativeLib = NativeLib()
    private var v4l2Camera: V4l2CameraManager? = null
    private var cameraManager: CameraManager? = null
    private var mjpegCamera: MjpegCameraManager? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var poseAnalyzer: PoseAnalyzerBridge? = null
    private var smoother: TemporalSmoother? = null
    private var audioAlerter: AudioAlerter? = null
    private var alertOrchestrator: AlertOrchestrator? = null
    private var vehicleChannelManager: VehicleChannelManager? = null
    private val overlayRenderer = OverlayRenderer()

    // --- Remote VLM client (mutually exclusive with local camera/models) ---
    private var vlmClient: VlmClient? = null

    // --- Rear camera (WiFi MJPEG rear-view) ---
    private var rearMjpegCamera: MjpegCameraManager? = null
    private var rearAnalyzer: RearAnalyzer? = null
    private var rearSmoother: RearSmoother? = null
    private val rearPipelineLock = ReentrantLock()

    // --- Device setup (automotive BSP only) ---
    private var deviceSetup: DeviceSetup? = null
    @Volatile private var initialized = false

    // --- Pipeline watchdog ---
    private var watchdog: PipelineWatchdog? = null

    // --- Face recognition ---
    private var faceRecognizer: FaceRecognizerBridge? = null
    private var faceStore: FaceStore? = null
    private var recognitionFrameCounter = 0
    private var cachedDriverName: String? = null
    private var lastFaceDetected = false
    private var lastWelcomedDriverName: String? = null

    // --- Driver profiles ---
    private var driverProfileStore: DriverProfileStore? = null

    // --- Thread safety: guards processFrame() vs onDestroy() (C1, H6) ---
    private val pipelineLock = ReentrantLock()

    // --- Distraction duration counter (C2: AtomicInteger for thread-safe increment) ---
    private val distractionDurationS = AtomicInteger(0)
    // Grace period: consecutive clean frames before resetting distraction counter
    @Volatile private var cleanFrameCount = 0

    // --- Audio toggle transition tracker ---
    @Volatile private var prevAudioEnabled = true

    // --- Pre-allocated pixel buffer for BGR→Bitmap conversion ---
    // Resized dynamically if actual frame dimensions differ from initial allocation
    private var pixelBuffer = IntArray(Config.CAMERA_WIDTH * Config.CAMERA_HEIGHT)
    private var pixelBufferSize = Config.CAMERA_WIDTH * Config.CAMERA_HEIGHT

    // --- Grace period handler (10s silence after welcome greeting) ---
    private val graceHandler = Handler(Looper.getMainLooper())

    // --- Inference error tracking ---
    private var consecutiveInferenceErrors = 0

    // --- Performance stats ---
    private var frameCount: Int = 0
    private val recentFrameTimes = mutableListOf<Long>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize persistent crash log
        CrashLog.init(filesDir)

        // Install uncaught exception handler to capture crashes to persistent log
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.logException(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REVERSE_ON -> {
                onGearChanged(true)
                return START_STICKY
            }
            ACTION_REVERSE_OFF -> {
                onGearChanged(false)
                return START_STICKY
            }
        }

        // Must call startForeground within 5s of startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Guard against double-init (BootReceiver + Activity may both start us)
        if (initialized) {
            Log.i(TAG, "Service already initialized, ignoring duplicate start")
            return START_STICKY
        }
        initialized = true

        // Load user settings from SharedPreferences (ensures BootReceiver path uses saved config)
        ConfigPrefs.loadIntoConfig(this)

        // Detect platform once
        platformProfile = PlatformProfile.detect()

        if (Config.INFERENCE_MODE == "remote") {
            // Remote VLM mode: skip all local model init and camera
            initializeComponents()
            startVlmClient()
        } else if (platformProfile.isAutomotiveBsp) {
            runDeviceSetupThenStart()
        } else {
            initializeComponents()
            startCamera()
        }
        Log.i(TAG, "Service started (mode=${Config.INFERENCE_MODE})")

        return START_STICKY
    }

    /**
     * On automotive BSPs, run DeviceSetup (rmmod, camera poll, chmod, pm grant)
     * before initializing inference components and starting the camera.
     * DeviceSetup runs on its own background thread — no ANR risk.
     */
    private fun runDeviceSetupThenStart() {
        val setup = DeviceSetup()
        deviceSetup = setup

        // Quick check: if camera is already available, skip setup
        if (setup.isCameraAvailable()) {
            Log.i(TAG, "Camera already available on automotive BSP, skipping DeviceSetup")
            CrashLog.info(TAG, "Camera already available, skipping DeviceSetup")
            initializeComponents()
            startCamera()
            return
        }

        Log.i(TAG, "Starting DeviceSetup for automotive BSP")
        CrashLog.info(TAG, "Starting DeviceSetup")
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.CONNECTING)

        setup.startSetup(packageName, object : DeviceSetup.Callback {
            override fun onStageChanged(stage: DeviceSetup.Stage, message: String) {
                Log.i(TAG, "DeviceSetup stage: $stage - $message")
                CrashLog.info(TAG, "DeviceSetup: $stage - $message")
            }

            override fun onSetupComplete() {
                Log.i(TAG, "DeviceSetup complete, initializing components")
                CrashLog.info(TAG, "DeviceSetup complete")
                FrameHolder.postCameraStatus(FrameHolder.CameraStatus.READY)
                initializeComponents()
                startCamera()
            }

            override fun onSetupFailed(message: String) {
                Log.w(TAG, "DeviceSetup failed: $message — attempting best-effort start")
                CrashLog.warn(TAG, "DeviceSetup failed: $message — best-effort start")
                // Best effort: camera might already be available, or user can fix manually
                initializeComponents()
                startCamera()
            }
        })
    }

    override fun onDestroy() {
        // Cancel DeviceSetup if still running
        deviceSetup?.cancel()
        deviceSetup = null

        graceHandler.removeCallbacksAndMessages(null)

        watchdog?.stop()
        watchdog = null

        pipelineLock.lock()
        try {
            FrameHolder.clear()

            v4l2Camera?.stop()
            v4l2Camera = null

            mjpegCamera?.stop()
            mjpegCamera = null

            cameraManager?.stop()
            cameraManager = null

            vlmClient?.stop()
            vlmClient = null

            rearMjpegCamera?.stop()
            rearMjpegCamera = null
            rearAnalyzer?.close()
            rearAnalyzer = null
            rearSmoother = null

            faceAnalyzer?.close()
            faceAnalyzer = null

            poseAnalyzer?.close()
            poseAnalyzer = null

            faceRecognizer?.close()
            faceRecognizer = null
            faceStore = null
            driverProfileStore = null
            cachedDriverName = null
            lastWelcomedDriverName = null

            alertOrchestrator?.close()
            alertOrchestrator = null
            audioAlerter = null  // closed by orchestrator
            vehicleChannelManager = null  // closed by orchestrator

            smoother = null
            distractionDurationS.set(0)
        } finally {
            pipelineLock.unlock()
        }

        initialized = false
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val action = MemoryPolicy.decideAction(level)
        if (action.disablePreview || action.clearBuffers || action.requestGc) {
            Log.w(TAG, "Memory pressure (level=$level): preview=${action.disablePreview}, " +
                "clearBuffers=${action.clearBuffers}, gc=${action.requestGc}")
            CrashLog.warn(TAG, "Memory pressure level=$level")
        }
        if (action.disablePreview) {
            Config.ENABLE_PREVIEW = false
        }
        if (action.clearBuffers) {
            FrameHolder.clear()
        }
        if (action.requestGc) {
            System.gc()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    private fun initializeComponents() {
        Log.i(TAG, "=== Platform: ${platformProfile.platform} ===")

        // Apply per-platform thresholds
        Config.EAR_THRESHOLD = platformProfile.earThreshold
        Config.HEAD_PITCH_THRESHOLD = platformProfile.headPitchThreshold
        Log.i(TAG, "Thresholds: EAR=${Config.EAR_THRESHOLD}, HEAD_PITCH=${Config.HEAD_PITCH_THRESHOLD}")

        // --- Device Info ---
        Log.i(TAG, "=== Device Info ===")
        Log.i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        val activityManager = getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val processHeapMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
        Log.i(TAG, "CPUs: ${Runtime.getRuntime().availableProcessors()}, RAM: ${totalRamMb} MB, Process heap: ${processHeapMb} MB")

        val initStartMs = System.currentTimeMillis()

        if (Config.INFERENCE_MODE == "local") {
            // --- Asset Verification (local mode only) ---
            Log.i(TAG, "=== Asset Verification ===")
            for (assetName in ASSET_FILES) {
                try {
                    val size = assets.open(assetName).use { it.available().toLong() }
                    Log.i(TAG, "Asset $assetName: $size bytes")
                } catch (_: Exception) {
                    // FP32/FP16 variants: only warn (C++ falls back between them)
                    if (assetName.endsWith("-fp16.onnx") || assetName == "yolov8n-pose.onnx" || assetName == "yolov8n.onnx") {
                        Log.d(TAG, "Asset $assetName: not found (variant, C++ will try fallback)")
                    } else {
                        Log.e(TAG, "Asset $assetName: MISSING or unreadable")
                    }
                }
            }

            // --- Component Init (parallel where possible, local mode only) ---
            Log.i(TAG, "=== Component Init (local) ===")

            // OpenCV must init before FaceAnalyzer (solvePnP dependency)
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "OpenCV initialization failed")
            } else {
                Log.i(TAG, "OpenCV ${org.opencv.core.Core.VERSION} initialized")
            }

            // PoseAnalyzerBridge, FaceRecognizerBridge are independent — init in parallel
            val latch = CountDownLatch(2)

            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    poseAnalyzer = PoseAnalyzerBridge(
                        assets,
                        platformProfile.poseThreadCount,
                        platformProfile.poseThreadAffinity
                    )
                    Log.i(TAG, "PoseAnalyzerBridge initialized (${System.currentTimeMillis() - t0}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "PoseAnalyzerBridge initialization failed", e)
                } finally {
                    latch.countDown()
                }
            }, "PoseAnalyzer-Init").start()

            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    faceRecognizer = FaceRecognizerBridge(
                        assets,
                        platformProfile.faceRecThreadCount,
                        platformProfile.faceRecThreadAffinity
                    )
                    Log.i(TAG, "FaceRecognizerBridge initialized (${System.currentTimeMillis() - t0}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "FaceRecognizerBridge initialization failed", e)
                } finally {
                    latch.countDown()
                }
            }, "FaceRecognizer-Init").start()

            // FaceAnalyzer on current thread (needs Context, already on service thread)
            try {
                val t0 = System.currentTimeMillis()
                faceAnalyzer = FaceAnalyzer(this)
                Log.i(TAG, "FaceAnalyzer initialized (${System.currentTimeMillis() - t0}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "FaceAnalyzer initialization failed", e)
            }

            // Wait for parallel inits to finish (bounded to prevent hang on init failure)
            val initCompleted = latch.await(Config.INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!initCompleted) {
                Log.e(TAG, "Component init timed out after ${Config.INIT_TIMEOUT_MS}ms — continuing with partial init")
                CrashLog.error(TAG, "Component init timed out after ${Config.INIT_TIMEOUT_MS}ms")
            }

            // FaceStore (fast disk I/O, init on main thread)
            try {
                faceStore = FaceStore.getInstance(this)
                Log.i(TAG, "FaceStore initialized (${faceStore?.count() ?: 0} faces)")
            } catch (e: Exception) {
                Log.e(TAG, "FaceStore initialization failed", e)
            }

            // DriverProfileStore (fast disk I/O, init on main thread)
            try {
                driverProfileStore = DriverProfileStore.getInstance(this)
                Log.i(TAG, "DriverProfileStore initialized (${driverProfileStore?.count() ?: 0} profiles)")
            } catch (e: Exception) {
                Log.e(TAG, "DriverProfileStore initialization failed", e)
            }
        } else {
            Log.i(TAG, "Remote VLM mode — skipping local model initialization")
        }

        // --- Common init (both local and remote modes) ---
        // TemporalSmoother (uses Config defaults: window=3, threshold=0.6)
        smoother = TemporalSmoother()
        Log.i(TAG, "TemporalSmoother initialized")

        // AudioAlerter (TextToSpeech with platform-specific audio routing)
        try {
            audioAlerter = AudioAlerter(this, platformProfile.audioUsage)
            Log.i(TAG, "AudioAlerter initialized (audioUsage=${platformProfile.audioUsage})")
        } catch (e: Exception) {
            Log.e(TAG, "AudioAlerter initialization failed", e)
        }

        // VehicleChannelManager (Car API for VHAL-backed vehicle actions)
        if (platformProfile.enableVehicleChannels) {
            try {
                vehicleChannelManager = VehicleChannelManager(this)
                Log.i(TAG, "VehicleChannelManager initialized")
            } catch (e: Exception) {
                Log.w(TAG, "VehicleChannelManager initialization failed — vehicle channels disabled", e)
                vehicleChannelManager = null
            }
        }

        // Wire gear state callback to rear camera control
        vehicleChannelManager?.onGearChanged = ::onGearChanged

        // If car was already in reverse at startup (probed by VehicleChannelManager)
        if (Config.REVERSE_GEAR_ACTIVE) {
            onGearChanged(true)
        }

        // AlertOrchestrator wraps AudioAlerter + VehicleChannelManager
        audioAlerter?.let { alerter ->
            alertOrchestrator = AlertOrchestrator(alerter, vehicleChannelManager)
            Log.i(TAG, "AlertOrchestrator initialized (vehicleChannels=${vehicleChannelManager != null})")
        }

        Log.i(TAG, "Parallel init completed in ${System.currentTimeMillis() - initStartMs}ms")

        // Reset counters
        distractionDurationS.set(0)
        cleanFrameCount = 0
        consecutiveInferenceErrors = 0
        frameCount = 0
        recentFrameTimes.clear()
        recognitionFrameCounter = 0
        cachedDriverName = null
        lastFaceDetected = false
        lastWelcomedDriverName = null
        prevAudioEnabled = Config.ENABLE_AUDIO_ALERTS

        // Start pipeline watchdog
        watchdog = PipelineWatchdog(onTimeout = ::restartPipeline)
        watchdog?.start()

        Log.i(TAG, "=== Service Ready ===")
        CrashLog.info(TAG, "Service initialized (${System.currentTimeMillis() - initStartMs}ms)")
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    private fun startCamera() {
        // WiFi camera URL is now used exclusively for rear-view camera (not in-cabin replacement).
        // In-cabin always uses USB (V4L2 or Camera2).
        val tryV4l2First = platformProfile.cameraStrategy == PlatformProfile.CameraStrategy.V4L2_FIRST

        if (tryV4l2First) {
            val v4l2 = V4l2CameraManager(nativeLib, ::onBgrFrame)
            if (v4l2.start()) {
                v4l2Camera = v4l2
                FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
                Log.i(TAG, "Using V4L2 camera (strategy: V4L2_FIRST)")
                return
            }
            Log.w(TAG, "V4L2 not available, falling back to Camera2")
        }

        // Camera2 path (primary for generic Android, fallback for automotive)
        cameraManager = CameraManager(this, ::onFrame)
        cameraManager?.start()
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        Log.i(TAG, "Using Camera2 (strategy: ${platformProfile.cameraStrategy})")
    }

    /** Reinitialize inference components after repeated errors. */
    private fun reinitializeInference() {
        try {
            poseAnalyzer?.close()
            poseAnalyzer = PoseAnalyzerBridge(
                assets,
                platformProfile.poseThreadCount,
                platformProfile.poseThreadAffinity
            )
            Log.i(TAG, "PoseAnalyzer reinitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reinitialize PoseAnalyzer", e)
        }
        try {
            faceAnalyzer?.close()
            faceAnalyzer = FaceAnalyzer(this)
            Log.i(TAG, "FaceAnalyzer reinitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reinitialize FaceAnalyzer", e)
        }
        smoother = TemporalSmoother()
        consecutiveInferenceErrors = 0
    }

    /** Restart pipeline after watchdog timeout. Mode-aware: restarts camera or VLM client. */
    private fun restartPipeline() {
        Log.w(TAG, "Restarting pipeline (watchdog trigger, mode=${Config.INFERENCE_MODE})")
        CrashLog.warn(TAG, "Pipeline restart triggered by watchdog (mode=${Config.INFERENCE_MODE})")
        if (Config.INFERENCE_MODE == "remote") {
            try {
                vlmClient?.stop()
                vlmClient = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping VLM client during restart", e)
            }
            startVlmClient()
        } else {
            try {
                v4l2Camera?.stop()
                v4l2Camera = null
                mjpegCamera?.stop()
                mjpegCamera = null
                cameraManager?.stop()
                cameraManager = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping cameras during restart", e)
            }
            startCamera()
        }
    }

    // -------------------------------------------------------------------------
    // Remote VLM Client
    // -------------------------------------------------------------------------

    private fun startVlmClient() {
        val url = Config.VLM_SERVER_URL
        if (url.isBlank()) {
            Log.e(TAG, "VLM server URL not configured")
            return
        }
        vlmClient = VlmClient(url, ::onVlmResult)
        vlmClient?.start()
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.CONNECTING)
        Log.i(TAG, "VLM client started: $url")
    }

    /**
     * Callback from VlmClient: processes a remote detection result through the
     * shared downstream pipeline (smoother → distraction → alerts → JSON → dashboard).
     * This is a NEW, SEPARATE callback — processFrame() is NOT touched.
     */
    private fun onVlmResult(rawResult: OutputResult) {
        if (!pipelineLock.tryLock()) return
        try {
            // Smooth (same as local pipeline step 4)
            val smoothed = smoother?.smooth(rawResult) ?: rawResult

            // Distraction duration (same as local pipeline step 5)
            val durationVal = if (!smoothed.driverDetected) {
                cleanFrameCount = 0
                distractionDurationS.set(0)
                0
            } else if (DISTRACTION_FIELDS_CHECK(smoothed)) {
                cleanFrameCount = 0
                distractionDurationS.incrementAndGet()
            } else {
                cleanFrameCount++
                if (cleanFrameCount >= Config.DISTRACTION_GRACE_FRAMES) {
                    distractionDurationS.set(0)
                    0
                } else {
                    distractionDurationS.get()
                }
            }

            // Inject distraction_duration_s (VLM doesn't send driver_name via recognition,
            // so we pass through whatever the server provides)
            // During reverse gear, cap in-cabin risk at "medium"
            val cappedRisk = if (Config.REVERSE_GEAR_ACTIVE && smoothed.riskLevel == "high") {
                Config.REVERSE_RISK_CAP
            } else {
                smoothed.riskLevel
            }
            val finalResult = smoothed.copy(distractionDurationS = durationVal, riskLevel = cappedRisk)

            // Alert orchestrator (core — must run)
            val audioEnabled = Config.ENABLE_AUDIO_ALERTS
            if (audioEnabled) {
                if (!prevAudioEnabled) {
                    alertOrchestrator?.resetState()
                }
                alertOrchestrator?.evaluate(finalResult, applicationContext)
            }
            prevAudioEnabled = audioEnabled

            // Log JSON output (core)
            Log.i(TAG, finalResult.toJson())

            // Post result for fast dashboard updates
            FrameHolder.postResult(finalResult)

            // Heartbeats (pipeline is alive)
            watchdog?.recordHeartbeat()
            FrameHolder.postHeartbeat()

            // Periodic stats
            frameCount++
            if (frameCount % STATS_INTERVAL == 0) {
                val javaHeapMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
                Log.i(TAG, "[VLM Stats @$frameCount] frames=$frameCount, heap=${javaHeapMb}MB, " +
                    "distraction=${finalResult.distractionDurationS}s")
            }
        } catch (e: Exception) {
            Log.e(TAG, "VLM result processing error", e)
            CrashLog.logException(TAG, "VLM result processing error", e)
        } finally {
            pipelineLock.unlock()
        }
    }

    // -------------------------------------------------------------------------
    // Rear Camera (WiFi MJPEG rear-view)
    // -------------------------------------------------------------------------

    /**
     * Start the rear camera pipeline. Creates MjpegCameraManager with its own
     * frame callback and a separate RearAnalyzer + RearSmoother.
     * Called when reverse gear is detected or manual toggle is activated.
     */
    fun startRearCamera() {
        val wifiUrl = Config.WIFI_CAMERA_URL
        if (wifiUrl.isBlank()) {
            Log.w(TAG, "Rear camera URL not configured")
            return
        }
        if (rearMjpegCamera != null) {
            Log.i(TAG, "Rear camera already running")
            return
        }

        // Initialize rear analyzer (separate ONNX session, 2 threads)
        if (rearAnalyzer == null) {
            try {
                rearAnalyzer = RearAnalyzer(assets)
                Log.i(TAG, "RearAnalyzer initialized")
            } catch (e: Exception) {
                Log.e(TAG, "RearAnalyzer initialization failed", e)
                return
            }
        }
        if (rearSmoother == null) {
            rearSmoother = RearSmoother()
        }

        val mjpeg = MjpegCameraManager(nativeLib, ::onRearBgrFrame)
        if (mjpeg.start(wifiUrl)) {
            rearMjpegCamera = mjpeg
            FrameHolder.postRearCameraStatus(FrameHolder.CameraStatus.CONNECTING)
            Log.i(TAG, "Rear camera started: $wifiUrl")
        } else {
            Log.w(TAG, "Failed to start rear camera: $wifiUrl")
            FrameHolder.postRearCameraStatus(FrameHolder.CameraStatus.NOT_CONNECTED)
        }
    }

    /** Stop the rear camera pipeline and clear rear FrameHolder channels. */
    fun stopRearCamera() {
        rearMjpegCamera?.stop()
        rearMjpegCamera = null
        rearSmoother?.reset()
        alertOrchestrator?.resetRearState()
        FrameHolder.postRearCameraStatus(FrameHolder.CameraStatus.NOT_CONNECTED)
        FrameHolder.postRearResult(RearResult.default())
        Log.i(TAG, "Rear camera stopped")
    }

    /** Rear camera BGR frame callback. Runs on MJPEG reader thread. */
    private fun onRearBgrFrame(bgrData: ByteArray, width: Int, height: Int) {
        if (!rearPipelineLock.tryLock()) return
        try {
            val result = rearAnalyzer?.analyze(bgrData, width, height) ?: return
            val smoothed = rearSmoother?.smooth(result) ?: result

            FrameHolder.postRearResult(smoothed)

            // Rear audio alerts
            if (Config.ENABLE_AUDIO_ALERTS) {
                alertOrchestrator?.checkRearAlerts(smoothed)
            }

            Log.i(TAG, "Rear: ${smoothed.toJson()}")
        } catch (e: Exception) {
            Log.w(TAG, "Rear camera pipeline error", e)
        } finally {
            rearPipelineLock.unlock()
        }
    }

    /**
     * Called when gear state changes (from VehicleChannelManager callback or manual toggle).
     * Starts/stops rear camera and sets reverse gear flag.
     */
    fun onGearChanged(isReverse: Boolean) {
        Config.REVERSE_GEAR_ACTIVE = isReverse
        if (isReverse && Config.WIFI_CAMERA_URL.isNotBlank()) {
            startRearCamera()
        } else {
            stopRearCamera()
        }
    }

    // -------------------------------------------------------------------------
    // Per-Frame Pipeline
    // -------------------------------------------------------------------------

    /** Camera2 callback: receives YUV planes, converts to BGR, then runs pipeline. */
    private fun onFrame(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int
    ) {
        val bgrData = nativeLib.nativeYuvToBgr(
            y, u, v, yRowStride, uvRowStride, uvPixelStride, width, height
        )

        if (bgrData == null) {
            Log.e(TAG, "YUV->BGR conversion failed")
            return
        }

        processFrame(bgrData, width, height)
    }

    /** V4L2 callback: receives BGR data directly. */
    private fun onBgrFrame(bgrData: ByteArray, width: Int, height: Int) {
        processFrame(bgrData, width, height)
    }

    /** Common inference pipeline for both Camera2 and V4L2 paths. */
    private fun processFrame(bgrData: ByteArray, width: Int, height: Int) {
        val frameStartMs = System.currentTimeMillis()

        // C1/H6: Acquire pipeline lock; skip frame if service is shutting down
        if (!pipelineLock.tryLock()) return
        try {
            // Step 1: Run PoseAnalyzer (C++/JNI) with seat-side selection
            // Config stores user perspective (which side of the car the driver sits on).
            // Camera faces the occupants, so left/right are mirrored — invert for frame detection.
            val poseStartMs = System.currentTimeMillis()
            val seatOnLeft = Config.DRIVER_SEAT_SIDE == "right"
            val poseResult = poseAnalyzer?.analyze(bgrData, width, height, seatOnLeft) ?: PoseResult()
            val poseElapsed = System.currentTimeMillis() - poseStartMs

            // Step 1.5: Extract driver bbox for face-to-driver spatial validation
            val driverBbox: FloatArray? = poseResult.persons
                .firstOrNull { it.isDriver }
                ?.let { floatArrayOf(it.x1, it.y1, it.x2, it.y2) }

            // Step 2: BGR -> Bitmap conversion for FaceAnalyzer
            // When driver not detected, pass null bbox so face analysis returns NO_FACE
            val faceStartMs = System.currentTimeMillis()
            val bitmap = bgrToBitmap(bgrData, width, height)
            val faceResult = if (poseResult.driverDetected) {
                faceAnalyzer?.analyze(bitmap, width, height, driverBbox, poseResult.passengerCount) ?: FaceResult.NO_FACE
            } else {
                FaceResult.NO_FACE
            }
            val faceElapsed = System.currentTimeMillis() - faceStartMs

            // Step 2.5: Face recognition (try-catch isolated, never blocks core pipeline)
            // Extract face crop once and reuse for both posting and recognition
            val recognizedName: String? = try {
                recognizeDriver(bgrData, width, height, faceResult)
            } catch (e: Exception) {
                Log.w(TAG, "Face recognition failed", e)
                cachedDriverName
            }

            // Step 3: Merge results
            val merged = mergeResults(faceResult, poseResult)

            // Step 4: Temporal smoothing
            val smoothed = smoother?.smooth(merged) ?: merged

            // Step 5: Update distraction duration counter (C2: atomic read-modify-write)
            // Skip distraction tracking when driver is absent — passengers shouldn't
            // accumulate distraction duration
            // Grace period: require 2 consecutive clean frames before resetting,
            // to avoid premature reset from single-frame detection dips
            val durationVal = if (!smoothed.driverDetected) {
                // Driver absent: reset distraction counter
                cleanFrameCount = 0
                distractionDurationS.set(0)
                0
            } else if (DISTRACTION_FIELDS_CHECK(smoothed)) {
                cleanFrameCount = 0
                distractionDurationS.incrementAndGet()
            } else {
                cleanFrameCount++
                if (cleanFrameCount >= Config.DISTRACTION_GRACE_FRAMES) {
                    distractionDurationS.set(0)
                    0
                } else {
                    distractionDurationS.get()
                }
            }

            // Step 6: Inject distraction_duration_s and driver name into result
            // During reverse gear, cap in-cabin risk at "medium" (driver looking backward
            // triggers driver_distracted which is expected during reverse)
            val cappedRisk = if (Config.REVERSE_GEAR_ACTIVE && smoothed.riskLevel == "high") {
                Config.REVERSE_RISK_CAP
            } else {
                smoothed.riskLevel
            }
            val finalResult = smoothed.copy(
                distractionDurationS = durationVal,
                driverName = recognizedName,
                riskLevel = cappedRisk
            )

            // Step 6.5: Welcome greeting + theme apply on first recognition
            // Wrapped in try-catch: comfort feature must never abort core pipeline
            try {
                if (recognizedName != null && recognizedName != lastWelcomedDriverName) {
                    val isJapanese = Config.LANGUAGE == "ja"
                    val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val profile = if (Config.ENABLE_DRIVER_PROFILES) driverProfileStore?.get(recognizedName) else null
                    val theme = if (profile != null) CabinTheme.findById(profile.themeId) else null
                    if (theme != null) {
                        ThemeApplier.applyAll(theme, applicationContext)
                        // Reset ClimateController to new base so ramp starts from new temp
                        vehicleChannelManager?.climateController?.restore()
                        Log.i(TAG, "Applied theme '${theme.id}' for $recognizedName")
                    }
                    val themeName = if (isJapanese) theme?.displayNameJa else theme?.displayName
                    alertOrchestrator?.enqueueWelcome(
                        AudioAlerter.buildWelcomeGreeting(recognizedName, hourOfDay, themeName, isJapanese)
                    )
                    // Activate 10s grace period to suppress safety onset alerts
                    audioAlerter?.gracePeriodActive = true
                    graceHandler.postDelayed({ audioAlerter?.gracePeriodActive = false }, 10_000L)

                    // Force HVAC write with theme temperature
                    if (theme != null) {
                        vehicleChannelManager?.climateController?.setTemperatureOverride(theme.tempC)
                    }

                    lastWelcomedDriverName = recognizedName // set AFTER success
                }
            } catch (e: Exception) {
                Log.w(TAG, "Theme apply/greeting failed, will retry next frame", e)
            }

            // Step 7: Alert orchestrator (core — must run even if overlay fails)
            val audioEnabled = Config.ENABLE_AUDIO_ALERTS
            if (audioEnabled) {
                // Reset alert state on false→true transition to avoid stale prevDangers
                if (!prevAudioEnabled) {
                    alertOrchestrator?.resetState()
                }
                alertOrchestrator?.evaluate(finalResult, applicationContext)
            }
            prevAudioEnabled = audioEnabled

            // Step 8: Log JSON output (core — must run even if overlay fails)
            Log.i(TAG, finalResult.toJson())

            // Step 8.5: Post result immediately for fast dashboard updates
            FrameHolder.postResult(finalResult)

            // Step 8.6: Post per-passenger posture data for UI display (display-only, not in OutputResult)
            // Also trigger audio alerts for passenger posture changes
            try {
                var paxIndex = 0
                val postures = poseResult.persons
                    .filter { !it.isDriver }
                    .map { FrameHolder.PassengerPosture(++paxIndex, it.badPosture) }
                FrameHolder.postPassengerPostures(postures)

                // Audio alert for passenger posture (pipeline-isolated, separate from driver alerts)
                if (audioEnabled) {
                    try {
                        alertOrchestrator?.checkPassengerPostures(postures)
                    } catch (e: Exception) {
                        Log.w(TAG, "Passenger posture audio alert failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to post passenger postures", e)
            }

            // Step 8.7: Per-seat position assignment (display-only, not in OutputResult)
            try {
                val driverState = SeatAssigner.deriveDriverState(finalResult)
                // Invert side for frame coordinates (camera mirrors left/right)
                val frameSide = if (Config.DRIVER_SEAT_SIDE == "left") "right" else "left"
                val seatMap = SeatAssigner.assign(
                    persons = poseResult.persons,
                    driverSeatSide = frameSide,
                    frameWidth = Config.CAMERA_WIDTH,
                    driverState = driverState
                )
                FrameHolder.postSeatMap(seatMap)
            } catch (e: Exception) {
                Log.w(TAG, "Seat assignment failed", e)
            }

            // Step 9: Render overlay and post bitmap to FrameHolder (skipped when preview disabled)
            if (Config.ENABLE_PREVIEW) {
                try {
                    overlayRenderer.render(bitmap, poseResult, faceResult, finalResult)
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay rendering failed", e)
                }
                FrameHolder.postFrame(bitmap, finalResult)
            } else {
                bitmap.recycle()
            }

            // Timing summary
            val totalElapsed = System.currentTimeMillis() - frameStartMs
            Log.i(
                TAG,
                "Frame timing: Pose=${poseElapsed}ms, " +
                    "Face=${faceElapsed}ms, Total=${totalElapsed}ms"
            )

            // Successful frame — reset error counter
            consecutiveInferenceErrors = 0

            // Record heartbeats (pipeline is alive)
            watchdog?.recordHeartbeat()
            FrameHolder.postHeartbeat()

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
            CrashLog.logException(TAG, "Inference pipeline error", e)
            consecutiveInferenceErrors++
            if (shouldReinitialize(consecutiveInferenceErrors, Config.MAX_CONSECUTIVE_INFERENCE_ERRORS)) {
                Log.e(TAG, "Consecutive inference errors reached ${Config.MAX_CONSECUTIVE_INFERENCE_ERRORS} — reinitializing")
                CrashLog.error(TAG, "Reinitializing inference after $consecutiveInferenceErrors consecutive errors")
                reinitializeInference()
            }
        } finally {
            pipelineLock.unlock()
        }
    }

    // -------------------------------------------------------------------------
    // Face Recognition
    // -------------------------------------------------------------------------

    /**
     * Recognize the driver's face, running every Nth frame or returning cached result.
     * Isolated from core pipeline — callers wrap in try-catch.
     */
    private fun recognizeDriver(
        bgrData: ByteArray, width: Int, height: Int, faceResult: FaceResult
    ): String? {
        val landmarks = faceAnalyzer?.lastLandmarks

        // No face detected — clear cache
        if (landmarks == null) {
            if (lastFaceDetected) {
                cachedDriverName = null
                lastFaceDetected = false
            }
            recognitionFrameCounter = 0
            return null
        }

        lastFaceDetected = true

        // Extract face crop ONCE and reuse for both posting and recognition
        val crop = try {
            faceAnalyzer?.extractFaceCrop(bgrData, width, height, landmarks)
        } catch (e: Exception) {
            null
        }

        // Post face crop for registration UI (even with no registered faces)
        if (crop != null) {
            try {
                FrameHolder.postCaptureData(
                    FrameHolder.CaptureData(crop.bgrData, crop.width, crop.height)
                )
            } catch (_: Exception) {}
        }

        // Skip recognition if no recognizer or no registered faces
        val recognizer = faceRecognizer ?: return null
        val store = faceStore ?: return null
        if (store.count() == 0) return null

        recognitionFrameCounter++

        // Return cached result on non-recognition frames (run every Nth frame)
        if (recognitionFrameCounter % Config.FACE_RECOGNITION_INTERVAL != 0 && cachedDriverName != null) {
            return cachedDriverName
        }

        // Run recognition using the already-extracted crop
        if (crop == null) return cachedDriverName
        val embedding = recognizer.computeEmbedding(crop.bgrData, crop.width, crop.height) ?: return cachedDriverName
        val match = store.findBestMatch(embedding, Config.FACE_RECOGNITION_THRESHOLD)

        cachedDriverName = match?.first
        if (match != null) {
            Log.d(TAG, "Face recognized: ${match.first} (similarity: ${"%.3f".format(match.second)})")
        }

        return cachedDriverName
    }

    // -------------------------------------------------------------------------
    // BGR -> Bitmap conversion
    // -------------------------------------------------------------------------

    /**
     * Convert BGR byte array to an ARGB_8888 Bitmap for MediaPipe FaceAnalyzer.
     * Uses native C++ for pixel conversion with pre-allocated IntArray buffer.
     */
    private fun bgrToBitmap(bgrData: ByteArray, width: Int, height: Int): Bitmap {
        val numPixels = width * height
        if (numPixels > pixelBufferSize) {
            pixelBuffer = IntArray(numPixels)
            pixelBufferSize = numPixels
            Log.i(TAG, "Resized pixelBuffer for ${width}x${height} ($numPixels pixels)")
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        nativeLib.nativeBgrToArgbPixels(bgrData, pixelBuffer, width, height)
        bitmap.setPixels(pixelBuffer, 0, width, 0, 0, width, height)
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
