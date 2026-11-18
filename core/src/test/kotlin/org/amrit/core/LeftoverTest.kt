package org.amrit.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftoverTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun anAntibioticSavedWeeksAgoWithNoCourseIsALeftover() {
        assertTrue(Leftover.isLeftover(systemicAntibiotic = true, savedAtMs = now - 30 * day, activeCourse = false, nowMs = now))
    }

    @Test fun aCourseInProgressIsNotALeftover() {
        assertFalse(Leftover.isLeftover(true, now - 30 * day, activeCourse = true, nowMs = now))
    }

    @Test fun aStripSavedThisWeekIsStillTheCurrentPrescription() {
        assertFalse(Leftover.isLeftover(true, now - 3 * day, activeCourse = false, nowMs = now))
        assertTrue(Leftover.isLeftover(true, now - 7 * day, activeCourse = false, nowMs = now))
    }

    @Test fun nonAntibioticsAndSkinOrEyeAntibioticsAreNotFlagged() {
        assertFalse(Leftover.isLeftover(systemicAntibiotic = false, savedAtMs = now - 90 * day, activeCourse = false, nowMs = now))
    }
}
