package com.incabin

/**
 * Lightweight detection result for rear-view camera.
 * Only tracks person/cat/dog — no face analysis or driver state.
 */
data class RearResult(
    val timestamp: String,
    val personDetected: Boolean,
    val personCount: Int,
    val catDetected: Boolean,
    val dogDetected: Boolean,
    val riskLevel: String  // "clear", "caution", "danger"
) {
    fun toJson(): String {
        val sb = StringBuilder(192)
        sb.append("{\"timestamp\":\"").append(timestamp)
        sb.append("\",\"person_detected\":").append(personDetected)
        sb.append(",\"person_count\":").append(personCount)
        sb.append(",\"cat_detected\":").append(catDetected)
        sb.append(",\"dog_detected\":").append(dogDetected)
        sb.append(",\"risk_level\":\"").append(riskLevel).append('"')
        sb.append('}')
        return sb.toString()
    }

    companion object {
        private val VALID_RISK_LEVELS = setOf("clear", "caution", "danger")

        fun default(): RearResult = RearResult(
            timestamp = java.time.Instant.now().toString(),
            personDetected = false,
            personCount = 0,
            catDetected = false,
            dogDetected = false,
            riskLevel = "clear"
        )

        /**
         * Compute rear risk level from detections.
         * Person → "danger", cat/dog only → "caution", none → "clear".
         */
        fun computeRisk(personDetected: Boolean, catDetected: Boolean, dogDetected: Boolean): String {
            return when {
                personDetected -> "danger"
                catDetected || dogDetected -> "caution"
                else -> "clear"
            }
        }

        fun validate(data: Map<String, Any?>): List<String> {
            val errors = mutableListOf<String>()
            val requiredFields = listOf(
                "timestamp", "person_detected", "person_count",
                "cat_detected", "dog_detected", "risk_level"
            )
            for (field in requiredFields) {
                if (field !in data) errors.add("Missing required field: $field")
            }
            data["risk_level"]?.let {
                if (it !is String || it !in VALID_RISK_LEVELS) {
                    errors.add("risk_level must be one of: clear, caution, danger")
                }
            }
            data["person_count"]?.let {
                val v = (it as? Number)?.toInt()
                if (v == null) errors.add("person_count must be an integer")
                else if (v < 0) errors.add("person_count must be >= 0")
            }
            val boolFields = listOf("person_detected", "cat_detected", "dog_detected")
            for (field in boolFields) {
                data[field]?.let {
                    if (it !is Boolean) errors.add("$field must be a boolean")
                }
            }
            return errors
        }
    }
}
