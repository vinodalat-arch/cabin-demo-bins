package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SeatAssigner — pure seat assignment logic.
 * Uses OverlayPerson bboxes to test heuristic seat mapping.
 */
class SeatAssignerTest {

    // Helper to create an OverlayPerson with just bbox and driver flag
    private fun person(
        x1: Float, y1: Float, x2: Float, y2: Float,
        isDriver: Boolean = false, badPosture: Boolean = false
    ) = OverlayPerson(
        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
        confidence = 0.9f, isDriver = isDriver, badPosture = badPosture
    )

    // Frame: 1280 wide. Left side = x < 640, Right side = x >= 640

    // -------------------------------------------------------------------------
    // assign() tests
    // -------------------------------------------------------------------------

    @Test
    fun test_assign_emptyList() {
        val map = SeatAssigner.assign(emptyList(), "left")
        assertFalse(map.driver.occupied)
        assertFalse(map.frontPassenger.occupied)
        assertFalse(map.rearLeft.occupied)
        assertFalse(map.rearCenter.occupied)
        assertFalse(map.rearRight.occupied)
    }

    @Test
    fun test_assign_driverOnly() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        val map = SeatAssigner.assign(listOf(driver), "left", driverState = "Upright")
        assertTrue(map.driver.occupied)
        assertEquals("Upright", map.driver.state)
        assertFalse(map.frontPassenger.occupied)
        assertFalse(map.rearLeft.occupied)
        assertFalse(map.rearCenter.occupied)
        assertFalse(map.rearRight.occupied)
    }

    @Test
    fun test_assign_driverAndFrontPassenger_leftDrive() {
        // Driver on left (x=100-400), passenger on right (x=700-1000)
        // Both large bboxes → front row
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // Driver area = 300*500 = 150000. Passenger area = 300*500 = 150000 >= 0.55*150000 = 82500 → front
        val passenger = person(700f, 100f, 1000f, 600f)
        val map = SeatAssigner.assign(listOf(driver, passenger), "left", driverState = "Upright")
        assertTrue(map.driver.occupied)
        assertTrue(map.frontPassenger.occupied)
        assertEquals("Upright", map.frontPassenger.state)
    }

    @Test
    fun test_assign_driverAndFrontPassenger_rightDrive() {
        // Driver on right (x=700-1000), passenger on left (x=100-400)
        val driver = person(700f, 100f, 1000f, 600f, isDriver = true)
        val passenger = person(100f, 100f, 400f, 600f)
        val map = SeatAssigner.assign(listOf(driver, passenger), "right", driverState = "Upright")
        assertTrue(map.driver.occupied)
        assertTrue(map.frontPassenger.occupied)
        assertEquals("Upright", map.frontPassenger.state)
    }

    @Test
    fun test_assign_fourOccupants() {
        // Driver left front, large bbox
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // Front passenger right, large bbox → front row
        val frontPax = person(700f, 100f, 1000f, 600f)
        // Rear left: small bbox on left side
        val rearL = person(100f, 200f, 250f, 400f)  // area=150*200=30000, < 0.55*150000=82500 → rear
        // Rear right: small bbox on right side
        val rearR = person(800f, 200f, 950f, 400f)
        val map = SeatAssigner.assign(
            listOf(driver, frontPax, rearL, rearR), "left", driverState = "Phone"
        )
        assertTrue(map.driver.occupied)
        assertEquals("Phone", map.driver.state)
        assertTrue(map.frontPassenger.occupied)
        assertTrue(map.rearLeft.occupied)
        assertFalse(map.rearCenter.occupied)
        assertTrue(map.rearRight.occupied)
    }

    @Test
    fun test_assign_noDriver() {
        // No one tagged as driver
        val p1 = person(100f, 100f, 300f, 500f)
        val p2 = person(700f, 100f, 900f, 500f)
        val map = SeatAssigner.assign(listOf(p1, p2), "left", driverState = "Vacant")
        assertFalse(map.driver.occupied)
        assertEquals("Vacant", map.driver.state)
        // Without driver area, all non-drivers go to rear (since driverArea=0, area >= 0 is false)
        // p1 center < 640 → left, p2 center > 640 → right
        assertTrue(map.rearLeft.occupied)
        assertTrue(map.rearRight.occupied)
    }

    @Test
    fun test_assign_rearOnly_smallBboxes() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // Smaller bbox on right side (area < 55% of driver area) → rear
        val small = person(750f, 200f, 950f, 400f)  // area=200*200=40000, < 0.55*150000=82500 → rear
        val map = SeatAssigner.assign(listOf(driver, small), "left", driverState = "Upright")
        assertTrue(map.driver.occupied)
        assertFalse(map.frontPassenger.occupied)
        assertFalse(map.rearLeft.occupied)
        assertTrue(map.rearRight.occupied)  // right side → rear right
    }

    @Test
    fun test_assign_leftSideDrive_passengerOnRight() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // Large bbox on right → front passenger (driver side = left, so passenger side = right)
        val pax = person(700f, 100f, 1000f, 600f)
        val map = SeatAssigner.assign(listOf(driver, pax), "left", driverState = "Upright")
        assertTrue(map.frontPassenger.occupied)
    }

    @Test
    fun test_assign_rightSideDrive_passengerOnLeft() {
        val driver = person(700f, 100f, 1000f, 600f, isDriver = true)
        // Large bbox on left → front passenger (driver side = right, so passenger side = left)
        val pax = person(100f, 100f, 400f, 600f)
        val map = SeatAssigner.assign(listOf(driver, pax), "right", driverState = "Upright")
        assertTrue(map.frontPassenger.occupied)
    }

    @Test
    fun test_assign_multipleOnSameSide_takesLargest() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // Two bboxes on right side, both rear (< 55% of driver area)
        val small = person(800f, 200f, 950f, 400f)   // area=150*200=30000
        val bigger = person(700f, 200f, 900f, 450f)  // area=200*250=50000
        val map = SeatAssigner.assign(listOf(driver, small, bigger), "left", driverState = "Upright")
        // Largest on right side is 'bigger'
        assertTrue(map.rearRight.occupied)
    }

    @Test
    fun test_assign_badPosture_mapsSleeping() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        val pax = person(750f, 200f, 950f, 400f, badPosture = true) // area=200*200=40000 > min area
        val map = SeatAssigner.assign(listOf(driver, pax), "left", driverState = "Upright")
        assertEquals("Sleeping", map.rearRight.state)
    }

    // -------------------------------------------------------------------------
    // 3 rear passengers / 5 occupants tests
    // -------------------------------------------------------------------------

    @Test
    fun test_assign_threeRearPassengers() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // 3 small bboxes (rear row): left, center, right
        val rearL = person(100f, 300f, 250f, 450f)   // centerX=175, left side
        val rearC = person(550f, 300f, 700f, 450f)    // centerX=625, near center
        val rearR = person(900f, 300f, 1050f, 450f)   // centerX=975, right side
        val map = SeatAssigner.assign(
            listOf(driver, rearL, rearC, rearR), "left", driverState = "Upright"
        )
        assertTrue(map.driver.occupied)
        assertFalse(map.frontPassenger.occupied)
        assertTrue(map.rearLeft.occupied)
        assertTrue(map.rearCenter.occupied)
        assertTrue(map.rearRight.occupied)
        assertEquals("Upright", map.rearLeft.state)
        assertEquals("Upright", map.rearCenter.state)
        assertEquals("Upright", map.rearRight.state)
    }

    @Test
    fun test_assign_fiveOccupants() {
        // Driver left front, large bbox
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        // Front passenger right, large bbox → front row
        val frontPax = person(700f, 100f, 1000f, 600f)
        // 3 rear passengers: left, center, right
        val rearL = person(100f, 300f, 250f, 450f)   // centerX=175
        val rearC = person(550f, 300f, 700f, 450f)    // centerX=625
        val rearR = person(900f, 300f, 1050f, 450f)   // centerX=975
        val map = SeatAssigner.assign(
            listOf(driver, frontPax, rearL, rearC, rearR), "left", driverState = "Distracted"
        )
        assertTrue(map.driver.occupied)
        assertEquals("Distracted", map.driver.state)
        assertTrue(map.frontPassenger.occupied)
        assertTrue(map.rearLeft.occupied)
        assertTrue(map.rearCenter.occupied)
        assertTrue(map.rearRight.occupied)
    }

    @Test
    fun test_assign_threeRearPassengers_badPosture() {
        val driver = person(100f, 100f, 400f, 600f, isDriver = true)
        val rearL = person(100f, 300f, 250f, 450f)
        val rearC = person(550f, 300f, 700f, 450f, badPosture = true)  // center one sleeping
        val rearR = person(900f, 300f, 1050f, 450f)
        val map = SeatAssigner.assign(
            listOf(driver, rearL, rearC, rearR), "left", driverState = "Upright"
        )
        assertEquals("Upright", map.rearLeft.state)
        assertEquals("Sleeping", map.rearCenter.state)
        assertEquals("Upright", map.rearRight.state)
    }

    // -------------------------------------------------------------------------
    // deriveDriverState() tests
    // -------------------------------------------------------------------------

    private fun baseResult() = OutputResult.default()

    @Test
    fun test_deriveDriverState_upright() {
        assertEquals("Upright", SeatAssigner.deriveDriverState(baseResult()))
    }

    @Test
    fun test_deriveDriverState_phone() {
        val r = baseResult().copy(driverUsingPhone = true)
        assertEquals("Phone", SeatAssigner.deriveDriverState(r))
    }

    @Test
    fun test_deriveDriverState_eyes() {
        val r = baseResult().copy(driverEyesClosed = true)
        assertEquals("Sleeping", SeatAssigner.deriveDriverState(r))
    }

    @Test
    fun test_deriveDriverState_distracted() {
        val r = baseResult().copy(driverDistracted = true)
        assertEquals("Distracted", SeatAssigner.deriveDriverState(r))
    }

    @Test
    fun test_deriveDriverState_eating() {
        val r = baseResult().copy(driverEatingDrinking = true)
        assertEquals("Eating", SeatAssigner.deriveDriverState(r))
    }

    @Test
    fun test_deriveDriverState_yawning() {
        val r = baseResult().copy(driverYawning = true)
        assertEquals("Yawning", SeatAssigner.deriveDriverState(r))
    }

    @Test
    fun test_deriveDriverState_vacant() {
        val r = baseResult().copy(driverDetected = false)
        assertEquals("Vacant", SeatAssigner.deriveDriverState(r))
    }

    @Test
    fun test_deriveDriverState_priority_eyes_over_phone() {
        // Eyes closed has higher priority than phone
        val r = baseResult().copy(driverEyesClosed = true, driverUsingPhone = true)
        assertEquals("Sleeping", SeatAssigner.deriveDriverState(r))
    }
}
