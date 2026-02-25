package com.incabin

import com.incabin.AudioAlerter.AlertMessage
import com.incabin.AudioAlerter.AlertPriority
import com.incabin.AudioAlerter.DangerSnapshot
import com.incabin.AudioAlerter.EscalationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow integration tests for the full escalation sequence through
 * AudioAlerter.buildAlerts() with advancing timestamps.
 * Tests onset → escalation ladder → all-clear → multi-danger → cooldown.
 */
class FlowEscalationTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val CLEAR = DangerSnapshot(false, false, false, false, false, false, false)
    private val NOW = 100_000L

    private fun snap(
        phone: Boolean = false, eyes: Boolean = false, yawning: Boolean = false,
        distracted: Boolean = false, eating: Boolean = false, posture: Boolean = false,
        slouching: Boolean = false
    ) = DangerSnapshot(phone, eyes, yawning, distracted, eating, posture, slouching)

    private fun build(
        current: DangerSnapshot, prev: DangerSnapshot,
        duration: Int = 0, prevDuration: Int = 0,
        nowMs: Long = NOW,
        cooldownMap: MutableMap<String, Long> = mutableMapOf(),
        escalationMap: MutableMap<String, EscalationState> = mutableMapOf(),
        isJapanese: Boolean = false
    ) = AudioAlerter.buildAlerts(current, prev, duration, prevDuration, nowMs, cooldownMap, escalationMap, isJapanese)

    // -------------------------------------------------------------------------
    // Full Escalation Sequence (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_escalation_10s_warning_no_beep() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        val alert = AudioAlerter.buildEscalationAlert(10, NOW, escalationMap, false)
        assertTrue("Should produce escalation at 10s", alert != null)
        assertEquals(AlertPriority.WARNING, alert!!.priority)
        assertTrue(alert.text.contains("10 seconds"))
        assertFalse("No beep at 10s", alert.playBeepFirst)
    }

    @Test
    fun test_escalation_20s_critical_with_beep() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        // First hit 10s threshold
        AudioAlerter.buildEscalationAlert(10, NOW, escalationMap, false)
        // Then 20s
        val alert = AudioAlerter.buildEscalationAlert(20, NOW + 10_000, escalationMap, false)
        assertTrue("Should produce escalation at 20s", alert != null)
        assertEquals(AlertPriority.CRITICAL, alert!!.priority)
        assertTrue(alert.text.contains("20 seconds"))
        assertTrue("Beep at 20s", alert.playBeepFirst)
    }

    @Test
    fun test_escalation_30s_beep_repeat() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        // Progress through 10s, 20s, then 30s
        AudioAlerter.buildEscalationAlert(10, NOW, escalationMap, false)
        AudioAlerter.buildEscalationAlert(20, NOW + 10_000, escalationMap, false)
        val alert = AudioAlerter.buildEscalationAlert(30, NOW + 20_000, escalationMap, false)
        assertTrue("Should produce escalation at 30s", alert != null)
        assertEquals(AlertPriority.CRITICAL, alert!!.priority)
        assertTrue("Beep repeats at 30s", alert.playBeepFirst)
        assertTrue(alert.text.contains("30"))
    }

    // -------------------------------------------------------------------------
    // Escalation Reset on All-Clear (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_escalation_resets_on_all_clear() {
        val cooldownMap = mutableMapOf<String, Long>()
        val escalationMap = mutableMapOf<String, EscalationState>()

        // Onset
        build(snap(phone = true), CLEAR, 0, 0, NOW, cooldownMap, escalationMap)
        // Escalation at 10s
        build(snap(phone = true), snap(phone = true), 10, 5, NOW + 10_000, cooldownMap, escalationMap)

        // All-clear
        val alerts = build(CLEAR, snap(phone = true), 0, 10, NOW + 15_000, cooldownMap, escalationMap)
        assertEquals(1, alerts.size)
        assertEquals("All clear", alerts[0].text)
        assertTrue("Cooldown map should be cleared", cooldownMap.isEmpty())
        assertTrue("Escalation map should be cleared", escalationMap.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Multi-Danger Onset (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_multi_danger_onset_then_escalation() {
        val cooldownMap = mutableMapOf<String, Long>()
        val escalationMap = mutableMapOf<String, EscalationState>()

        // Phone + eyes onset simultaneously
        val alerts = build(snap(phone = true, eyes = true), CLEAR, 0, 0, NOW, cooldownMap, escalationMap)
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        // Both CRITICAL parts joined: "Phone. Eyes closed"
        assertTrue("Should contain Phone", alerts[0].text.contains("Phone detected"))
        assertTrue("Should contain Eyes closed", alerts[0].text.contains("Eyes closed, please stay alert"))
    }

    // -------------------------------------------------------------------------
    // Cooldown Tests (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_cooldown_prevents_re_announcement_within_10s() {
        val cooldownMap = mutableMapOf<String, Long>()
        val escalationMap = mutableMapOf<String, EscalationState>()

        // First onset
        build(snap(phone = true), CLEAR, 0, 0, NOW, cooldownMap, escalationMap)
        // Phone off then back on within 10s
        val alerts = build(snap(phone = true), CLEAR, 0, 0, NOW + 5_000, cooldownMap, escalationMap)
        assertTrue("Should be suppressed by cooldown", alerts.isEmpty())
    }

    @Test
    fun test_cooldown_expires_allows_re_announcement() {
        val cooldownMap = mutableMapOf<String, Long>()
        val escalationMap = mutableMapOf<String, EscalationState>()

        // First onset
        build(snap(phone = true), CLEAR, 0, 0, NOW, cooldownMap, escalationMap)
        // Phone off then back on after 11s (cooldown expired)
        val alerts = build(snap(phone = true), CLEAR, 0, 0, NOW + 11_000, cooldownMap, escalationMap)
        assertEquals(1, alerts.size)
        assertEquals("Phone detected, please put it down", alerts[0].text)
    }

    // -------------------------------------------------------------------------
    // All-Clear Clears Both Maps (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_all_clear_clears_cooldown_and_escalation() {
        val cooldownMap = mutableMapOf<String, Long>()
        val escalationMap = mutableMapOf<String, EscalationState>()

        // Build up state
        build(snap(phone = true), CLEAR, 0, 0, NOW, cooldownMap, escalationMap)
        assertTrue("Cooldown map should have entry", cooldownMap.isNotEmpty())

        // All-clear
        build(CLEAR, snap(phone = true), 0, 0, NOW + 5_000, cooldownMap, escalationMap)
        assertTrue("Cooldown map cleared on all-clear", cooldownMap.isEmpty())
        assertTrue("Escalation map cleared on all-clear", escalationMap.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Onset Suppresses Escalation (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_onset_suppresses_escalation_on_same_frame() {
        val cooldownMap = mutableMapOf<String, Long>()
        val escalationMap = mutableMapOf<String, EscalationState>()

        // Phone already active, eyes onset at exactly 10s duration
        // Onset for eyes should fire, not the escalation
        val alerts = build(
            snap(phone = true, eyes = true), snap(phone = true),
            10, 9, NOW, cooldownMap, escalationMap
        )
        assertEquals(1, alerts.size)
        // The alert is for the new eyes onset, not escalation
        assertTrue("Should be eyes onset, not escalation", alerts[0].text.contains("Eyes closed, please stay alert"))
    }

    // -------------------------------------------------------------------------
    // Beep Flag (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_beep_flag_only_at_20s_plus() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        val alert10 = AudioAlerter.buildEscalationAlert(10, NOW, escalationMap, false)
        assertFalse("No beep at 10s", alert10!!.playBeepFirst)

        val alert20 = AudioAlerter.buildEscalationAlert(20, NOW + 10_000, escalationMap, false)
        assertTrue("Beep at 20s", alert20!!.playBeepFirst)
    }

    // -------------------------------------------------------------------------
    // No Escalation Without Danger (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_escalation_when_no_danger() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        // Duration 0, no danger
        val alert = AudioAlerter.buildEscalationAlert(0, NOW, escalationMap, false)
        assertNull("No escalation at duration 0", alert)
    }

    // -------------------------------------------------------------------------
    // Japanese Escalation Messages (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_japanese_escalation_messages() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        val alert = AudioAlerter.buildEscalationAlert(10, NOW, escalationMap, true)
        assertTrue("Japanese 10s message", alert != null)
        assertTrue("Should contain Japanese text", alert!!.text.contains("秒"))
    }
}
