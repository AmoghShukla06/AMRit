package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.data.ScanPath
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.Screen
import org.amrit.core.BoxAudit
import org.amrit.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** "Check my medicine box": strips are collected on the camera screen, then judged together in one report. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BoxAuditFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()

    private fun vm(): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN) }
    }

    private fun done(v: MainViewModel, name: String): ScanService.Outcome.Done {
        val p = v.g.ref.productsByName(name).first()
        return ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 0)
    }

    @Test fun stripsAreCollectedWithoutLeavingTheCamera() {
        val v = vm()
        v.startBox()
        assertEquals(Screen.Scan, v.screen); assertTrue(v.boxMode)
        v.onBoxOutcome(done(v, "Dolo 650 Tablet"))
        v.onBoxOutcome(done(v, "Crocin Advance Tablet"))
        assertEquals(Screen.Scan, v.screen)
        assertEquals(2, v.box.size)
    }

    @Test fun unidentifiedStripIsListed() {
        val v = vm()
        v.startBox()
        v.onBoxOutcome(done(v, "Dolo 650 Tablet"))
        v.onBoxOutcome(ScanService.Outcome.NotFound("nothing matched", 0))
        assertEquals(1, v.box.size); assertEquals(1, v.boxUnknown)
        v.finishBox()
        assertEquals(Screen.BoxReport, v.screen)
        assertEquals(1, v.boxReport().items.size)
    }

    @Test fun sameStripHeldInViewIsCountedOnce() {
        val v = vm()
        v.startBox()
        v.onBoxOutcome(done(v, "Dolo 650 Tablet")); v.onBoxOutcome(done(v, "Dolo 650 Tablet"))
        assertEquals(1, v.box.size)
    }

    @Test fun reportFindsTheDoubleDoseAndSavesToMedicines() {
        val v = vm()
        v.startBox()
        v.onBoxOutcome(done(v, "Dolo 650 Tablet")); v.onBoxOutcome(done(v, "Crocin Advance Tablet"))
        v.finishBox()
        val r = v.boxReport()
        assertTrue(r.findings.any { it.reason == BoxAudit.Reason.DUPLICATE })
        val before = runBlocking { v.g.dao.cabinetCount(v.profileId.value) }
        val job = v.saveBox()
        repeat(200) { if (job.isCompleted) return@repeat; ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue(job.isCompleted)
        assertEquals(before + 2, runBlocking { v.g.dao.cabinetCount(v.profileId.value) })
        assertEquals(false, v.boxMode)
    }

    @Test fun savingAMedicineAlreadyInTheListDoesNotDuplicateIt() {
        val v = vm()
        fun saveBox(vararg names: String) {
            v.startBox(); names.forEach { v.onBoxOutcome(done(v, it)) }; v.finishBox()
            val job = v.saveBox()
            repeat(200) { if (job.isCompleted) return@repeat; ShadowLooper.idleMainLooper(); Thread.sleep(10) }
            repeat(50) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }  // let the cabinet flow catch up
        }
        saveBox("Dolo 650 Tablet")
        val after1 = runBlocking { v.g.dao.cabinetCount(v.profileId.value) }
        saveBox("Dolo 650 Tablet", "Crocin Advance Tablet")
        assertEquals(after1 + 1, runBlocking { v.g.dao.cabinetCount(v.profileId.value) })
    }
}
