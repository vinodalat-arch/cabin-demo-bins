package com.incabin

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

data class OutputResult(
    @SerializedName("timestamp")
    val timestamp: String,

    @SerializedName("passenger_count")
    val passengerCount: Int,

    @SerializedName("driver_using_phone")
    val driverUsingPhone: Boolean,

    @SerializedName("driver_eyes_closed")
    val driverEyesClosed: Boolean,

    @SerializedName("driver_yawning")
    val driverYawning: Boolean,

    @SerializedName("driver_distracted")
    val driverDistracted: Boolean,

    @SerializedName("driver_eating_drinking")
    val driverEatingDrinking: Boolean,

    @SerializedName("dangerous_posture")
    val dangerousPosture: Boolean,

    @SerializedName("child_present")
    val childPresent: Boolean,

    @SerializedName("child_slouching")
    val childSlouching: Boolean,

    @SerializedName("risk_level")
    val riskLevel: String,

    @SerializedName("ear_value")
    val earValue: Float?,

    @SerializedName("mar_value")
    val marValue: Float?,

    @SerializedName("head_yaw")
    val headYaw: Float?,

    @SerializedName("head_pitch")
    val headPitch: Float?,

    @SerializedName("distraction_duration_s")
    val distractionDurationS: Int,

    @SerializedName("driver_name")
    val driverName: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    fun toMap(): Map<String, Any?> = mapOf(
        "timestamp" to timestamp,
        "passenger_count" to passengerCount,
        "driver_using_phone" to driverUsingPhone,
        "driver_eyes_closed" to driverEyesClosed,
        "driver_yawning" to driverYawning,
        "driver_distracted" to driverDistracted,
        "driver_eating_drinking" to driverEatingDrinking,
        "dangerous_posture" to dangerousPosture,
        "child_present" to childPresent,
        "child_slouching" to childSlouching,
        "risk_level" to riskLevel,
        "ear_value" to earValue,
        "mar_value" to marValue,
        "head_yaw" to headYaw,
        "head_pitch" to headPitch,
        "distraction_duration_s" to distractionDurationS,
        "driver_name" to driverName
    )

    companion object {
        private val gson = GsonBuilder().serializeNulls().create()

        private val VALID_RISK_LEVELS = setOf("low", "medium", "high")

        private val REQUIRED_FIELDS = listOf(
            "timestamp", "passenger_count",
            "driver_using_phone", "driver_eyes_closed", "driver_yawning",
            "driver_distracted", "driver_eating_drinking", "dangerous_posture",
            "child_present", "child_slouching", "risk_level", "distraction_duration_s"
        )

        private val ALL_FIELDS = REQUIRED_FIELDS + listOf(
            "ear_value", "mar_value", "head_yaw", "head_pitch", "driver_name"
        )

        fun fromJson(json: String): OutputResult = gson.fromJson(json, OutputResult::class.java)

        fun validate(data: Map<String, Any?>): List<String> {
            val errors = mutableListOf<String>()

            // Check required fields
            for (field in REQUIRED_FIELDS) {
                if (field !in data) {
                    errors.add("Missing required field: $field")
                }
            }

            // Check no additional properties
            for (key in data.keys) {
                if (key !in ALL_FIELDS) {
                    errors.add("Unexpected field: $key")
                }
            }

            // Type checks
            data["timestamp"]?.let {
                if (it !is String) errors.add("timestamp must be a string")
            }

            data["passenger_count"]?.let {
                val v = (it as? Number)?.toInt()
                if (v == null) errors.add("passenger_count must be an integer")
                else if (v < 0) errors.add("passenger_count must be >= 0")
            }

            val boolFields = listOf(
                "driver_using_phone", "driver_eyes_closed", "driver_yawning",
                "driver_distracted", "driver_eating_drinking", "dangerous_posture",
                "child_present", "child_slouching"
            )
            for (field in boolFields) {
                data[field]?.let {
                    if (it !is Boolean) errors.add("$field must be a boolean")
                }
            }

            data["risk_level"]?.let {
                if (it !is String || it !in VALID_RISK_LEVELS) {
                    errors.add("risk_level must be one of: low, medium, high")
                }
            }

            data["distraction_duration_s"]?.let {
                if (it !is Number) {
                    errors.add("distraction_duration_s must be an integer")
                } else if (it is Double || it is Float) {
                    val d = it.toDouble()
                    if (d != d.toLong().toDouble()) {
                        errors.add("distraction_duration_s must be an integer")
                    } else if (it.toInt() < 0) {
                        errors.add("distraction_duration_s must be >= 0")
                    }
                } else {
                    val v = it.toInt()
                    if (v < 0) errors.add("distraction_duration_s must be >= 0")
                }
            }

            // Optional nullable float fields
            val nullableFloatFields = listOf("ear_value", "mar_value", "head_yaw", "head_pitch")
            for (field in nullableFloatFields) {
                if (field in data) {
                    val v = data[field]
                    if (v != null && v !is Number) {
                        errors.add("$field must be a number or null")
                    }
                }
            }

            // Optional nullable string field
            if ("driver_name" in data) {
                val v = data["driver_name"]
                if (v != null && v !is String) {
                    errors.add("driver_name must be a string or null")
                }
            }

            return errors
        }

        fun default(): OutputResult = OutputResult(
            timestamp = java.time.Instant.now().toString(),
            passengerCount = 0,
            driverUsingPhone = false,
            driverEyesClosed = false,
            driverYawning = false,
            driverDistracted = false,
            driverEatingDrinking = false,
            dangerousPosture = false,
            childPresent = false,
            childSlouching = false,
            riskLevel = "low",
            earValue = null,
            marValue = null,
            headYaw = null,
            headPitch = null,
            distractionDurationS = 0,
            driverName = null
        )
    }
}
