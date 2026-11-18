package org.amrit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class RemindersTest {
    private val h = 3_600_000L
    private val start = 1_800_000_000_000L

    @Test fun firstDoseIsDueRightAway() {
        assertEquals(1, Reminders.dosesDue(start, perDay = 2, days = 5, nowMs = start))
    }

    @Test fun twiceADayMeansOneMoreDoseEveryTwelveHours() {
        assertEquals(1, Reminders.dosesDue(start, 2, 5, start + 11 * h))
        assertEquals(2, Reminders.dosesDue(start, 2, 5, start + 12 * h))
        assertEquals(5, Reminders.dosesDue(start, 2, 5, start + 48 * h))
    }

    @Test fun neverMoreThanTheWholeCourse() {
        assertEquals(10, Reminders.dosesDue(start, 2, 5, start + 30 * 24 * h))
    }

    @Test fun expiringWithinAMonthOrRecentlyExpiredIsWorthATelling() {
        val today = LocalDate.of(2026, 11, 14)
        assertTrue(Reminders.expiresSoon(YearMonth.of(2026, 11), today))   // ends this month
        assertTrue(Reminders.expiresSoon(YearMonth.of(2026, 12), today))   // ends within ~45 days
        assertTrue(Reminders.expiresSoon(YearMonth.of(2026, 10), today))   // already expired
        assertFalse(Reminders.expiresSoon(YearMonth.of(2027, 3), today))
    }
}
