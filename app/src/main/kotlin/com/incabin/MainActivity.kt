package com.incabin

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val TAG = "InCabin-Activity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREVIEW_POLL_MS = 500L
        private const val PREFS_NAME = "incabin_prefs"
        private const val PREF_PREVIEW_ENABLED = "preview_enabled"
        private const val PREF_AUDIO_ENABLED = "audio_enabled"
        private const val PREF_LANGUAGE = "language"
        private const val PREF_SEAT_SIDE = "seat_side"
        private const val PREF_WIFI_URL = "wifi_camera_url"

        // Score tuning
        private const val SCORE_RECOVERY = 0.5f
        private val SCORE_PENALTIES = mapOf(
            "phone" to 2.0f,
            "eyes" to 2.0f,
            "distracted" to 1.5f,
            "yawning" to 1.0f,
            "eating" to 1.0f,
            "posture" to 1.0f,
            "child_slouching" to 0.5f
        )

        // Streak milestones in milliseconds
        private val STREAK_MILESTONES_EN = listOf(
            5L * 60 * 1000 to "5 minutes distraction-free! Keep it up!",
            15L * 60 * 1000 to "15 minutes! You're on fire!",
            30L * 60 * 1000 to "Half hour of perfect driving. Legend!"
        )
        private val STREAK_MILESTONES_JA = listOf(
            5L * 60 * 1000 to "5分間集中！この調子で！",
            15L * 60 * 1000 to "15分間！素晴らしい！",
            30L * 60 * 1000 to "30分間完璧な運転。伝説です！"
        )

        // Varied detection messages
        private val PHONE_MESSAGES = listOf(
            "Phone spotted! That text can wait.",
            "Phone down, eyes up!",
            "The phone can wait. The road can't."
        )
        private val EYES_MESSAGES = listOf(
            "Hey sleepyhead, wake up!",
            "Blink is fine. Napping isn't!",
            "Your eyes called. They want the road back."
        )
        private val DISTRACTED_MESSAGES = listOf(
            "The road is this way!",
            "Hey, eyes on the road!",
            "Focus! The scenery can wait."
        )
        private val YAWNING_MESSAGES = listOf(
            "Big yawn! Time for a coffee break?",
            "That's a big one! Need a pit stop?",
            "Yawn alert! Maybe pull over for a stretch?"
        )
        private val EATING_MESSAGES = listOf(
            "Snack break? Not behind the wheel!",
            "Eating while driving? Risky combo!",
            "Hands on the wheel, not the meal!"
        )
        private val POSTURE_MESSAGES = listOf(
            "Sit up straight, captain!",
            "Posture check! Sit up and stay alert.",
            "Lean back and focus on the road."
        )
        private val CHILD_SLOUCH_MESSAGES = listOf(
            "Little one slouching back there!",
            "Check on your little passenger!",
            "Kiddo needs a posture check!"
        )

        private val ALL_CLEAR_MESSAGES = listOf(
            "Smooth sailing! Drive safe.",
            "All clear. You're doing great!",
            "Cruising along. Stay sharp!",
            "Road warrior mode: ON",
            "Eyes on the road. Looking good!"
        )

        // Japanese AI status messages
        private val PHONE_MESSAGES_JA = listOf(
            "スマホを検出！運転に集中してください。",
            "スマホは置いて、前を見て！",
            "メッセージは後で。安全が最優先です。"
        )
        private val EYES_MESSAGES_JA = listOf(
            "目を覚まして！居眠り危険です。",
            "まばたきはOK。居眠りはNG！",
            "目を開けて！前方注意です。"
        )
        private val DISTRACTED_MESSAGES_JA = listOf(
            "前を向いてください！",
            "よそ見注意！前方を確認して。",
            "集中！景色は後で楽しみましょう。"
        )
        private val YAWNING_MESSAGES_JA = listOf(
            "大あくび！休憩しませんか？",
            "眠そうですね。休憩をおすすめします。",
            "あくび検出！ストレッチ休憩は？"
        )
        private val EATING_MESSAGES_JA = listOf(
            "運転中の飲食は危険です！",
            "食事は停車してから！",
            "ハンドルをしっかり握って！"
        )
        private val POSTURE_MESSAGES_JA = listOf(
            "姿勢を正してください！",
            "姿勢チェック！背筋を伸ばして。",
            "正しい姿勢で安全運転を。"
        )
        private val CHILD_SLOUCH_MESSAGES_JA = listOf(
            "お子様の姿勢が悪いです！",
            "後部座席のお子様を確認して！",
            "お子様の姿勢チェック！"
        )
        private val ALL_CLEAR_MESSAGES_JA = listOf(
            "順調です！安全運転を。",
            "問題なし。素晴らしい運転です！",
            "快適走行中。油断せずに。",
            "安全運転モード：ON",
            "前方注視。いい調子です！"
        )

        // Detection labels with dot colors
        private val DETECTION_LABELS_EN = mapOf(
            "driverUsingPhone" to "Phone Detected",
            "driverEyesClosed" to "Eyes Closed",
            "driverYawning" to "Yawning",
            "driverDistracted" to "Distracted",
            "driverEatingDrinking" to "Eating / Drinking",
            "dangerousPosture" to "Dangerous Posture",
            "childSlouching" to "Child Slouching"
        )
        private val DETECTION_LABELS_JA = mapOf(
            "driverUsingPhone" to "スマホ検出",
            "driverEyesClosed" to "目を閉じている",
            "driverYawning" to "あくび",
            "driverDistracted" to "よそ見",
            "driverEatingDrinking" to "飲食中",
            "dangerousPosture" to "危険な姿勢",
            "childSlouching" to "子供の姿勢不良"
        )
        private val DETECTION_DANGER_FIELDS = setOf("driverUsingPhone", "driverEyesClosed")

        private val TICKER_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    // --- Palette colors (resolved once) ---
    private var colorSafe = 0
    private var colorCaution = 0
    private var colorDanger = 0
    private var colorAccent = 0
    private var colorGold = 0
    private var colorTextPrimary = 0
    private var colorTextSecondary = 0
    private var colorTextMuted = 0
    private var colorSurface = 0
    private var colorSurfaceElevated = 0

    // --- UI references ---
    private lateinit var toggleButton: Button
    private lateinit var registerButton: Button
    private lateinit var previewToggle: Button
    private lateinit var audioToggle: Button
    private lateinit var langToggle: Button
    private lateinit var seatToggle: Button
    private lateinit var cameraStatusText: TextView
    private lateinit var cameraStatusDot: View
    private lateinit var driverNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var idleOverlay: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var riskBanner: TextView
    private lateinit var earText: TextView
    private lateinit var marText: TextView
    private lateinit var yawText: TextView
    private lateinit var pitchText: TextView
    private lateinit var passengerText: TextView
    private lateinit var distractionText: TextView
    private lateinit var detectionsContainer: LinearLayout
    private lateinit var aiStatusText: TextView
    private lateinit var scoreArc: ScoreArcView
    private lateinit var scoreContainer: FrameLayout
    private lateinit var streakText: TextView
    private lateinit var bestStreakText: TextView
    private lateinit var sessionTimeText: TextView
    private lateinit var scorePanel: LinearLayout
    private lateinit var leftDivider: View
    private lateinit var tickerContainer: LinearLayout
    private lateinit var tickerLine1: TextView
    private lateinit var tickerLine2: TextView
    private lateinit var tickerLine3: TextView

    private var isRunning = false
    private var isSettingUp = false

    // --- Platform & setup ---
    private lateinit var platformProfile: PlatformProfile
    private var deviceSetup: DeviceSetup? = null

    // --- Score & streak state ---
    private var drivingScore = 100f
    private var framesSinceStart = 0
    private var allClearIndex = 0
    private var sessionStartMs = 0L
    private var lastDetectionMs = 0L
    private var bestStreakMs = 0L
    private val announcedMilestones = mutableSetOf<Long>()

    // --- Session stats ---
    private var totalFrames = 0
    private val detectionCounts = mutableMapOf<String, Int>()
    private var scoreSum = 0.0

    // --- Ticker ---
    private val tickerEvents = mutableListOf<String>()
    private var lastTickerDetections = ""

    // --- Animation state ---
    private var currentRiskColor = 0
    private var riskAnimator: ValueAnimator? = null
    private var currentDetections = setOf<String>()

    // --- Dedup: skip re-processing the same frame ---
    private var lastFrameTimestamp = ""

    private val handler = Handler(Looper.getMainLooper())
    private val previewPoller = object : Runnable {
        override fun run() {
            try {
                // 1. Dashboard updates from result-only channel (fast, independent of bitmap)
                val result = FrameHolder.getLatestResult()
                if (result != null) {
                    val ts = result.timestamp
                    if (ts != lastFrameTimestamp) {
                        lastFrameTimestamp = ts
                        updateDashboard(result)
                        updateScore(result)
                        updateStreak(result)
                        updateAiStatus(result)
                        updateTicker(result)
                        totalFrames++
                        scoreSum += drivingScore
                    }
                    updateSessionTime()
                }

                // 2. Camera preview from bitmap channel (only when preview enabled)
                if (Config.ENABLE_PREVIEW) {
                    val frameData = FrameHolder.getLatest()
                    if (frameData != null && !frameData.bitmap.isRecycled) {
                        previewImage.setImageBitmap(frameData.bitmap)
                    }
                }

                // 3. Update camera status indicator
                updateCameraStatus()
            } catch (e: Exception) {
                Log.w(TAG, "Preview update failed", e)
            }
            handler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Resolve palette colors once
        colorSafe = ContextCompat.getColor(this, R.color.safe)
        colorCaution = ContextCompat.getColor(this, R.color.caution)
        colorDanger = ContextCompat.getColor(this, R.color.danger)
        colorAccent = ContextCompat.getColor(this, R.color.accent)
        colorGold = ContextCompat.getColor(this, R.color.gold)
        colorTextPrimary = ContextCompat.getColor(this, R.color.text_primary)
        colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        colorTextMuted = ContextCompat.getColor(this, R.color.text_muted)
        colorSurface = ContextCompat.getColor(this, R.color.surface)
        colorSurfaceElevated = ContextCompat.getColor(this, R.color.surface_elevated)

        // Detect platform once
        platformProfile = PlatformProfile.detect()

        // Bind views
        toggleButton = findViewById(R.id.toggleButton)
        registerButton = findViewById(R.id.registerButton)
        previewToggle = findViewById(R.id.previewToggle)
        audioToggle = findViewById(R.id.audioToggle)
        langToggle = findViewById(R.id.langToggle)
        seatToggle = findViewById(R.id.seatToggle)
        cameraStatusText = findViewById(R.id.cameraStatusText)
        cameraStatusDot = findViewById(R.id.cameraStatusDot)
        driverNameText = findViewById(R.id.driverNameText)
        statusText = findViewById(R.id.statusText)
        previewImage = findViewById(R.id.previewImage)
        idleOverlay = findViewById(R.id.idleOverlay)
        rightPanel = findViewById(R.id.rightPanel)
        riskBanner = findViewById(R.id.riskBanner)
        earText = findViewById(R.id.earText)
        marText = findViewById(R.id.marText)
        yawText = findViewById(R.id.yawText)
        pitchText = findViewById(R.id.pitchText)
        passengerText = findViewById(R.id.passengerText)
        distractionText = findViewById(R.id.distractionText)
        detectionsContainer = findViewById(R.id.detectionsContainer)
        aiStatusText = findViewById(R.id.aiStatusText)
        scoreArc = findViewById(R.id.scoreArc)
        scoreContainer = findViewById(R.id.scoreContainer)
        streakText = findViewById(R.id.streakText)
        bestStreakText = findViewById(R.id.bestStreakText)
        sessionTimeText = findViewById(R.id.sessionTimeText)
        scorePanel = findViewById(R.id.scorePanel)
        leftDivider = findViewById(R.id.leftDivider)
        tickerContainer = findViewById(R.id.tickerContainer)
        tickerLine1 = findViewById(R.id.tickerLine1)
        tickerLine2 = findViewById(R.id.tickerLine2)
        tickerLine3 = findViewById(R.id.tickerLine3)

        currentRiskColor = colorSafe

        // Restore toggle states
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Config.ENABLE_PREVIEW = prefs.getBoolean(PREF_PREVIEW_ENABLED, false)
        Config.ENABLE_AUDIO_ALERTS = prefs.getBoolean(PREF_AUDIO_ENABLED, true)
        Config.LANGUAGE = prefs.getString(PREF_LANGUAGE, "en") ?: "en"
        Config.DRIVER_SEAT_SIDE = prefs.getString(PREF_SEAT_SIDE, "left") ?: "left"
        Config.WIFI_CAMERA_URL = prefs.getString(PREF_WIFI_URL, "") ?: ""
        updatePreviewToggleUI()
        updateAudioToggleUI()
        updateLangToggleUI()
        updateSeatToggleUI()

        toggleButton.setOnClickListener {
            if (isSettingUp) return@setOnClickListener
            if (isRunning) {
                stopMonitoring()
            } else {
                onStartButtonTap()
            }
        }

        registerButton.setOnClickListener {
            try {
                val intent = Intent(this, FaceRegistrationActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch FaceRegistrationActivity", e)
            }
        }

        previewToggle.setOnClickListener {
            Config.ENABLE_PREVIEW = !Config.ENABLE_PREVIEW
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PREVIEW_ENABLED, Config.ENABLE_PREVIEW)
                .apply()
            updatePreviewToggleUI()
            if (!Config.ENABLE_PREVIEW) {
                previewImage.setImageBitmap(null)
            }
            Log.i(TAG, "Preview toggled: ${Config.ENABLE_PREVIEW}")
        }

        audioToggle.setOnClickListener {
            Config.ENABLE_AUDIO_ALERTS = !Config.ENABLE_AUDIO_ALERTS
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUDIO_ENABLED, Config.ENABLE_AUDIO_ALERTS)
                .apply()
            updateAudioToggleUI()
            Log.i(TAG, "Audio alerts toggled: ${Config.ENABLE_AUDIO_ALERTS}")
        }

        langToggle.setOnClickListener {
            Config.LANGUAGE = if (Config.LANGUAGE == "en") "ja" else "en"
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LANGUAGE, Config.LANGUAGE)
                .apply()
            updateLangToggleUI()
            Log.i(TAG, "Language toggled: ${Config.LANGUAGE}")
        }

        seatToggle.setOnClickListener {
            Config.DRIVER_SEAT_SIDE = if (Config.DRIVER_SEAT_SIDE == "left") "right" else "left"
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_SEAT_SIDE, Config.DRIVER_SEAT_SIDE)
                .apply()
            updateSeatToggleUI()
            Log.i(TAG, "Driver seat side: ${Config.DRIVER_SEAT_SIDE}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRunning) {
            handler.post(previewPoller)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(previewPoller)
    }

    override fun onDestroy() {
        riskAnimator?.cancel()
        deviceSetup?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Preview Toggle
    // ---------------------------------------------------------------------

    private fun updatePreviewToggleUI() {
        if (Config.ENABLE_PREVIEW) {
            previewToggle.text = "Preview ON"
            previewToggle.setTextColor(colorTextPrimary)
        } else {
            previewToggle.text = "Preview"
            previewToggle.setTextColor(colorTextSecondary)
        }
    }

    private fun updateAudioToggleUI() {
        if (Config.ENABLE_AUDIO_ALERTS) {
            audioToggle.text = "Audio ON"
            audioToggle.setTextColor(colorTextPrimary)
        } else {
            audioToggle.text = "Audio OFF"
            audioToggle.setTextColor(colorTextMuted)
        }
    }

    private fun updateLangToggleUI() {
        if (Config.LANGUAGE == "ja") {
            langToggle.text = "JA"
            langToggle.setTextColor(colorTextPrimary)
        } else {
            langToggle.text = "EN"
            langToggle.setTextColor(colorTextSecondary)
        }
    }

    private fun updateSeatToggleUI() {
        if (Config.DRIVER_SEAT_SIDE == "right") {
            seatToggle.text = "Seat: Right"
            seatToggle.setTextColor(colorTextPrimary)
        } else {
            seatToggle.text = "Seat: Left"
            seatToggle.setTextColor(colorTextSecondary)
        }
    }

    // ---------------------------------------------------------------------
    // Camera Status Indicator (dot + text)
    // ---------------------------------------------------------------------

    private fun setCameraStatusDotColor(color: Int) {
        val bg = cameraStatusDot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            cameraStatusDot.setBackgroundColor(color)
        }
    }

    private fun updateCameraStatus() {
        val status = FrameHolder.getCameraStatus()
        when (status) {
            FrameHolder.CameraStatus.NOT_CONNECTED -> {
                cameraStatusText.text = "No camera"
                setCameraStatusDotColor(colorDanger)
            }
            FrameHolder.CameraStatus.CONNECTING -> {
                cameraStatusText.text = "Connecting..."
                setCameraStatusDotColor(colorCaution)
            }
            FrameHolder.CameraStatus.READY -> {
                cameraStatusText.text = "Ready"
                setCameraStatusDotColor(colorSafe)
            }
            FrameHolder.CameraStatus.ACTIVE -> {
                cameraStatusText.text = "Active"
                setCameraStatusDotColor(colorSafe)
            }
            FrameHolder.CameraStatus.LOST -> {
                cameraStatusText.text = "Lost"
                setCameraStatusDotColor(colorDanger)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Setup Flow
    // ---------------------------------------------------------------------

    private fun onStartButtonTap() {
        if (platformProfile.isAutomotiveBsp) {
            // On automotive BSP: check if camera is already available first
            // to skip the full setup flow (ODK/chmod/grant) when unnecessary
            val setup = DeviceSetup()
            if (setup.isCameraAvailable() && hasRequiredPermissions()) {
                Log.i(TAG, "Camera + permissions already available, skipping setup")
                FrameHolder.postCameraStatus(FrameHolder.CameraStatus.READY)
                startMonitoring()
            } else {
                startAutomotiveSetup()
            }
        } else {
            checkPermissionsAndStart()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return cameraGranted && notifGranted
    }

    private fun startAutomotiveSetup() {
        val setup = DeviceSetup()
        deviceSetup = setup

        if (setup.isCameraAvailable()) {
            Log.i(TAG, "Camera already available, skipping full setup")
            FrameHolder.postCameraStatus(FrameHolder.CameraStatus.READY)
            checkPermissionsAndStart()
            return
        }

        isSettingUp = true
        toggleButton.isEnabled = false
        toggleButton.text = "Setting up..."

        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.CONNECTING)

        setup.startSetup(packageName, object : DeviceSetup.Callback {
            override fun onStageChanged(stage: DeviceSetup.Stage, message: String) {
                runOnUiThread {
                    statusText.text = message
                    Log.i(TAG, "Setup stage: $stage - $message")
                }
            }

            override fun onSetupComplete() {
                runOnUiThread {
                    isSettingUp = false
                    toggleButton.isEnabled = true
                    FrameHolder.postCameraStatus(FrameHolder.CameraStatus.READY)
                    checkPermissionsAndStart()
                }
            }

            override fun onSetupFailed(message: String) {
                runOnUiThread {
                    isSettingUp = false
                    toggleButton.isEnabled = true
                    toggleButton.text = getString(R.string.start_service)
                    statusText.text = "Setup failed"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    Log.w(TAG, "Setup failed: $message, user can still start manually")
                }
            }
        })
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startMonitoring()
        } else {
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val cameraGranted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                startMonitoring()
            } else {
                statusText.text = "Camera permission denied"
                Log.w(TAG, "Camera permission denied")
            }
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, InCabinService::class.java).apply {
            action = InCabinService.ACTION_START
        }
        startForegroundService(intent)
        isRunning = true
        toggleButton.text = getString(R.string.stop_service)
        statusText.text = "Monitoring active"

        // Reset all state
        drivingScore = 100f
        framesSinceStart = 0
        allClearIndex = 0
        sessionStartMs = System.currentTimeMillis()
        lastDetectionMs = System.currentTimeMillis()
        bestStreakMs = 0L
        announcedMilestones.clear()
        totalFrames = 0
        detectionCounts.clear()
        scoreSum = 0.0
        tickerEvents.clear()
        lastTickerDetections = ""
        lastFrameTimestamp = ""
        currentDetections = emptySet()

        scoreArc.score = 100
        streakText.text = "Streak: 0:00"
        bestStreakText.text = "Best: 0:00"
        sessionTimeText.text = "Session: 0:00"

        // Show monitoring UI with fade-in
        idleOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            idleOverlay.visibility = View.GONE
        }.start()

        scoreContainer.visibility = View.VISIBLE
        scorePanel.visibility = View.VISIBLE
        leftDivider.visibility = View.VISIBLE
        aiStatusText.visibility = View.VISIBLE
        aiStatusText.text = "Warming up the AI brain..."
        aiStatusText.setTextColor(colorAccent)

        // Fade in right panel
        rightPanel.alpha = 0f
        rightPanel.visibility = View.VISIBLE
        rightPanel.animate().alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()

        tickerContainer.visibility = View.VISIBLE

        // Reset risk banner
        currentRiskColor = colorSafe
        setRiskPillColor(colorSafe)
        riskBanner.text = "LOW"
        riskBanner.setTextColor(Color.BLACK)

        handler.post(previewPoller)
        Log.i(TAG, "Monitoring started")
    }

    private fun stopMonitoring() {
        val intent = Intent(this, InCabinService::class.java).apply {
            action = InCabinService.ACTION_STOP
        }
        startService(intent)

        // Show session summary before resetting
        showSessionSummary()

        isRunning = false
        toggleButton.text = getString(R.string.start_service)
        statusText.text = "Tap Start to begin monitoring"

        // Hide monitoring UI with fade
        rightPanel.animate().alpha(0f).setDuration(200).withEndAction {
            rightPanel.visibility = View.GONE
        }.start()

        scoreContainer.visibility = View.GONE
        scorePanel.visibility = View.GONE
        leftDivider.visibility = View.GONE
        aiStatusText.visibility = View.GONE
        tickerContainer.visibility = View.GONE

        // Show idle overlay
        idleOverlay.visibility = View.VISIBLE
        idleOverlay.alpha = 0f
        idleOverlay.animate().alpha(1f).setDuration(300).start()

        handler.removeCallbacks(previewPoller)
        previewImage.setImageBitmap(null)
        detectionsContainer.removeAllViews()
        Log.i(TAG, "Monitoring stopped")
    }

    // ---------------------------------------------------------------------
    // Dashboard
    // ---------------------------------------------------------------------

    private fun updateDashboard(result: OutputResult) {
        // --- Risk pill with animated color transition ---
        val targetColor: Int
        val riskText: String
        val riskTextColor: Int

        if (result.passengerCount == 0) {
            targetColor = colorSurfaceElevated
            riskText = "NO OCCUPANTS"
            riskTextColor = colorTextSecondary
        } else when (result.riskLevel) {
            "high" -> {
                targetColor = colorDanger
                riskText = "HIGH"
                riskTextColor = Color.WHITE
            }
            "medium" -> {
                targetColor = colorCaution
                riskText = "MEDIUM"
                riskTextColor = Color.BLACK
            }
            else -> {
                targetColor = colorSafe
                riskText = "LOW"
                riskTextColor = Color.BLACK
            }
        }

        riskBanner.text = riskText
        riskBanner.setTextColor(riskTextColor)
        animateRiskColor(targetColor)

        // Driver name
        val name = result.driverName
        if (name != null) {
            driverNameText.text = "Driver: $name"
            if (driverNameText.visibility != View.VISIBLE) {
                driverNameText.alpha = 0f
                driverNameText.visibility = View.VISIBLE
                driverNameText.animate().alpha(1f).setDuration(200).start()
            }
        } else {
            driverNameText.visibility = View.GONE
        }

        // Engineering metrics (hidden, but kept updated)
        earText.text = "EAR: ${result.earValue?.let { "%.3f".format(it) } ?: "--"}"
        marText.text = "MAR: ${result.marValue?.let { "%.3f".format(it) } ?: "--"}"
        yawText.text = "Yaw: ${result.headYaw?.let { "%.1f".format(it) } ?: "--"}"
        pitchText.text = "Pitch: ${result.headPitch?.let { "%.1f".format(it) } ?: "--"}"

        // Info
        val pCount = result.passengerCount
        passengerText.text = "$pCount passenger${if (pCount != 1) "s" else ""}"
        val distS = result.distractionDurationS
        if (distS > 0) {
            distractionText.text = "Distraction: ${distS}s"
            distractionText.setTextColor(if (distS >= 10) colorDanger else colorCaution)
        } else {
            distractionText.text = "Distraction: 0s"
            distractionText.setTextColor(colorTextSecondary)
        }

        // --- Detection labels with animated appear/disappear ---
        updateDetectionLabels(result)
    }

    // --- Risk pill color animation ---
    private fun animateRiskColor(targetColor: Int) {
        if (targetColor == currentRiskColor) return
        riskAnimator?.cancel()
        riskAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentRiskColor, targetColor).apply {
            duration = 500
            addUpdateListener { setRiskPillColor(it.animatedValue as Int) }
            start()
        }
        currentRiskColor = targetColor
    }

    private fun setRiskPillColor(color: Int) {
        val bg = riskBanner.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        }
    }

    // --- Detection labels: vertical stack with dots ---
    private fun updateDetectionLabels(result: OutputResult) {
        val labelMap = if (Config.LANGUAGE == "ja") DETECTION_LABELS_JA else DETECTION_LABELS_EN

        // Build active set using field keys (language-independent)
        val activeKeys = mutableSetOf<String>()
        if (result.driverUsingPhone) activeKeys.add("driverUsingPhone")
        if (result.driverEyesClosed) activeKeys.add("driverEyesClosed")
        if (result.driverYawning) activeKeys.add("driverYawning")
        if (result.driverDistracted) activeKeys.add("driverDistracted")
        if (result.driverEatingDrinking) activeKeys.add("driverEatingDrinking")
        if (result.dangerousPosture) activeKeys.add("dangerousPosture")
        if (result.childSlouching) activeKeys.add("childSlouching")

        // Skip update if unchanged
        if (activeKeys == currentDetections) return
        val removed = currentDetections - activeKeys
        val added = activeKeys - currentDetections
        currentDetections = activeKeys

        // Remove labels that are no longer active
        for (key in removed) {
            val tag = "det_$key"
            val view = detectionsContainer.findViewWithTag<View>(tag)
            if (view != null) {
                view.animate().alpha(0f).setDuration(300).withEndAction {
                    try {
                        if (view.parent == detectionsContainer) {
                            detectionsContainer.removeView(view)
                        }
                        showAllClearIfEmpty()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove detection label", e)
                    }
                }.start()
            }
        }

        // Remove "All Clear" if we're adding detections
        if (added.isNotEmpty()) {
            val allClearView = detectionsContainer.findViewWithTag<View>("det_allclear")
            if (allClearView != null) {
                detectionsContainer.removeView(allClearView)
            }
        }

        // Add new detection labels
        for (key in added) {
            val isDanger = key in DETECTION_DANGER_FIELDS
            val dotColor = if (isDanger) colorDanger else colorCaution
            val displayLabel = labelMap[key] ?: key

            val tv = TextView(this).apply {
                tag = "det_$key"
                text = "\u25CF  $displayLabel"
                textSize = 15f
                setTextColor(dotColor)
                setPadding(0, dpToPx(2), 0, dpToPx(2))
                alpha = 0f
            }
            detectionsContainer.addView(tv)
            tv.animate().alpha(1f).setDuration(200).start()
        }

        // Show "All Clear" if nothing active
        if (activeKeys.isEmpty()) {
            showAllClearIfEmpty()
        }
    }

    private fun showAllClearIfEmpty() {
        if (currentDetections.isNotEmpty()) return
        if (detectionsContainer.findViewWithTag<View>("det_allclear") != null) return

        // Check no animated views still present
        if (detectionsContainer.childCount > 0) return

        val tv = TextView(this).apply {
            tag = "det_allclear"
            text = if (Config.LANGUAGE == "ja") "安全" else "All Clear"
            textSize = 15f
            setTextColor(colorSafe)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            alpha = 0f
        }
        detectionsContainer.addView(tv)
        tv.animate().alpha(1f).setDuration(200).start()
    }

    // ---------------------------------------------------------------------
    // Safe Driving Score
    // ---------------------------------------------------------------------

    private fun updateScore(result: OutputResult) {
        val hasDetection = result.driverUsingPhone || result.driverEyesClosed ||
            result.driverDistracted || result.driverYawning ||
            result.driverEatingDrinking || result.dangerousPosture ||
            result.childSlouching

        if (hasDetection) {
            var penalty = 0f
            if (result.driverUsingPhone) penalty += SCORE_PENALTIES["phone"]!!
            if (result.driverEyesClosed) penalty += SCORE_PENALTIES["eyes"]!!
            if (result.driverDistracted) penalty += SCORE_PENALTIES["distracted"]!!
            if (result.driverYawning) penalty += SCORE_PENALTIES["yawning"]!!
            if (result.driverEatingDrinking) penalty += SCORE_PENALTIES["eating"]!!
            if (result.dangerousPosture) penalty += SCORE_PENALTIES["posture"]!!
            if (result.childSlouching) penalty += SCORE_PENALTIES["child_slouching"]!!
            drivingScore = (drivingScore - penalty).coerceAtLeast(0f)
        } else {
            drivingScore = (drivingScore + SCORE_RECOVERY).coerceAtMost(100f)
        }

        scoreArc.score = drivingScore.toInt()
    }

    // ---------------------------------------------------------------------
    // Distraction-Free Streak
    // ---------------------------------------------------------------------

    private fun updateStreak(result: OutputResult) {
        val now = System.currentTimeMillis()
        val hasDetection = result.driverUsingPhone || result.driverEyesClosed ||
            result.driverDistracted || result.driverYawning ||
            result.driverEatingDrinking || result.dangerousPosture ||
            result.childSlouching

        if (hasDetection) {
            if (result.driverUsingPhone) detectionCounts["Phone"] = (detectionCounts["Phone"] ?: 0) + 1
            if (result.driverEyesClosed) detectionCounts["Eyes Closed"] = (detectionCounts["Eyes Closed"] ?: 0) + 1
            if (result.driverYawning) detectionCounts["Yawning"] = (detectionCounts["Yawning"] ?: 0) + 1
            if (result.driverDistracted) detectionCounts["Distracted"] = (detectionCounts["Distracted"] ?: 0) + 1
            if (result.driverEatingDrinking) detectionCounts["Eating"] = (detectionCounts["Eating"] ?: 0) + 1
            if (result.dangerousPosture) detectionCounts["Posture"] = (detectionCounts["Posture"] ?: 0) + 1
            if (result.childSlouching) detectionCounts["Child Slouch"] = (detectionCounts["Child Slouch"] ?: 0) + 1

            val currentStreak = now - lastDetectionMs
            if (currentStreak > bestStreakMs) {
                bestStreakMs = currentStreak
            }
            lastDetectionMs = now
            announcedMilestones.clear()
        }

        val streakMs = now - lastDetectionMs
        streakText.text = "Streak: ${formatDuration(streakMs)}"
        streakText.setTextColor(if (streakMs > 60_000) colorSafe else colorTextSecondary)

        val displayBest = if (streakMs > bestStreakMs) streakMs else bestStreakMs
        bestStreakText.text = "Best: ${formatDuration(displayBest)}"
    }

    // ---------------------------------------------------------------------
    // AI Status with crossfade
    // ---------------------------------------------------------------------

    private fun updateAiStatus(result: OutputResult) {
        framesSinceStart++
        val isJa = Config.LANGUAGE == "ja"

        val message: String
        val color: Int

        // First few frames: initialization feedback
        if (framesSinceStart == 1) {
            message = if (isJa) "AIの準備が完了しました！" else "Smart eyes are ready!"
            color = colorAccent
        } else if (framesSinceStart == 2) {
            message = if (isJa) "検出開始！安全運転を。" else "Got you! Let's roll."
            color = colorSafe
        } else {
            // Check for streak milestones
            val streakMs = System.currentTimeMillis() - lastDetectionMs
            val milestones = if (isJa) STREAK_MILESTONES_JA else STREAK_MILESTONES_EN
            val milestone = milestones.firstOrNull { (thresholdMs, _) ->
                streakMs >= thresholdMs && thresholdMs !in announcedMilestones
            }

            if (milestone != null) {
                announcedMilestones.add(milestone.first)
                message = milestone.second
                color = colorGold
            } else when {
                result.driverUsingPhone -> { message = (if (isJa) PHONE_MESSAGES_JA else PHONE_MESSAGES).random(); color = colorDanger }
                result.driverEyesClosed -> { message = (if (isJa) EYES_MESSAGES_JA else EYES_MESSAGES).random(); color = colorDanger }
                result.driverDistracted -> { message = (if (isJa) DISTRACTED_MESSAGES_JA else DISTRACTED_MESSAGES).random(); color = colorCaution }
                result.driverYawning -> { message = (if (isJa) YAWNING_MESSAGES_JA else YAWNING_MESSAGES).random(); color = colorCaution }
                result.driverEatingDrinking -> { message = (if (isJa) EATING_MESSAGES_JA else EATING_MESSAGES).random(); color = colorCaution }
                result.dangerousPosture -> { message = (if (isJa) POSTURE_MESSAGES_JA else POSTURE_MESSAGES).random(); color = colorCaution }
                result.childSlouching -> { message = (if (isJa) CHILD_SLOUCH_MESSAGES_JA else CHILD_SLOUCH_MESSAGES).random(); color = colorCaution }
                result.distractionDurationS >= 20 -> { message = if (isJa) "20秒間！停車してください。" else "20 seconds! Please pull over."; color = colorDanger }
                result.distractionDurationS >= 10 -> { message = if (isJa) "10秒間！前を見て！" else "10 seconds! Seriously, eyes on road."; color = colorDanger }
                result.distractionDurationS >= 5 -> { message = if (isJa) "5秒間よそ見…集中して！" else "5 seconds distracted... focus up!"; color = colorCaution }
                result.riskLevel == "high" -> { message = if (isJa) "危険！今すぐ集中して！" else "Danger zone! Focus NOW."; color = colorDanger }
                result.riskLevel == "medium" -> { message = if (isJa) "注意！油断しないで。" else "Heads up! Stay alert."; color = colorCaution }
                else -> {
                    val clearMsgs = if (isJa) ALL_CLEAR_MESSAGES_JA else ALL_CLEAR_MESSAGES
                    message = clearMsgs[allClearIndex % clearMsgs.size]
                    allClearIndex++
                    color = colorSafe
                }
            }
        }

        // Crossfade: alpha out → set text → alpha in
        if (aiStatusText.text != message) {
            aiStatusText.animate().alpha(0f).setDuration(75).withEndAction {
                aiStatusText.text = message
                aiStatusText.setTextColor(color)
                aiStatusText.animate().alpha(1f).setDuration(150).start()
            }.start()
        }
    }

    // ---------------------------------------------------------------------
    // Detection History Ticker (last 3 events, static lines)
    // ---------------------------------------------------------------------

    private fun updateTicker(result: OutputResult) {
        val active = mutableListOf<String>()
        if (result.driverUsingPhone) active.add("Phone")
        if (result.driverEyesClosed) active.add("Eyes Closed")
        if (result.driverYawning) active.add("Yawning")
        if (result.driverDistracted) active.add("Distracted")
        if (result.driverEatingDrinking) active.add("Eating")
        if (result.dangerousPosture) active.add("Posture")
        if (result.childSlouching) active.add("Child Slouch")

        val currentLabel = if (active.isEmpty()) "All clear" else active.joinToString(", ")

        if (currentLabel != lastTickerDetections) {
            lastTickerDetections = currentLabel
            val timestamp = TICKER_TIME_FORMAT.format(Date())
            tickerEvents.add(0, "$timestamp  $currentLabel")
            if (tickerEvents.size > 20) tickerEvents.removeAt(tickerEvents.size - 1)

            // Update the 3 static lines
            tickerLine1.text = tickerEvents.getOrNull(0) ?: ""
            tickerLine2.text = tickerEvents.getOrNull(1) ?: ""
            tickerLine3.text = tickerEvents.getOrNull(2) ?: ""
        }
    }

    // ---------------------------------------------------------------------
    // Session time display
    // ---------------------------------------------------------------------

    private fun updateSessionTime() {
        val elapsed = System.currentTimeMillis() - sessionStartMs
        sessionTimeText.text = "Session: ${formatDuration(elapsed)}"
    }

    // ---------------------------------------------------------------------
    // Session Summary
    // ---------------------------------------------------------------------

    private fun showSessionSummary() {
        val sessionDurationMs = System.currentTimeMillis() - sessionStartMs
        val avgScore = if (totalFrames > 0) (scoreSum / totalFrames).toInt() else 100
        val finalBest = maxOf(bestStreakMs, System.currentTimeMillis() - lastDetectionMs)
        val isJa = Config.LANGUAGE == "ja"

        val sb = StringBuilder()
        sb.appendLine("${if (isJa) "走行時間" else "Duration"}: ${formatDuration(sessionDurationMs)}")
        sb.appendLine("${if (isJa) "注意スコア" else "Attention Score"}: $avgScore / 100")
        sb.appendLine("${if (isJa) "最終スコア" else "Final Score"}: ${drivingScore.toInt()} / 100")
        sb.appendLine("${if (isJa) "最長連続" else "Longest Streak"}: ${formatDuration(finalBest)}")
        sb.appendLine()

        if (detectionCounts.isEmpty()) {
            sb.appendLine(if (isJa) "検出ゼロ。完璧なセッション！" else "Zero detections. Perfect session!")
        } else {
            sb.appendLine(if (isJa) "検出:" else "Detections:")
            for ((name, count) in detectionCounts.entries.sortedByDescending { it.value }) {
                sb.appendLine("  $name: $count")
            }
        }

        sb.appendLine()
        sb.append(
            if (isJa) when {
                avgScore >= 90 -> "素晴らしい運転！安全運転を続けてください。"
                avgScore >= 70 -> "良いセッション。もう少し改善の余地があります！"
                avgScore >= 50 -> "まずまず。次回はもっと集中しましょう。"
                else -> "大変なセッション。休憩を検討してください。"
            } else when {
                avgScore >= 90 -> "Outstanding driving! Stay safe out there."
                avgScore >= 70 -> "Good session. A bit of room to improve!"
                avgScore >= 50 -> "Decent, but stay more focused next time."
                else -> "Tough session. Consider taking a break."
            }
        )

        try {
            AlertDialog.Builder(this)
                .setTitle(if (isJa) "セッション概要" else "Session Summary")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show session summary", e)
        }
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
