package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.data.CourseEntity
import org.amrit.app.data.ScanPath
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.MainViewModel
import org.amrit.core.Aware
import org.amrit.core.Lang
import org.amrit.core.Passport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The antibiotic passport is built per person from courses, prescription answers and medicines kept at home. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PassportFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()

    private fun vm(): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN) }
    }

    private fun done(v: MainViewModel, name: String): ScanService.Outcome.Done {
        val p = v.g.ref.productsByName(name).first()
        return ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 0,
            event = org.amrit.core.ScanEvent.from(v.g.rules.evaluate(p, p.items), null))
    }

    private fun settle(job: kotlinx.coroutines.Job) {
        repeat(200) { if (job.isCompleted) return; ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        error("job did not finish")
    }

    @Test fun passportIsPerPerson() {
        val v = vm()
        val me = v.profileId.value
        settle(v.startCourse(done(v, "Azithral 500 Tablet"), 3, 1))
        runBlocking { v.g.dao.startCourse(CourseEntity(productName = "Ciplox 500 Tablet", antibiotic = "Ciprofloxacin",
            startedAt = System.currentTimeMillis(), days = 5, dosesPerDay = 2, profileId = me + 100)) }
        val entries = runBlocking { v.passport(me) }
        assertEquals(listOf("Azithral 500 Tablet"), entries.map { it.product })
        assertEquals(Aware.Watch, entries.single().aware)
        assertEquals(Passport.Status.ONGOING, entries.single().status)
    }

    @Test fun prescriptionAnswerIsKeptOnTheCourse() {
        val v = vm()
        val o = done(v, "Azithral 500 Tablet")
        settle(v.answerPrescription(o, false))
        settle(v.startCourse(o, 3, 1))
        assertEquals(false, runBlocking { v.passport(v.profileId.value) }.single { it.product == "Azithral 500 Tablet" }.prescribed)
    }

    @Test fun removingACourseEarlyIsStoppedEarly() {
        val v = vm()
        settle(v.startCourse(done(v, "Azithral 500 Tablet"), 3, 1))
        val id = runBlocking { v.g.dao.coursesOf(v.profileId.value) }.first().id
        settle(v.endCourse(id))
        assertEquals(Passport.Status.STOPPED_EARLY, runBlocking { v.passport(v.profileId.value) }.first().status)
    }

    @Test fun antibioticKeptAtHomeIsListed() {
        val v = vm()
        val o = done(v, "Augmentin 625 Duo Tablet")
        runBlocking { v.g.scans.addToCabinet(o.report, v.profileId.value, null) }
        val e = runBlocking { v.passport(v.profileId.value) }.single { it.product == "Augmentin 625 Duo Tablet" }
        assertEquals(Passport.Status.IN_HOUSE, e.status)
    }
}
