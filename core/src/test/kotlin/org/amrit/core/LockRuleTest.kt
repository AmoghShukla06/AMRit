package org.amrit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the live scanner stops and answers. In box mode the camera stays open between strips, so it never times out. */
class LockRuleTest {
    private fun lock(band: Band, elapsed: Long, box: Boolean = false, stable: Int = 0, composition: Boolean = false, frames: Int = 1,
                     packNames: Boolean = false, stillPending: Boolean = false, forced: Boolean = false) =
        LockRule.shouldLock(band, stable, composition, frames, packNames, elapsed, stillPending, forced, box)

    @Test fun acceptLocksOnceStable() {
        assertEquals(false, lock(Band.ACCEPT, 500, stable = 1))
        assertEquals(true, lock(Band.ACCEPT, 500, stable = 2))
        assertEquals(true, lock(Band.ACCEPT, 500, stable = 2, box = true))
        assertEquals(true, lock(Band.ACCEPT, 500, composition = true, frames = 2))
    }

    @Test fun confirmAfterAWhile() {
        assertEquals(false, lock(Band.CONFIRM, 3000))
        assertEquals(true, lock(Band.CONFIRM, 5000))
        assertEquals(true, lock(Band.CONFIRM, 5000, box = true))
        assertEquals(false, lock(Band.CONFIRM, 5000, stillPending = true))
    }

    @Test fun singleScanGivesUpAfterSixAndAHalfSeconds() {
        assertEquals(false, lock(Band.FALLBACK, 6000))
        assertEquals(true, lock(Band.FALLBACK, 7000))
        assertEquals(false, lock(Band.FALLBACK, 7000, stillPending = true))
        assertEquals(true, lock(Band.FALLBACK, 9500, stillPending = true))
    }

    @Test fun boxModeNeverTimesOutOnNothing() {
        // the camera is pointed at the table between strips: that is not an unidentified strip
        assertEquals(false, lock(Band.FALLBACK, 60_000, box = true))
    }

    @Test fun shutterAlwaysLocks() { assertEquals(true, lock(Band.FALLBACK, 10, forced = true, box = true)) }
}
