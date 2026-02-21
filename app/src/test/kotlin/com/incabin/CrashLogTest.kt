package com.incabin

import org.junit.Assert.*
import org.junit.Test

class CrashLogTest {

    // --- formatLine tests ---

    @Test
    fun test_formatLine_produces_correct_format() {
        val line = CrashLog.formatLine("ERROR", "InCabin", "Something failed", "2026-01-15 10:30:45.123")
        assertEquals("2026-01-15 10:30:45.123 [ERROR] InCabin: Something failed", line)
    }

    @Test
    fun test_formatLine_includes_all_fields() {
        val line = CrashLog.formatLine("WARN", "Watchdog", "Pipeline stalled", "2026-02-22 08:00:00.000")
        assertTrue(line.contains("2026-02-22 08:00:00.000"))
        assertTrue(line.contains("[WARN]"))
        assertTrue(line.contains("Watchdog"))
        assertTrue(line.contains("Pipeline stalled"))
    }

    @Test
    fun test_formatLine_with_info_level() {
        val line = CrashLog.formatLine("INFO", "Service", "Started", "2026-01-01 00:00:00.000")
        assertTrue(line.contains("[INFO]"))
        assertTrue(line.contains("Service: Started"))
    }

    @Test
    fun test_formatLine_with_custom_timestamp() {
        val line = CrashLog.formatLine("ERROR", "Test", "msg", "custom-ts")
        assertTrue(line.startsWith("custom-ts"))
    }

    // --- shouldRotate tests ---

    @Test
    fun test_shouldRotate_false_under_limit() {
        assertFalse(CrashLog.shouldRotate(100_000, 500_000))
    }

    @Test
    fun test_shouldRotate_true_over_limit() {
        assertTrue(CrashLog.shouldRotate(600_000, 500_000))
    }

    @Test
    fun test_shouldRotate_false_at_exact_limit() {
        assertFalse(CrashLog.shouldRotate(500_000, 500_000))
    }
}
