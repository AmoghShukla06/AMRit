package org.amrit.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.amrit.app.data.ScanPath
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.AmritTheme
import org.amrit.app.ui.AppFrame
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.Screen
import org.amrit.app.ui.skinFor
import org.amrit.core.Lang
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 3.9: contrast, TalkBack labels and the largest system font size. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi")
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()
    private val out = File(System.getProperty("amrit.shots") ?: "build/screenshots").apply { mkdirs() }

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return hi.toDouble() / lo
    }

    /** WCAG AA: 4.5:1 for body text. Every text/background pair the skin uses, in light and dark. */
    @Test fun everyTextColourMeetsAA() {
        val fails = ArrayList<String>()
        for (dark in listOf(false, true)) {
            val s = skinFor(Lang.EN, dark)
            val pairs = mapOf(
                "ink/bg" to (s.ink to s.bg), "ink/card" to (s.ink to s.card), "muted/bg" to (s.muted to s.bg), "muted/card" to (s.muted to s.card),
                "onBrand/brand" to (s.onBrand to s.brand), "onBrandSoft/brandSoft" to (s.onBrandSoft to s.brandSoft), "brandInk/card" to (s.brandInk to s.card), "brandInk/bg" to (s.brandInk to s.bg),
                "onAccent/accent" to (s.onAccent to s.accent), "onInverse/inverse" to (s.onInverse to s.inverse),
                "onInverseMuted/inverse" to (s.onInverseMuted to s.inverse), // the generic card is navy in light mode and a dark card in dark mode
                "accent/genericBg" to (s.accent to (if (dark) s.card else s.inverse)),
                "danger/dangerBg" to (s.danger to s.dangerBg), "warn/warnBg" to (s.warn to s.warnBg),
                "good/goodBg" to (s.good to s.goodBg), "info/infoBg" to (s.info to s.infoBg),
                "foilInk/foilTop" to (s.foilInk to s.foilTop), "foilInk/foilBottom" to (s.foilInk to s.foilBottom),
                "foilMuted/foilTop" to (s.foilMuted to s.foilTop), "foilMuted/foilBottom" to (s.foilMuted to s.foilBottom),
            )
            for ((name, p) in pairs) ratio(p.first, p.second).let { if (it < 4.5) fails += "${if (dark) "dark" else "light"} $name %.2f".format(it) }
        }
        assertTrue("below 4.5:1: $fails", fails.isEmpty())
    }

    private fun vm(): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(Lang.EN) }
    }

    private fun show(v: MainViewModel, s: Screen) {
        compose.setContent { AmritTheme(v.lang, v.largeText) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(v, s) } } }
        compose.waitForIdle(); Thread.sleep(400); compose.waitForIdle()
    }

    /** Everything TalkBack can tap must say what it is (text or a content description), merged as TalkBack reads it. */
    private fun unlabelled(): List<String> =
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { n ->
            val c = n.config
            c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() && c.getOrNull(SemanticsProperties.Text).isNullOrEmpty() &&
                c.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
        }.map { "${it.boundsInRoot} ${it.config}" }

    private fun result(v: MainViewModel) = v.g.ref.productsByName("Azithral 500 Tablet").first().let { p ->
        Screen.Result(ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 900))
    }

    @Test fun homeButtonsAreLabelled() { val v = vm(); show(v, Screen.Home); assertTrue(unlabelled().toString(), unlabelled().isEmpty()) }
    @Test fun resultButtonsAreLabelled() { val v = vm(); show(v, result(v)); assertTrue(unlabelled().toString(), unlabelled().isEmpty()) }
    @Test fun settingsButtonsAreLabelled() { val v = vm(); show(v, Screen.Settings); assertTrue(unlabelled().toString(), unlabelled().isEmpty()) }
    @Test fun medicinesButtonsAreLabelled() { val v = vm(); show(v, Screen.Medicines); assertTrue(unlabelled().toString(), unlabelled().isEmpty()) }
    @Test fun searchButtonsAreLabelled() { val v = vm(); show(v, Screen.Search); assertTrue(unlabelled().toString(), unlabelled().isEmpty()) }

    private fun labelled(s: Screen, prep: (MainViewModel) -> Unit = {}) {
        val v = vm(); prep(v); show(v, s)
        assertTrue("$s ${unlabelled()}", unlabelled().isEmpty())
    }
    @Test fun passportIsLabelled() = labelled(Screen.Passport)
    @Test fun nearYouIsLabelled() = labelled(Screen.NearYou) { it.fetchDashboard = { null } }
    @Test fun billIsLabelled() = labelled(Screen.Bill) { it.openBill() }
    @Test fun boxReportIsLabelled() = labelled(Screen.BoxReport) { v ->
        v.startBox()
        val p = v.g.ref.productsByName("Dolo 650 Tablet").first()
        v.onBoxOutcome(ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 0))
    }

    /** Android's largest font size (2.0x) together with the app's own larger text: screenshots for review. */
    @Test fun largestSystemFont() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val v = vm(); v.updateLargeText(true)
            try {
                show(v, Screen.Home); compose.onRoot().captureRoboImage(File(out, "23_home_font2x.png").path)
            } finally { v.updateLargeText(false) }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun largestSystemFontResult() {
        RuntimeEnvironment.setFontScale(2f)
        try { val v = vm(); show(v, result(v)); compose.onRoot().captureRoboImage(File(out, "24_result_font2x.png").path) }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
