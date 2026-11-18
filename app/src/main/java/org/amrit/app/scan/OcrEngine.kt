package org.amrit.app.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.amrit.core.OcrLine
import org.amrit.core.OcrResult
import java.io.ByteArrayOutputStream

/** ML Kit text recognition with the model bundled in the APK: works offline, no Play Services download. */
class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Output(val result: OcrResult, val textBox: Rect?, val ms: Long)

    /** Blocking; call off the main thread. */
    fun run(bitmap: Bitmap): Output {
        val t0 = System.nanoTime()
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val lines = ArrayList<OcrLine>()
        var box: Rect? = null
        for (block in text.textBlocks) for (line in block.lines) {
            val bb = line.boundingBox ?: continue
            lines += OcrLine(line.text, bb.height().toFloat(), line.confidence, listOf(bb.left, bb.top, bb.right, bb.bottom))
            box = box?.apply { union(bb) } ?: Rect(bb)
        }
        return Output(OcrResult(lines, bitmap.width, bitmap.height), box, (System.nanoTime() - t0) / 1_000_000)
    }

    companion object {
        /** Long side to which camera frames are scaled before OCR: enough for strip text, keeps OCR fast. */
        const val OCR_LONG_SIDE = 1600

        fun scaleTo(src: Bitmap, longSide: Int, rotationDegrees: Int = 0): Bitmap {
            val s = longSide.toFloat() / maxOf(src.width, src.height)
            val m = Matrix().apply { if (s < 1f) postScale(s, s); if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat()) }
            return if (s >= 1f && rotationDegrees == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        /** What the fallback sends: the text region only, long side <= 1600 px (composition text stays legible), JPEG q85. */
        fun cropForFallback(bitmap: Bitmap, textBox: Rect?): ByteArray {
            val r = textBox?.let {
                val padX = (it.width() * 0.08f).toInt() + 16; val padY = (it.height() * 0.08f).toInt() + 16
                Rect(maxOf(0, it.left - padX), maxOf(0, it.top - padY), minOf(bitmap.width, it.right + padX), minOf(bitmap.height, it.bottom + padY))
            }?.takeIf { it.width() > 64 && it.height() > 64 && it.width() * it.height() > bitmap.width * bitmap.height / 8 }
            val cropped = if (r != null) Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height()) else bitmap
            val small = scaleTo(cropped, 1600)
            return ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
        }
    }
}
