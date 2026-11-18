package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.data.ScanPath
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.Screen
import org.amrit.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 4.2: the demo strips show every feature through the real pipeline, without polluting the AMR surveillance data. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DemoModeTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()

    private fun vm(): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN) }
    }

    private fun pick(v: MainViewModel, label: String): ScanService.Outcome.Done {
        val strip = v.g.demo.strips.first { it.label == label }
        v.demoPick(strip)
        repeat(200) {
            ShadowLooper.idleMainLooper()
            (v.screen as? Screen.Result)?.let { return it.outcome }
            Thread.sleep(20)
        }
        error("no result for $label")
    }

    @Test fun everyDemoStripIsInTheDatabase() {
        val v = vm()
        assertTrue(v.g.demo.strips.size >= 5)
        for (s in v.g.demo.strips) assertNotNull(s.label, v.g.demo.candidateFor(s))
    }

    @Test fun demoScansNeverReachTheSurveillanceData() {
        val v = vm()
        val before = runBlocking { v.g.dao.pending(1000).size }
        val o = pick(v, "Azithral")
        assertEquals(ScanPath.DEMO, o.path)
        assertNotNull("prescription question still shown", o.event)
        val job = v.answerPrescription(o, false)
        repeat(200) { if (job.isCompleted) return@repeat; ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue(job.isCompleted)
        assertEquals(before, runBlocking { v.g.dao.pending(1000).size })
    }

    @Test fun batchDemoStripShowsTheCdscoAlert() {
        val v = vm()
        val o = pick(v, "Telma-AM batch")
        assertEquals("18230791", o.batch)
        assertTrue(o.batchAlerts.isNotEmpty())
        assertNotNull(o.expiry)
    }
}
