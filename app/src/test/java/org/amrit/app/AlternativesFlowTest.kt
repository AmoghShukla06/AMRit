package org.amrit.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.ui.MainViewModel
import org.amrit.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Other brands with the same salt after a scan, and where to buy them near the user. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlternativesFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()
    private fun vm(): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN) }
    }

    @Test fun fiveCheapestSameSaltBrands() {
        val v = vm()
        val dolo = v.g.ref.productsByName("Dolo 650 Tablet").first()
        val a = runBlocking { v.alternatives(dolo) }
        assertEquals(5, a.options.size)
        assertTrue(a.options.all { it.product.compositionId == dolo.compositionId && it.product.form == dolo.form })
    }

    @Test fun mapSearchUsesTheSavedPinCode() {
        val v = vm()
        v.g.settings.pincode = "411038"
        assertEquals("geo:0,0?q=Jan%20Aushadhi%20Kendra%20411038", v.kendraIntent().data.toString())
        assertEquals("geo:0,0?q=pharmacy%20411038", v.pharmacyIntent().data.toString())
        v.g.settings.pincode = null
        assertEquals("geo:0,0?q=pharmacy", v.pharmacyIntent().data.toString())
        assertEquals("https://janaushadhi.gov.in/KendraDetails.aspx", v.kendraLocatorIntent().data.toString())
    }
}
