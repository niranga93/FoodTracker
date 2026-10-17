package com.example.nourio.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** When the eating window opened, for the "eating window closing" reminder. */
class FastingWindowTest {
    private val zone = ZoneOffset.UTC
    private val d0 = LocalDate.of(2026, 10, 16)
    private fun at(day: Int, hour: Int, min: Int = 0) =
        d0.plusDays(day.toLong()).atTime(hour, min).toInstant(zone).toEpochMilli()

    // Breakfast 9:00, dinner 20:00, then a snack at 1:03 the next night
    private val times = listOf(at(0, 9), at(0, 20), at(1, 1, 3))

    @Test fun aLateNightSnackDoesNotStartANewWindow() {
        assertNull(Reminders.windowStart(times, at(1, 8), zone))              // no 9 am "window closing"
        assertEquals(at(0, 9), Reminders.windowStart(times, at(1, 2), zone))  // before 4 am: still yesterday
    }

    @Test fun breakfastStartsTheWindow() {
        val withBreakfast = times + at(1, 10)
        assertEquals(at(1, 10), Reminders.windowStart(withBreakfast, at(1, 12), zone))   // closes at 18:00 for 16:8
    }

    @Test fun noMealsNoWindow() = assertNull(Reminders.windowStart(emptyList(), at(1, 12), zone))
}
