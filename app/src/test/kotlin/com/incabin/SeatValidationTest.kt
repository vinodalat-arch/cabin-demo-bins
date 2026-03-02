package com.incabin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive seat map validation: ~100 tests covering every seat combination,
 * state permutation, assignment heuristic, view companion logic, VLM parsing,
 * and driver state derivation. No Android dependencies — pure JVM.
 */
class SeatValidationTest {

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun person(
        x1: Float, y1: Float, x2: Float, y2: Float,
        isDriver: Boolean = false, badPosture: Boolean = false
    ) = OverlayPerson(
        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
        confidence = 0.9f, isDriver = isDriver, badPosture = badPosture
    )

    private fun baseResult() = OutputResult.default()

    /** Build a full 5-seat SeatMap from state strings. */
    private fun fullMap(
        driver: String = "Upright",
        frontPax: String = "Upright",
        rearL: String = "Upright",
        rearC: String = "Vacant",
        rearR: String = "Upright"
    ) = SeatMap(
        driver = SeatState(driver != "Vacant", driver),
        frontPassenger = SeatState(frontPax != "Vacant", frontPax),
        rearLeft = SeatState(rearL != "Vacant", rearL),
        rearCenter = SeatState(rearC != "Vacant", rearC),
        rearRight = SeatState(rearR != "Vacant", rearR)
    )

    /** Standard driver bbox (left side, large). Area = 300*500 = 150000. */
    private val driverLeft = person(100f, 100f, 400f, 600f, isDriver = true)

    /** Standard driver bbox (right side, large). Area = 300*500 = 150000. */
    private val driverRight = person(700f, 100f, 1000f, 600f, isDriver = true)

    /** Front passenger on right side (large — passes area threshold). */
    private val frontPaxRight = person(700f, 100f, 1000f, 600f)

    /** Front passenger on left side (large). */
    private val frontPaxLeft = person(100f, 100f, 400f, 600f)

    /** Small rear bbox on left side. Area = 150*200 = 30000 (< 60000 threshold). */
    private val rearSmallLeft = person(100f, 300f, 250f, 500f)

    /** Small rear bbox on right side. */
    private val rearSmallRight = person(800f, 300f, 950f, 500f)

    /** Small rear bbox near center (left of midline). */
    private val rearCenterLeft = person(500f, 300f, 630f, 450f)

    /** Small rear bbox near center (right of midline). */
    private val rearCenterRight = person(650f, 300f, 780f, 450f)

    // =====================================================================
    // 1. SeatMap data model (5 tests)
    // =====================================================================

    @Test fun model_defaultAllVacant() {
        val m = SeatMap()
        listOf(m.driver, m.frontPassenger, m.rearLeft, m.rearCenter, m.rearRight).forEach {
            assertFalse(it.occupied); assertEquals("Vacant", it.state)
        }
    }

    @Test fun model_occupiedStatePersists() {
        val s = SeatState(true, "Phone")
        assertTrue(s.occupied); assertEquals("Phone", s.state)
    }

    @Test fun model_seatEnumOrdinals() {
        assertEquals(0, Seat.DRIVER.ordinal)
        assertEquals(1, Seat.FRONT_PASSENGER.ordinal)
        assertEquals(2, Seat.REAR_LEFT.ordinal)
        assertEquals(3, Seat.REAR_CENTER.ordinal)
        assertEquals(4, Seat.REAR_RIGHT.ordinal)
    }

    @Test fun model_dataClassEquality() {
        assertEquals(fullMap("Phone", "Upright"), fullMap("Phone", "Upright"))
    }

    @Test fun model_dataClassInequality() {
        assertNotEquals(fullMap("Phone"), fullMap("Upright"))
    }

    // =====================================================================
    // 2. CarSeatMapView companion — seatColor (8 tests)
    // =====================================================================

    @Test fun color_upright()    = assertEquals("safe",   CarSeatMapView.seatColor("Upright"))
    @Test fun color_sleeping()   = assertEquals("danger", CarSeatMapView.seatColor("Sleeping"))
    @Test fun color_phone()      = assertEquals("danger", CarSeatMapView.seatColor("Phone"))
    @Test fun color_distracted() = assertEquals("caution", CarSeatMapView.seatColor("Distracted"))
    @Test fun color_eating()     = assertEquals("caution", CarSeatMapView.seatColor("Eating"))
    @Test fun color_yawning()    = assertEquals("caution", CarSeatMapView.seatColor("Yawning"))
    @Test fun color_vacant()     = assertEquals("vacant", CarSeatMapView.seatColor("Vacant"))
    @Test fun color_unknown()    = assertEquals("caution", CarSeatMapView.seatColor("SomethingElse"))

