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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
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

        // Tips rotation interval
        private const val TIPS_ROTATION_MS = 10_000L

        // Wellness tips — English
        private val TIPS_GENERAL_EN = listOf(
            "Stay hydrated on long drives.",
            "Adjust your mirrors before you go.",
            "Take a break every 2 hours.",
            "Keep both hands on the wheel.",
            "Good posture reduces fatigue.",
            "Stretch at the next rest area.",
            "Turn on headlights early in rain or fog.",
            "Enjoy a refreshment stop — you've earned it.",
            "Chewing gum helps maintain alertness.",
            "Courteous driving keeps everyone safe.",
            "Take a deep breath before starting your drive.",
            "Slow down early for toll plazas.",
            "A little fresh air through the window keeps you sharp."
        )
        // Wellness tips — Japanese (with cultural nuances)
        private val TIPS_GENERAL_JA = listOf(
            "長距離運転は水分補給を忘れずに。",
            "出発前にミラーを調整しましょう。",
            "2時間ごとに休憩を取りましょう。",
            "両手でハンドルを握りましょう。",
            "正しい姿勢は疲労を軽減します。",
            "高速のSA・PAで軽いストレッチを。",
            "梅雨の時期はヘッドライト早めに点灯。",
            "道の駅で地元の名物を楽しもう。",
            "居眠り防止にガムや昆布が効果的。",
            "思いやり運転で事故ゼロを目指そう。",
            "運転前にお辞儀のように深呼吸を。",
            "ETCレーンは余裕を持って減速を。",
            "窓を少し開けて新鮮な空気で目を覚まそう。"
        )

        // 5-tap gesture
        private const val TAP_WINDOW_MS = 3000L
        private const val REQUIRED_TAPS = 5

        // Score tuning
        private const val SCORE_RECOVERY = 0.5f
        private val SCORE_PENALTIES = mapOf(
            "phone" to 2.0f,
            "eyes" to 2.0f,
            "hands_off" to 2.0f,
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

        // ASIMO mascot pose priority (highest first)
        private val ASIMO_POSE_PRIORITY = listOf(
            "driverUsingPhone" to R.drawable.asimo_phone,
            "driverEyesClosed" to R.drawable.asimo_eyes_closed,
            "handsOffWheel" to R.drawable.asimo_distracted,
            "driverDistracted" to R.drawable.asimo_distracted,
            "driverYawning" to R.drawable.asimo_yawning,
            "driverEatingDrinking" to R.drawable.asimo_eating,
            "dangerousPosture" to R.drawable.asimo_posture,
            "childSlouching" to R.drawable.asimo_child_slouch
        )
        private const val ASIMO_CROSSFADE_MS = 300L

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
        private val HANDS_OFF_MESSAGES = listOf(
            "Hands on the wheel, please!",
            "Grip the steering wheel!",
            "Both hands off? Not safe!"
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
        private val HANDS_OFF_MESSAGES_JA = listOf(
            "ハンドルを握ってください！",
            "ハンドルから手を離さないで！",
            "両手でハンドルを！"
        )
        private val ALL_CLEAR_MESSAGES_JA = listOf(
            "順調です！安全運転を。",
            "問題なし。素晴らしい運転です！",
            "快適走行中。油断せずに。",
            "安全運転モード：ON",
            "前方注視。いい調子です！"
        )

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
    private var colorVlmPurple = 0

    // --- UI references ---
    private lateinit var rootLayout: FrameLayout
    private lateinit var inferenceBadge: TextView
    private lateinit var toggleButton: TextView
    private lateinit var registerButton: Button
    private lateinit var previewToggle: Button
    private lateinit var audioToggle: Button
    private lateinit var wifiCamButton: Button
    private lateinit var inferenceModeLocal: TextView
    private lateinit var inferenceModeRemote: TextView
    private lateinit var vlmServerButton: Button
    private lateinit var cameraStatusText: TextView
    private lateinit var cameraStatusDot: View
    private lateinit var driverNameText: TextView
    private lateinit var driverPositionText: TextView
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
    private lateinit var passengerPostureContainer: LinearLayout
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

    // --- Bottom widget views ---
    private lateinit var statsWidget: LinearLayout
    private lateinit var tipsWidget: LinearLayout
    private lateinit var statPhone: TextView
    private lateinit var statEyes: TextView
    private lateinit var statYawn: TextView
    private lateinit var statDistracted: TextView
    private lateinit var statEating: TextView
    private lateinit var statPosture: TextView
    private lateinit var statChild: TextView
    private lateinit var statFooter: TextView
    private lateinit var tipsText: TextView

    // --- Settings panel ---
    private lateinit var widgetOffBtn: TextView
    private lateinit var widgetStatsBtn: TextView
    private lateinit var widgetTipsBtn: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsScrim: View
    private lateinit var settingsCloseBtn: TextView
    private lateinit var seatLeftBtn: TextView
    private lateinit var seatRightBtn: TextView
    private lateinit var langEnBtn: TextView
    private lateinit var langJaBtn: TextView
    private lateinit var paxMinimalBtn: TextView
    private lateinit var paxDetailedBtn: TextView
    private lateinit var asimoSizeSmallBtn: TextView
    private lateinit var asimoSizeMediumBtn: TextView
    private lateinit var asimoSizeLargeBtn: TextView
    private var settingsVisible = false

    // --- 5-tap gesture ---
    private var tapCount = 0
    private var firstTapTime = 0L

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

    // --- ASIMO mascot / companion hub ---
    private lateinit var asimoContainer: LinearLayout
    private lateinit var asimoMascot: ImageView
    private lateinit var asimoBrandingText: TextView
    private lateinit var asimoBubbleFrame: FrameLayout
    private lateinit var asimoBubbleText: TextView
    private lateinit var asimoBubbleTail: ImageView
    private lateinit var asimoGlowFrame: FrameLayout
    private lateinit var asimoGlowView: AsimoBgGlowView
    private lateinit var asimoDetectionLabel: TextView
    private var currentAsimoPose: Int = 0
    private var asimoAnimating: Boolean = false
    private var currentAsimoDetectionKey: String = ""

    // --- Tips rotation ---
    private var tipsIndex = 0
    private val tipsRotationRunnable = object : Runnable {
        override fun run() {
            updateTipsContent()
            handler.postDelayed(this, TIPS_ROTATION_MS)
        }
    }

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
                        updateBottomWidget(result)
                        updatePassengerPostures()
                        updateAsimoPose(result)
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
        colorVlmPurple = ContextCompat.getColor(this, R.color.vlm_purple)

        // Detect platform once
        platformProfile = PlatformProfile.detect()

        // Bind views — main layout
        rootLayout = findViewById(R.id.rootLayout)
        toggleButton = findViewById(R.id.toggleButton)
        cameraStatusText = findViewById(R.id.cameraStatusText)
        cameraStatusDot = findViewById(R.id.cameraStatusDot)
        inferenceBadge = findViewById(R.id.inferenceBadge)
        driverNameText = findViewById(R.id.driverNameText)
        driverPositionText = findViewById(R.id.driverPositionText)
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
        passengerPostureContainer = findViewById(R.id.passengerPostureContainer)
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

        // Bind views — ASIMO companion hub
        asimoContainer = findViewById(R.id.asimoContainer)
        asimoMascot = findViewById(R.id.asimoMascot)
        asimoBrandingText = findViewById(R.id.asimoBrandingText)
        asimoBubbleFrame = findViewById(R.id.asimoBubbleFrame)
        asimoBubbleText = findViewById(R.id.asimoBubbleText)
        asimoBubbleTail = findViewById(R.id.asimoBubbleTail)
        asimoGlowFrame = findViewById(R.id.asimoGlowFrame)
        asimoGlowView = findViewById(R.id.asimoGlowView)
        asimoDetectionLabel = findViewById(R.id.asimoDetectionLabel)

        // Bind views — bottom widgets
        statsWidget = findViewById(R.id.statsWidget)
        tipsWidget = findViewById(R.id.tipsWidget)
        statPhone = findViewById(R.id.statPhone)
        statEyes = findViewById(R.id.statEyes)
        statYawn = findViewById(R.id.statYawn)
        statDistracted = findViewById(R.id.statDistracted)
        statEating = findViewById(R.id.statEating)
        statPosture = findViewById(R.id.statPosture)
        statChild = findViewById(R.id.statChild)
        statFooter = findViewById(R.id.statFooter)
        tipsText = findViewById(R.id.tipsText)

        // Bind views — settings panel
        widgetOffBtn = findViewById(R.id.widgetOffBtn)
        widgetStatsBtn = findViewById(R.id.widgetStatsBtn)
        widgetTipsBtn = findViewById(R.id.widgetTipsBtn)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsScrim = findViewById(R.id.settingsScrim)
        settingsCloseBtn = findViewById(R.id.settingsCloseBtn)
        seatLeftBtn = findViewById(R.id.seatLeftBtn)
        seatRightBtn = findViewById(R.id.seatRightBtn)
        langEnBtn = findViewById(R.id.langEnBtn)
        langJaBtn = findViewById(R.id.langJaBtn)
        paxMinimalBtn = findViewById(R.id.paxMinimalBtn)
        paxDetailedBtn = findViewById(R.id.paxDetailedBtn)
        registerButton = findViewById(R.id.registerButton)
        previewToggle = findViewById(R.id.previewToggle)
        audioToggle = findViewById(R.id.audioToggle)
        wifiCamButton = findViewById(R.id.wifiCamButton)
        inferenceModeLocal = findViewById(R.id.inferenceModeLocal)
        inferenceModeRemote = findViewById(R.id.inferenceModeRemote)
        vlmServerButton = findViewById(R.id.vlmServerButton)
        asimoSizeSmallBtn = findViewById(R.id.asimoSizeSmallBtn)
        asimoSizeMediumBtn = findViewById(R.id.asimoSizeMediumBtn)
        asimoSizeLargeBtn = findViewById(R.id.asimoSizeLargeBtn)

        currentRiskColor = colorSafe

        // Restore toggle states
        ConfigPrefs.loadIntoConfig(this)
        val prefs = getSharedPreferences(ConfigPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        updatePreviewToggleUI()
        updateAudioToggleUI()
        updateSeatSegmentUI()
        updateLangSegmentUI()
        updateWifiCamButtonUI()
        updateInferenceModeUI()
        updateVlmServerButtonUI()
        updatePaxDetailSegmentUI()
        updateAsimoSizeSegmentUI()
        updateBottomWidgetSegmentUI()

        // --- Main controls ---
        toggleButton.setOnClickListener {
            if (isSettingUp) return@setOnClickListener
            if (isRunning) {
                stopMonitoring()
            } else {
                onStartButtonTap()
            }
        }

        // Camera status dot tap → show tooltip
        cameraStatusDot.setOnClickListener {
            Toast.makeText(this, cameraStatusText.text, Toast.LENGTH_SHORT).show()
        }

        // --- 5-tap gesture on root layout ---
        rootLayout.setOnClickListener {
            onRootTap()
        }

        // --- Settings panel controls ---
        settingsCloseBtn.setOnClickListener { hideSettingsPanel() }
        settingsScrim.setOnClickListener { hideSettingsPanel() }

        seatLeftBtn.setOnClickListener {
            Config.DRIVER_SEAT_SIDE = "left"
            prefs.edit().putString(ConfigPrefs.PREF_SEAT_SIDE, "left").apply()
            updateSeatSegmentUI()
            Log.i(TAG, "Driver seat side: left")
        }
        seatRightBtn.setOnClickListener {
            Config.DRIVER_SEAT_SIDE = "right"
            prefs.edit().putString(ConfigPrefs.PREF_SEAT_SIDE, "right").apply()
            updateSeatSegmentUI()
            Log.i(TAG, "Driver seat side: right")
        }

        langEnBtn.setOnClickListener {
            Config.LANGUAGE = "en"
            prefs.edit().putString(ConfigPrefs.PREF_LANGUAGE, "en").apply()
            updateLangSegmentUI()
            Log.i(TAG, "Language: en")
        }
        langJaBtn.setOnClickListener {
            Config.LANGUAGE = "ja"
            prefs.edit().putString(ConfigPrefs.PREF_LANGUAGE, "ja").apply()
            updateLangSegmentUI()
            Log.i(TAG, "Language: ja")
        }

        paxMinimalBtn.setOnClickListener {
            Config.PASSENGER_INFO_DETAIL = "minimal"
            prefs.edit().putString(ConfigPrefs.PREF_PASSENGER_DETAIL, "minimal").apply()
            updatePaxDetailSegmentUI()
            Log.i(TAG, "Passenger info: minimal")
        }
        paxDetailedBtn.setOnClickListener {
            Config.PASSENGER_INFO_DETAIL = "detailed"
            prefs.edit().putString(ConfigPrefs.PREF_PASSENGER_DETAIL, "detailed").apply()
            updatePaxDetailSegmentUI()
            Log.i(TAG, "Passenger info: detailed")
        }

        audioToggle.setOnClickListener {
            Config.ENABLE_AUDIO_ALERTS = !Config.ENABLE_AUDIO_ALERTS
            prefs.edit().putBoolean(ConfigPrefs.PREF_AUDIO_ENABLED, Config.ENABLE_AUDIO_ALERTS).apply()
            updateAudioToggleUI()
            Log.i(TAG, "Audio alerts toggled: ${Config.ENABLE_AUDIO_ALERTS}")
        }

        previewToggle.setOnClickListener {
            Config.ENABLE_PREVIEW = !Config.ENABLE_PREVIEW
            prefs.edit().putBoolean(ConfigPrefs.PREF_PREVIEW_ENABLED, Config.ENABLE_PREVIEW).apply()
            updatePreviewToggleUI()
            if (!Config.ENABLE_PREVIEW) {
                previewImage.setImageBitmap(null)
            }
            if (isRunning) {
                updateAsimoSize()
            }
            Log.i(TAG, "Preview toggled: ${Config.ENABLE_PREVIEW}")
        }

        asimoSizeSmallBtn.setOnClickListener {
            Config.ASIMO_SIZE = "s"
            prefs.edit().putString(ConfigPrefs.PREF_ASIMO_SIZE, "s").apply()
            updateAsimoSizeSegmentUI()
            if (isRunning) updateAsimoSize()
            Log.i(TAG, "ASIMO size: s")
        }
        asimoSizeMediumBtn.setOnClickListener {
            Config.ASIMO_SIZE = "m"
            prefs.edit().putString(ConfigPrefs.PREF_ASIMO_SIZE, "m").apply()
            updateAsimoSizeSegmentUI()
            if (isRunning) updateAsimoSize()
            Log.i(TAG, "ASIMO size: m")
        }
        asimoSizeLargeBtn.setOnClickListener {
            Config.ASIMO_SIZE = "l"
            prefs.edit().putString(ConfigPrefs.PREF_ASIMO_SIZE, "l").apply()
            updateAsimoSizeSegmentUI()
            if (isRunning) updateAsimoSize()
            Log.i(TAG, "ASIMO size: l")
        }

        widgetOffBtn.setOnClickListener {
            Config.BOTTOM_WIDGET = "none"
            prefs.edit().putString(ConfigPrefs.PREF_BOTTOM_WIDGET, "none").apply()
            updateBottomWidgetSegmentUI()
            updateBottomWidgetVisibility()
            Log.i(TAG, "Bottom widget: none")
        }
        widgetStatsBtn.setOnClickListener {
            Config.BOTTOM_WIDGET = "stats"
            prefs.edit().putString(ConfigPrefs.PREF_BOTTOM_WIDGET, "stats").apply()
            updateBottomWidgetSegmentUI()
            updateBottomWidgetVisibility()
            Log.i(TAG, "Bottom widget: stats")
        }
        widgetTipsBtn.setOnClickListener {
            Config.BOTTOM_WIDGET = "tips"
            prefs.edit().putString(ConfigPrefs.PREF_BOTTOM_WIDGET, "tips").apply()
            updateBottomWidgetSegmentUI()
            updateBottomWidgetVisibility()
            Log.i(TAG, "Bottom widget: tips")
        }

        inferenceModeLocal.setOnClickListener {
            Config.INFERENCE_MODE = "local"
            prefs.edit().putString(ConfigPrefs.PREF_INFERENCE_MODE, "local").apply()
            updateInferenceModeUI()
            updateVlmServerButtonUI()
            Log.i(TAG, "Inference mode: local")
        }
        inferenceModeRemote.setOnClickListener {
            Config.INFERENCE_MODE = "remote"
            prefs.edit().putString(ConfigPrefs.PREF_INFERENCE_MODE, "remote").apply()
            updateInferenceModeUI()
            updateVlmServerButtonUI()
            if (isRunning) {
                Toast.makeText(this, "Changes apply after restart", Toast.LENGTH_SHORT).show()
            }
            Log.i(TAG, "Inference mode: remote")
        }

        vlmServerButton.setOnClickListener {
            showVlmServerDialog()
        }

        wifiCamButton.setOnClickListener {
            showWifiCameraDialog()
        }

        registerButton.setOnClickListener {
            try {
                // Stop monitoring first — only one consumer can use the USB camera
                if (isRunning) {
                    Log.i(TAG, "Stopping monitoring before face registration")
                    stopMonitoringForRegistration()
                }
                val intent = Intent(this, FaceRegistrationActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch FaceRegistrationActivity", e)
            }
        }

        // --- Detect already-running service (started by BootReceiver) ---
        if (FrameHolder.isServiceRunning()) {
            Log.i(TAG, "Service already running (boot auto-start), syncing UI")
            isRunning = true
            startMonitoringUiOnly()
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
        stopTipsRotation()
        if (::asimoGlowView.isInitialized) asimoGlowView.stopPulse()
        deviceSetup?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // 5-Tap Gesture → Settings Panel
    // ---------------------------------------------------------------------

    private fun onRootTap() {
        val now = System.currentTimeMillis()
        if (now - firstTapTime > TAP_WINDOW_MS) {
            tapCount = 0
            firstTapTime = now
        }
        tapCount++

        // Visual hint on 4th tap — brief flash of the left panel border
        if (tapCount == REQUIRED_TAPS - 1) {
            val leftPanel = findViewById<View>(R.id.leftPanel)
            leftPanel.animate().alpha(0.5f).setDuration(100).withEndAction {
                leftPanel.animate().alpha(1f).setDuration(100).start()
            }.start()
        }

        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            toggleSettingsPanel()
        }
    }

    private fun toggleSettingsPanel() {
        if (settingsVisible) {
            hideSettingsPanel()
        } else {
            showSettingsPanel()
        }
    }

    private fun showSettingsPanel() {
        settingsVisible = true

        // Position off-screen to the left, then slide in
        settingsPanel.translationX = -dpToPx(300).toFloat()
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.animate()
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Fade in scrim
        settingsScrim.alpha = 0f
        settingsScrim.visibility = View.VISIBLE
        settingsScrim.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun hideSettingsPanel() {
        settingsVisible = false

        settingsPanel.animate()
            .translationX(-dpToPx(300).toFloat())
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                settingsPanel.visibility = View.GONE
            }
            .start()

        settingsScrim.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                settingsScrim.visibility = View.GONE
            }
            .start()
    }

    // ---------------------------------------------------------------------
    // Settings UI Updates
    // ---------------------------------------------------------------------

    private fun updatePreviewToggleUI() {
        if (Config.ENABLE_PREVIEW) {
            previewToggle.text = "ON"
            previewToggle.setTextColor(colorTextPrimary)
            previewToggle.setBackgroundResource(R.drawable.bg_button_accent)
        } else {
            previewToggle.text = "OFF"
            previewToggle.setTextColor(colorTextSecondary)
            previewToggle.setBackgroundResource(R.drawable.bg_button)
        }
    }

    private fun updateAudioToggleUI() {
        if (Config.ENABLE_AUDIO_ALERTS) {
            audioToggle.text = "ON"
            audioToggle.setTextColor(colorTextPrimary)
            audioToggle.setBackgroundResource(R.drawable.bg_button_accent)
        } else {
            audioToggle.text = "OFF"
            audioToggle.setTextColor(colorTextMuted)
            audioToggle.setBackgroundResource(R.drawable.bg_button)
        }
    }

    private fun updateSeatSegmentUI() {
        if (Config.DRIVER_SEAT_SIDE == "left") {
            seatLeftBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
            seatLeftBtn.setTextColor(Color.WHITE)
            seatRightBtn.setBackgroundColor(Color.TRANSPARENT)
            seatRightBtn.setTextColor(colorTextSecondary)
        } else {
            seatRightBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
            seatRightBtn.setTextColor(Color.WHITE)
            seatLeftBtn.setBackgroundColor(Color.TRANSPARENT)
            seatLeftBtn.setTextColor(colorTextSecondary)
        }
    }

    private fun updateLangSegmentUI() {
        if (Config.LANGUAGE == "en") {
            langEnBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
            langEnBtn.setTextColor(Color.WHITE)
            langJaBtn.setBackgroundColor(Color.TRANSPARENT)
            langJaBtn.setTextColor(colorTextSecondary)
        } else {
            langJaBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
            langJaBtn.setTextColor(Color.WHITE)
            langEnBtn.setBackgroundColor(Color.TRANSPARENT)
            langEnBtn.setTextColor(colorTextSecondary)
        }
    }

    private fun updatePaxDetailSegmentUI() {
        if (Config.PASSENGER_INFO_DETAIL == "minimal") {
            paxMinimalBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
            paxMinimalBtn.setTextColor(Color.WHITE)
            paxDetailedBtn.setBackgroundColor(Color.TRANSPARENT)
            paxDetailedBtn.setTextColor(colorTextSecondary)
        } else {
            paxDetailedBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
            paxDetailedBtn.setTextColor(Color.WHITE)
            paxMinimalBtn.setBackgroundColor(Color.TRANSPARENT)
            paxMinimalBtn.setTextColor(colorTextSecondary)
        }
    }

    private fun updateAsimoSizeSegmentUI() {
        val buttons = listOf(asimoSizeSmallBtn, asimoSizeMediumBtn, asimoSizeLargeBtn)
        val keys = listOf("s", "m", "l")
        for (i in buttons.indices) {
            if (keys[i] == Config.ASIMO_SIZE) {
                buttons[i].setBackgroundResource(R.drawable.bg_segmented_selected)
                buttons[i].setTextColor(Color.WHITE)
            } else {
                buttons[i].setBackgroundColor(Color.TRANSPARENT)
                buttons[i].setTextColor(colorTextSecondary)
            }
        }
    }

    private fun updateBottomWidgetSegmentUI() {
        val buttons = listOf(widgetOffBtn, widgetStatsBtn, widgetTipsBtn)
        val keys = listOf("none", "stats", "tips")
        for (i in buttons.indices) {
            if (keys[i] == Config.BOTTOM_WIDGET) {
                buttons[i].setBackgroundResource(R.drawable.bg_segmented_selected)
                buttons[i].setTextColor(Color.WHITE)
            } else {
                buttons[i].setBackgroundColor(Color.TRANSPARENT)
                buttons[i].setTextColor(colorTextSecondary)
            }
        }
    }

    private fun updateBottomWidgetVisibility() {
        when (Config.BOTTOM_WIDGET) {
            "stats" -> {
                statsWidget.visibility = View.VISIBLE
                tipsWidget.visibility = View.GONE
                stopTipsRotation()
            }
            "tips" -> {
                statsWidget.visibility = View.GONE
                tipsWidget.visibility = View.VISIBLE
                if (isRunning) startTipsRotation()
            }
            else -> {
                statsWidget.visibility = View.GONE
                tipsWidget.visibility = View.GONE
                stopTipsRotation()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Bottom Widget: Session Stats
    // ---------------------------------------------------------------------

    private fun updateBottomWidget(result: OutputResult) {
        when (Config.BOTTOM_WIDGET) {
            "stats" -> updateStatsWidget()
            "tips" -> updateTipsContext(result)
        }
    }

    private fun updateStatsWidget() {
        statPhone.text = "Phone: ${detectionCounts["Phone"] ?: 0}"
        statEyes.text = "Eyes Closed: ${detectionCounts["Eyes Closed"] ?: 0}"
        statYawn.text = "Yawning: ${detectionCounts["Yawning"] ?: 0}"
        statDistracted.text = "Distracted: ${detectionCounts["Distracted"] ?: 0}"
        statEating.text = "Eating: ${detectionCounts["Eating"] ?: 0}"
        statPosture.text = "Posture: ${detectionCounts["Posture"] ?: 0}"
        statChild.text = "Child Slouch: ${detectionCounts["Child Slouch"] ?: 0}"
        val avgScore = if (totalFrames > 0) (scoreSum / totalFrames).toInt() else 100
        statFooter.text = "Frames: $totalFrames  |  Avg Score: $avgScore"
    }

    // ---------------------------------------------------------------------
    // Bottom Widget: Driver Wellness Tips
    // ---------------------------------------------------------------------

    private var lastContextTip: String? = null

    private fun updateTipsContext(result: OutputResult) {
        val isJa = Config.LANGUAGE == "ja"
        val sessionMin = ((System.currentTimeMillis() - sessionStartMs) / 60_000).toInt()
        val streakMin = ((System.currentTimeMillis() - lastDetectionMs) / 60_000).toInt()

        // Context-aware tip (override rotation if relevant)
        val contextTip: String? = when {
            sessionMin >= 120 -> if (isJa) "2時間以上運転中。休憩をおすすめします。" else "Driving 2+ hours. Time for a break!"
            sessionMin in 20..119 && sessionMin % 20 == 0 -> if (isJa) "${sessionMin}分運転中。ストレッチを考えて。" else "Driving $sessionMin min \u2014 consider a stretch."
            streakMin >= 5 -> if (isJa) "${streakMin}分間集中！その調子！" else "$streakMin min distraction-free!"
            (detectionCounts["Phone"] ?: 0) >= 3 -> if (isJa) "スマホ${detectionCounts["Phone"]}回検出。グローブボックスへ。" else "Phone detected ${detectionCounts["Phone"]} times \u2014 try the glovebox."
            else -> null
        }

        if (contextTip != null && contextTip != lastContextTip) {
            lastContextTip = contextTip
            crossfadeTip(contextTip)
        }
    }

    private fun startTipsRotation() {
        handler.removeCallbacks(tipsRotationRunnable)
        if (Config.BOTTOM_WIDGET == "tips") {
            updateTipsContent()
            handler.postDelayed(tipsRotationRunnable, TIPS_ROTATION_MS)
        }
    }

    private fun stopTipsRotation() {
        handler.removeCallbacks(tipsRotationRunnable)
    }

    private fun updateTipsContent() {
        val tips = if (Config.LANGUAGE == "ja") TIPS_GENERAL_JA else TIPS_GENERAL_EN
        val tip = tips[tipsIndex % tips.size]
        tipsIndex++
        crossfadeTip(tip)
    }

    private fun crossfadeTip(tip: String) {
        tipsText.animate().alpha(0f).setDuration(75).withEndAction {
            tipsText.text = tip
            tipsText.animate().alpha(1f).setDuration(150).start()
        }.start()
    }

    // ---------------------------------------------------------------------
    // WiFi Camera Configuration
    // ---------------------------------------------------------------------

    private fun updateWifiCamButtonUI() {
        if (Config.WIFI_CAMERA_URL.isNotBlank()) {
            wifiCamButton.text = "WiFi Cam: ON"
            wifiCamButton.setTextColor(colorAccent)
        } else {
            wifiCamButton.text = "WiFi Camera..."
            wifiCamButton.setTextColor(colorTextSecondary)
        }
    }

    private fun showWifiCameraDialog() {
        val currentUrl = Config.WIFI_CAMERA_URL

        val input = EditText(this).apply {
            setText(currentUrl)
            hint = "http://192.168.1.100:8080/video"
            setHintTextColor(colorTextMuted)
            setTextColor(colorTextPrimary)
            setBackgroundColor(colorSurfaceElevated)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            textSize = 14f
            isSingleLine = true
            if (currentUrl.isNotBlank()) selectAll()
        }

        val hintText = if (isRunning)
            "MJPEG stream URL (e.g. IP Webcam app)\nChanges apply after restart"
        else
            "MJPEG stream URL (e.g. IP Webcam app)"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), 0)
            addView(input)
            // Subtitle hint
            addView(TextView(this@MainActivity).apply {
                text = hintText
                setTextColor(colorTextMuted)
                textSize = 11f
                setPadding(0, dpToPx(6), 0, 0)
            })
        }

        val prefs = getSharedPreferences(ConfigPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("WiFi Camera")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Config.WIFI_CAMERA_URL = url
                prefs.edit().putString(ConfigPrefs.PREF_WIFI_URL, url).apply()
                updateWifiCamButtonUI()
                Log.i(TAG, "WiFi camera URL: ${if (url.isBlank()) "(cleared)" else url}")
            }
            .setNeutralButton("Clear") { _, _ ->
                Config.WIFI_CAMERA_URL = ""
                prefs.edit().putString(ConfigPrefs.PREF_WIFI_URL, "").apply()
                updateWifiCamButtonUI()
                Log.i(TAG, "WiFi camera URL cleared")
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    // ---------------------------------------------------------------------
    // Inference Mode Configuration
    // ---------------------------------------------------------------------

    private fun updateInferenceModeUI() {
        if (Config.INFERENCE_MODE == "remote") {
            inferenceModeRemote.setTextColor(colorTextPrimary)
            inferenceModeLocal.setTextColor(colorTextSecondary)
        } else {
            inferenceModeLocal.setTextColor(colorTextPrimary)
            inferenceModeRemote.setTextColor(colorTextSecondary)
        }
    }

    private fun updateVlmServerButtonUI() {
        if (Config.INFERENCE_MODE == "remote") {
            vlmServerButton.visibility = View.VISIBLE
            if (Config.VLM_SERVER_URL.isNotBlank()) {
                vlmServerButton.text = "VLM: ON"
                vlmServerButton.setTextColor(colorAccent)
            } else {
                vlmServerButton.text = "VLM Server..."
                vlmServerButton.setTextColor(colorTextSecondary)
            }
        } else {
            vlmServerButton.visibility = View.GONE
        }
    }

    private fun showVlmServerDialog() {
        val currentUrl = Config.VLM_SERVER_URL

        val input = EditText(this).apply {
            setText(currentUrl)
            hint = "http://192.168.1.100:8000"
            setHintTextColor(colorTextMuted)
            setTextColor(colorTextPrimary)
            setBackgroundColor(colorSurfaceElevated)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            textSize = 14f
            isSingleLine = true
            if (currentUrl.isNotBlank()) selectAll()
        }

        val hintText = if (isRunning)
            "VLM server base URL\nChanges apply after restart"
        else
            "VLM server base URL (e.g. laptop on LAN)"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), 0)
            addView(input)
            addView(TextView(this@MainActivity).apply {
                text = hintText
                setTextColor(colorTextMuted)
                textSize = 11f
                setPadding(0, dpToPx(6), 0, 0)
            })
        }

        val prefs = getSharedPreferences(ConfigPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("VLM Server")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Config.VLM_SERVER_URL = url
                prefs.edit().putString(ConfigPrefs.PREF_VLM_URL, url).apply()
                updateVlmServerButtonUI()
                Log.i(TAG, "VLM server URL: ${if (url.isBlank()) "(cleared)" else url}")

                // Advisory health check on save
                if (url.isNotBlank()) {
                    Thread {
                        val health = VlmClient.checkHealthOnce(url)
                        runOnUiThread {
                            val msg = if (health != null)
                                "VLM Online \u2014 ${health.model ?: "unknown"}"
                            else "VLM Offline"
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                Config.VLM_SERVER_URL = ""
                prefs.edit().putString(ConfigPrefs.PREF_VLM_URL, "").apply()
                updateVlmServerButtonUI()
                Log.i(TAG, "VLM server URL cleared")
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    // ---------------------------------------------------------------------
    // Camera Status Indicator (dot + tooltip)
    // ---------------------------------------------------------------------

    private fun setCameraStatusDotColor(color: Int) {
        val bg = cameraStatusDot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            cameraStatusDot.setBackgroundColor(color)
        }
    }

    private fun updateInferenceBadge() {
        val isRemote = Config.INFERENCE_MODE == "remote"
        inferenceBadge.text = if (isRemote) "VLM" else "LOCAL"
        val bg = inferenceBadge.background
        if (bg is GradientDrawable) {
            bg.setColor(if (isRemote) colorVlmPurple else colorAccent)
        }
    }

    private fun updateCameraStatus() {
        // Check for service stall (heartbeat older than threshold)
        if (isRunning && FrameHolder.isServiceRunning() &&
            FrameHolder.getHeartbeatAgeMs() > Config.SERVICE_STALL_THRESHOLD_MS) {
            cameraStatusText.text = "Stalled"
            setCameraStatusDotColor(colorDanger)
            return
        }

        val isRemote = Config.INFERENCE_MODE == "remote"
        val status = FrameHolder.getCameraStatus()
        when (status) {
            FrameHolder.CameraStatus.NOT_CONNECTED -> {
                cameraStatusText.text = if (isRemote) "VLM: N/C" else "No camera"
                setCameraStatusDotColor(colorDanger)
            }
            FrameHolder.CameraStatus.CONNECTING -> {
                cameraStatusText.text = if (isRemote) "VLM: Connecting" else "Connecting..."
                setCameraStatusDotColor(colorCaution)
            }
            FrameHolder.CameraStatus.READY -> {
                cameraStatusText.text = if (isRemote) "VLM: Ready" else "Ready"
                setCameraStatusDotColor(colorSafe)
            }
            FrameHolder.CameraStatus.ACTIVE -> {
                cameraStatusText.text = if (isRemote) "VLM: Active" else "Active"
                setCameraStatusDotColor(colorSafe)
            }
            FrameHolder.CameraStatus.LOST -> {
                cameraStatusText.text = if (isRemote) "VLM: Lost" else "Lost"
                setCameraStatusDotColor(colorDanger)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Setup Flow
    // ---------------------------------------------------------------------

    private fun onStartButtonTap() {
        if (Config.INFERENCE_MODE == "remote") {
            // Remote VLM mode: sanity-check URL and server before starting
            if (Config.VLM_SERVER_URL.isBlank()) {
                Toast.makeText(this, "VLM server URL not configured", Toast.LENGTH_SHORT).show()
                return
            }
            statusText.text = "Checking VLM server..."
            Thread {
                val health = VlmClient.checkHealthOnce(Config.VLM_SERVER_URL)
                runOnUiThread {
                    val msg = if (health != null)
                        "VLM Online \u2014 ${health.model ?: "unknown"}"
                    else "VLM server unreachable"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    checkPermissionsAndStart()  // Always proceed — advisory only
                }
            }.start()
            return
        }
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
        toggleButton.text = "..."

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
        val permissions = mutableListOf<String>()
        // Skip camera permission in remote VLM mode (no local camera used)
        if (Config.INFERENCE_MODE != "remote") {
            permissions.add(Manifest.permission.CAMERA)
        }
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
            if (Config.INFERENCE_MODE == "remote") {
                // In remote mode, camera is not requested — just start
                startMonitoring()
                return
            }
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

    /** Enter monitoring UI state without starting the service (for instrumented tests). */
    internal fun startMonitoringUiOnly() {
        startMonitoringInternal(launchService = false)
    }

    private fun startMonitoring() {
        startMonitoringInternal(launchService = true)
    }

    private fun startMonitoringInternal(launchService: Boolean) {
        if (launchService) {
            val intent = Intent(this, InCabinService::class.java).apply {
                action = InCabinService.ACTION_START
            }
            startForegroundService(intent)
        }
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
        streakText.text = "0:00"
        bestStreakText.text = "Best 0:00"
        sessionTimeText.text = "0:00"

        // Show monitoring UI with fade-in
        idleOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            idleOverlay.visibility = View.GONE
        }.start()

        scoreContainer.visibility = View.VISIBLE
        scorePanel.visibility = View.VISIBLE
        leftDivider.visibility = View.VISIBLE

        // Show inference mode badge with fade-in
        updateInferenceBadge()
        inferenceBadge.alpha = 0f
        inferenceBadge.visibility = View.VISIBLE
        inferenceBadge.animate().alpha(1f).setDuration(200).start()

        // Show aiStatusText only when preview is ON (bubble handles it otherwise)
        if (Config.ENABLE_PREVIEW) {
            aiStatusText.visibility = View.VISIBLE
            aiStatusText.text = "Warming up the AI brain..."
            aiStatusText.setTextColor(colorAccent)
        } else {
            aiStatusText.visibility = View.GONE
        }

        // Show ASIMO companion hub with fade-in
        currentAsimoPose = R.drawable.asimo_all_clear
        asimoAnimating = false
        currentAsimoDetectionKey = ""
        asimoMascot.setImageResource(R.drawable.asimo_all_clear)
        asimoGlowView.setGlowColor(colorSafe)
        asimoGlowView.stopPulse()
        asimoBubbleText.text = if (Config.LANGUAGE == "ja") "AIの準備が完了しました！" else "Smart eyes are ready!"
        asimoDetectionLabel.visibility = View.GONE
        updateAsimoSize()
        asimoContainer.alpha = 0f
        asimoContainer.visibility = View.VISIBLE
        asimoContainer.animate().alpha(1f).setDuration(300).start()

        // Fade in right panel
        rightPanel.alpha = 0f
        rightPanel.visibility = View.VISIBLE
        rightPanel.animate().alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()

        tickerContainer.visibility = View.VISIBLE

        // Show bottom widget + start tips rotation
        tipsIndex = 0
        updateBottomWidgetVisibility()
        startTipsRotation()

        // Reset risk banner
        currentRiskColor = colorSafe
        setRiskPillColor(colorSafe)
        riskBanner.text = "LOW"
        riskBanner.setTextColor(Color.BLACK)

        handler.post(previewPoller)
        Log.i(TAG, "Monitoring started")
    }

    /** Exit monitoring UI state without stopping the service (for instrumented tests). */
    internal fun stopMonitoringUiOnly() {
        stopMonitoringInternal(stopService = false)
    }

    private fun stopMonitoring() {
        stopMonitoringInternal(stopService = true)
    }

    /** Stop monitoring silently (no session summary dialog). Used before launching face registration. */
    private fun stopMonitoringForRegistration() {
        stopMonitoringInternal(stopService = true, showSummary = false)
    }

    private fun stopMonitoringInternal(stopService: Boolean, showSummary: Boolean = true) {
        if (stopService) {
            val intent = Intent(this, InCabinService::class.java).apply {
                action = InCabinService.ACTION_STOP
            }
            startService(intent)

            // Show session summary before resetting (skip when stopping for registration)
            if (showSummary) {
                showSessionSummary()
            }
        }

        isRunning = false
        toggleButton.text = getString(R.string.start_service)
        statusText.text = "Tap Start to begin monitoring"

        // Hide monitoring UI with fade
        rightPanel.animate().alpha(0f).setDuration(200).withEndAction {
            rightPanel.visibility = View.GONE
        }.start()

        // Fade out ASIMO companion hub
        asimoGlowView.stopPulse()
        asimoContainer.animate().alpha(0f).setDuration(200).withEndAction {
            asimoContainer.visibility = View.GONE
        }.start()

        scoreContainer.visibility = View.GONE
        scorePanel.visibility = View.GONE
        leftDivider.visibility = View.GONE
        aiStatusText.visibility = View.GONE
        inferenceBadge.visibility = View.GONE
        tickerContainer.visibility = View.GONE
        stopTipsRotation()
        statsWidget.visibility = View.GONE
        tipsWidget.visibility = View.GONE

        // Show idle overlay
        idleOverlay.visibility = View.VISIBLE
        idleOverlay.alpha = 0f
        idleOverlay.animate().alpha(1f).setDuration(300).start()

        handler.removeCallbacks(previewPoller)
        previewImage.setImageBitmap(null)
        detectionsContainer.removeAllViews()
        passengerPostureContainer.removeAllViews()
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
        } else if (!result.driverDetected) {
            targetColor = colorCaution
            riskText = "NO DRIVER"
            riskTextColor = Color.BLACK
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

        // Driver position (seat side) — static at bottom of right panel
        driverPositionText.text = "Driver Position: ${Config.DRIVER_SEAT_SIDE.uppercase()}"
        if (driverPositionText.visibility != View.VISIBLE) {
            driverPositionText.visibility = View.VISIBLE
        }

        // Engineering metrics (hidden, but kept updated)
        earText.text = "EAR: ${result.earValue?.let { "%.3f".format(it) } ?: "--"}"
        marText.text = "MAR: ${result.marValue?.let { "%.3f".format(it) } ?: "--"}"
        yawText.text = "Yaw: ${result.headYaw?.let { "%.1f".format(it) } ?: "--"}"
        pitchText.text = "Pitch: ${result.headPitch?.let { "%.1f".format(it) } ?: "--"}"

        // Info — passenger count (simple)
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
        val labelMap = if (Config.LANGUAGE == "ja") AsimoHub.DETECTION_LABELS_JA else AsimoHub.DETECTION_LABELS_EN

        // Build active set using field keys (language-independent)
        val activeKeys = mutableSetOf<String>()
        if (!result.driverDetected && result.passengerCount > 0) activeKeys.add("noDriverDetected")
        if (result.driverUsingPhone) activeKeys.add("driverUsingPhone")
        if (result.driverEyesClosed) activeKeys.add("driverEyesClosed")
        if (result.driverYawning) activeKeys.add("driverYawning")
        if (result.driverDistracted) activeKeys.add("driverDistracted")
        if (result.driverEatingDrinking) activeKeys.add("driverEatingDrinking")
        if (result.handsOffWheel) activeKeys.add("handsOffWheel")
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
            val isDanger = AsimoHub.isDangerField(key)
            val dotColor = if (isDanger) colorDanger else colorCaution
            val displayLabel = labelMap[key] ?: key

            val tv = TextView(this).apply {
                tag = "det_$key"
                text = "\u25CF  $displayLabel"
                textSize = 22f
                setTextColor(dotColor)
                setPadding(0, dpToPx(3), 0, dpToPx(3))
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

        // Remove any lingering animated-out views (their withEndAction may not have fired yet)
        for (i in detectionsContainer.childCount - 1 downTo 0) {
            val child = detectionsContainer.getChildAt(i)
            if (child.tag?.toString()?.startsWith("det_") == true && child.tag != "det_allclear") {
                child.animate().cancel()
                detectionsContainer.removeViewAt(i)
            }
        }

        val tv = TextView(this).apply {
            tag = "det_allclear"
            text = if (Config.LANGUAGE == "ja") "安全" else "All Clear"
            textSize = 22f
            setTextColor(colorSafe)
            setPadding(0, dpToPx(3), 0, dpToPx(3))
            alpha = 0f
        }
        detectionsContainer.addView(tv)
        tv.animate().alpha(1f).setDuration(200).start()
    }

    // --- Passenger posture display ---
    private var currentPassengerPostures = emptyList<FrameHolder.PassengerPosture>()

    private fun updatePassengerPostures() {
        val postures = FrameHolder.getPassengerPostures()
        if (postures == currentPassengerPostures) return
        currentPassengerPostures = postures

        passengerPostureContainer.removeAllViews()
        for (p in postures) {
            if (!p.hasBadPosture) continue
            val isJa = Config.LANGUAGE == "ja"
            val label = if (isJa) "\u25CF  乗客${p.index}: 姿勢不良" else "\u25CF  Passenger ${p.index}: Bad Posture"
            val tv = TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(colorCaution)
                setPadding(0, dpToPx(2), 0, dpToPx(2))
                alpha = 0f
            }
            passengerPostureContainer.addView(tv)
            tv.animate().alpha(1f).setDuration(200).start()
        }
    }

    // ---------------------------------------------------------------------
    // ASIMO Mascot
    // ---------------------------------------------------------------------

    private fun updateAsimoPose(result: OutputResult) {
        val detectionKey = AsimoHub.resolveDetectionKey(result)
        val targetDrawable = ASIMO_POSE_PRIORITY.firstOrNull { it.first == detectionKey }?.second
            ?: R.drawable.asimo_all_clear

        // Update glow color based on risk level
        val glowCategory = AsimoHub.resolveGlowCategory(result.riskLevel)
        val glowColor = when (glowCategory) {
            AsimoHub.GlowCategory.DANGER -> colorDanger
            AsimoHub.GlowCategory.CAUTION -> colorCaution
            AsimoHub.GlowCategory.SAFE -> colorSafe
        }
        asimoGlowView.animateColorTo(glowColor)

        // Pulse on danger
        if (AsimoHub.shouldPulse(result.riskLevel)) {
            asimoGlowView.startPulse()
        } else {
            asimoGlowView.stopPulse()
        }

        // Update detection label
        updateAsimoDetectionLabel(detectionKey)

        if (targetDrawable == currentAsimoPose || asimoAnimating) return

        // Crossfade: half out, swap drawable, half in
        asimoAnimating = true
        val halfDuration = ASIMO_CROSSFADE_MS / 2
        asimoMascot.animate()
            .alpha(0f)
            .setDuration(halfDuration)
            .withEndAction {
                asimoMascot.setImageResource(targetDrawable)
                currentAsimoPose = targetDrawable
                asimoMascot.animate()
                    .alpha(1f)
                    .setDuration(halfDuration)
                    .withEndAction { asimoAnimating = false }
                    .start()
            }
            .start()
    }

    private fun updateAsimoDetectionLabel(detectionKey: String) {
        if (detectionKey == currentAsimoDetectionKey) return
        currentAsimoDetectionKey = detectionKey

        if (detectionKey.isEmpty()) {
            // No detection — fade out label
            if (asimoDetectionLabel.visibility == View.VISIBLE) {
                asimoDetectionLabel.animate().alpha(0f).setDuration(200).withEndAction {
                    asimoDetectionLabel.visibility = View.GONE
                }.start()
            }
            return
        }

        val label = AsimoHub.getDetectionLabel(detectionKey, Config.LANGUAGE) ?: return
        val isDanger = AsimoHub.isDangerField(detectionKey)
        val labelColor = if (isDanger) colorDanger else colorCaution

        // Tint the background drawable
        val bg = asimoDetectionLabel.background
        if (bg is GradientDrawable) {
            bg.setColor(AsimoHub.computeLabelTint(labelColor))
        }

        asimoDetectionLabel.text = label
        asimoDetectionLabel.setTextColor(labelColor)

        if (asimoDetectionLabel.visibility != View.VISIBLE) {
            asimoDetectionLabel.alpha = 0f
            asimoDetectionLabel.visibility = View.VISIBLE
            asimoDetectionLabel.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun updateAsimoSize() {
        val isCompact = Config.ENABLE_PREVIEW

        // Robot size — user setting (S/M/L) when full, compact when preview is on
        val robotSize = if (isCompact) {
            resources.getDimensionPixelSize(R.dimen.asimo_size_compact)
        } else when (Config.ASIMO_SIZE) {
            "s" -> resources.getDimensionPixelSize(R.dimen.asimo_size_s)
            "l" -> resources.getDimensionPixelSize(R.dimen.asimo_size_l)
            else -> resources.getDimensionPixelSize(R.dimen.asimo_size_m)
        }
        val robotLp = asimoMascot.layoutParams
        robotLp.width = robotSize
        robotLp.height = robotSize
        asimoMascot.layoutParams = robotLp

        // Glow frame size — matches mascot tier
        val glowSize = if (isCompact) {
            resources.getDimensionPixelSize(R.dimen.asimo_glow_compact)
        } else when (Config.ASIMO_SIZE) {
            "s" -> resources.getDimensionPixelSize(R.dimen.asimo_glow_s)
            "l" -> resources.getDimensionPixelSize(R.dimen.asimo_glow_l)
            else -> resources.getDimensionPixelSize(R.dimen.asimo_glow_m)
        }
        val glowLp = asimoGlowFrame.layoutParams
        glowLp.width = glowSize
        glowLp.height = glowSize
        asimoGlowFrame.layoutParams = glowLp

        // Show/hide bubble, label, branding based on mode
        val hubVisibility = if (isCompact) View.GONE else View.VISIBLE
        asimoBrandingText.visibility = hubVisibility
        asimoBubbleFrame.visibility = hubVisibility
        asimoBubbleTail.visibility = hubVisibility
        asimoDetectionLabel.visibility = if (isCompact || currentAsimoDetectionKey.isEmpty()) View.GONE else View.VISIBLE

        // Container gravity: center when full, bottom-end when compact
        val containerLp = asimoContainer.layoutParams
        if (containerLp is FrameLayout.LayoutParams) {
            containerLp.gravity = if (isCompact) {
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            } else {
                android.view.Gravity.CENTER
            }
            asimoContainer.layoutParams = containerLp
        }

        // AI status text visibility (show when compact for fallback)
        if (isRunning) {
            aiStatusText.visibility = if (isCompact) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------------
    // Safe Driving Score
    // ---------------------------------------------------------------------

    private fun updateScore(result: OutputResult) {
        val hasDetection = result.driverUsingPhone || result.driverEyesClosed ||
            result.handsOffWheel || result.driverDistracted || result.driverYawning ||
            result.driverEatingDrinking || result.dangerousPosture ||
            result.childSlouching

        if (hasDetection) {
            var penalty = 0f
            if (result.driverUsingPhone) penalty += SCORE_PENALTIES["phone"]!!
            if (result.driverEyesClosed) penalty += SCORE_PENALTIES["eyes"]!!
            if (result.handsOffWheel) penalty += SCORE_PENALTIES["hands_off"]!!
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
            result.handsOffWheel || result.driverDistracted || result.driverYawning ||
            result.driverEatingDrinking || result.dangerousPosture ||
            result.childSlouching

        if (hasDetection) {
            if (result.driverUsingPhone) detectionCounts["Phone"] = (detectionCounts["Phone"] ?: 0) + 1
            if (result.driverEyesClosed) detectionCounts["Eyes Closed"] = (detectionCounts["Eyes Closed"] ?: 0) + 1
            if (result.handsOffWheel) detectionCounts["Hands Off"] = (detectionCounts["Hands Off"] ?: 0) + 1
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
        streakText.text = formatDuration(streakMs)
        streakText.setTextColor(if (streakMs > 60_000) colorSafe else colorTextMuted)

        val displayBest = if (streakMs > bestStreakMs) streakMs else bestStreakMs
        bestStreakText.text = "Best ${formatDuration(displayBest)}"
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
                result.handsOffWheel -> { message = (if (isJa) HANDS_OFF_MESSAGES_JA else HANDS_OFF_MESSAGES).random(); color = colorDanger }
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

        // Route to bubble (preview OFF) or aiStatusText (preview ON)
        val targetView = if (Config.ENABLE_PREVIEW) aiStatusText else asimoBubbleText

        if (targetView.text != message) {
            targetView.animate().alpha(0f).setDuration(75).withEndAction {
                targetView.text = message
                targetView.setTextColor(color)
                targetView.animate().alpha(1f).setDuration(150).start()
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
        if (result.handsOffWheel) active.add("Hands Off")
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
        sessionTimeText.text = formatDuration(elapsed)
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
