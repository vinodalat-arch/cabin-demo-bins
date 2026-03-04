package com.incabin

/**
 * Enhanced session summary with posture score, comfort events, and badges.
 * Pure data class + companion functions — no Android dependencies.
 */
data class TripStats(
    val durationMs: Long,
    val totalFrames: Int,
    val uprightFrames: Int,
    val detectionCounts: Map<String, Int>,
    val avgScore: Int,
    val bestStreakMs: Long,
    val comfortEvents: Int,
    val wellnessMilestones: Int,
    val nightDrive: Boolean,
    val maxPassengers: Int
) {
    companion object {
        fun computePosturePercent(uprightFrames: Int, totalFrames: Int): Int {
            if (totalFrames <= 0) return 0
            return ((uprightFrames.toLong() * 100) / totalFrames).toInt().coerceIn(0, 100)
        }

        fun awardBadges(stats: TripStats): List<Badge> {
            val badges = mutableListOf<Badge>()
            val durationMin = (stats.durationMs / 60_000).toInt()

            // ZEN_DRIVER: 0 distractions for 30+ continuous minutes
            if (durationMin >= 30 && stats.bestStreakMs >= 30 * 60_000L) {
                badges.add(Badge.ZEN_DRIVER)
            }

            // NIGHT_OWL: drove between 22:00-05:00
            if (stats.nightDrive) {
                badges.add(Badge.NIGHT_OWL)
            }

            // ROAD_TRIP_PRO: 4+ passengers for 2+ hours
            if (stats.maxPassengers >= 4 && durationMin >= 120) {
                badges.add(Badge.ROAD_TRIP_PRO)
            }

            // PERFECT_POSTURE: >90% upright frames
            if (computePosturePercent(stats.uprightFrames, stats.totalFrames) > 90) {
                badges.add(Badge.PERFECT_POSTURE)
            }

            // COMFORT_CAPTAIN: 5+ comfort events triggered
            if (stats.comfortEvents >= 5) {
                badges.add(Badge.COMFORT_CAPTAIN)
            }

            // MARATHON: drove 3+ hours
            if (durationMin >= 180) {
                badges.add(Badge.MARATHON)
            }

            // REFRESHED: took a fatigue break and recovered (wellness milestone 2+ reached)
            if (stats.wellnessMilestones >= 2) {
                badges.add(Badge.REFRESHED)
            }

            // FAMILY_DRIVE: child present for 30+ minutes (approximated by detection counts)
            val childFrames = stats.detectionCounts.getOrDefault("child_present", 0)
            if (childFrames >= 30) {  // ~30 frames at 1fps = ~30 min
                badges.add(Badge.FAMILY_DRIVE)
            }

            return badges
        }

        fun formatSummary(stats: TripStats, badges: List<Badge>, isJapanese: Boolean): String {
            val durationMin = (stats.durationMs / 60_000).toInt()
            val posture = computePosturePercent(stats.uprightFrames, stats.totalFrames)

            val sb = StringBuilder()
            if (isJapanese) {
                sb.appendLine("走行時間: ${durationMin}分")
                sb.appendLine("姿勢スコア: ${posture}%")
                sb.appendLine("快適イベント: ${stats.comfortEvents}回")
                if (badges.isNotEmpty()) {
                    sb.appendLine("バッジ: ${badges.joinToString(", ") { it.displayNameJa }}")
                }
            } else {
                sb.appendLine("Trip duration: ${durationMin} min")
                sb.appendLine("Posture score: ${posture}%")
                sb.appendLine("Comfort events: ${stats.comfortEvents}")
                if (badges.isNotEmpty()) {
                    sb.appendLine("Badges: ${badges.joinToString(", ") { it.displayName }}")
                }
            }
            return sb.toString().trimEnd()
        }
    }
}

enum class Badge(val displayName: String, val displayNameJa: String) {
    ZEN_DRIVER("Zen Driver", "禅ドライバー"),
    NIGHT_OWL("Night Owl", "夜行性"),
    ROAD_TRIP_PRO("Road Trip Pro", "ロードトリップ達人"),
    PERFECT_POSTURE("Perfect Posture", "完璧な姿勢"),
    COMFORT_CAPTAIN("Comfort Captain", "快適キャプテン"),
    MARATHON("Marathon", "マラソン"),
    REFRESHED("Refreshed", "リフレッシュ"),
    FAMILY_DRIVE("Family Drive", "ファミリードライブ")
}
