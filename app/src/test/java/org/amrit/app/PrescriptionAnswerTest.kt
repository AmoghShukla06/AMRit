package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.data.PendingEventEntity
import org.amrit.app.data.ScanPath
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.MainViewModel
import org.amrit.core.ScanEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The answer travels inside the same anonymous event: same random id, one queued row, nothing else added. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrescriptionAnswerTest {
    @Test fun answeringReQueuesTheSameEventWithTheAnswer() {
        val app = ApplicationProvider.getApplicationContext<AmritApp>()
        app.graph.settings.onboarded = true
        val v = MainViewModel(app)
        val p = v.g.ref.productsByName("Azithral 500 Tablet").first()
        val report = v.g.rules.evaluate(p, p.items)
        val ev = ScanEvent.from(report, "201309")
        runBlocking { v.g.dao.queue(PendingEventEntity(ev.event_id, kotlinx.serialization.json.Json.encodeToString(ScanEvent.serializer(), ev), 1)) }
        val o = ScanService.Outcome.Done(report, ScanPath.ON_DEVICE, 1.0, 0, event = ev)

        v.answerPrescription(o, prescribed = false)
        repeat(50) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }

        val rows = runBlocking { v.g.dao.pending(10) }.filter { it.eventId == ev.event_id }
        assertEquals(1, rows.size)
        assertTrue(rows[0].json, rows[0].json.contains("\"prescribed\":false"))
    }
}
