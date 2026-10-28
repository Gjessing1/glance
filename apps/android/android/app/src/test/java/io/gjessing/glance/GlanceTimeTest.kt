package io.gjessing.glance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class GlanceTimeTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val rules = TimeRules()
    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), oslo)

    @Test fun contextLabelsFollowTheDay() {
        assertEquals("Morning", GlanceTime.contextLabel(at("2026-10-28T08:15"), rules))
        assertEquals("Morning", GlanceTime.contextLabel(at("2026-10-28T11:59"), rules))
        assertEquals("Afternoon", GlanceTime.contextLabel(at("2026-10-28T12:00"), rules))
        val gap = rules.copy(morning = Window(LocalTime.of(7, 0), LocalTime.of(10, 0)), evening = Window(LocalTime.of(15, 0), LocalTime.of(18, 30)))
        assertEquals("Workday", GlanceTime.contextLabel(at("2026-10-28T12:00"), gap))
        assertEquals("Evening", GlanceTime.contextLabel(at("2026-10-28T18:30"), rules))
        assertEquals("Evening", GlanceTime.contextLabel(at("2026-10-29T02:00"), rules))
        assertEquals("Weekend", GlanceTime.contextLabel(at("2026-10-31T08:15"), rules))
    }

    @Test fun windowsAndWorkHoursAreWorkdaysOnly() {
        assertEquals(rules.morning, GlanceTime.commuteWindow(at("2026-10-28T07:00"), rules))
        assertEquals(rules.morning, GlanceTime.commuteWindow(at("2026-10-28T11:59"), rules))
        assertEquals(rules.evening, GlanceTime.commuteWindow(at("2026-10-28T12:00"), rules))
        assertNull(GlanceTime.commuteWindow(at("2026-10-28T06:59"), rules))
        assertEquals(rules.evening, GlanceTime.commuteWindow(at("2026-10-28T18:29"), rules))
        assertNull(GlanceTime.commuteWindow(at("2026-10-31T08:00"), rules))
        assertTrue(GlanceTime.inWorkHours(at("2026-10-28T08:00"), rules))
        assertFalse(GlanceTime.inWorkHours(at("2026-10-28T17:00"), rules))
    }

    @Test fun directionSwitchIsConfigurable() {
        assertEquals(CommuteRepository.OUTBOUND, GlanceTime.clockDirection(at("2026-10-28T11:59"), rules))
        assertEquals(CommuteRepository.INBOUND, GlanceTime.clockDirection(at("2026-10-28T12:00"), rules))
        val late = rules.copy(switchAt = LocalTime.of(14, 30))
        assertEquals(CommuteRepository.OUTBOUND, GlanceTime.clockDirection(at("2026-10-28T14:00"), late))
    }

    @Test fun windowsKeepWallTimeAcrossBothDstChanges() {
        // Last Sunday of March: 02:00 jumps to 03:00. Monday's window still opens at 07:00 local.
        val march = at("2026-03-29T23:00")
        assertEquals(at("2026-03-30T00:00"), GlanceTime.nextRuleChange(march, rules))
        assertEquals("+02:00", at("2026-03-30T07:00").offset.toString())
        assertEquals(rules.morning, GlanceTime.commuteWindow(at("2026-03-30T07:00"), rules))
        // Last Sunday of October: 03:00 falls back to 02:00, so that night is 25 hours long.
        val october = at("2026-10-25T01:00")
        val next = GlanceTime.nextRuleChange(october, rules)
        assertEquals(LocalTime.of(3, 0), next.toLocalTime())
        assertEquals(3, java.time.Duration.between(october, next).toHours())
        assertEquals("+01:00", at("2026-10-26T07:00").offset.toString())
    }

    @Test fun nextRuleChangeWalksThroughTheDay() {
        val order = generateSequence(at("2026-10-28T06:00")) { GlanceTime.nextRuleChange(it, rules) }
            .take(7).map { "%02d:%02d".format(it.hour, it.minute) }.toList()
        assertEquals(listOf("06:00", "07:00", "08:00", "12:00", "17:00", "18:30", "00:00"), order)
    }

    @Test fun commuteFocusRunsFromTheNightToTheEndOfTheReturn() {
        assertFalse(GlanceTime.commuteFocus(at("2026-10-28T02:59"), rules))
        assertTrue(GlanceTime.commuteFocus(at("2026-10-28T03:00"), rules))
        assertTrue(GlanceTime.commuteFocus(at("2026-10-28T12:00"), rules))
        assertTrue(GlanceTime.commuteFocus(at("2026-10-28T18:29"), rules))
        assertFalse(GlanceTime.commuteFocus(at("2026-10-28T18:30"), rules))
        assertFalse(GlanceTime.commuteFocus(at("2026-10-31T08:00"), rules))
    }

    @Test fun weekendTransportLastsUntilMondayCommuteFocus() {
        val sunday = at("2026-10-25T23:59")
        val mondayBeforeFocus = at("2026-10-26T02:59")
        val mondayAtFocus = at("2026-10-26T03:00")
        for (time in listOf(sunday, at("2026-10-26T00:00"), mondayBeforeFocus)) {
            assertFalse(GlanceTime.isGlanceWorkday(time, rules))
            assertEquals("Weekend", GlanceTime.contextLabel(time, rules))
            assertFalse(GlanceTime.commuteFocus(time, rules))
        }
        assertTrue(GlanceTime.isGlanceWorkday(mondayAtFocus, rules))
        assertEquals("Morning", GlanceTime.contextLabel(mondayAtFocus, rules))
        assertTrue(GlanceTime.commuteFocus(mondayAtFocus, rules))
        assertEquals(mondayAtFocus, GlanceTime.nextRuleChange(mondayBeforeFocus, rules))
    }

    @Test fun fridayTransportEndsAtSaturdayFocusStart() {
        assertTrue(GlanceTime.isGlanceWorkday(at("2026-10-31T02:59"), rules))
        assertEquals("Evening", GlanceTime.contextLabel(at("2026-10-31T02:59"), rules))
        assertFalse(GlanceTime.isGlanceWorkday(at("2026-10-31T03:00"), rules))
        assertEquals("Weekend", GlanceTime.contextLabel(at("2026-10-31T03:00"), rules))
        val late = rules.copy(focusStart = LocalTime.of(4, 0))
        assertFalse(GlanceTime.isGlanceWorkday(at("2026-10-26T03:59"), late))
        assertTrue(GlanceTime.isGlanceWorkday(at("2026-10-26T04:00"), late))
    }

    @Test fun partialOrBrokenRulesFallBackToDefaults() {
        assertEquals(LocalTime.of(13, 0), TimeRules.fromJson(JSONObject().put("switchAt", "13:00")).switchAt)
        assertEquals(rules, TimeRules.fromJson(JSONObject().put("workStart", "18:00").put("workEnd", "09:00")))
        assertEquals(rules, TimeRules.fromJson(JSONObject().put("morningStart", "nonsense")))
        assertEquals(rules, TimeRules.fromJson(rules.toJson()))
    }
}
