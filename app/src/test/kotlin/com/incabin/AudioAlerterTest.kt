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
 * Unit tests for AudioAlerter's pure alert-building logic.
 * Tests the companion-object functions [AudioAlerter.buildAlerts] and
 * [AudioAlerter.buildEscalationAlert] which have no Android dependencies.
 */
class AudioAlerterTest {

    // --- Helpers ---

    private val CLEAR = DangerSnapshot(false, false, false, false, false, false, false, false)
    private val NOW = 100_000L

    private fun snap(
        phone: Boolean = false, eyes: Boolean = false, handsOff: Boolean = false,
        yawning: Boolean = false,
        distracted: Boolean = false, eating: Boolean = false, posture: Boolean = false,
        slouching: Boolean = false
    ) = DangerSnapshot(phone, eyes, handsOff, yawning, distracted, eating, posture, slouching)

    private fun build(
        current: DangerSnapshot, prev: DangerSnapshot,
        duration: Int = 0, prevDuration: Int = 0,
        nowMs: Long = NOW,
        cooldownMap: MutableMap<String, Long> = mutableMapOf(),
        escalationMap: MutableMap<String, EscalationState> = mutableMapOf(),
        isJapanese: Boolean = false
    ) = AudioAlerter.buildAlerts(current, prev, duration, prevDuration, nowMs, cooldownMap, escalationMap, isJapanese)

