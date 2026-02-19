package com.incabin

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val TAG = "InCabin-Activity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREVIEW_POLL_MS = 500L

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
        private val STREAK_MILESTONES = listOf(
            5L * 60 * 1000 to "5 minutes distraction-free! Keep it up!",
            15L * 60 * 1000 to "15 minutes! You're on fire!",
            30L * 60 * 1000 to "Half hour of perfect driving. Legend!"
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

        private val COLOR_GREEN = Color.rgb(0x4C, 0xAF, 0x50)
        private val COLOR_ORANGE = Color.rgb(0xFF, 0x98, 0x00)
        private val COLOR_RED = Color.rgb(0xF4, 0x43, 0x36)
        private val COLOR_BLUE = Color.rgb(0x64, 0xB5, 0xF6)
        private val COLOR_GOLD = Color.rgb(0xFF, 0xD5, 0x4F)

        private val TICKER_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    // --- UI references ---
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var dashboardPanel: LinearLayout
    private lateinit var riskBanner: TextView
    private lateinit var earText: TextView
    private lateinit var marText: TextView
    private lateinit var yawText: TextView
    private lateinit var pitchText: TextView
    private lateinit var passengerText: TextView
    private lateinit var distractionText: TextView
    private lateinit var detectionsText: TextView
    private lateinit var aiStatusText: TextView
    private lateinit var scoreArc: ScoreArcView
    private lateinit var streakText: TextView
    private lateinit var bestStreakText: TextView
    private lateinit var sessionTimeText: TextView
    private lateinit var scorePanel: LinearLayout
    private lateinit var tickerText: TextView

    private var isRunning = false

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

    private val handler = Handler(Looper.getMainLooper())
    private val previewPoller = object : Runnable {
        override fun run() {
            try {
                val frameData = FrameHolder.getLatest()
                if (frameData != null && !frameData.bitmap.isRecycled) {
                    previewImage.setImageBitmap(frameData.bitmap)
                    updateDashboard(frameData.result)
                    updateScore(frameData.result)
                    updateStreak(frameData.result)
                    updateAiStatus(frameData.result)
                    updateTicker(frameData.result)
                    updateSessionTime()
                    totalFrames++
                    scoreSum += drivingScore
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preview update failed", e)
            }
            handler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        previewImage = findViewById(R.id.previewImage)
        dashboardPanel = findViewById(R.id.dashboardPanel)
        riskBanner = findViewById(R.id.riskBanner)
        earText = findViewById(R.id.earText)
        marText = findViewById(R.id.marText)
        yawText = findViewById(R.id.yawText)
        pitchText = findViewById(R.id.pitchText)
        passengerText = findViewById(R.id.passengerText)
        distractionText = findViewById(R.id.distractionText)
        detectionsText = findViewById(R.id.detectionsText)
        aiStatusText = findViewById(R.id.aiStatusText)
        scoreArc = findViewById(R.id.scoreArc)
        streakText = findViewById(R.id.streakText)
        bestStreakText = findViewById(R.id.bestStreakText)
        sessionTimeText = findViewById(R.id.sessionTimeText)
        scorePanel = findViewById(R.id.scorePanel)
        tickerText = findViewById(R.id.tickerText)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopMonitoring()
            } else {
                checkPermissionsAndStart()
            }
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
                statusText.text = "Status: Camera permission denied"
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
        statusText.text = "Status: Monitoring active"

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

        aiStatusText.text = "Warming up the AI brain..."
        aiStatusText.setTextColor(COLOR_BLUE)
        scoreArc.score = 100
        streakText.text = "Streak: 0:00"
        bestStreakText.text = "Best: 0:00"
        sessionTimeText.text = "Session: 0:00"

        scorePanel.visibility = LinearLayout.VISIBLE
        dashboardPanel.visibility = LinearLayout.VISIBLE
        tickerText.visibility = TextView.VISIBLE
        tickerText.isSelected = true // start marquee
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
        statusText.text = "Status: Idle"
        aiStatusText.text = "Tap Start to begin monitoring"
        aiStatusText.setTextColor(COLOR_GREEN)
        scorePanel.visibility = LinearLayout.GONE
        dashboardPanel.visibility = LinearLayout.GONE
        tickerText.visibility = TextView.GONE
        handler.removeCallbacks(previewPoller)
        previewImage.setImageBitmap(null)
        Log.i(TAG, "Monitoring stopped")
    }

    // ---------------------------------------------------------------------
    // Dashboard
    // ---------------------------------------------------------------------

    private fun updateDashboard(result: OutputResult) {
        when (result.riskLevel) {
            "high" -> {
                riskBanner.text = "RISK: HIGH"
                riskBanner.setBackgroundColor(COLOR_RED)
                riskBanner.setTextColor(Color.WHITE)
            }
            "medium" -> {
                riskBanner.text = "RISK: MEDIUM"
                riskBanner.setBackgroundColor(COLOR_ORANGE)
                riskBanner.setTextColor(Color.BLACK)
            }
            else -> {
                riskBanner.text = "RISK: LOW"
                riskBanner.setBackgroundColor(COLOR_GREEN)
                riskBanner.setTextColor(Color.BLACK)
            }
        }

        earText.text = "EAR: ${result.earValue?.let { "%.3f".format(it) } ?: "--"}"
        marText.text = "MAR: ${result.marValue?.let { "%.3f".format(it) } ?: "--"}"
        yawText.text = "Yaw: ${result.headYaw?.let { "%.1f".format(it) } ?: "--"}"
        pitchText.text = "Pitch: ${result.headPitch?.let { "%.1f".format(it) } ?: "--"}"

        passengerText.text = "Passengers: ${result.passengerCount}"
        distractionText.text = "Distraction: ${result.distractionDurationS}s"

        val active = mutableListOf<String>()
        if (result.driverUsingPhone) active.add("Phone")
        if (result.driverEyesClosed) active.add("Eyes Closed")
        if (result.driverYawning) active.add("Yawning")
        if (result.driverDistracted) active.add("Distracted")
        if (result.driverEatingDrinking) active.add("Eating/Drinking")
        if (result.dangerousPosture) active.add("Bad Posture")
        if (result.childSlouching) active.add("Child Slouching")
        detectionsText.text = active.joinToString(" | ")
    }

    // ---------------------------------------------------------------------
    // Feature 1: Safe Driving Score
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
    // Feature 2: Distraction-Free Streak
    // ---------------------------------------------------------------------

    private fun updateStreak(result: OutputResult) {
        val now = System.currentTimeMillis()
        val hasDetection = result.driverUsingPhone || result.driverEyesClosed ||
            result.driverDistracted || result.driverYawning ||
            result.driverEatingDrinking || result.dangerousPosture ||
            result.childSlouching

        if (hasDetection) {
            // Track detection counts for session summary
            if (result.driverUsingPhone) detectionCounts["Phone"] = (detectionCounts["Phone"] ?: 0) + 1
            if (result.driverEyesClosed) detectionCounts["Eyes Closed"] = (detectionCounts["Eyes Closed"] ?: 0) + 1
            if (result.driverYawning) detectionCounts["Yawning"] = (detectionCounts["Yawning"] ?: 0) + 1
            if (result.driverDistracted) detectionCounts["Distracted"] = (detectionCounts["Distracted"] ?: 0) + 1
            if (result.driverEatingDrinking) detectionCounts["Eating"] = (detectionCounts["Eating"] ?: 0) + 1
            if (result.dangerousPosture) detectionCounts["Posture"] = (detectionCounts["Posture"] ?: 0) + 1
            if (result.childSlouching) detectionCounts["Child Slouch"] = (detectionCounts["Child Slouch"] ?: 0) + 1

            // Save best streak before reset
            val currentStreak = now - lastDetectionMs
            if (currentStreak > bestStreakMs) {
                bestStreakMs = currentStreak
            }
            lastDetectionMs = now
            announcedMilestones.clear()
        }

        val streakMs = now - lastDetectionMs
        streakText.text = "Streak: ${formatDuration(streakMs)}"
        streakText.setTextColor(if (streakMs > 60_000) COLOR_GREEN else Color.rgb(0xCC, 0xCC, 0xCC))

        val displayBest = if (streakMs > bestStreakMs) streakMs else bestStreakMs
        bestStreakText.text = "Best: ${formatDuration(displayBest)}"
    }

    // ---------------------------------------------------------------------
    // Feature 3 + 6: AI Status with Varied Messages + Milestones
    // ---------------------------------------------------------------------

    private fun updateAiStatus(result: OutputResult) {
        framesSinceStart++

        // First few frames: initialization feedback
        if (framesSinceStart == 1) {
            aiStatusText.text = "Smart eyes are ready!"
            aiStatusText.setTextColor(COLOR_BLUE)
            return
        }
        if (framesSinceStart == 2) {
            aiStatusText.text = "Got you! Let's roll."
            aiStatusText.setTextColor(COLOR_GREEN)
            return
        }

        // Check for streak milestones (Feature 6)
        val streakMs = System.currentTimeMillis() - lastDetectionMs
        for ((thresholdMs, milestoneMsg) in STREAK_MILESTONES) {
            if (streakMs >= thresholdMs && thresholdMs !in announcedMilestones) {
                announcedMilestones.add(thresholdMs)
                aiStatusText.text = milestoneMsg
                aiStatusText.setTextColor(COLOR_GOLD)
                return
            }
        }

        val message: String
        val color: Int

        when {
            // Priority 1: Detection messages (varied — Feature 3)
            result.driverUsingPhone -> {
                message = PHONE_MESSAGES.random()
                color = COLOR_RED
            }
            result.driverEyesClosed -> {
                message = EYES_MESSAGES.random()
                color = COLOR_RED
            }
            result.driverDistracted -> {
                message = DISTRACTED_MESSAGES.random()
                color = COLOR_ORANGE
            }
            result.driverYawning -> {
                message = YAWNING_MESSAGES.random()
                color = COLOR_ORANGE
            }
            result.driverEatingDrinking -> {
                message = EATING_MESSAGES.random()
                color = COLOR_ORANGE
            }
            result.dangerousPosture -> {
                message = POSTURE_MESSAGES.random()
                color = COLOR_ORANGE
            }
            result.childSlouching -> {
                message = CHILD_SLOUCH_MESSAGES.random()
                color = COLOR_ORANGE
            }

            // Priority 2: Distraction duration
            result.distractionDurationS >= 20 -> {
                message = "20 seconds! Please pull over."
                color = COLOR_RED
            }
            result.distractionDurationS >= 10 -> {
                message = "10 seconds! Seriously, eyes on road."
                color = COLOR_RED
            }
            result.distractionDurationS >= 5 -> {
                message = "5 seconds distracted... focus up!"
                color = COLOR_ORANGE
            }

            // Priority 3: Risk escalation
            result.riskLevel == "high" -> {
                message = "Danger zone! Focus NOW."
                color = COLOR_RED
            }
            result.riskLevel == "medium" -> {
                message = "Heads up! Stay alert."
                color = COLOR_ORANGE
            }

            // All clear: rotate messages
            else -> {
                message = ALL_CLEAR_MESSAGES[allClearIndex % ALL_CLEAR_MESSAGES.size]
                allClearIndex++
                color = COLOR_GREEN
            }
        }

        aiStatusText.text = message
        aiStatusText.setTextColor(color)
    }

    // ---------------------------------------------------------------------
    // Feature 9: Detection History Ticker
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

        // Only add event when state changes
        if (currentLabel != lastTickerDetections) {
            lastTickerDetections = currentLabel
            val timestamp = TICKER_TIME_FORMAT.format(Date())
            tickerEvents.add(0, "$timestamp - $currentLabel")
            if (tickerEvents.size > 20) tickerEvents.removeAt(tickerEvents.size - 1)
            tickerText.text = tickerEvents.joinToString("    |    ")
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
    // Feature 4: Session Summary
    // ---------------------------------------------------------------------

    private fun showSessionSummary() {
        val sessionDurationMs = System.currentTimeMillis() - sessionStartMs
        val avgScore = if (totalFrames > 0) (scoreSum / totalFrames).toInt() else 100
        val finalBest = maxOf(bestStreakMs, System.currentTimeMillis() - lastDetectionMs)

        val sb = StringBuilder()
        sb.appendLine("Duration: ${formatDuration(sessionDurationMs)}")
        sb.appendLine("Attention Score: $avgScore / 100")
        sb.appendLine("Final Score: ${drivingScore.toInt()} / 100")
        sb.appendLine("Longest Streak: ${formatDuration(finalBest)}")
        sb.appendLine()

        if (detectionCounts.isEmpty()) {
            sb.appendLine("Zero detections. Perfect session!")
        } else {
            sb.appendLine("Detections:")
            for ((name, count) in detectionCounts.entries.sortedByDescending { it.value }) {
                sb.appendLine("  $name: $count")
            }
        }

        sb.appendLine()
        sb.append(
            when {
                avgScore >= 90 -> "Outstanding driving! Stay safe out there."
                avgScore >= 70 -> "Good session. A bit of room to improve!"
                avgScore >= 50 -> "Decent, but stay more focused next time."
                else -> "Tough session. Consider taking a break."
            }
        )

        try {
            AlertDialog.Builder(this)
                .setTitle("Session Summary")
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
}