    // =====================================================================
    // 3. CarSeatMapView companion — stateIcon (8 tests)
    // =====================================================================

    @Test fun icon_upright()    = assertEquals("OK",  CarSeatMapView.stateIcon("Upright"))
    @Test fun icon_sleeping()   = assertEquals("Zzz", CarSeatMapView.stateIcon("Sleeping"))
    @Test fun icon_phone()      = assertEquals("TEL", CarSeatMapView.stateIcon("Phone"))
    @Test fun icon_distracted() = assertEquals("!!", CarSeatMapView.stateIcon("Distracted"))
    @Test fun icon_eating()     = assertEquals("EAT", CarSeatMapView.stateIcon("Eating"))
    @Test fun icon_yawning()    = assertEquals("~",  CarSeatMapView.stateIcon("Yawning"))
    @Test fun icon_vacant()     = assertEquals("--", CarSeatMapView.stateIcon("Vacant"))
    @Test fun icon_unknown()    = assertEquals("?",  CarSeatMapView.stateIcon("Garbage"))

    // =====================================================================
    // 4. CarSeatMapView companion — isDanger (7 tests)
    // =====================================================================

    @Test fun danger_sleeping()   = assertTrue(CarSeatMapView.isDanger("Sleeping"))
    @Test fun danger_phone()      = assertTrue(CarSeatMapView.isDanger("Phone"))
    @Test fun danger_distracted() = assertTrue(CarSeatMapView.isDanger("Distracted"))
    @Test fun danger_eating()     = assertTrue(CarSeatMapView.isDanger("Eating"))
    @Test fun danger_yawning()    = assertTrue(CarSeatMapView.isDanger("Yawning"))
    @Test fun danger_upright()    = assertFalse(CarSeatMapView.isDanger("Upright"))
    @Test fun danger_vacant()     = assertFalse(CarSeatMapView.isDanger("Vacant"))

    // =====================================================================
    // 5. benchZoneCount (5 tests)
    // =====================================================================

    @Test fun bench_emptyMap_2zones() {
        assertEquals(2, CarSeatMapView.benchZoneCount(SeatMap()))
    }

    @Test fun bench_rearLeftRightOnly_2zones() {
        assertEquals(2, CarSeatMapView.benchZoneCount(fullMap(rearC = "Vacant")))
    }

    @Test fun bench_rearCenterOccupied_3zones() {
        assertEquals(3, CarSeatMapView.benchZoneCount(fullMap(rearC = "Upright")))
    }

    @Test fun bench_rearCenterDanger_3zones() {
        assertEquals(3, CarSeatMapView.benchZoneCount(fullMap(rearC = "Sleeping")))
    }

    @Test fun bench_onlyRearCenter_3zones() {
        val m = SeatMap(rearCenter = SeatState(true, "Upright"))
        assertEquals(3, CarSeatMapView.benchZoneCount(m))
    }

    // =====================================================================
    // 6. deriveDriverState — all states + priority (10 tests)
    // =====================================================================

    @Test fun driverState_default_upright() {
        assertEquals("Upright", SeatAssigner.deriveDriverState(baseResult()))
    }

    @Test fun driverState_notDetected_vacant() {
        assertEquals("Vacant", SeatAssigner.deriveDriverState(baseResult().copy(driverDetected = false)))
    }

    @Test fun driverState_eyes() {
        assertEquals("Sleeping", SeatAssigner.deriveDriverState(baseResult().copy(driverEyesClosed = true)))
    }

    @Test fun driverState_phone() {
        assertEquals("Phone", SeatAssigner.deriveDriverState(baseResult().copy(driverUsingPhone = true)))
    }

    @Test fun driverState_distracted() {
        assertEquals("Distracted", SeatAssigner.deriveDriverState(baseResult().copy(driverDistracted = true)))
    }

    @Test fun driverState_eating() {
        assertEquals("Eating", SeatAssigner.deriveDriverState(baseResult().copy(driverEatingDrinking = true)))
    }

    @Test fun driverState_yawning() {
        assertEquals("Yawning", SeatAssigner.deriveDriverState(baseResult().copy(driverYawning = true)))
    }

    @Test fun driverState_priority_eyes_beats_phone() {
        val r = baseResult().copy(driverEyesClosed = true, driverUsingPhone = true)
        assertEquals("Sleeping", SeatAssigner.deriveDriverState(r))
    }

    @Test fun driverState_priority_phone_beats_distracted() {
        val r = baseResult().copy(driverUsingPhone = true, driverDistracted = true)
        assertEquals("Phone", SeatAssigner.deriveDriverState(r))
    }

