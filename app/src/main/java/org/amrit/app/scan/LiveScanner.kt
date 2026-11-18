package org.amrit.app.scan

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.amrit.app.AppGraph
import org.amrit.core.Band
import org.amrit.core.Evidence
import org.amrit.core.OcrResult
import org.amrit.core.PackCode
import org.amrit.core.Resolution

/**
 * Reads the camera stream instead of one still photo. Each analysed frame (~2 MP) goes through ML Kit; the last few
 * frames' text is pooled, so a word lost to glare in one frame is read in another, and strengths are decided by vote
 * (CompositionReader counts every reading). Locks automatically when the evidence rules give a stable answer.
 */
class LiveScanner(private val g: AppGraph, private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        /** short things read so far, for the on-screen chips (UI thread NOT guaranteed) */
        fun onSeen(chips: List<String>)
        fun onLocked(result: Locked)
        /** what to tell the user now (glare, move closer, turn the strip over, hold still) */
        fun onHint(hint: org.amrit.core.ScanCoach.Hint) {}
        /** take one full-resolution still and hand it to [addStill] (or call [stillFailed]) */
        fun onWantStill() {}
    }

    class Locked(val bitmap: Bitmap, val ocr: OcrResult, val textBox: Rect?, val res: Resolution, val t0: Long, val ocrMs: Long,
                 val packCode: PackCode? = null,
                 /** the lines (with boxes) of [bitmap] itself, for "What we read" */
                 val frameLines: List<org.amrit.core.OcrLine> = emptyList())

    private val frames = ArrayDeque<OcrResult>()
    private var bestFrame: Bitmap? = null
    private var bestBox: Rect? = null
    private var bestLines: List<org.amrit.core.OcrLine> = emptyList()
    private var bestChars = -1
    private var ocrMsTotal = 0L
    private var lastSignature: String? = null
    private var stableCount = 0
    private var t0 = System.nanoTime()
    @Volatile private var active = true
    @Volatile private var forceLock = false
    /** "Check my medicine box": the camera stays open between strips, so never give up on a timer */
    @Volatile var boxMode = false
    /** the pack's QR/DataMatrix, once read in any frame */
    private var packCode: PackCode? = null
    /** 2.6: lines read from one high-resolution still, kept in the pool for the rest of this scan */
    private var stillLines: List<org.amrit.core.OcrLine> = emptyList()
    private var stillTaken = false
    @Volatile private var stillPending = false
    private var frameWidth = 0

    fun reset() {
        frames.clear(); bestFrame = null; bestBox = null; bestLines = emptyList(); bestChars = -1; ocrMsTotal = 0
        lastSignature = null; stableCount = 0; t0 = System.nanoTime(); forceLock = false; active = true; packCode = null
        stillLines = emptyList(); stillTaken = false; stillPending = false
    }

    fun pause() { active = false }

    /** Shutter button: decide now with whatever has been read. */
    fun lockNow() { forceLock = true }

    override fun analyze(image: ImageProxy) {
        if (!active) { image.close(); return }
        val resolver = g.resolverNow
        if (resolver == null) { image.close(); return }
        val rot = image.imageInfo.rotationDegrees
        val raw = try { image.toBitmap() } finally { image.close() }
        val frame = OcrEngine.scaleTo(raw, 1920, rot)
        frameWidth = frame.width
        val out = g.ocr.run(frame)
        ocrMsTotal += out.ms
        val chars = out.result.lines.sumOf { it.text.length }
        if (chars > bestChars) { bestChars = chars; bestFrame = frame; bestBox = out.textBox; bestLines = out.result.lines }
        if (out.result.lines.isNotEmpty()) {
            frames.addLast(out.result)
            while (frames.size > 6) frames.removeFirst()
        }
        if (packCode == null) packCode = g.codes.read(frame)
        // names from the pack's code are read like the biggest print on the strip
        val pooled = frames.flatMap { it.lines } + stillLines
        val codeLines = packCode?.asOcrLines(pooled.maxOfOrNull { it.height } ?: 100f).orEmpty()
        val merged = OcrResult(pooled + codeLines, frame.width, frame.height)
        val res = resolver.resolve(merged)

        listener.onSeen(chips(res) + listOfNotNull(packCode?.let { "code" }))
        val brandSeen = res.best?.let { it.evidence.rank >= Evidence.WEAK.rank && it.product.baseKey.isNotBlank() } == true || packCode?.hasNames == true
        // brand read, small print in view but unreadable at preview resolution: one sharp still of it
        if (org.amrit.core.ScanCoach.wantsStill(brandSeen, res.found.isNotEmpty(), frames.size,
                org.amrit.core.ScanCoach.finePrint(out.result.lines), stillTaken)) {
            stillTaken = true; stillPending = true
            android.util.Log.i("AMRit", "live: taking a full-resolution still for the small print")
            listener.onWantStill()
        }
        listener.onHint(if (stillPending) org.amrit.core.ScanCoach.Hint.HOLD else org.amrit.core.ScanCoach.hint(
            glare = glareOf(frame), tallestLinePx = out.result.lines.maxOfOrNull { it.height } ?: 0f, frameWidthPx = frame.width,
            brandSeen = brandSeen, compositionSeen = res.found.isNotEmpty(), frames = frames.size))

        val elapsed = (System.nanoTime() - t0) / 1_000_000
        val sig = res.best?.compositionSignature
        if (res.band == Band.ACCEPT && sig == lastSignature) stableCount++ else stableCount = if (res.band == Band.ACCEPT) 1 else 0
        lastSignature = sig
        val lock = org.amrit.core.LockRule.shouldLock(res.band, stableCount, res.best?.evidence == Evidence.COMPOSITION, frames.size,
            packCode?.hasNames == true, elapsed, stillPending, forceLock, boxMode)
        if (lock && (bestFrame != null)) {
            active = false
            // diagnostics (local device log only): adb logcat -s AMRit
            android.util.Log.i("AMRit", "live lock after ${elapsed}ms frames=${frames.size} band=${res.band} " +
                res.candidates.joinToString { "${it.product.name}/${it.evidence}" } +
                " found=" + res.found.values.joinToString { "${it.ingredient.name} ${it.strength}" } +
                " text=" + merged.lines.distinctBy { it.text }.sortedByDescending { it.height }.take(12).joinToString(" | ") { it.text })
            listener.onLocked(Locked(bestFrame!!, merged, bestBox, res, t0, ocrMsTotal, packCode, bestLines))
        }
    }

    /**
     * The still from [Listener.onWantStill]: rotated upright, cropped to where text was seen (or the frame guide),
     * OCR'd once at up to 3000 px, and pooled with the live frames. Called on the analysis thread, never stored.
     */
    fun addStill(still: Bitmap, rotationDegrees: Int) {
        try {
            if (!active || frameWidth == 0) return
            val up = OcrEngine.scaleTo(still, maxOf(still.width, still.height), rotationDegrees)
            val k = up.width.toFloat() / frameWidth
            val box = bestBox?.let { b ->
                val padX = b.width() * 0.1f + 24; val padY = b.height() * 0.15f + 24
                Rect(maxOf(0, ((b.left - padX) * k).toInt()), maxOf(0, ((b.top - padY) * k).toInt()),
                    minOf(up.width, ((b.right + padX) * k).toInt()), minOf(up.height, ((b.bottom + padY) * k).toInt()))
            }?.takeIf { it.width() > 200 && it.height() > 100 }
                ?: Rect((up.width * 0.06f).toInt(), (up.height * 0.2f).toInt(), (up.width * 0.94f).toInt(), (up.height * 0.8f).toInt())
            val crop = OcrEngine.scaleTo(Bitmap.createBitmap(up, box.left, box.top, box.width(), box.height()), 3000)
            val out = g.ocr.run(crop)
            ocrMsTotal += out.ms
            // heights back to the live frame's scale: crop px -> still px (box.width / crop.width) -> frame px (1 / k)
            val asFrame = crop.width.toFloat() * frameWidth * k / box.width()
            stillLines = org.amrit.core.ScanCoach.scaleLines(out.result.lines, stillWidth = asFrame.toInt().coerceAtLeast(1),
                frameWidth = frameWidth)
            android.util.Log.i("AMRit", "live still: ${crop.width}x${crop.height} ${out.ms}ms lines=${stillLines.size}")
        } catch (e: Throwable) {
            android.util.Log.w("AMRit", "live still failed", e)
        } finally { stillPending = false }
    }

    fun stillFailed() { stillPending = false }

    /** Burnt-out share of the frame centre, measured on a 96-px thumbnail (cheap: ~7k pixels). */
    private fun glareOf(frame: Bitmap): Double {
        val small = OcrEngine.scaleTo(frame, 96)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val luma = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
        return org.amrit.core.ScanCoach.glare(luma, small.width, small.height)
    }

    private fun chips(res: Resolution): List<String> {
        val out = ArrayList<String>()
        res.best?.takeIf { it.evidence.rank >= Evidence.WEAK.rank && it.product.baseKey.isNotBlank() }?.let { out += it.product.name.substringBefore(" Tablet").substringBefore(" Capsule") }
        res.found.values.filter { it.exact }.take(3).forEach { f -> out += listOfNotNull(f.ingredient.name, f.strength?.toString()).joinToString(" ") }
        return out.distinct().take(4)
    }
}
