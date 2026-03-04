package com.incabin

/**
 * Named cabin theme — bundles temperature, brightness, night mode, and ambient color
 * into a single-tap selection. Replaces individual color picker + temp slider.
 *
 * Pure data class with no Android dependencies. Theme application is in [ThemeApplier].
 */
data class CabinTheme(
    val id: String,
    val displayName: String,
    val displayNameJa: String,
    val tempC: Float,
    val brightness: Int,       // 0-255
    val nightMode: Int,        // -1=auto, 1=off, 2=on (UiModeManager constants)
    val ambientColorHex: String,
    val description: String,
    val descriptionJa: String
) {
    companion object {
        val THEMES: List<CabinTheme> = listOf(
            CabinTheme(
                id = "comfort",
                displayName = "Comfort",
                displayNameJa = "コンフォート",
                tempC = 22.0f,
                brightness = 200,
                nightMode = -1,
                ambientColorHex = "#5B8DEF",
                description = "Balanced defaults",
                descriptionJa = "バランスの取れた設定"
            ),
            CabinTheme(
                id = "energize",
                displayName = "Energize",
                displayNameJa = "エナジャイズ",
                tempC = 20.0f,
                brightness = 255,
                nightMode = 1,
                ambientColorHex = "#2ECC71",
                description = "Bright, cool, alert",
                descriptionJa = "明るく、涼しく、集中"
            ),
            CabinTheme(
                id = "relax",
                displayName = "Relax",
                displayNameJa = "リラックス",
                tempC = 24.0f,
                brightness = 150,
                nightMode = 2,
                ambientColorHex = "#9B59B6",
                description = "Warm, dim, calm",
                descriptionJa = "暖かく、穏やかに"
            ),
            CabinTheme(
                id = "night_drive",
                displayName = "Night Drive",
                displayNameJa = "ナイトドライブ",
                tempC = 21.0f,
                brightness = 80,
                nightMode = 2,
                ambientColorHex = "#E74C3C",
                description = "Low glare",
                descriptionJa = "まぶしさ軽減"
            ),
            CabinTheme(
                id = "eco",
                displayName = "Eco",
                displayNameJa = "エコ",
                tempC = 26.0f,
                brightness = 180,
                nightMode = -1,
                ambientColorHex = "#1ABC9C",
                description = "Energy-saving",
                descriptionJa = "省エネルギー"
            )
        )

        /** Find a theme by ID. Returns null if not found. */
        fun findById(id: String): CabinTheme? = THEMES.firstOrNull { it.id == id }

        /** Default theme (Comfort). */
        fun defaultTheme(): CabinTheme = THEMES[0]
    }
}