    @Test fun driverState_priority_distracted_beats_eating() {
        val r = baseResult().copy(driverDistracted = true, driverEatingDrinking = true)
        assertEquals("Distracted", SeatAssigner.deriveDriverState(r))
    }

    // =====================================================================
    // 7. SeatAssigner.assign — single occupant scenarios (6 tests)
    // =====================================================================

    @Test fun assign_empty() {
        val m = SeatAssigner.assign(emptyList(), "left")
        assertFalse(m.driver.occupied)
        assertFalse(m.frontPassenger.occupied)
    }

    @Test fun assign_driverOnlyLeft() {
        val m = SeatAssigner.assign(listOf(driverLeft), "left", driverState = "Upright")
        assertTrue(m.driver.occupied); assertEquals("Upright", m.driver.state)
        assertFalse(m.frontPassenger.occupied)
    }

    @Test fun assign_driverOnlyRight() {
        val m = SeatAssigner.assign(listOf(driverRight), "right", driverState = "Phone")
        assertTrue(m.driver.occupied); assertEquals("Phone", m.driver.state)
    }

    @Test fun assign_singleNonDriver_goesToRearLeft() {
        // Single small person on left side, no driver → rear left
        val p = person(200f, 300f, 350f, 500f)
        val m = SeatAssigner.assign(listOf(p), "left", driverState = "Vacant")
        assertFalse(m.driver.occupied)
        assertTrue(m.rearLeft.occupied)
    }

    @Test fun assign_singleNonDriver_goesToRearRight() {
        val p = person(700f, 300f, 850f, 500f)
        val m = SeatAssigner.assign(listOf(p), "left", driverState = "Vacant")
        assertFalse(m.driver.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_driverVacantState() {
        val m = SeatAssigner.assign(listOf(driverLeft), "left", driverState = "Vacant")
        // Driver person is present but state is "Vacant" — seat still occupied because person detected
        assertTrue(m.driver.occupied)
        assertEquals("Vacant", m.driver.state)
    }

    // =====================================================================
    // 8. SeatAssigner.assign — two occupants (8 tests)
    // =====================================================================

    @Test fun assign_driverPlusFrontPax_leftDrive() {
        val m = SeatAssigner.assign(listOf(driverLeft, frontPaxRight), "left", driverState = "Upright")
        assertTrue(m.driver.occupied)
        assertTrue(m.frontPassenger.occupied)
        assertEquals("Upright", m.frontPassenger.state)
    }

    @Test fun assign_driverPlusFrontPax_rightDrive() {
        val m = SeatAssigner.assign(listOf(driverRight, frontPaxLeft), "right", driverState = "Upright")
        assertTrue(m.frontPassenger.occupied)
    }

    @Test fun assign_driverPlusRearLeft() {
        val m = SeatAssigner.assign(listOf(driverLeft, rearSmallLeft), "left", driverState = "Upright")
        assertFalse(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
    }

    @Test fun assign_driverPlusRearRight() {
        val m = SeatAssigner.assign(listOf(driverLeft, rearSmallRight), "left", driverState = "Upright")
        assertFalse(m.frontPassenger.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_driverPhonePlusFrontPax() {
        val m = SeatAssigner.assign(listOf(driverLeft, frontPaxRight), "left", driverState = "Phone")
        assertEquals("Phone", m.driver.state)
        assertEquals("Upright", m.frontPassenger.state)
    }

    @Test fun assign_driverSleepingPlusRear() {
        val m = SeatAssigner.assign(listOf(driverLeft, rearSmallRight), "left", driverState = "Sleeping")
        assertEquals("Sleeping", m.driver.state)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_frontPaxOnWrongSide_goesToRear() {
        // Large passenger on *driver* side (left when driverSeatSide=left) → not assigned as front pax
        // Goes to rear instead (since it's on the driver's side, not passenger side)
        val sameSidePax = person(100f, 100f, 400f, 600f) // left side
        val m = SeatAssigner.assign(listOf(driverLeft, sameSidePax), "left", driverState = "Upright")
        assertFalse(m.frontPassenger.occupied)
    }

    @Test fun assign_badPostureRear() {
        val badRear = person(800f, 300f, 950f, 500f, badPosture = true)
        val m = SeatAssigner.assign(listOf(driverLeft, badRear), "left", driverState = "Upright")
        assertEquals("Sleeping", m.rearRight.state)
    }

    // =====================================================================
    // 9. SeatAssigner.assign — three occupants (5 tests)
    // =====================================================================

    @Test fun assign_driverPlusFrontPlusSingleRearLeft() {
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rearSmallLeft), "left", driverState = "Upright"
        )
        assertTrue(m.driver.occupied)
        assertTrue(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertFalse(m.rearCenter.occupied)
        assertFalse(m.rearRight.occupied)
    }

    @Test fun assign_driverPlusFrontPlusSingleRearRight() {
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rearSmallRight), "left", driverState = "Upright"
        )
        assertTrue(m.rearRight.occupied)
        assertFalse(m.rearLeft.occupied)
    }

    @Test fun assign_driverPlusTwoRear_leftAndRight() {
        val m = SeatAssigner.assign(
            listOf(driverLeft, rearSmallLeft, rearSmallRight), "left", driverState = "Upright"
        )
        assertFalse(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearRight.occupied)
        assertFalse(m.rearCenter.occupied)
    }

    @Test fun assign_driverPlusTwoRear_bothRight() {
        // Two people on right side → largest takes RR, other goes to RL
        val small1 = person(700f, 300f, 800f, 400f) // area=10000
        val small2 = person(850f, 300f, 980f, 420f) // area=15600
        val m = SeatAssigner.assign(listOf(driverLeft, small1, small2), "left", driverState = "Upright")
        assertTrue(m.rearRight.occupied)
        assertTrue(m.rearLeft.occupied)
    }

    @Test fun assign_driverPlusTwoRear_bothLeft() {
        val small1 = person(100f, 300f, 200f, 400f)
        val small2 = person(300f, 300f, 430f, 420f)
        val m = SeatAssigner.assign(listOf(driverLeft, small1, small2), "left", driverState = "Upright")
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearRight.occupied)
    }

    // =====================================================================
    // 10. SeatAssigner.assign — four occupants (4 tests)
    // =====================================================================

    @Test fun assign_fullFrontPlusTwoRear() {
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rearSmallLeft, rearSmallRight),
            "left", driverState = "Distracted"
        )
        assertTrue(m.driver.occupied); assertEquals("Distracted", m.driver.state)
        assertTrue(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearRight.occupied)
        assertFalse(m.rearCenter.occupied)
    }

