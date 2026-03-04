package com.incabin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue

/**
 * Premium audio alerter with priority queue, staleness checks, per-danger cooldown,
 * progressive escalation, and beep-TTS coordination on a single worker thread.
 *
 * Call site: `audioAlerter.checkAndAnnounce(result)` — unchanged from previous API.
 */
class AudioAlerter(context: Context, private val audioUsage: Int = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {

    companion object {
        private const val TAG = "AudioAlerter"
        private const val ALERT_CHANNEL_ID = "incabin_alerts"
        private const val ALERT_NOTIFICATION_ID = 2

        val DANGER_FIELDS = listOf(
            "driver_using_phone",
            "driver_eyes_closed",
            "hands_off_wheel",
            "driver_yawning",
            "driver_distracted",
            "driver_eating_drinking",
            "dangerous_posture",
            "child_slouching"
        )

        val FRIENDLY_NAMES = mapOf(
            "driver_using_phone" to "Phone detected, please put it down",
            "driver_eyes_closed" to "Eyes closed, please stay alert",
            "hands_off_wheel" to "Hands off wheel, please grip the steering",
            "driver_yawning" to "Yawning detected, consider a break",
            "driver_distracted" to "Distracted, please watch the road",
            "driver_eating_drinking" to "Eating while driving, please focus",
            "dangerous_posture" to "Dangerous posture detected",
            "child_slouching" to "Child is slouching, please check"
        )

        val FRIENDLY_NAMES_JA = mapOf(
            "driver_using_phone" to "スマートフォンを検出、置いてください",
            "driver_eyes_closed" to "目を閉じています、注意してください",
            "hands_off_wheel" to "ハンドルから手を離しています",
            "driver_yawning" to "あくびを検出、休憩を取ってください",
            "driver_distracted" to "よそ見を検出、前を見てください",
            "driver_eating_drinking" to "飲食を検出、運転に集中してください",
            "dangerous_posture" to "危険な姿勢を検出",
            "child_slouching" to "子供の姿勢が悪いです、確認してください"
        )

        fun priorityForField(field: String): AlertPriority = when (field) {
            "driver_using_phone", "driver_eyes_closed", "hands_off_wheel" -> AlertPriority.CRITICAL
            else -> AlertPriority.WARNING
        }

        /**
         * Pure function: should we alert "No driver detected"?
         * Fires on transition from driver-present to driver-absent with passengers remaining.
         */
        fun shouldAlertNoDriver(
            driverDetected: Boolean,
            passengerCount: Int,
            prevDriverDetected: Boolean?,
            nowMs: Long,
            cooldownMap: Map<String, Long>
        ): Boolean {
            if (driverDetected) return false
            if (passengerCount <= 0) return false
            if (prevDriverDetected != true) return false // only on transition, not first frame
            val lastCooldown = cooldownMap["no_driver_detected"]
            if (lastCooldown != null && (nowMs - lastCooldown) < Config.ALERT_COOLDOWN_MS) return false
            return true
        }

        /**
         * Pure function: should we alert for a passenger's bad posture?
         * Fires on transition to bad posture (not already in prevSet) and outside cooldown.
         */
        fun shouldAlertPassengerPosture(
            index: Int,
            hasBadPosture: Boolean,
            prevSet: Set<Int>,
            nowMs: Long,
            cooldownMap: Map<String, Long>
        ): Boolean {
            if (!hasBadPosture) return false
            if (index in prevSet) return false
            val key = "passenger_posture_$index"
            val lastCooldown = cooldownMap[key]
            if (lastCooldown != null && (nowMs - lastCooldown) < Config.ALERT_COOLDOWN_MS) return false
            return true
        }

        /**
         * Builds alert messages from a state transition. Mutates [cooldownMap] and
         * [escalationMap] in place but has no other side effects — safe for unit testing.
         */
        fun buildAlerts(
            current: DangerSnapshot,
            prev: DangerSnapshot,
            duration: Int,
            prevDuration: Int,
            nowMs: Long,
            cooldownMap: MutableMap<String, Long>,
            escalationMap: MutableMap<String, EscalationState>,
            isJapanese: Boolean = false,
            driverDetected: Boolean = true
        ): List<AlertMessage> {
            val nameMap = if (isJapanese) FRIENDLY_NAMES_JA else FRIENDLY_NAMES

            // --- All-clear flush ---
            // Suppress "All clear" when dangers drop because driver left frame
            if (prev.any() && !current.any() && driverDetected) {
                cooldownMap.clear()
                escalationMap.clear()
                val text = if (isJapanese) "安全です" else "All clear"
                return listOf(AlertMessage(AlertPriority.INFO, text, null, false, nowMs))
            }

            val alerts = mutableListOf<AlertMessage>()

            // --- New dangers (onset) ---
            val criticalParts = mutableListOf<String>()
            val warningParts = mutableListOf<String>()
            val newDangerFields = mutableListOf<String>()

            for (i in DANGER_FIELDS.indices) {
                val field = DANGER_FIELDS[i]
                if (current.get(i) && !prev.get(i)) {
                    val lastAnnounced = cooldownMap[field]
                    if (lastAnnounced != null && (nowMs - lastAnnounced) < Config.ALERT_COOLDOWN_MS) {
                        continue
                    }
                    val name = nameMap[field] ?: field
                    val priority = priorityForField(field)
                    if (priority == AlertPriority.CRITICAL) {
                        criticalParts.add(name)
                    } else {
                        warningParts.add(name)
                    }
                    newDangerFields.add(field)
                }
            }

            // Build joined onset message (CRITICAL parts first, then WARNING)
            // dangerField = null: onset alerts must always be spoken. The worker thread's
            // "danger still active?" staleness check would drop transient detections
            // (e.g. eyes_closed with fast-clear) that resolve before the worker dequeues.
            // The 4s age staleness check is sufficient protection for onset messages.
            val allParts = criticalParts + warningParts
            if (allParts.isNotEmpty()) {
                val text = allParts.joinToString(". ")
                val priority = if (criticalParts.isNotEmpty()) AlertPriority.CRITICAL else AlertPriority.WARNING
                alerts.add(AlertMessage(priority, text, null, false, nowMs))

                for (field in newDangerFields) {
                    cooldownMap[field] = nowMs
                }
            }

            // --- Escalation ladder (only if no onset message) ---
            if (alerts.isEmpty() && duration > 0) {
                val escAlert = buildEscalationAlert(duration, nowMs, escalationMap, isJapanese)
                if (escAlert != null) {
                    alerts.add(escAlert)
                }
            }

            return alerts
        }

        fun buildEscalationAlert(
            duration: Int,
            nowMs: Long,
            escalationMap: MutableMap<String, EscalationState>,
            isJapanese: Boolean
        ): AlertMessage? {
            val key = "distraction"
            val state = escalationMap.getOrPut(key) { EscalationState(0, 0) }

            if (duration >= Config.ALERT_ESCALATION_FIRST_S && state.lastAnnouncedDuration < Config.ALERT_ESCALATION_FIRST_S) {
                state.lastAnnouncedDuration = duration
                val text = if (isJapanese) "まだよそ見、${Config.ALERT_ESCALATION_FIRST_S}秒"
                           else "Still distracted, ${Config.ALERT_ESCALATION_FIRST_S} seconds"
                return AlertMessage(AlertPriority.WARNING, text, null, false, nowMs)
            }

            if (duration >= Config.ALERT_ESCALATION_BEEP_S && state.lastAnnouncedDuration < Config.ALERT_ESCALATION_BEEP_S) {
                state.lastAnnouncedDuration = duration
                state.beepCount = 1
                val text = if (isJapanese) "警告。${Config.ALERT_ESCALATION_BEEP_S}秒よそ見"
                           else "Warning. Distracted ${Config.ALERT_ESCALATION_BEEP_S} seconds"
                return AlertMessage(AlertPriority.CRITICAL, text, null, true, nowMs)
            }

            if (duration >= Config.ALERT_ESCALATION_BEEP_S) {
                val nextThreshold = Config.ALERT_ESCALATION_BEEP_S + state.beepCount * Config.ALERT_ESCALATION_REPEAT_S
                if (duration >= nextThreshold && state.lastAnnouncedDuration < nextThreshold) {
                    state.lastAnnouncedDuration = duration
                    state.beepCount++
                    val text = if (isJapanese) "警告。${duration}秒よそ見"
                               else "Warning. Distracted $duration seconds"
                    return AlertMessage(AlertPriority.CRITICAL, text, null, true, nowMs)
                }
            }

            return null
        }

        /**
         * Pure function: build a time-of-day aware welcome greeting.
         * Morning (5-10), Afternoon (11-16), Evening (17-20), Night (21-4).
         * When [themeName] is non-null, appends "Applying {theme} theme" suffix.
         */
        fun buildWelcomeGreeting(name: String, hourOfDay: Int, themeName: String?, isJapanese: Boolean): String {
            if (isJapanese) {
                val greeting = when (hourOfDay) {
                    in 5..10 -> "おはようございます"
                    in 11..16 -> "こんにちは"
                    in 17..20 -> "こんばんは"
                    else -> "こんばんは"
                }
                val suffix = if (themeName != null) "。${themeName}テーマを適用しています" else ""
                return "$greeting、${name}さん$suffix"
            } else {
                val greeting = when (hourOfDay) {
                    in 5..10 -> "Good morning"
                    in 11..16 -> "Good afternoon"
                    in 17..20 -> "Good evening"
                    else -> "Good evening"
                }
                val suffix = if (themeName != null) ". Applying $themeName theme" else ""
                return "$greeting, $name$suffix"
            }
        }
    }

    // --- Priority system ---

    enum class AlertPriority {
        INFO,      // all-clear, duration milestones
        WARNING,   // yawning, distracted, eating, posture, slouching
        CRITICAL   // phone, eyes_closed
    }

    data class AlertMessage(
        val priority: AlertPriority,
        val text: String,
        val dangerField: String?,    // null for all-clear / duration
        val playBeepFirst: Boolean,
        val createdAtMs: Long
    )

    data class EscalationState(var lastAnnouncedDuration: Int, var beepCount: Int)

    /** Snapshot of one frame's danger booleans, indexed by DANGER_FIELDS order. */
    data class DangerSnapshot(
        val phone: Boolean, val eyes: Boolean, val handsOff: Boolean,
        val yawning: Boolean,
        val distracted: Boolean, val eating: Boolean, val posture: Boolean,
        val slouching: Boolean
    ) {
        fun get(index: Int): Boolean = when (index) {
            0 -> phone; 1 -> eyes; 2 -> handsOff; 3 -> yawning; 4 -> distracted
            5 -> eating; 6 -> posture; 7 -> slouching; else -> false
        }
        fun getByField(field: String): Boolean = when (field) {
            "driver_using_phone" -> phone
            "driver_eyes_closed" -> eyes
            "hands_off_wheel" -> handsOff
            "driver_yawning" -> yawning
            "driver_distracted" -> distracted
            "driver_eating_drinking" -> eating
            "dangerous_posture" -> posture
            "child_slouching" -> slouching
            else -> false
        }
        fun any(): Boolean = phone || eyes || handsOff || yawning || distracted || eating || posture || slouching
    }

    // --- Rear camera alert state ---
    private var prevRearResult: RearResult? = null

    // --- State ---
    private var prevDangers: DangerSnapshot? = null
    private var prevDriverDetected: Boolean? = null
    private var prevDuration = 0
    private val cooldownMap = HashMap<String, Long>()
    private val escalationMap = HashMap<String, EscalationState>()
    private var prevPassengerBadPosture = mutableSetOf<Int>()
    private var currentTtsLang = "en"
    private val retryHandler = Handler(Looper.getMainLooper())

    // Bounded priority queue
    private val messageQueue = ArrayBlockingQueue<AlertMessage>(Config.ALERT_QUEUE_CAPACITY + 1) // +1 for STOP sentinel
    private val STOP_SENTINEL = AlertMessage(AlertPriority.INFO, "", null, false, 0L)

    @Volatile private var ttsReady = false
    private var tts: TextToSpeech
    private var ttsRetried = false
    private val appContext: Context = context.applicationContext
    private val notificationManager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    /** Pre-built PCM beep buffer (1kHz sine wave, 1 second, 16-bit mono 44100Hz). */
    private val beepBuffer: ShortArray = run {
        val sampleRate = 44100
        val durationMs = Config.ALERT_BEEP_DURATION_MS
        val numSamples = sampleRate * durationMs / 1000
        val freq = 1000.0
        ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * kotlin.math.sin(2.0 * Math.PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    private val workerThread: Thread

    init {
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "InCabin Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Driver safety alert notifications"
        }
        notificationManager.createNotificationChannel(alertChannel)

        tts = TextToSpeech(appContext) { status -> onTtsInit(status) }

        workerThread = Thread({
            Log.i(TAG, "Worker thread started")
            while (true) {
                val msg = messageQueue.take()
                if (msg === STOP_SENTINEL) {
                    Log.i(TAG, "Worker received stop signal")
                    break
                }
                processAlertMessage(msg)
            }
        }, "AudioAlerter-Worker")
        workerThread.isDaemon = true
        workerThread.start()
    }

    private fun onTtsInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.8f)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts.setAudioAttributes(audioAttrs)
            ttsReady = true
            Log.i(TAG, "TTS initialized successfully (audioUsage=$audioUsage)")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
            if (!ttsRetried) {
                ttsRetried = true
                Log.i(TAG, "Scheduling TTS retry in 3 seconds")
                retryHandler.postDelayed({
                    Log.i(TAG, "Retrying TTS initialization")
                    tts.shutdown()
                    tts = TextToSpeech(appContext) { retryStatus ->
                        if (retryStatus == TextToSpeech.SUCCESS) {
                            tts.language = Locale.US
                            tts.setSpeechRate(0.8f)
                            val audioAttrs = AudioAttributes.Builder()
                                .setUsage(audioUsage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            tts.setAudioAttributes(audioAttrs)
                            ttsReady = true
                            Log.i(TAG, "TTS retry succeeded")
                        } else {
                            Log.e(TAG, "TTS retry failed with status: $retryStatus")
                        }
                    }
                }, 3000)
            }
        }
    }

    fun setLanguage(langCode: String) {
        if (!ttsReady) return
        val locale = if (langCode == "ja") Locale.JAPANESE else Locale.US
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS language $locale not available (result=$result), falling back to US")
            tts.setLanguage(Locale.US)
        } else {
            Log.i(TAG, "TTS language set to: $locale")
        }
    }

    // --- Worker thread: process one alert message ---

    private fun processAlertMessage(msg: AlertMessage) {
        // Staleness check: age
        val age = SystemClock.elapsedRealtime() - msg.createdAtMs
        if (age > Config.ALERT_STALENESS_MS) {
            Log.i(TAG, "Dropping stale message (age=${age}ms): ${msg.text}")
            return
        }

        // Staleness check: danger still active?
        if (msg.dangerField != null) {
            val latestResult = FrameHolder.getLatestResult()
            if (latestResult != null) {
                val stillActive = isDangerActive(latestResult, msg.dangerField)
                if (!stillActive) {
                    Log.i(TAG, "Dropping resolved message (${msg.dangerField}): ${msg.text}")
                    return
                }
            }
        }

        // Beep-then-speak coordination
        if (msg.playBeepFirst) {
            tts.stop()
            playBeepBlocking()
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }

        // Speak
        if (ttsReady) {
            val utteranceId = UUID.randomUUID().toString()
            tts.speak(msg.text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            Log.i(TAG, "Speaking: ${msg.text}")
        } else {
            Log.i(TAG, "[ALERT] ${msg.text}")
        }
    }

    private fun isDangerActive(result: OutputResult, field: String): Boolean = when (field) {
        "driver_using_phone" -> result.driverUsingPhone
        "driver_eyes_closed" -> result.driverEyesClosed
        "hands_off_wheel" -> result.handsOffWheel
        "driver_yawning" -> result.driverYawning
        "driver_distracted" -> result.driverDistracted
        "driver_eating_drinking" -> result.driverEatingDrinking
        "dangerous_posture" -> result.dangerousPosture
        "child_slouching" -> result.childSlouching
        "no_driver_detected" -> !result.driverDetected && result.passengerCount > 0
        else -> false
    }

    private fun playBeepBlocking() {
        var track: AudioTrack? = null
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(beepBuffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(beepBuffer, 0, beepBuffer.size)
            track.play()
            Thread.sleep(Config.ALERT_BEEP_DURATION_MS.toLong())
            Log.i(TAG, "Beep playback completed")
        } catch (e: Exception) {
            Log.w(TAG, "Beep playback failed", e)
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    // --- Main thread: build alerts and enqueue ---

    fun checkAndAnnounce(result: OutputResult) {
        // Auto-sync TTS locale
        val lang = Config.LANGUAGE
        if (lang != currentTtsLang) {
            currentTtsLang = lang
            setLanguage(lang)
        }

        val duration = result.distractionDurationS
        val current = DangerSnapshot(
            result.driverUsingPhone, result.driverEyesClosed, result.handsOffWheel,
            result.driverYawning,
            result.driverDistracted, result.driverEatingDrinking,
            result.dangerousPosture, result.childSlouching
        )

        // First frame: store state only
        if (prevDangers == null) {
            prevDangers = current
            prevDriverDetected = result.driverDetected
            return
        }

        val prev = prevDangers!!
        val now = SystemClock.elapsedRealtime()
        val isJapanese = Config.LANGUAGE == "ja"

        // --- No-driver alert (before DangerSnapshot logic) ---
        if (shouldAlertNoDriver(result.driverDetected, result.passengerCount, prevDriverDetected, now, cooldownMap)) {
            val noDriverText = if (isJapanese) "運転者未検出" else "No driver detected"
            val noDriverAlert = AlertMessage(AlertPriority.WARNING, noDriverText, null, false, now)
            enqueueWithPriority(noDriverAlert)
            cooldownMap["no_driver_detected"] = now
            try {
                postAlertNotification(noDriverText, "medium")
            } catch (e: Exception) {
                Log.w(TAG, "Notification posting failed, TTS unaffected", e)
            }
        }

        // All-clear flush: drain queue + stop TTS before building the all-clear message
        // Suppress "All clear" when dangers drop because driver left frame
        val isAllClear = prev.any() && !current.any() && result.driverDetected
        if (isAllClear) {
            drainQueue()
            tts.stop()
        }

        val alerts = buildAlerts(current, prev, duration, prevDuration, now, cooldownMap, escalationMap, isJapanese, result.driverDetected)

        // Enqueue alerts
        for (alert in alerts) {
            enqueueWithPriority(alert)
            try {
                postAlertNotification(alert.text, result.riskLevel)
            } catch (e: Exception) {
                Log.w(TAG, "Notification posting failed, TTS unaffected", e)
            }
        }

        // Dismiss notification when all dangers clear
        if (!current.any()) {
            try {
                notificationManager.cancel(ALERT_NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Notification cancel failed", e)
            }
        }

        // Reset escalation on duration drop to 0
        if (duration == 0 && prevDuration > 0) {
            escalationMap.clear()
        }

        prevDangers = current
        prevDriverDetected = result.driverDetected
        prevDuration = duration
    }

    private fun drainQueue() {
        var drained = 0
        while (messageQueue.poll() != null) { drained++ }
        if (drained > 0) Log.i(TAG, "Drained $drained messages from queue")
    }

    /**
     * Enqueue with priority insertion. If queue is full, drain → sort by priority → keep
     * top (CAPACITY-1) → add new message → re-insert all.
     */
    private fun enqueueWithPriority(alert: AlertMessage) {
        if (messageQueue.offer(alert)) return

        // Queue full — drain, sort, keep highest priority
        val existing = mutableListOf<AlertMessage>()
        messageQueue.drainTo(existing)
        existing.add(alert)
        existing.sortByDescending { it.priority.ordinal }

        // Keep top CAPACITY messages
        val keep = existing.take(Config.ALERT_QUEUE_CAPACITY)
        val dropped = existing.size - keep.size
        if (dropped > 0) Log.i(TAG, "Dropped $dropped low-priority messages")

        for (msg in keep) {
            messageQueue.offer(msg)
        }
    }

    private fun postAlertNotification(message: String, riskLevel: String) {
        val title = when (riskLevel) {
            "high" -> "HIGH RISK Alert"
            "medium" -> "Medium Risk Alert"
            else -> "InCabin Alert"
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(appContext, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message.replaceFirstChar { it.uppercaseChar() })
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        Log.i(TAG, "Posted notification: $message")
    }

    /**
     * Check per-passenger posture transitions and enqueue TTS alerts for newly bad postures.
     * Separated from driver DangerSnapshot path — no schema changes.
     */
    fun checkPassengerPostures(postures: List<FrameHolder.PassengerPosture>) {
        val now = SystemClock.elapsedRealtime()
        val isJapanese = Config.LANGUAGE == "ja"
        val currentBad = mutableSetOf<Int>()

        for (p in postures) {
            if (p.hasBadPosture) currentBad.add(p.index)

            if (shouldAlertPassengerPosture(p.index, p.hasBadPosture, prevPassengerBadPosture, now, cooldownMap)) {
                val text = if (isJapanese) "同乗者${p.index}: 姿勢不良を検出"
                           else "Co-passenger ${p.index}: bad posture detected"
                val alert = AlertMessage(AlertPriority.WARNING, text, null, false, now)
                enqueueWithPriority(alert)
                cooldownMap["passenger_posture_${p.index}"] = now
                Log.i(TAG, "Passenger posture alert: $text")
            }
        }

        prevPassengerBadPosture = currentBad
    }

    /**
     * Enqueue a welcome message (e.g. "Welcome, Alice") at INFO priority.
     * Used for driver greeting on first recognition in a session.
     */
    fun enqueueWelcome(text: String) {
        val msg = AlertMessage(AlertPriority.INFO, text, null, false, SystemClock.elapsedRealtime())
        enqueueWithPriority(msg)
    }

    /**
     * Enqueue a critical alert that bypasses per-danger cooldown.
     * Used for safety-critical one-shot alerts (e.g., child left behind, emergency override).
     */
    fun enqueueCritical(text: String) {
        val msg = AlertMessage(AlertPriority.CRITICAL, text, null, true, SystemClock.elapsedRealtime())
        enqueueWithPriority(msg)
    }

    /**
     * Check for rear camera alert transitions. Called from InCabinService rear pipeline.
     * Person behind vehicle → CRITICAL (immediate danger).
     * Cat/dog behind vehicle → WARNING.
     */
    fun checkRearAlerts(result: RearResult) {
        val prev = prevRearResult
        prevRearResult = result
        if (prev == null) return  // first frame, store only

        val isJapanese = Config.LANGUAGE == "ja"
        val now = SystemClock.elapsedRealtime()

        // Person detected (CRITICAL — immediate collision risk)
        if (result.personDetected && !prev.personDetected) {
            val text = if (isJapanese) "後方に人を検出" else "Person behind vehicle"
            enqueueWithPriority(AlertMessage(AlertPriority.CRITICAL, text, null, true, now))
        }

        // Cat/dog detected (WARNING)
        if ((result.catDetected || result.dogDetected) &&
            !(prev.catDetected || prev.dogDetected)
        ) {
            val text = if (isJapanese) "後方に動物を検出" else "Animal behind vehicle"
            enqueueWithPriority(AlertMessage(AlertPriority.WARNING, text, null, false, now))
        }
    }

    /** Reset rear alert state (e.g., when rear camera stops). */
    fun resetRearState() {
        prevRearResult = null
    }

    /**
     * Reset alert state so the next frame is treated as "first frame" (store-only).
     * Call when re-enabling audio alerts after a period of silence to avoid
     * false onset/all-clear from stale prevDangers.
     */
    fun resetState() {
        prevDangers = null
        prevDriverDetected = null
        prevDuration = 0
        cooldownMap.clear()
        escalationMap.clear()
        prevPassengerBadPosture.clear()
        prevRearResult = null
    }

    fun close() {
        retryHandler.removeCallbacksAndMessages(null)
        // Use offer to avoid blocking if queue is full; worst case worker exits on thread interrupt
        if (!messageQueue.offer(STOP_SENTINEL)) {
            messageQueue.clear()
            messageQueue.offer(STOP_SENTINEL)
        }
        tts.stop()
        tts.shutdown()
        Log.i(TAG, "AudioAlerter closed")
    }

}
