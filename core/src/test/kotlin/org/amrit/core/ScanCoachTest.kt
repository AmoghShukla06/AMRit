package org.amrit.core

import org.amrit.core.ScanCoach.Hint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoachTest {
    /** w x h luma image, [bright] = fraction of the centre region burnt out (255), rest mid-grey. */
    private fun image(w: Int, h: Int, bright: Double): IntArray {
        val px = IntArray(w * h) { 120 }
        val x0 = w / 4; val y0 = h / 4; val cw = w / 2; val ch = h / 2
        val n = (cw * ch * bright).toInt()
        var k = 0
        for (y in y0 until y0 + ch) for (x in x0 until x0 + cw) { if (k++ < n) px[y * w + x] = 255 }
        return px
    }

    @Test fun glareIsTheBurntOutShareOfTheCentre() {
        assertEquals(0.0, ScanCoach.glare(image(80, 60, 0.0), 80, 60), 1e-9)
        assertEquals(0.25, ScanCoach.glare(image(80, 60, 0.25), 80, 60), 0.01)
    }

    @Test fun burntOutEdgesOutsideTheCentreAreNotGlareOnTheStrip() {
        val px = IntArray(80 * 60) { 120 }
        for (x in 0 until 80) { px[x] = 255; px[59 * 80 + x] = 255 }  // bright window behind, top and bottom rows
        assertEquals(0.0, ScanCoach.glare(px, 80, 60), 1e-9)
    }

    @Test fun glareOutranksEverythingElse() {
        assertEquals(Hint.GLARE, ScanCoach.hint(glare = 0.2, tallestLinePx = 10f, frameWidthPx = 1920, brandSeen = true,
            compositionSeen = false, frames = 6))
    }

    @Test fun tinyTextMeansMoveCloser() {
        assertEquals(Hint.CLOSER, ScanCoach.hint(0.0, tallestLinePx = 14f, frameWidthPx = 1920, brandSeen = false, compositionSeen = false, frames = 3))
    }

    @Test fun brandWithoutCompositionForAWhileMeansTurnTheStripOver() {
        assertEquals(Hint.FLIP, ScanCoach.hint(0.0, 60f, 1920, brandSeen = true, compositionSeen = false, frames = 5))
        // not on the first frames: the composition may simply not have been read yet
        assertEquals(Hint.HOLD, ScanCoach.hint(0.0, 60f, 1920, brandSeen = true, compositionSeen = false, frames = 2))
        assertEquals(Hint.HOLD, ScanCoach.hint(0.0, 60f, 1920, brandSeen = true, compositionSeen = true, frames = 5))
    }

    @Test fun nothingReadYetGivesNoHint() {
        assertEquals(Hint.NONE, ScanCoach.hint(0.0, 0f, 1920, brandSeen = false, compositionSeen = false, frames = 0))
        assertTrue(ScanCoach.hint(0.0, 40f, 1920, false, false, 1) == Hint.HOLD)
    }

    // 2.6: one high-resolution still when the brand is read but the small print isn't
    private fun l(t: String, h: Float) = OcrLine(t, h, 0.9f)
    private val strip = listOf(l("DOLO 650", 80f), l("Each uncoated tablet contans", 14f), l("Paracetamo1 IP 650 mq", 13f),
        l("Mfd by Micro Labs Ltd", 12f), l("Store below 30C", 12f))

    @Test fun finePrintCountsSmallLinesWithRealText() {
        assertEquals(4, ScanCoach.finePrint(strip))
        assertEquals(0, ScanCoach.finePrint(listOf(l("DOLO 650", 80f), l("DOLO", 78f))))
        assertEquals(0, ScanCoach.finePrint(listOf(l("DOLO 650", 80f), l("x1", 10f), l("::", 9f))))  // specks, not text
        assertEquals(0, ScanCoach.finePrint(emptyList()))
    }

    @Test fun stillOnlyWhenBrandReadButSmallPrintUnreadable() {
        assertTrue(ScanCoach.wantsStill(brandSeen = true, compositionSeen = false, frames = 3, finePrintLines = 4, stillTaken = false))
        // too early, already have the ingredients, no brand yet, nothing small to read (wrong side), or already taken once
        assertEquals(false, ScanCoach.wantsStill(true, false, 2, 4, false))
        assertEquals(false, ScanCoach.wantsStill(true, true, 3, 4, false))
        assertEquals(false, ScanCoach.wantsStill(false, false, 3, 4, false))
        assertEquals(false, ScanCoach.wantsStill(true, false, 3, 1, false))
        assertEquals(false, ScanCoach.wantsStill(true, false, 5, 4, true))
    }

    @Test fun stillLinesAreScaledToTheLiveFrame() {
        // a 4000-px still read beside 1920-px frames: heights shrink so the brand still ranks as the biggest print
        val out = ScanCoach.scaleLines(listOf(l("Paracetamol IP 650 mg", 30f)), stillWidth = 4000, frameWidth = 1920)
        assertEquals(14.4f, out.single().height, 0.01f)
        assertEquals("Paracetamol IP 650 mg", out.single().text)
    }
}
