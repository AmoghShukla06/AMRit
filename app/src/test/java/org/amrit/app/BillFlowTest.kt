package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.billSafety
import org.amrit.core.Lang
import org.amrit.core.OcrLine
import org.amrit.core.OcrResult
import org.amrit.core.Texts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Bill saver end to end from OCR lines: items, saving, and the safety check across the bill. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BillFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()
    private fun vm(): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN) }
    }
    private fun show(v: MainViewModel, vararg lines: String) {
        val job = v.showBillFrom(OcrResult(lines.map { OcrLine(it, 20f) }))
        repeat(300) { if (job.isCompleted) return; ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        error("bill not read")
    }

    @Test fun doubleDoseOnTheBillIsFlagged() {
        val v = vm()
        show(v, "DOLO 650 TAB 15 30.50", "CROCIN ADVANCE TAB 20 22.62", "AZITHRAL 500 TAB 3 120.00")
        val b = v.bill!!
        assertEquals(3, b.result.lines.size)
        val safety = billSafety(b, Lang.EN)
        assertTrue(safety.any { it.kind == Texts.Kind.DANGER })                       // paracetamol twice
        assertTrue(safety.any { it.title.contains("Azithral") })                      // the antibiotic is named
    }

    @Test fun unreadableBillShowsNothingMatched() {
        val v = vm()
        show(v, "THANK YOU VISIT AGAIN")
        assertTrue(v.bill!!.result.lines.isEmpty())
    }
}
