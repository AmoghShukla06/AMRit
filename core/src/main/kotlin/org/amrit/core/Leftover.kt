package org.amrit.core

/**
 * A leftover antibiotic: a systemic antibiotic the household already had for at least a week, with no course running.
 * Reusing leftovers for a new illness (wrong drug, too few doses) or sharing them is a common driver of resistance.
 * Skin/eye/ear antibiotics are not flagged: they are routinely used again from the same tube.
 */
object Leftover {
    private const val WEEK_MS = 7 * 86_400_000L

    fun isLeftover(systemicAntibiotic: Boolean, savedAtMs: Long, activeCourse: Boolean, nowMs: Long): Boolean =
        systemicAntibiotic && !activeCourse && nowMs - savedAtMs >= WEEK_MS
}
