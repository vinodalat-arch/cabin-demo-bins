package com.incabin

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario

/**
 * Utility functions for instrumented UI tests.
 *
 * IMPORTANT: Functions that take ActivityScenario must be called from the TEST thread
 * (never from inside onActivity {}). Functions that take Activity directly are safe
 * to call from inside onActivity {} (they're already on the main thread).
 */
object UiTestUtils {

    /** Standard wait time: 500ms poll interval + 300ms margin. */
    const val POLL_WAIT_MS = 800L

    /** Extended wait for animations (color transitions, fades). AAOS emulator is slow. */
    const val ANIM_WAIT_MS = 2000L

    /** Short delay between taps in gesture sequences. */
    const val TAP_DELAY_MS = 100L

    /**
     * Posts an OutputResult to FrameHolder and waits for the UI poller to pick it up.
     * Call from the test thread.
     */
    fun postResultAndWait(result: OutputResult, waitMs: Long = POLL_WAIT_MS) {
        FrameHolder.postResult(result)
        Thread.sleep(waitMs)
    }

    /**
     * Enters monitoring UI state without starting the actual InCabinService.
     * Uses the internal test-only method to avoid Camera2/service crashes on emulator.
     * Call from the TEST thread (not inside onActivity).
     */
    fun enterMonitoringState(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.startMonitoringUiOnly()
        }
        Thread.sleep(500)
    }

    /**
     * Exits monitoring UI state without stopping the actual InCabinService.
     * Call from the TEST thread (not inside onActivity).
     */
    fun exitMonitoringState(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.stopMonitoringUiOnly()
        }
        Thread.sleep(500)
    }

    /**
     * Clicks a view by ID. Call from the TEST thread.
     */
    fun clickView(scenario: ActivityScenario<MainActivity>, viewId: Int) {
        scenario.onActivity { activity ->
            activity.findViewById<View>(viewId)?.performClick()
        }
    }

    /**
     * Performs the 5-tap gesture to open/toggle the settings panel.
     * Call from the TEST thread.
     */
    fun performFiveTapGesture(scenario: ActivityScenario<MainActivity>) {
        repeat(5) {
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rootLayout)?.performClick()
            }
            Thread.sleep(TAP_DELAY_MS)
        }
        Thread.sleep(500) // Wait for slide animation
    }

    /**
     * Performs N taps on the root layout. Call from the TEST thread.
     */
    fun performTaps(scenario: ActivityScenario<MainActivity>, count: Int) {
        repeat(count) {
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rootLayout)?.performClick()
            }
            Thread.sleep(TAP_DELAY_MS)
        }
    }

    // --- Direct-access helpers (safe inside onActivity {}) ---

    /** Gets text from a TextView. Call from main thread (inside onActivity). */
    fun getText(activity: Activity, viewId: Int): String {
        return activity.findViewById<TextView>(viewId)?.text?.toString() ?: ""
    }

    /** Gets visibility of a View. Call from main thread (inside onActivity). */
    fun getVisibility(activity: Activity, viewId: Int): Int {
        return activity.findViewById<View>(viewId)?.visibility ?: View.GONE
    }

    /** Gets text color of a TextView. Call from main thread (inside onActivity). */
    fun getTextColor(activity: Activity, viewId: Int): Int {
        return activity.findViewById<TextView>(viewId)?.currentTextColor ?: 0
    }

    /** Checks if a tagged view exists in a container. Call from main thread. */
    fun hasViewWithTag(activity: Activity, containerId: Int, tag: String): Boolean {
        val container = activity.findViewById<ViewGroup>(containerId)
        return container?.findViewWithTag<View>(tag) != null
    }

    /** Gets text from a tagged view in a container. Call from main thread. */
    fun getTaggedViewText(activity: Activity, containerId: Int, tag: String): String? {
        val container = activity.findViewById<ViewGroup>(containerId)
        return container?.findViewWithTag<TextView>(tag)?.text?.toString()
    }
}