    // -------------------------------------------------------------------------
    // Onset Tests (8 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_new_single_critical_danger_phone() {
        val alerts = build(current = snap(phone = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        assertEquals("Phone detected, please put it down", alerts[0].text)
        assertNull(alerts[0].dangerField) // onset alerts bypass "danger still active?" check
        assertFalse(alerts[0].playBeepFirst)
    }

    @Test
    fun test_new_single_critical_danger_eyes() {
        val alerts = build(current = snap(eyes = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        assertEquals("Eyes closed, please stay alert", alerts[0].text)
    }

    @Test
    fun test_new_single_warning_danger_yawning() {
        val alerts = build(current = snap(yawning = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.WARNING, alerts[0].priority)
        assertEquals("Yawning detected, consider a break", alerts[0].text)
    }

    @Test
    fun test_new_warning_danger_eating() {
        val alerts = build(current = snap(eating = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals("Eating while driving, please focus", alerts[0].text)
    }

    @Test
    fun test_new_warning_danger_posture() {
        val alerts = build(current = snap(posture = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals("Dangerous posture detected", alerts[0].text)
    }

    @Test
    fun test_new_warning_danger_child_slouching() {
        val alerts = build(current = snap(slouching = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals("Child is slouching, please check", alerts[0].text)
    }

    @Test
    fun test_new_warning_danger_distracted() {
        val alerts = build(current = snap(distracted = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals("Distracted, please watch the road", alerts[0].text)
    }

    @Test
    fun test_no_change_no_alert() {
        val phoneOn = snap(phone = true)
        val alerts = build(current = phoneOn, prev = phoneOn)
        assertTrue(alerts.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Priority Ordering Tests (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_critical_plus_warning_critical_first() {
        val alerts = build(
            current = snap(phone = true, yawning = true),
            prev = CLEAR
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        // "Phone" (critical) should come before "Yawning" (warning)
        assertTrue(alerts[0].text.startsWith("Phone detected"))
        assertTrue(alerts[0].text.contains("Yawning detected"))
        assertEquals("Phone detected, please put it down. Yawning detected, consider a break", alerts[0].text)
    }

    @Test
    fun test_two_critical_dangers() {
        val alerts = build(
            current = snap(phone = true, eyes = true),
            prev = CLEAR
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        assertEquals("Phone detected, please put it down. Eyes closed, please stay alert", alerts[0].text)
    }

    @Test
    fun test_two_warning_dangers() {
        val alerts = build(
            current = snap(yawning = true, distracted = true),
            prev = CLEAR
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.WARNING, alerts[0].priority)
        assertEquals("Yawning detected, consider a break. Distracted, please watch the road", alerts[0].text)
    }

    // -------------------------------------------------------------------------
    // All-Clear Tests (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_all_clear_transition() {
        val alerts = build(
            current = CLEAR,
            prev = snap(phone = true)
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.INFO, alerts[0].priority)
        assertEquals("All clear", alerts[0].text)
        assertNull(alerts[0].dangerField)
        assertFalse(alerts[0].playBeepFirst)
    }

    @Test
    fun test_all_clear_clears_cooldown_map() {
        val cooldownMap = mutableMapOf("driver_using_phone" to NOW - 1000L)
        build(
            current = CLEAR,
            prev = snap(phone = true),
            cooldownMap = cooldownMap
        )
        assertTrue(cooldownMap.isEmpty())
    }

    @Test
    fun test_all_clear_clears_escalation_map() {
        val escalationMap = mutableMapOf("distraction" to EscalationState(10, 0))
        build(
            current = CLEAR,
            prev = snap(phone = true),
            escalationMap = escalationMap
        )
        assertTrue(escalationMap.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Cooldown Tests (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_danger_within_cooldown_no_alert() {
        val cooldownMap = mutableMapOf("driver_using_phone" to NOW - 5000L) // 5s ago, within 10s cooldown
        val alerts = build(
            current = snap(phone = true),
            prev = CLEAR,
            cooldownMap = cooldownMap
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun test_danger_after_cooldown_expires_alert() {
        val cooldownMap = mutableMapOf("driver_using_phone" to NOW - 11_000L) // 11s ago, past 10s cooldown
        val alerts = build(
            current = snap(phone = true),
            prev = CLEAR,
            cooldownMap = cooldownMap
        )
        assertEquals(1, alerts.size)
        assertEquals("Phone detected, please put it down", alerts[0].text)
    }

    @Test
    fun test_cooldown_set_on_announcement() {
        val cooldownMap = mutableMapOf<String, Long>()
        build(
            current = snap(phone = true),
            prev = CLEAR,
            nowMs = NOW,
            cooldownMap = cooldownMap
        )
        assertEquals(NOW, cooldownMap["driver_using_phone"])
    }

    // -------------------------------------------------------------------------
    // Escalation Tests (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_escalation_at_10s_reminder() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        val alerts = build(
            current = snap(phone = true),
            prev = snap(phone = true), // no new danger
            duration = 10,
            prevDuration = 9,
            escalationMap = escalationMap
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.WARNING, alerts[0].priority)
        assertEquals("Still distracted, 10 seconds", alerts[0].text)
        assertFalse(alerts[0].playBeepFirst)
    }

    @Test
    fun test_escalation_at_20s_beep() {
        val escalationMap = mutableMapOf("distraction" to EscalationState(10, 0))
        val alerts = build(
            current = snap(phone = true),
            prev = snap(phone = true),
            duration = 20,
            prevDuration = 19,
            escalationMap = escalationMap
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        assertEquals("Warning. Distracted 20 seconds", alerts[0].text)
        assertTrue(alerts[0].playBeepFirst)
    }

    @Test
    fun test_escalation_at_30s_repeat_beep() {
        val escalationMap = mutableMapOf("distraction" to EscalationState(20, 1))
        val alerts = build(
            current = snap(phone = true),
            prev = snap(phone = true),
            duration = 30,
            prevDuration = 29,
            escalationMap = escalationMap
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        assertEquals("Warning. Distracted 30 seconds", alerts[0].text)
        assertTrue(alerts[0].playBeepFirst)
    }

    @Test
    fun test_escalation_at_40s_repeat_beep() {
        val escalationMap = mutableMapOf("distraction" to EscalationState(30, 2))
        val alerts = build(
            current = snap(phone = true),
            prev = snap(phone = true),
            duration = 40,
            prevDuration = 39,
            escalationMap = escalationMap
        )
        assertEquals(1, alerts.size)
        assertEquals("Warning. Distracted 40 seconds", alerts[0].text)
    }

    @Test
    fun test_no_escalation_between_thresholds() {
        val escalationMap = mutableMapOf("distraction" to EscalationState(10, 0))
        val alerts = build(
            current = snap(phone = true),
            prev = snap(phone = true),
            duration = 15,
            prevDuration = 14,
            escalationMap = escalationMap
        )
        assertTrue(alerts.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Edge Cases (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_danger_no_alert() {
        val alerts = build(current = CLEAR, prev = CLEAR)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun test_onset_suppresses_escalation() {
        // New danger appears at the same time duration hits escalation threshold —
        // onset message takes priority, escalation is skipped
        val escalationMap = mutableMapOf<String, EscalationState>()
        val alerts = build(
            current = snap(phone = true, eyes = true),
            prev = snap(phone = true), // eyes is NEW
            duration = 10,
            prevDuration = 9,
            escalationMap = escalationMap
        )
        assertEquals(1, alerts.size)
        assertEquals("Eyes closed, please stay alert", alerts[0].text) // onset, not escalation
    }

    @Test
    fun test_onset_dangerField_is_null() {
        // Onset alerts have null dangerField to bypass "danger still active?" staleness check
        val alerts = build(current = snap(eating = true), prev = CLEAR)
        assertNull(alerts[0].dangerField)
    }

    @Test
    fun test_escalation_dangerField_is_null() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        val alerts = build(
            current = snap(phone = true),
            prev = snap(phone = true),
            duration = 10,
            escalationMap = escalationMap
        )
        assertNull(alerts[0].dangerField)
    }

    // -------------------------------------------------------------------------
    // Japanese Locale Tests (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_japanese_onset_message() {
        val alerts = build(
            current = snap(phone = true),
            prev = CLEAR,
            isJapanese = true
        )
        assertEquals(1, alerts.size)
        assertEquals("スマートフォンを検出、置いてください", alerts[0].text)
    }

    @Test
    fun test_japanese_all_clear() {
        val alerts = build(
            current = CLEAR,
            prev = snap(phone = true),
            isJapanese = true
        )
        assertEquals("安全です", alerts[0].text)
    }

    // -------------------------------------------------------------------------
    // buildEscalationAlert Direct Tests (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_escalation_below_threshold_no_alert() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        val result = AudioAlerter.buildEscalationAlert(5, NOW, escalationMap, false)
        assertNull(result)
    }

    @Test
    fun test_escalation_state_tracks_duration() {
        val escalationMap = mutableMapOf<String, EscalationState>()
        AudioAlerter.buildEscalationAlert(10, NOW, escalationMap, false)
        val state = escalationMap["distraction"]!!
        assertEquals(10, state.lastAnnouncedDuration)
    }

    @Test
    fun test_escalation_beep_count_increments() {
        val escalationMap = mutableMapOf("distraction" to EscalationState(20, 1))
        AudioAlerter.buildEscalationAlert(30, NOW, escalationMap, false)
        val state = escalationMap["distraction"]!!
        assertEquals(2, state.beepCount)
    }

    // -------------------------------------------------------------------------
    // Priority Helper Tests (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_new_critical_danger_hands_off_wheel() {
        val alerts = build(current = snap(handsOff = true), prev = CLEAR)
        assertEquals(1, alerts.size)
        assertEquals(AlertPriority.CRITICAL, alerts[0].priority)
        assertEquals("Hands off wheel, please grip the steering", alerts[0].text)
        assertNull(alerts[0].dangerField) // onset alerts bypass staleness check
    }

    @Test
    fun test_priority_for_critical_fields() {
        assertEquals(AlertPriority.CRITICAL, AudioAlerter.priorityForField("driver_using_phone"))
        assertEquals(AlertPriority.CRITICAL, AudioAlerter.priorityForField("driver_eyes_closed"))
        assertEquals(AlertPriority.CRITICAL, AudioAlerter.priorityForField("hands_off_wheel"))
    }

    @Test
    fun test_priority_for_warning_fields() {
        assertEquals(AlertPriority.WARNING, AudioAlerter.priorityForField("driver_yawning"))
        assertEquals(AlertPriority.WARNING, AudioAlerter.priorityForField("driver_distracted"))
        assertEquals(AlertPriority.WARNING, AudioAlerter.priorityForField("driver_eating_drinking"))
        assertEquals(AlertPriority.WARNING, AudioAlerter.priorityForField("dangerous_posture"))
        assertEquals(AlertPriority.WARNING, AudioAlerter.priorityForField("child_slouching"))
    }

    // -------------------------------------------------------------------------
    // DangerSnapshot Tests (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_snapshot_any_true() {
        assertTrue(snap(phone = true).any())
        assertTrue(snap(slouching = true).any())
    }

    @Test
    fun test_snapshot_any_false() {
        assertFalse(CLEAR.any())
    }

    // -------------------------------------------------------------------------
    // shouldAlertNoDriver Tests (7 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_driver_transition_with_passengers() {
        assertTrue(AudioAlerter.shouldAlertNoDriver(
            driverDetected = false, passengerCount = 2,
            prevDriverDetected = true, nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_no_driver_no_passengers_no_alert() {
        assertFalse(AudioAlerter.shouldAlertNoDriver(
            driverDetected = false, passengerCount = 0,
            prevDriverDetected = true, nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_no_driver_first_frame_no_alert() {
        assertFalse(AudioAlerter.shouldAlertNoDriver(
            driverDetected = false, passengerCount = 2,
            prevDriverDetected = null, nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_no_driver_already_absent_no_alert() {
        assertFalse(AudioAlerter.shouldAlertNoDriver(
            driverDetected = false, passengerCount = 2,
            prevDriverDetected = false, nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_no_driver_within_cooldown_no_alert() {
        val cooldown = mapOf("no_driver_detected" to NOW - 5000L)
        assertFalse(AudioAlerter.shouldAlertNoDriver(
            driverDetected = false, passengerCount = 2,
            prevDriverDetected = true, nowMs = NOW, cooldownMap = cooldown
        ))
    }

    @Test
    fun test_no_driver_after_cooldown_alert() {
        val cooldown = mapOf("no_driver_detected" to NOW - 11_000L)
        assertTrue(AudioAlerter.shouldAlertNoDriver(
            driverDetected = false, passengerCount = 1,
            prevDriverDetected = true, nowMs = NOW, cooldownMap = cooldown
        ))
    }

    @Test
    fun test_no_driver_driver_present_no_alert() {
        assertFalse(AudioAlerter.shouldAlertNoDriver(
            driverDetected = true, passengerCount = 2,
            prevDriverDetected = true, nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    // -------------------------------------------------------------------------
    // shouldAlertPassengerPosture Tests (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_passenger_posture_onset_alert() {
        // New bad posture → should alert
        assertTrue(AudioAlerter.shouldAlertPassengerPosture(
            index = 1, hasBadPosture = true,
            prevSet = emptySet(), nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_passenger_posture_no_change_no_alert() {
        // Already bad → no alert (not a transition)
        assertFalse(AudioAlerter.shouldAlertPassengerPosture(
            index = 1, hasBadPosture = true,
            prevSet = setOf(1), nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_passenger_posture_good_no_alert() {
        // Good posture → no alert
        assertFalse(AudioAlerter.shouldAlertPassengerPosture(
            index = 1, hasBadPosture = false,
            prevSet = emptySet(), nowMs = NOW, cooldownMap = emptyMap()
        ))
    }

    @Test
    fun test_passenger_posture_cooldown_no_alert() {
        // Within cooldown → no alert
        val cooldown = mapOf("passenger_posture_1" to NOW - 5000L)
        assertFalse(AudioAlerter.shouldAlertPassengerPosture(
            index = 1, hasBadPosture = true,
            prevSet = emptySet(), nowMs = NOW, cooldownMap = cooldown
        ))
    }

    @Test
    fun test_passenger_posture_after_cooldown_alert() {
        // Past cooldown → should alert
        val cooldown = mapOf("passenger_posture_1" to NOW - 11_000L)
        assertTrue(AudioAlerter.shouldAlertPassengerPosture(
            index = 1, hasBadPosture = true,
            prevSet = emptySet(), nowMs = NOW, cooldownMap = cooldown
        ))
    }

    // -------------------------------------------------------------------------
    // All-clear suppressed when driver disappears (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_all_clear_suppressed_when_driver_absent() {
        // Dangers cleared because driver left — should NOT fire "All clear"
        val alerts = AudioAlerter.buildAlerts(
            current = CLEAR, prev = snap(phone = true),
            duration = 0, prevDuration = 0, nowMs = NOW,
            cooldownMap = mutableMapOf(), escalationMap = mutableMapOf(),
            isJapanese = false, driverDetected = false
        )
        assertTrue(alerts.isEmpty())
    }
}
