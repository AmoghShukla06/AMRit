package org.amrit.app.scan

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.amrit.core.PackCode
import org.amrit.core.PackCodes

/**
 * Reads the pack's machine-readable code (Schedule H2 QR, or a GS1 DataMatrix) with ML Kit's bundled model, offline.
 * Returns null when there is no code in the frame, which is the case for most strips outside the top-300 brands.
 */
class CodeReader {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX).build())

    /** Blocking; call off the main thread. */
    fun read(bitmap: Bitmap): PackCode? = runCatching {
        Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)))
            .firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
            ?.let { PackCodes.parse(it) }
    }.getOrNull()
}