    @Test fun assign_fourOccupants_rightDrive() {
        val m = SeatAssigner.assign(
            listOf(driverRight, frontPaxLeft,
                person(100f, 300f, 250f, 450f),   // rear left
                person(800f, 300f, 950f, 450f)),   // rear right
            "right", driverState = "Upright"
        )
        assertTrue(m.driver.occupied)
        assertTrue(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_fourOccupants_driverEating() {
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rearSmallLeft, rearSmallRight),
            "left", driverState = "Eating"
        )
        assertEquals("Eating", m.driver.state)
    }

    @Test fun assign_fourOccupants_rearBadPosture() {
        val badRL = person(100f, 300f, 250f, 500f, badPosture = true)
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, badRL, rearSmallRight),
            "left", driverState = "Upright"
        )
        assertEquals("Sleeping", m.rearLeft.state)
        assertEquals("Upright", m.rearRight.state)
    }

    // =====================================================================
    // 11. SeatAssigner.assign — five occupants / rear center (6 tests)
    // =====================================================================

    @Test fun assign_fiveOccupants_allUpright() {
        val rl = person(100f, 300f, 250f, 450f)    // centerX=175
        val rc = person(550f, 300f, 700f, 450f)     // centerX=625
        val rr = person(900f, 300f, 1050f, 450f)    // centerX=975
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rl, rc, rr), "left", driverState = "Upright"
        )
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearCenter.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_fiveOccupants_driverYawning() {
        val rl = person(100f, 300f, 250f, 450f)
        val rc = person(550f, 300f, 700f, 450f)
        val rr = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rl, rc, rr), "left", driverState = "Yawning"
        )
        assertEquals("Yawning", m.driver.state)
    }

    @Test fun assign_fiveOccupants_rearCenterBadPosture() {
        val rl = person(100f, 300f, 250f, 450f)
        val rc = person(550f, 300f, 700f, 450f, badPosture = true)
        val rr = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rl, rc, rr), "left", driverState = "Upright"
        )
        assertEquals("Sleeping", m.rearCenter.state)
        assertEquals("Upright", m.rearLeft.state)
        assertEquals("Upright", m.rearRight.state)
    }

    @Test fun assign_threeRear_sortedByX() {
        // Ensure leftmost→RL, middle→RC, rightmost→RR regardless of input order
        val rl = person(100f, 300f, 250f, 450f)    // centerX=175
        val rc = person(550f, 300f, 700f, 450f)     // centerX=625
        val rr = person(900f, 300f, 1050f, 450f)    // centerX=975
        // Pass in reversed order
        val m = SeatAssigner.assign(
            listOf(driverLeft, rr, rl, rc), "left", driverState = "Upright"
        )
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearCenter.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_fiveOccupants_rightDrive() {
        val rl = person(100f, 300f, 250f, 450f)
        val rc = person(550f, 300f, 700f, 450f)
        val rr = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(
            listOf(driverRight, frontPaxLeft, rl, rc, rr), "right", driverState = "Upright"
        )
        assertTrue(m.driver.occupied)
        assertTrue(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearCenter.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_fiveOccupants_allBadPosture() {
        val rl = person(100f, 300f, 250f, 450f, badPosture = true)
        val rc = person(550f, 300f, 700f, 450f, badPosture = true)
        val rr = person(900f, 300f, 1050f, 450f, badPosture = true)
        val fpBad = person(700f, 100f, 1000f, 600f, badPosture = true)
        val m = SeatAssigner.assign(
            listOf(driverLeft, fpBad, rl, rc, rr), "left", driverState = "Sleeping"
        )
        assertEquals("Sleeping", m.driver.state)
        assertEquals("Sleeping", m.frontPassenger.state)
        assertEquals("Sleeping", m.rearLeft.state)
        assertEquals("Sleeping", m.rearCenter.state)
        assertEquals("Sleeping", m.rearRight.state)
    }

    // =====================================================================
    // 12. SeatAssigner.assign — area threshold boundary (4 tests)
    // =====================================================================

    @Test fun assign_exactlyAtThreshold_isFront() {
        // Driver area = 150000. Threshold = 0.40 * 150000 = 60000
        // Passenger area exactly = 60000 → front row
        // area = (x2-x1)*(y2-y1) = 200*300 = 60000
        val pax = person(700f, 100f, 900f, 400f)
        val m = SeatAssigner.assign(listOf(driverLeft, pax), "left", driverState = "Upright")
        assertTrue(m.frontPassenger.occupied)
    }

    @Test fun assign_justBelowThreshold_isRear() {
        // area = 200*299 = 59800 < 60000 → rear
        val pax = person(700f, 100f, 900f, 399f)
        val m = SeatAssigner.assign(listOf(driverLeft, pax), "left", driverState = "Upright")
        assertFalse(m.frontPassenger.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_muchLargerThanThreshold_isFront() {
        val hugePax = person(650f, 50f, 1100f, 650f) // area=450*600=270000 >> 60000
        val m = SeatAssigner.assign(listOf(driverLeft, hugePax), "left", driverState = "Upright")
        assertTrue(m.frontPassenger.occupied)
    }

    @Test fun assign_tinyBbox_isRear() {
        val tiny = person(800f, 400f, 830f, 420f) // area=30*20=600
        val m = SeatAssigner.assign(listOf(driverLeft, tiny), "left", driverState = "Upright")
        assertFalse(m.frontPassenger.occupied)
        assertTrue(m.rearRight.occupied)
    }

    // =====================================================================
    // 13. SeatAssigner — no driver reference (3 tests)
    // =====================================================================

    @Test fun assign_noDriver_allGoToRear() {
        // No driver → driverArea=0 → area >= 0 is "front" check... actually 0*0.40 = 0, area>=0 is true
        // BUT driverArea=0 → condition is "driverArea > 0f && ..." which is false → rear
        val p1 = person(200f, 200f, 500f, 600f)
        val p2 = person(700f, 200f, 1000f, 600f)
        val m = SeatAssigner.assign(listOf(p1, p2), "left", driverState = "Vacant")
        assertFalse(m.driver.occupied)
        assertFalse(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_noDriver_threePersons_fillsRearCenter() {
        val p1 = person(100f, 300f, 250f, 450f)
        val p2 = person(550f, 300f, 700f, 450f)
        val p3 = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(listOf(p1, p2, p3), "left", driverState = "Vacant")
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearCenter.occupied)
        assertTrue(m.rearRight.occupied)
    }

    @Test fun assign_noDriver_singlePerson() {
        val p = person(300f, 200f, 500f, 600f) // centerX=400 → left side
        val m = SeatAssigner.assign(listOf(p), "left", driverState = "Vacant")
        assertTrue(m.rearLeft.occupied)
        assertFalse(m.rearRight.occupied)
    }

    // =====================================================================
    // 14. VlmClient.parseSeatMap — JSON parsing (10 tests)
    // =====================================================================

    private fun jsonObj(json: String): JsonObject =
        JsonParser.parseString(json).asJsonObject

    @Test fun parseSeatMap_fullValid() {
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Phone"},"front_passenger":{"occupied":true,"state":"Upright"},"rear_left":{"occupied":false,"state":"Vacant"},"rear_center":{"occupied":false,"state":"Vacant"},"rear_right":{"occupied":true,"state":"Sleeping"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertTrue(m.driver.occupied); assertEquals("Phone", m.driver.state)
        assertTrue(m.frontPassenger.occupied)
        assertFalse(m.rearLeft.occupied)
        assertTrue(m.rearRight.occupied); assertEquals("Sleeping", m.rearRight.state)
    }

    @Test fun parseSeatMap_noSeatMap_returnsNull() {
        assertNull(VlmClient.parseSeatMap(jsonObj("""{"other":"data"}""")))
    }

    @Test fun parseSeatMap_emptySeatMap() {
        val m = VlmClient.parseSeatMap(jsonObj("""{"seat_map":{}}"""))!!
        assertFalse(m.driver.occupied)
        assertEquals("Vacant", m.driver.state)
    }

    @Test fun parseSeatMap_allOccupied() {
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Upright"},"front_passenger":{"occupied":true,"state":"Eating"},"rear_left":{"occupied":true,"state":"Yawning"},"rear_center":{"occupied":true,"state":"Distracted"},"rear_right":{"occupied":true,"state":"Upright"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertTrue(m.rearCenter.occupied)
        assertEquals("Distracted", m.rearCenter.state)
        assertEquals("Yawning", m.rearLeft.state)
        assertEquals("Eating", m.frontPassenger.state)
    }

    @Test fun parseSeatMap_allVacant() {
        val json = """{"seat_map":{"driver":{"occupied":false,"state":"Vacant"},"front_passenger":{"occupied":false,"state":"Vacant"},"rear_left":{"occupied":false,"state":"Vacant"},"rear_center":{"occupied":false,"state":"Vacant"},"rear_right":{"occupied":false,"state":"Vacant"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertFalse(m.driver.occupied)
        assertFalse(m.frontPassenger.occupied)
        assertFalse(m.rearLeft.occupied)
        assertFalse(m.rearCenter.occupied)
        assertFalse(m.rearRight.occupied)
    }

    @Test fun parseSeatMap_partialSeats_missingFieldsVacant() {
        // Only driver present — other seats default to vacant
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Phone"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertTrue(m.driver.occupied)
        assertFalse(m.frontPassenger.occupied)
        assertFalse(m.rearLeft.occupied)
    }

    @Test fun parseSeatMap_missingState_defaultsVacant() {
        val json = """{"seat_map":{"driver":{"occupied":true}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertTrue(m.driver.occupied)
        assertEquals("Vacant", m.driver.state)
    }

    @Test fun parseSeatMap_missingOccupied_defaultsFalse() {
        val json = """{"seat_map":{"driver":{"state":"Phone"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertFalse(m.driver.occupied)
        assertEquals("Phone", m.driver.state)
    }

    @Test fun parseSeatMap_rearCenterOccupied() {
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Upright"},"front_passenger":{"occupied":true,"state":"Upright"},"rear_left":{"occupied":true,"state":"Upright"},"rear_center":{"occupied":true,"state":"Sleeping"},"rear_right":{"occupied":true,"state":"Upright"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertTrue(m.rearCenter.occupied)
        assertEquals("Sleeping", m.rearCenter.state)
    }

    @Test fun parseSeatMap_dangerStatesAllSeats() {
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Phone"},"front_passenger":{"occupied":true,"state":"Sleeping"},"rear_left":{"occupied":true,"state":"Distracted"},"rear_center":{"occupied":true,"state":"Eating"},"rear_right":{"occupied":true,"state":"Yawning"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertEquals("Phone", m.driver.state)
        assertEquals("Sleeping", m.frontPassenger.state)
        assertEquals("Distracted", m.rearLeft.state)
        assertEquals("Eating", m.rearCenter.state)
        assertEquals("Yawning", m.rearRight.state)
    }

    // =====================================================================
    // 15. End-to-end: dummy event scenarios (16 tests)
    // Simulate complete pipeline: OutputResult → deriveDriverState → assign → view logic
    // =====================================================================

    @Test fun e2e_allSafe_2passengers() {
        val result = baseResult().copy(passengerCount = 2)
        val driverState = SeatAssigner.deriveDriverState(result)
        assertEquals("Upright", driverState)
        val m = SeatAssigner.assign(listOf(driverLeft, frontPaxRight), "left", driverState = driverState)
        assertEquals("safe", CarSeatMapView.seatColor(m.driver.state))
        assertEquals("safe", CarSeatMapView.seatColor(m.frontPassenger.state))
        assertEquals(2, CarSeatMapView.benchZoneCount(m))
    }

    @Test fun e2e_driverPhone_3passengers() {
        val result = baseResult().copy(driverUsingPhone = true, passengerCount = 3)
        val driverState = SeatAssigner.deriveDriverState(result)
        assertEquals("Phone", driverState)
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rearSmallLeft), "left", driverState = driverState
        )
        assertTrue(CarSeatMapView.isDanger(m.driver.state))
        assertEquals("TEL", CarSeatMapView.stateIcon(m.driver.state))
    }

    @Test fun e2e_driverEyesClosed_fullCar() {
        val result = baseResult().copy(driverEyesClosed = true, passengerCount = 5)
        val driverState = SeatAssigner.deriveDriverState(result)
        val rl = person(100f, 300f, 250f, 450f)
        val rc = person(550f, 300f, 700f, 450f)
        val rr = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(
            listOf(driverLeft, frontPaxRight, rl, rc, rr), "left", driverState = driverState
        )
        assertEquals("Sleeping", m.driver.state)
        assertEquals("danger", CarSeatMapView.seatColor(m.driver.state))
        assertEquals("Zzz", CarSeatMapView.stateIcon(m.driver.state))
        assertEquals(3, CarSeatMapView.benchZoneCount(m))
    }

    @Test fun e2e_driverDistracted_rearSleeping() {
        val result = baseResult().copy(driverDistracted = true)
        val driverState = SeatAssigner.deriveDriverState(result)
        val badRear = person(800f, 300f, 950f, 500f, badPosture = true)
        val m = SeatAssigner.assign(listOf(driverLeft, badRear), "left", driverState = driverState)
        assertEquals("Distracted", m.driver.state)
        assertEquals("Sleeping", m.rearRight.state)
        assertTrue(CarSeatMapView.isDanger(m.driver.state))
        assertTrue(CarSeatMapView.isDanger(m.rearRight.state))
    }

    @Test fun e2e_noDriver_2passengers() {
        val result = baseResult().copy(driverDetected = false, passengerCount = 2)
        val driverState = SeatAssigner.deriveDriverState(result)
        assertEquals("Vacant", driverState)
        val p1 = person(200f, 200f, 400f, 500f)
        val p2 = person(700f, 200f, 900f, 500f)
        val m = SeatAssigner.assign(listOf(p1, p2), "left", driverState = driverState)
        assertFalse(m.driver.occupied)
        assertEquals("--", CarSeatMapView.stateIcon(m.driver.state))
    }

    @Test fun e2e_driverYawning_paxEating() {
        val result = baseResult().copy(driverYawning = true, passengerCount = 2)
        val driverState = SeatAssigner.deriveDriverState(result)
        assertEquals("Yawning", driverState)
        // VLM would set seat map directly; for local, passenger state is limited
        val m = fullMap(driver = "Yawning", frontPax = "Eating")
        assertEquals("~", CarSeatMapView.stateIcon(m.driver.state))
        assertEquals("EAT", CarSeatMapView.stateIcon(m.frontPassenger.state))
    }

    @Test fun e2e_allDangerStates_checkIcons() {
        val m = fullMap(
            driver = "Phone", frontPax = "Sleeping",
            rearL = "Distracted", rearC = "Eating", rearR = "Yawning"
        )
        assertEquals("TEL", CarSeatMapView.stateIcon(m.driver.state))
        assertEquals("Zzz", CarSeatMapView.stateIcon(m.frontPassenger.state))
        assertEquals("!!", CarSeatMapView.stateIcon(m.rearLeft.state))
        assertEquals("EAT", CarSeatMapView.stateIcon(m.rearCenter.state))
        assertEquals("~", CarSeatMapView.stateIcon(m.rearRight.state))
    }

    @Test fun e2e_allDangerStates_checkColors() {
        val m = fullMap(
            driver = "Phone", frontPax = "Sleeping",
            rearL = "Distracted", rearC = "Eating", rearR = "Yawning"
        )
        // Critical states → "danger", warning states → "caution"
        assertEquals("danger", CarSeatMapView.seatColor(m.driver.state))      // Phone
        assertEquals("danger", CarSeatMapView.seatColor(m.frontPassenger.state)) // Sleeping
        assertEquals("caution", CarSeatMapView.seatColor(m.rearLeft.state))    // Distracted
        assertEquals("caution", CarSeatMapView.seatColor(m.rearCenter.state))  // Eating
        assertEquals("caution", CarSeatMapView.seatColor(m.rearRight.state))   // Yawning
        // All are non-safe (isDanger returns true for all)
        listOf(m.driver, m.frontPassenger, m.rearLeft, m.rearCenter, m.rearRight).forEach {
            assertTrue(CarSeatMapView.isDanger(it.state))
        }
    }

    @Test fun e2e_mixedStates_safeAndDanger() {
        val m = fullMap(driver = "Upright", frontPax = "Phone", rearL = "Vacant", rearR = "Upright")
        assertEquals("safe", CarSeatMapView.seatColor(m.driver.state))
        assertEquals("danger", CarSeatMapView.seatColor(m.frontPassenger.state))
        assertEquals("vacant", CarSeatMapView.seatColor(m.rearLeft.state))
        assertEquals("safe", CarSeatMapView.seatColor(m.rearRight.state))
    }

    @Test fun e2e_emptyCarAfterAllLeave() {
        val m = SeatMap()
        // All vacant
        listOf(m.driver, m.frontPassenger, m.rearLeft, m.rearCenter, m.rearRight).forEach {
            assertEquals("vacant", CarSeatMapView.seatColor(it.state))
            assertFalse(CarSeatMapView.isDanger(it.state))
            assertEquals("--", CarSeatMapView.stateIcon(it.state))
        }
        assertEquals(2, CarSeatMapView.benchZoneCount(m))
    }

    @Test fun e2e_driverEating_rearCenterOccupied() {
        val result = baseResult().copy(driverEatingDrinking = true)
        val driverState = SeatAssigner.deriveDriverState(result)
        val rl = person(100f, 300f, 250f, 450f)
        val rc = person(550f, 300f, 700f, 450f)
        val rr = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(
            listOf(driverLeft, rl, rc, rr), "left", driverState = driverState
        )
        assertEquals("Eating", m.driver.state)
        assertEquals(3, CarSeatMapView.benchZoneCount(m))
    }

    @Test fun e2e_vlmParsed_fullCar_allDanger() {
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Phone"},"front_passenger":{"occupied":true,"state":"Sleeping"},"rear_left":{"occupied":true,"state":"Distracted"},"rear_center":{"occupied":true,"state":"Eating"},"rear_right":{"occupied":true,"state":"Yawning"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertEquals(3, CarSeatMapView.benchZoneCount(m))
        // All seats should show danger
        Seat.values().forEach { seat ->
            val state = when (seat) {
                Seat.DRIVER -> m.driver
                Seat.FRONT_PASSENGER -> m.frontPassenger
                Seat.REAR_LEFT -> m.rearLeft
                Seat.REAR_CENTER -> m.rearCenter
                Seat.REAR_RIGHT -> m.rearRight
            }
            assertTrue("${seat.name} should be danger", CarSeatMapView.isDanger(state.state))
        }
    }

    @Test fun e2e_vlmParsed_driverOnly() {
        val json = """{"seat_map":{"driver":{"occupied":true,"state":"Upright"},"front_passenger":{"occupied":false,"state":"Vacant"},"rear_left":{"occupied":false,"state":"Vacant"},"rear_center":{"occupied":false,"state":"Vacant"},"rear_right":{"occupied":false,"state":"Vacant"}}}"""
        val m = VlmClient.parseSeatMap(jsonObj(json))!!
        assertTrue(m.driver.occupied)
        assertEquals("OK", CarSeatMapView.stateIcon(m.driver.state))
        assertFalse(m.frontPassenger.occupied)
        assertEquals(2, CarSeatMapView.benchZoneCount(m))
    }

    @Test fun e2e_rightHandDrive_fullCar() {
        val rl = person(100f, 300f, 250f, 450f)
        val rc = person(550f, 300f, 700f, 450f)
        val rr = person(900f, 300f, 1050f, 450f)
        val m = SeatAssigner.assign(
            listOf(driverRight, frontPaxLeft, rl, rc, rr), "right", driverState = "Phone"
        )
        assertTrue(m.driver.occupied)
        assertEquals("Phone", m.driver.state)
        assertTrue(m.frontPassenger.occupied)
        assertTrue(m.rearLeft.occupied)
        assertTrue(m.rearCenter.occupied)
        assertTrue(m.rearRight.occupied)
        assertEquals(3, CarSeatMapView.benchZoneCount(m))
    }

    @Test fun e2e_transitionSafeToDanger() {
        // Simulate frame 1: safe → frame 2: danger
        val safe = fullMap(driver = "Upright", frontPax = "Upright")
        val danger = fullMap(driver = "Phone", frontPax = "Upright")
        assertFalse(CarSeatMapView.isDanger(safe.driver.state))
        assertTrue(CarSeatMapView.isDanger(danger.driver.state))
        assertEquals("safe", CarSeatMapView.seatColor(safe.driver.state))
        assertEquals("danger", CarSeatMapView.seatColor(danger.driver.state))
    }

    @Test fun e2e_transitionDangerToSafe() {
        val danger = fullMap(driver = "Sleeping")
        val safe = fullMap(driver = "Upright")
        assertTrue(CarSeatMapView.isDanger(danger.driver.state))
        assertFalse(CarSeatMapView.isDanger(safe.driver.state))
    }
}
