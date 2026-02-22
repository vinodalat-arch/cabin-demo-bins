package com.incabin

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Custom TestRule that:
 * 1. Grants permissions via UiAutomation (works on AAOS)
 * 2. Resets FrameHolder, Config defaults, and SharedPreferences before each test
 * 3. Launches ActivityScenario<MainActivity>
 * 4. Provides access to the scenario for assertions
 * 5. Cleans up after each test
 */
class InCabinTestRule : TestRule {

    lateinit var scenario: ActivityScenario<MainActivity>
        private set

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                // --- Grant permissions via UiAutomation (reliable on AAOS) ---
                grantPermissions()

                // --- Setup ---
                resetState()

                scenario = ActivityScenario.launch(MainActivity::class.java)

                try {
                    base.evaluate()
                } finally {
                    // --- Teardown ---
                    try {
                        scenario.close()
                    } catch (_: Exception) { }
                    resetState()
                }
            }
        }
    }

    /**
     * Runs a block with the Activity instance on the main thread.
     */
    fun onActivity(block: (MainActivity) -> Unit) {
        scenario.onActivity { activity ->
            block(activity)
        }
    }

    private fun grantPermissions() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        try { uiAutomation.grantRuntimePermission(pkg, Manifest.permission.CAMERA) } catch (_: Exception) { }
        try { uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { }
    }

    private fun resetState() {
        // Reset FrameHolder singleton
        FrameHolder.clear()

        // Reset Config toggleable properties to defaults
        Config.ENABLE_PREVIEW = false
        Config.ENABLE_AUDIO_ALERTS = true
        Config.LANGUAGE = "en"
        Config.DRIVER_SEAT_SIDE = "left"
        Config.WIFI_CAMERA_URL = ""

        // Reset TestResultFactory counter
        TestResultFactory.reset()

        // Clear SharedPreferences
        try {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.getSharedPreferences("incabin_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
        } catch (_: Exception) { }
    }
}
