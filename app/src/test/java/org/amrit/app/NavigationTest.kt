package org.amrit.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.Screen
import org.amrit.core.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Back from a scan's outcome goes home: the camera, the progress screen and the "which one?" question are done. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavigationTest {
    private fun vm(): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<AmritApp>()
        app.graph.settings.onboarded = true
        return MainViewModel(app)
    }

    private val notFound = Screen.NotFound(ScanService.Outcome.NotFound("x", 0))

    @Test fun backFromAScanResultGoesHomeNotToTheCamera() {
        val v = vm()
        v.go(Screen.Scan); v.go(Screen.Working)
        v.showOutcome(notFound)
        v.back()
        assertEquals(Screen.Home, v.screen)
    }

    @Test fun backAfterAnsweringWhichOneGoesHomeNotToTheQuestion() {
        val v = vm()
        val pending = ScanService.Pending(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), OcrResult(emptyList()), null, 0, 0, 0)
        v.go(Screen.Scan); v.go(Screen.Working)
        v.showOutcome(Screen.Confirm(ScanService.Outcome.Confirm(pending, emptyList(), false)))
        v.go(Screen.Working)            // user tapped a candidate
        v.showOutcome(notFound)
        v.back()
        assertEquals(Screen.Home, v.screen)
    }
}
