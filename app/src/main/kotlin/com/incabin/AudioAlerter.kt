package com.incabin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Audio alerter that announces danger state changes and distraction durations
 * via Android TextToSpeech. Messages are queued to a background worker thread
 * and spoken sequentially. At most one message is enqueued per inference cycle.
 */
class AudioAlerter(context: Context, private val audioUsage: Int = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {

    companion object {
        private const val TAG = "AudioAlerter"
        private const val ALERT_CHANNEL_ID = "incabin_alerts"
        private const val ALERT_NOTIFICATION_ID = 2

        val DANGER_FIELDS = listOf(
            "driver_using_phone",
            "driver_eyes_closed",
            "driver_yawning",
            "driver_distracted",
            "driver_eating_drinking",
            "dangerous_posture",
            "child_slouching"
        )

        val FRIENDLY_NAMES = mapOf(
            "driver_using_phone" to "phone detected",
            "driver_eyes_closed" to "eyes closed",
            "driver_yawning" to "yawning detected",
            "driver_distracted" to "driver distracted",
            "driver_eating_drinking" to "eating or drinking",
            "dangerous_posture" to "dangerous posture",
            "child_slouching" to "child slouching"
        )

        val FRIENDLY_NAMES_JA = mapOf(
            "driver_using_phone" to "スマホを検出しました",
            "driver_eyes_closed" to "目を閉じています",
            "driver_yawning" to "あくびを検出しました",
            "driver_distracted" to "よそ見をしています",
            "driver_eating_drinking" to "飲食を検出しました",
            "dangerous_posture" to "危険な姿勢です",
            "child_slouching" to "お子様の姿勢が悪いです"
        )
    }

    /** Snapshot of the previous frame's danger booleans (indexed by DANGER_FIELDS order). */
    private class DangerSnapshot(
        val phone: Boolean, val eyes: Boolean, val yawning: Boolean,
        val distracted: Boolean, val eating: Boolean, val posture: Boolean,
        val slouching: Boolean
    ) {
        fun get(index: Int): Boolean = when (index) {
            0 -> phone; 1 -> eyes; 2 -> yawning; 3 -> distracted
            4 -> eating; 5 -> posture; 6 -> slouching; else -> false
        }
        fun any(): Boolean = phone || eyes || yawning || distracted || eating || posture || slouching
    }

    private var prevDangers: DangerSnapshot? = null
    private val announcedDurations = mutableSetOf<Int>()
    @Volatile private var beepPlayed = false
    private var prevDuration = 0
    private var currentTtsLang = "en"  // Track current TTS locale to auto-sync with Config.LANGUAGE
    // H3: Store Handler so pending TTS retry can be cancelled in close()
    private val retryHandler = Handler(Looper.getMainLooper())

    private val messageQueue = LinkedBlockingQueue<String>()
    private val STOP_SIGNAL = "\u0000" // sentinel for worker shutdown

    @Volatile
    private var ttsReady = false
    private var tts: TextToSpeech
    private var ttsRetried = false
    private val appContext: Context = context.applicationContext
    private val notificationManager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    /** Pre-built PCM beep buffer (1kHz sine wave, 2 seconds, 16-bit mono 44100Hz). */
    private val beepBuffer: ShortArray = run {
        val sampleRate = 44100
        val durationMs = 2000
        val numSamples = sampleRate * durationMs / 1000
        val freq = 1000.0
        ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * kotlin.math.sin(2.0 * Math.PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    private val workerThread: Thread

    init {
        // Create high-importance channel for alert notifications
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "InCabin Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Driver safety alert notifications"
        }
        notificationManager.createNotificationChannel(alertChannel)

        tts = TextToSpeech(appContext) { status ->
            onTtsInit(status)
        }

        workerThread = Thread({
            Log.i(TAG, "Worker thread started")
            while (true) {
                val message = messageQueue.take() // blocks until available
                if (message == STOP_SIGNAL) {
                    Log.i(TAG, "Worker received stop signal")
                    break
                }
                if (ttsReady) {
                    val utteranceId = UUID.randomUUID().toString()
                    tts.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId)
                    Log.i(TAG, "Speaking: $message")
                } else {
                    Log.i(TAG, "[ALERT] $message")
                }
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

    /**
     * Examines the current [result], compares with previous state, and enqueues
     * an audio announcement if a state change warrants one.
     *
     * Must be called from the main/inference thread (not the TTS worker).
     */
    /** Update TTS locale when language changes at runtime. */
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

    fun checkAndAnnounce(result: OutputResult) {
        // Auto-sync TTS locale when Config.LANGUAGE changes at runtime
        val lang = Config.LANGUAGE
        if (lang != currentTtsLang) {
            currentTtsLang = lang
            setLanguage(lang)
        }

        val duration = result.distractionDurationS
        val current = DangerSnapshot(
            result.driverUsingPhone, result.driverEyesClosed, result.driverYawning,
            result.driverDistracted, result.driverEatingDrinking,
            result.dangerousPosture, result.childSlouching
        )

        // First frame: store state and return without speaking
        if (prevDangers == null) {
            prevDangers = current
            return
        }

        val prev = prevDangers!!
        val parts = mutableListOf<String>()

        // Step 1: New dangers activated (in DANGER_FIELDS order)
        val isJapanese = Config.LANGUAGE == "ja"
        val nameMap = if (isJapanese) FRIENDLY_NAMES_JA else FRIENDLY_NAMES
        for (i in DANGER_FIELDS.indices) {
            if (current.get(i) && !prev.get(i)) {
                nameMap[DANGER_FIELDS[i]]?.let { parts.add(it) }
            }
        }

        // Step 2: All-clear override
        val anyPrevDanger = prev.any()
        val anyCurrDanger = current.any()
        if (anyPrevDanger && !anyCurrDanger) {
            parts.clear()
            parts.add(if (isJapanese) "安全です" else "all clear")
        }

        // Step 3: Distraction duration (only if no other message)
        if (parts.isEmpty()) {
            for (threshold in Config.DISTRACTION_ALERT_THRESHOLDS) {
                if (duration >= threshold && threshold !in announcedDurations) {
                    announcedDurations.add(threshold)
                    parts.add(
                        if (isJapanese) "${threshold}秒間よそ見しています"
                        else "distracted $threshold seconds"
                    )
                    break // one per cycle
                }
            }
        }

        // Reset announced durations only on transition from distracted→clear
        // (not on every frame where duration==0, which caused double-beep)
        if (duration == 0 && prevDuration > 0) {
            announcedDurations.clear()
            beepPlayed = false
        }
        prevDuration = duration

        // Loud beep at 20s distraction threshold
        if (duration >= Config.DISTRACTION_BEEP_THRESHOLD_S && !beepPlayed) {
            beepPlayed = true
            Log.i(TAG, "Playing loud beep at ${Config.DISTRACTION_BEEP_THRESHOLD_S}s distraction")
            Thread({
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
                    Thread.sleep(2000)
                    Log.i(TAG, "Beep playback completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Beep playback failed", e)
                } finally {
                    try { track?.stop() } catch (_: Exception) {}
                    try { track?.release() } catch (_: Exception) {}
                }
            }, "Beep-Player").start()
        }

        // Enqueue message if we have anything to say
        if (parts.isNotEmpty()) {
            val message = parts.joinToString(". ")
            messageQueue.put(message)
            try {
                postAlertNotification(message, result.riskLevel)
            } catch (e: Exception) {
                Log.w(TAG, "Notification posting failed, TTS unaffected", e)
            }
        }

        // Dismiss alert notification when all dangers clear
        if (!anyCurrDanger) {
            try {
                notificationManager.cancel(ALERT_NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Notification cancel failed", e)
            }
        }

        // Update previous state
        prevDangers = current
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

    // extractDangers removed — replaced by DangerSnapshot to avoid per-frame Map allocation

    /**
     * Shuts down the TTS engine and stops the background worker thread.
     * Call this from service onDestroy or when the alerter is no longer needed.
     */
    fun close() {
        // H3: Cancel pending TTS retry callback to prevent leak after service destroyed
        retryHandler.removeCallbacksAndMessages(null)
        messageQueue.put(STOP_SIGNAL) // stop signal to worker
        tts.stop()
        tts.shutdown()
        Log.i(TAG, "AudioAlerter closed")
    }
}
