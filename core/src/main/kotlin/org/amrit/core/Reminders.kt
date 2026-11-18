package org.amrit.core

import java.time.LocalDate
import java.time.YearMonth

/** When to remind: a dose that is due and not logged, and a saved medicine that is about to expire (or just has). */
object Reminders {
    /** Doses that should have been taken by [nowMs]: the first at the start, then one every 24/perDay hours, capped at the course. */
    fun dosesDue(startedAtMs: Long, perDay: Int, days: Int, nowMs: Long): Int {
        if (perDay <= 0 || days <= 0 || nowMs < startedAtMs) return 0
        val interval = 24 * 3_600_000L / perDay
        return minOf(perDay * days, ((nowMs - startedAtMs) / interval + 1).toInt())
    }

    /** Expires this month or next, or expired within the last two months: worth one notification. */
    fun expiresSoon(expiry: YearMonth, today: LocalDate): Boolean {
        val now = YearMonth.from(today)
        return !expiry.isAfter(now.plusMonths(1)) && !expiry.isBefore(now.minusMonths(2))
    }
}
