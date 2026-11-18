package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.ui.MainViewModel
import org.amrit.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** "Antibiotics near you": live from /v1/dashboard, falling back to the last saved snapshot, never an error page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NearYouTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()
    private val sample = """{"total_scans":120,"antibiotic_scans":48,"by_group":{"Access":20,"Watch":25,"Reserve":3},
        "watch_reserve_share":0.5833,"banned_scans":2,"rx_answered":30,"without_prescription_share":0.4,
        "regions":[{"region":"411","scans":40,"antibiotic_scans":18,"watch_reserve_share":0.61}],"hidden_regions":4,
        "top_antibiotics":[{"name":"Azithromycin","scans":14},{"name":"Amoxycillin","scans":9}],"daily":[],"k":5,
        "updated_at":"2026-11-15T10:00:00.000Z"}"""

    private fun vm(fetch: suspend () -> String?): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN); it.fetchDashboard = fetch }
    }

    private fun load(v: MainViewModel) {
        val job = v.loadNearYou()
        repeat(200) { if (job.isCompleted) return; ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        error("load did not finish")
    }

    @Test fun liveDataIsShownAndSaved() {
        val v = vm { sample }
        load(v)
        val n = v.nearYou!!
        assertTrue(n.live); assertEquals(48, n.d.antibiotic_scans); assertEquals(0.4, n.d.without_prescription_share!!, 1e-9)
        assertEquals(sample, app.graph.settings.dashboardJson)
    }

    @Test fun nearYouOfflineShowsSnapshot() {
        app.graph.settings.dashboardJson = sample
        app.graph.settings.dashboardAt = 1_700_000_000_000L
        val v = vm { null }
        load(v)
        val n = v.nearYou!!
        assertEquals(false, n.live); assertEquals(120, n.d.total_scans); assertEquals(1_700_000_000_000L, n.fetchedAt)
    }

    @Test fun noDataYetIsNotAnError() {
        app.graph.settings.dashboardJson = null
        val v = vm { null }
        load(v)
        assertNull(v.nearYou); assertTrue(v.nearYouTried)
    }

    @Test fun myAreaIsMyThreeDigitRegion() {
        app.graph.settings.pincode = "411038"
        val v = vm { sample }
        load(v)
        assertEquals("411", v.nearYou!!.myRegion?.region)
    }
}
