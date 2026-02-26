package com.incabin

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP polling client for remote VLM inference.
 * Polls a VLM server's /api/detect endpoint at a configurable interval,
 * parses the JSON response into OutputResult, and delivers via callback.
 *
 * Pattern follows MjpegCameraManager: background thread, @Volatile running,
 * exponential backoff on error.
 */
class VlmClient(
    private val serverUrl: String,
    private val onResult: (OutputResult) -> Unit
) {
    companion object {
        private const val TAG = "InCabin-VLM"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000

        private val gson = Gson()

        /**
         * Parse a VLM /api/detect JSON response into an OutputResult.
         * Returns null on malformed input. Pure function — fully unit-testable.
         */
        fun parseDetectResponse(json: String): OutputResult? {
            return try {
                val obj = gson.fromJson(json, JsonObject::class.java) ?: return null

                // Validate required fields exist and have correct types
                val timestamp = obj.get("timestamp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
                val passengerCount = obj.get("passenger_count")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return null
                if (passengerCount < 0) return null

                val driverDetected = obj.get("driver_detected")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
                val riskLevel = obj.get("risk_level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
                if (riskLevel !in setOf("low", "medium", "high")) return null

                // Boolean fields — all required
                fun boolField(name: String): Boolean? {
                    val elem = obj.get(name) ?: return null
                    if (!elem.isJsonPrimitive || !elem.asJsonPrimitive.isBoolean) return null
                    return elem.asBoolean
                }

                val driverUsingPhone = boolField("driver_using_phone") ?: return null
                val driverEyesClosed = boolField("driver_eyes_closed") ?: return null
                val driverYawning = boolField("driver_yawning") ?: return null
                val driverDistracted = boolField("driver_distracted") ?: return null
                val driverEatingDrinking = boolField("driver_eating_drinking") ?: return null
                val handsOffWheel = boolField("hands_off_wheel") ?: false
                val dangerousPosture = boolField("dangerous_posture") ?: return null
                val childPresent = boolField("child_present") ?: return null
                val childSlouching = boolField("child_slouching") ?: return null

                // Optional integer fields
                val childCount = obj.get("child_count")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
                val adultCount = obj.get("adult_count")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0

                // Optional nullable float fields
                fun nullableFloat(name: String): Float? {
                    val elem = obj.get(name) ?: return null
                    if (elem.isJsonNull) return null
                    if (!elem.isJsonPrimitive || !elem.asJsonPrimitive.isNumber) return null
                    return elem.asFloat
                }

                val earValue = nullableFloat("ear_value")
                val marValue = nullableFloat("mar_value")
                val headYaw = nullableFloat("head_yaw")
                val headPitch = nullableFloat("head_pitch")

                // Optional nullable string
                val driverName = obj.get("driver_name")?.let {
                    if (it.isJsonNull) null
                    else if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString
                    else null
                }

                // distraction_duration_s is always 0 — computed on-device
                OutputResult(
                    timestamp = timestamp,
                    passengerCount = passengerCount,
                    childCount = childCount,
                    adultCount = adultCount,
                    driverUsingPhone = driverUsingPhone,
                    driverEyesClosed = driverEyesClosed,
                    driverYawning = driverYawning,
                    driverDistracted = driverDistracted,
                    driverEatingDrinking = driverEatingDrinking,
                    handsOffWheel = handsOffWheel,
                    dangerousPosture = dangerousPosture,
                    childPresent = childPresent,
                    childSlouching = childSlouching,
                    riskLevel = riskLevel,
                    earValue = earValue,
                    marValue = marValue,
                    headYaw = headYaw,
                    headPitch = headPitch,
                    distractionDurationS = 0,
                    driverName = driverName,
                    driverDetected = driverDetected
                )
            } catch (e: JsonSyntaxException) {
                null
            } catch (e: Exception) {
                null
            }
        }

        /** Construct the /api/health URL from a base server URL. */
        fun buildHealthUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return "$base/api/health"
        }

        /** Construct the /api/detect URL from a base server URL. */
        fun buildDetectUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return "$base/api/detect"
        }

        /**
         * One-shot health check (no VlmClient instance needed).
         * Blocking call — run on a background thread.
         * Returns parsed status or null on failure.
         */
        fun checkHealthOnce(serverUrl: String): VlmHealthStatus? {
            return try {
                val healthUrl = buildHealthUrl(serverUrl)
                val connection = URL(healthUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"

                try {
                    connection.connect()
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val body = BufferedReader(InputStreamReader(connection.inputStream)).use {
                            it.readText()
                        }
                        val obj = gson.fromJson(body, JsonObject::class.java)
                        VlmHealthStatus(
                            status = obj.get("status")?.asString ?: "unknown",
                            model = obj.get("model")?.asString,
                            fps = obj.get("fps")?.asDouble
                        )
                    } else null
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Health check result from /api/health endpoint. */
    data class VlmHealthStatus(val status: String, val model: String?, val fps: Double?)

    @Volatile private var running = false
    private var pollThread: Thread? = null

    /** Start the polling thread. */
    fun start() {
        if (running) return
        running = true

        pollThread = Thread({
            var backoffMs = Config.V4L2_RECONNECT_INITIAL_DELAY_MS
            var wasConnected = false

            while (running) {
                try {
                    val detectUrl = buildDetectUrl(serverUrl)
                    val connection = URL(detectUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.requestMethod = "GET"

                    try {
                        connection.connect()
                        val responseCode = connection.responseCode

                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            val body = BufferedReader(InputStreamReader(connection.inputStream)).use {
                                it.readText()
                            }
                            val result = parseDetectResponse(body)
                            if (result != null) {
                                if (!wasConnected) {
                                    Log.i(TAG, "VLM server connected: $serverUrl")
                                    FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
                                    wasConnected = true
                                }
                                backoffMs = Config.V4L2_RECONNECT_INITIAL_DELAY_MS
                                onResult(result)
                            } else {
                                Log.w(TAG, "Malformed VLM response, skipping")
                            }
                        } else {
                            Log.w(TAG, "VLM server HTTP $responseCode")
                            handleError(wasConnected, backoffMs)
                            backoffMs = MjpegCameraManager.nextBackoffDelay(backoffMs, Config.V4L2_RECONNECT_MAX_DELAY_MS)
                            continue
                        }
                    } finally {
                        connection.disconnect()
                    }

                    // Sleep for poll interval between successful requests (dynamic from FPS setting)
                    if (running) {
                        Thread.sleep(Config.inferenceIntervalMs())
                    }
                } catch (e: InterruptedException) {
                    // stop() interrupted us
                    break
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "VLM poll error: ${e.message}")
                        handleError(wasConnected, backoffMs)
                        backoffMs = MjpegCameraManager.nextBackoffDelay(backoffMs, Config.V4L2_RECONNECT_MAX_DELAY_MS)
                        if (!wasConnected) {
                            FrameHolder.postCameraStatus(FrameHolder.CameraStatus.NOT_CONNECTED)
                        } else {
                            FrameHolder.postCameraStatus(FrameHolder.CameraStatus.LOST)
                            wasConnected = false
                        }
                    }
                }
            }

            Log.i(TAG, "VLM polling stopped")
        }, "VLM-Poller")
        pollThread?.isDaemon = true
        pollThread?.start()
    }

    /** Stop the polling thread. */
    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        Log.i(TAG, "VLM client stopped")
    }

    /** Check server health (blocking call). Delegates to companion. */
    fun checkHealth(): VlmHealthStatus? = checkHealthOnce(serverUrl)

    private fun handleError(wasConnected: Boolean, delayMs: Long) {
        if (!running) return
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            // stop() interrupted us
        }
    }
}
