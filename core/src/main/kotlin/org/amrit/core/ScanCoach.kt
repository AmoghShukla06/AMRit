package org.amrit.core

/**
 * What the live scanner should tell the user right now, instead of waiting silently.
 * Priority: glare on the foil, then text too small to read, then the wrong side of the strip, then "hold still".
 */
object ScanCoach {
    enum class Hint { NONE, HOLD, GLARE, CLOSER, FLIP }

    /** Share of burnt-out pixels (luma >= 250) in the centre half of the frame, where the strip is framed. */
    fun glare(luma: IntArray, width: Int, height: Int): Double {
        val x0 = width / 4; val y0 = height / 4; val x1 = x0 + width / 2; val y1 = y0 + height / 2
        var bright = 0; var total = 0
        for (y in y0 until y1) for (x in x0 until x1) { total++; if (luma[y * width + x] >= 250) bright++ }
        return if (total == 0) 0.0 else bright.toDouble() / total
    }

    /**
     * [tallestLinePx]: height of the biggest text line in a frame [frameWidthPx] wide; [brandSeen] / [compositionSeen]:
     * whether a brand candidate / any ingredient has been read so far; [frames]: frames pooled so far.
     */
    fun hint(glare: Double, tallestLinePx: Float, frameWidthPx: Int, brandSeen: Boolean, compositionSeen: Boolean, frames: Int): Hint {
        if (glare >= 0.08) return Hint.GLARE
        if (tallestLinePx <= 0f) return Hint.NONE
        // in a 1920-px frame a strip's brand is 30+ px tall; under ~1% of the width, OCR misreads small print
        if (tallestLinePx < frameWidthPx * 0.01f) return Hint.CLOSER
        if (brandSeen && !compositionSeen && frames >= 4) return Hint.FLIP
        return Hint.HOLD
    }

    /**
     * Lines of small print: under half the height of the biggest text, with at least 8 letters or digits (so specks and
     * stray symbols don't count). Small print that is there but unread is what a sharper still can fix.
     */
    fun finePrint(lines: List<OcrLine>): Int {
        val top = lines.maxOfOrNull { it.height } ?: return 0
        return lines.count { it.height < top * 0.5f && it.text.count { c -> c.isLetterOrDigit() } >= 8 }
    }

    /**
     * Take one full-resolution still (2.6) when live frames found the brand but no ingredient after 3 frames, and
     * small print is in view. Without small print it is the wrong side (the FLIP hint covers that), not a resolution
     * problem. Only once per scan: a still costs about a second of OCR.
     */
    fun wantsStill(brandSeen: Boolean, compositionSeen: Boolean, frames: Int, finePrintLines: Int, stillTaken: Boolean): Boolean =
        !stillTaken && brandSeen && !compositionSeen && frames >= 3 && finePrintLines >= 3

    /** A still's lines, with heights brought to the live frames' scale so "biggest print = brand" still holds. */
    fun scaleLines(lines: List<OcrLine>, stillWidth: Int, frameWidth: Int): List<OcrLine> {
        if (stillWidth <= 0 || frameWidth <= 0) return lines
        val k = frameWidth.toFloat() / stillWidth
        return lines.map { it.copy(height = it.height * k) }
    }
}
