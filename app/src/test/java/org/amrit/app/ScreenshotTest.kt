package org.amrit.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.amrit.app.data.CabinetEntity
import org.amrit.app.data.CourseEntity
import org.amrit.app.data.ScanPath
import org.amrit.app.scan.ScanService
import org.amrit.app.ui.AmritTheme
import org.amrit.app.ui.AppFrame
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.Screen
import org.amrit.core.Lang
import org.amrit.core.LlmReading
import org.amrit.core.OcrResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens (real reference DB, real rule engine) to PNGs in app/screenshots/ for visual review
 * without a phone: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File(System.getProperty("amrit.shots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()

    private fun vm(lang: Lang = Lang.EN): MainViewModel {
        val g = app.graph
        runBlocking { g.resolver() }
        g.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(lang) }
    }

    private fun shot(name: String, dark: Boolean, v: MainViewModel, s: Screen) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent { AmritTheme(v.lang, v.largeText) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(v, s) } } }
        compose.waitForIdle()
        Thread.sleep(400) // produceState loads (uses, cabinet previews) run on background dispatchers
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name${if (dark) "_dark" else ""}.png").path)
    }

    private fun result(vm: MainViewModel, productName: String, path: ScanPath = ScanPath.ON_DEVICE, ms: Long = 1240): Screen {
        val p = vm.g.ref.productsByName(productName).first()
        return Screen.Result(ScanService.Outcome.Done(vm.g.rules.evaluate(p, p.items), path, 1.0, ms))
    }

    private fun becosules(vm: MainViewModel): Screen {
        val reading = Json { ignoreUnknownKeys = true }.decodeFromString(LlmReading.serializer(),
            javaClass.getResource("/becosules_reading.json")!!.readText())
        val r = runBlocking { vm.g.resolver() }.resolveReadings(listOf(reading, reading.copy(ingredients = reading.ingredients.map {
            if (it.name.startsWith("Niacinamide")) it.copy(strength = "100 mg") else it }) ), null)
        val c = r.best!!
        return Screen.Result(ScanService.Outcome.Done(vm.g.rules.evaluate(c.product, c.items), ScanPath.API, 1.0, 3800))
    }

    private fun seedCabinet(vm: MainViewModel) = runBlocking {
        val dao = vm.g.dao
        val pid = vm.profileId.value
        val soon = java.time.YearMonth.now().plusMonths(1).toString()
        for ((n, exp) in listOf("Dolo 650 Tablet" to null, "Azithral 500 Tablet" to "2027-08", "Combiflam Tablet" to soon, "Crocin Advance Tablet" to null)) {
            val p = vm.g.ref.productsByName(n).first()
            dao.addToCabinet(CabinetEntity(productId = p.id, displayName = p.name, addedAt = System.currentTimeMillis(), profileId = pid, expiry = exp))
        }
        dao.startCourse(CourseEntity(productName = "Azithral 500 Tablet", antibiotic = "Azithromycin",
            startedAt = System.currentTimeMillis() - 86_400_000L, days = 3, dosesPerDay = 1, dosesTaken = 1, profileId = vm.profileId.value))
    }

    // ------------------------------------------------------------------ cases
    @Test fun onboarding() { val v = vm(); shot("01_onboarding", false, v, Screen.Onboarding) }
    @Test fun onboardingDark() { val v = vm(); shot("01_onboarding", true, v, Screen.Onboarding) }

    @Test fun homeEmpty() { val v = vm(); shot("02_home_empty", false, v, Screen.Home) }
    @Test fun homeFull() { val v = vm(); seedCabinet(v); val s = Screen.Home; Thread.sleep(800)
        shot("03_home", false, v, s) }
    @Test fun homeFullDark() { val v = vm(); seedCabinet(v); Thread.sleep(800); shot("03_home", true, v, Screen.Home) }
    @Test fun homeHindi() { val v = vm(Lang.HI); seedCabinet(v); Thread.sleep(800); shot("03_home_hi", false, v, Screen.Home) }

    @Test fun resultAccess() { val v = vm(); val s = result(v, "Augmentin 625 Duo Tablet"); shot("10_result_augmentin", false, v, s) }
    @Test fun resultWatch() { val v = vm(); val s = result(v, "Azithral 500 Tablet"); shot("11_result_azithral", false, v, s) }
    @Test fun resultWatchDark() { val v = vm(); val s = result(v, "Azithral 500 Tablet"); shot("11_result_azithral", true, v, s) }
    @Test fun resultBanned() { val v = vm(); val s = result(v, "Nixar 100mg/500mg Tablet DT"); shot("12_result_banned", false, v, s) }
    @Test fun resultGeneric() { val v = vm(); seedCabinet(v); Thread.sleep(800); val s = result(v, "Crocin Advance Tablet")
        shot("13_result_crocin_double_dose", false, v, s) }
    @Test fun resultStripRead() { val v = vm(); val s = becosules(v); shot("14_result_becosules", false, v, s) }
    @Test fun resultHindi() { val v = vm(Lang.HI); val s = result(v, "Azithral 500 Tablet"); shot("15_result_azithral_hi", false, v, s) }
    @Test fun resultHindiDoubleDose() { val v = vm(Lang.HI); seedCabinet(v); Thread.sleep(800); val s = result(v, "Crocin Advance Tablet")
        shot("15_result_crocin_hi", false, v, s) }
    @Test fun resultHindiDark() { val v = vm(Lang.HI); val s = result(v, "Augmentin 625 Duo Tablet"); shot("15_result_augmentin_hi", true, v, s) }
    @Test fun medicines() { val v = vm(); seedCabinet(v); Thread.sleep(800); shot("04_medicines", false, v, Screen.Medicines) }
    @Test fun medicinesHindi() { val v = vm(Lang.HI); seedCabinet(v); Thread.sleep(800); shot("04_medicines_hi", false, v, Screen.Medicines) }
    @Test fun homeHindiDark() { val v = vm(Lang.HI); seedCabinet(v); Thread.sleep(800); shot("03_home_hi", true, v, Screen.Home) }
    @Test fun notFoundHindi() { val v = vm(Lang.HI); shot("21_not_found_hi", false, v, Screen.NotFound(ScanService.Outcome.NotFound("strip not legible", 0))) }
    private fun withPack(v: MainViewModel, name: String, qr: ScanService.QrCheck, qrBrand: String?): Screen {
        val p = v.g.ref.productsByName(name).first()
        return Screen.Result(ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 900,
            java.time.YearMonth.of(2027, 8), "DLP2201", qr, qrBrand))
    }
    /** A saved medicine whose batch CDSCO later flagged: found by the sync re-check, shown on Home and in Medicines. */
    private fun seedFlagged(v: MainViewModel) {
        val p = v.g.ref.productsByName("Telma-AM Tablet").first()
        runBlocking {
            v.g.dao.addToCabinet(CabinetEntity(productId = p.id, displayName = p.name, addedAt = System.currentTimeMillis(),
                profileId = v.profileId.value, batch = "18230791"))
            check(v.g.scans.recheckCabinet().size == 1) { "re-check should flag the spurious batch" }
            check(v.g.scans.recheckCabinet().isEmpty()) { "a flagged medicine is reported only once" }
        }
        Thread.sleep(800)
    }
    @Test fun flaggedInMedicines() { val v = vm(); seedFlagged(v); shot("05_medicines_flagged", false, v, Screen.Medicines) }
    @Test fun flaggedOnHome() { val v = vm(); seedFlagged(v); shot("05_home_flagged", false, v, Screen.Home) }

    /** Azithral saved a month ago, no course running, scanned again: leftover warning + disposal guidance. */
    @Test fun resultLeftoverAntibiotic() {
        val v = vm(); val p = v.g.ref.productsByName("Azithral 500 Tablet").first()
        runBlocking {
            v.g.dao.addToCabinet(CabinetEntity(productId = p.id, displayName = p.name, addedAt = System.currentTimeMillis() - 30 * 86_400_000L,
                profileId = v.profileId.value))
        }
        // the cabinet flow computes off the main thread, then delivers on Main (paused under Robolectric): pump until loaded
        repeat(100) { if (v.cabinet.value.meds.isEmpty()) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50) } }
        val s = result(v, "Azithral 500 Tablet")
        check(v.leftoverOf((s as Screen.Result).outcome.report) != null) { "a month-old antibiotic with no course is a leftover" }
        shot("16_result_leftover", false, v, s)
    }

    /** After a real scan of an antibiotic: the anonymous "sold with a prescription?" question. */
    @Test fun resultPrescriptionQuestion() {
        val v = vm(); val p = v.g.ref.productsByName("Azithral 500 Tablet").first()
        val rep = v.g.rules.evaluate(p, p.items)
        val s = Screen.Result(ScanService.Outcome.Done(rep, ScanPath.ON_DEVICE, 1.0, 900, event = org.amrit.core.ScanEvent.from(rep, null)))
        shot("16_result_rx_question", false, v, s)
    }

    /** Real record: CDSCO declared Telma AM batch 18230791 spurious (Aug 2026). */
    @Test fun resultBatchSpurious() {
        val v = vm(); val p = v.g.ref.productsByName("Telma-AM Tablet").first()
        val alerts = org.amrit.core.BatchAlerts.check(v.g.ref, "18230791", p, p.items)
        val s = Screen.Result(ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 900,
            java.time.YearMonth.of(2027, 5), "18230791", batchAlerts = alerts))
        shot("19_result_batch_spurious", false, v, s)
    }
    @Test fun resultQrMatch() { val v = vm(); val s = withPack(v, "Dolo 650 Tablet", ScanService.QrCheck.MATCH, "DOLO 650")
        shot("17_result_qr_match", false, v, s) }
    @Test fun resultQrMismatch() { val v = vm(); val s = withPack(v, "Dolo 650 Tablet", ScanService.QrCheck.MISMATCH, "CALPOL 650")
        shot("18_result_qr_mismatch", false, v, s) }
    @Test fun resultLargeText() { val v = vm(); v.updateLargeText(true)
        try { shot("20_result_large_text", false, v, result(v, "Azithral 500 Tablet")) } finally { v.updateLargeText(false) } }
    @Test fun settingsLargeText() { val v = vm(Lang.HI); v.updateLargeText(true)
        try { shot("21_settings_large_hi", false, v, Screen.Settings) } finally { v.updateLargeText(false) } }
    /** A drawn strip (no real photo in the repo) with the lines laid out where OCR would box them. */
    private fun seenDolo(v: MainViewModel): Screen.WhatWeRead {
        val bmp = Bitmap.createBitmap(1280, 860, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 1280f, 860f,
            0xFFD9DEE2.toInt(), 0xFFAEB7BE.toInt(), android.graphics.Shader.TileMode.CLAMP) })
        val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C2A44.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val lines = ArrayList<org.amrit.core.OcrLine>()
        var y = 80f
        for ((t, size) in listOf("DOLO-650" to 130f, "Each uncoated tablet contains:" to 38f, "Paracetamol IP 650 mg" to 40f,
                "Mfd. by Micro Labs Limited" to 32f, "B.No. DOBS4021" to 34f, "EXP. 08/2027" to 34f)) {
            ink.textSize = size
            val top = y; y += size; c.drawText(t, 70f, y, ink)
            val w = ink.measureText(t)
            lines += org.amrit.core.OcrLine(t, size, 0.9f, listOf(70, top.toInt(), (70 + w).toInt(), (y + size * 0.2f).toInt()))
            y += size * 0.75f
        }
        val p = v.g.ref.productsByName("Dolo 650 Tablet").first()
        val roles = org.amrit.core.WhatWeRead.roles(lines, p, runBlocking { v.g.resolver() }.composition)
        return Screen.WhatWeRead(ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 900,
            seen = ScanService.Seen(bmp, lines, roles)))
    }
    @Test fun whatWeRead() { val v = vm(); shot("25_what_we_read", false, v, seenDolo(v)) }
    @Test fun whatWeReadHindiDark() { val v = vm(Lang.HI); shot("25_what_we_read_hi", true, v, seenDolo(v)) }
    @Test fun tappingAMisreadLineSearchesForIt() {
        val v = vm(); val s = seenDolo(v)
        compose.setContent { AmritTheme(v.lang) { AppFrame(v, s) } }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Brand: DOLO-650")).performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(Screen.Search, v.screen)
        org.junit.Assert.assertEquals("DOLO 650", v.query)
    }
    @Test fun resultLinksToWhatWeRead() { val v = vm(); shot("26_result_with_seen", false, v, Screen.Result(seenDolo(v).outcome)) }
    private fun boxOf(v: MainViewModel): Screen {
        v.startBox()
        fun add(name: String, expiry: java.time.YearMonth? = null, batch: String? = null) {
            val p = v.g.ref.productsByName(name).first()
            val alerts = if (batch != null) org.amrit.core.BatchAlerts.check(v.g.ref, batch, p, p.items) else emptyList()
            v.onBoxOutcome(ScanService.Outcome.Done(v.g.rules.evaluate(p, p.items), ScanPath.ON_DEVICE, 1.0, 0, expiry, batch, batchAlerts = alerts))
        }
        add("Dolo 650 Tablet"); add("Combiflam Tablet", java.time.YearMonth.of(2026, 8)); add("Crocin Advance Tablet")
        add("Azithral 500 Tablet"); add("Telma-AM Tablet", batch = "18230791"); add("Nixar 100mg/500mg Tablet DT"); add("PAN 40 Tablet"); add("Ecosprin 75 Tablet")
        v.onBoxOutcome(ScanService.Outcome.NotFound("x", 0))
        v.finishBox()
        return Screen.BoxReport
    }
    @Test fun boxReport() { val v = vm(); shot("27_box_report", false, v, boxOf(v)); v.endBox() }
    @Test fun boxReportHindiDark() { val v = vm(Lang.HI); shot("27_box_report_hi", true, v, boxOf(v)); v.endBox() }
    private fun seedPassport(v: MainViewModel) = runBlocking {
        val day = 86_400_000L; val now = System.currentTimeMillis(); val pid = v.profileId.value
        v.g.dao.startCourse(CourseEntity(productName = "Azithral 500 Tablet", antibiotic = "Azithromycin", startedAt = now - 1 * day,
            days = 3, dosesPerDay = 1, dosesTaken = 1, profileId = pid, aware = "Watch", prescribed = false))
        v.g.dao.startCourse(CourseEntity(productName = "Ciplox 500 Tablet", antibiotic = "Ciprofloxacin", startedAt = now - 40 * day,
            days = 5, dosesPerDay = 2, dosesTaken = 4, active = false, profileId = pid, aware = "Watch", prescribed = true, endedAt = now - 38 * day))
        v.g.dao.startCourse(CourseEntity(productName = "Mox 500 Capsule", antibiotic = "Amoxycillin", startedAt = now - 200 * day,
            days = 5, dosesPerDay = 3, dosesTaken = 15, active = false, profileId = pid, aware = "Access", prescribed = true, endedAt = now - 195 * day))
    }
    @Test fun passport() { val v = vm(); seedPassport(v); shot("28_passport", false, v, Screen.Passport) }
    @Test fun passportHindi() { val v = vm(Lang.HI); seedPassport(v); shot("28_passport_hi", false, v, Screen.Passport) }
    @Test fun passportEmpty() { val v = vm(); shot("28_passport_empty", false, v, Screen.Passport) }
    @Test fun doctorCardImage() {
        val v = vm(); seedPassport(v)
        val e = runBlocking { v.passport(v.profileId.value) }
        val bmp = org.amrit.app.ui.ShareCard.renderCard(app, Lang.EN, org.amrit.app.ui.doctorCard("Me", e))
        File(out, "28_doctor_card.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun billOf(v: MainViewModel): Screen {
        v.openBill()
        val job = v.showBillFrom(OcrResult(listOf("MEDICO PHARMACY", "1 DOLO 650 TAB 15 30.50", "2 CROCIN ADVANCE TAB 20 22.62",
            "3 AZITHRAL 500 TAB 3 120.00", "4 PAN 40 TAB 15 155.00", "5 ZORBIX GEL 1 95.00", "GST 12% 39.98").map { org.amrit.core.OcrLine(it, 20f) }))
        repeat(300) { if (job.isCompleted) return Screen.Bill; org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        return Screen.Bill
    }
    @Test fun billSaver() { val v = vm(); shot("30_bill", false, v, billOf(v)) }
    @Test fun billSaverHindi() { val v = vm(Lang.HI); shot("30_bill_hi", false, v, billOf(v)) }
    @Test fun billStart() { val v = vm(); v.openBill(); shot("30_bill_start", false, v, Screen.Bill) }
    @Test fun nearYou() {
        val v = vm()
        v.fetchDashboard = { """{"total_scans":1240,"antibiotic_scans":412,"by_group":{"Access":160,"Watch":231,"Reserve":21},
            "watch_reserve_share":0.6117,"rx_answered":188,"without_prescription_share":0.43,"regions":[{"region":"411","scans":96,
            "antibiotic_scans":31,"watch_reserve_share":0.58}],"hidden_regions":12,"top_antibiotics":[{"name":"Azithromycin","scans":121},
            {"name":"Amoxycillin","scans":88},{"name":"Cefixime","scans":61},{"name":"Ciprofloxacin","scans":44}],"k":5,
            "updated_at":"2026-11-15T10:00:00.000Z"}""" }
        v.g.settings.pincode = "411038"
        val job = v.loadNearYou()
        repeat(300) { if (job.isCompleted) return@repeat; org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        shot("29_near_you", false, v, Screen.NearYou)
    }
    @Test fun nearYouDark() {
        val v = vm(); v.fetchDashboard = { """{"total_scans":10,"antibiotic_scans":0,"k":5,"regions":[{"region":"225","scans":10,"antibiotic_scans":0}]}""" }
        v.g.settings.pincode = "225001"
        val job = v.loadNearYou()
        repeat(300) { if (job.isCompleted) return@repeat; org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        shot("29_near_you", true, v, Screen.NearYou)
    }
    @Test fun resultCiploxResistance() { val v = vm(); shot("31_result_cipro_icmr", false, v, result(v, "Ciplox 500 Tablet")) }
    @Test fun resultAlternatives() {
        val v = vm(); val s = result(v, "Dolo 650 Tablet")
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.setContent { AmritTheme(v.lang) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(v, s) } } }
        compose.waitForIdle(); Thread.sleep(800); compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("Same medicine, other brands")).performScrollTo()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "32_result_alternatives.png").path)
    }
    @Test fun resultTamil() { val v = vm(Lang.TA); val s = becosules(v); shot("16_result_becosules_ta", false, v, s) }

    @Test fun confirm() {
        val v = vm()
        val r = runBlocking { v.g.resolver() }.resolve(OcrResult(listOf(
            org.amrit.core.OcrLine("AUGMENTIN", 60f), org.amrit.core.OcrLine("Tablets", 20f))))
        val pending = ScanService.Pending(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), OcrResult(emptyList()), null, 0, 0, 0)
        val cands = r.candidates.ifEmpty { listOf(org.amrit.core.Candidate(v.g.ref.productsByName("Augmentin 625 Duo Tablet").first(), org.amrit.core.Features(), 0.6)) }
        shot("20_confirm", false, v, Screen.Confirm(ScanService.Outcome.Confirm(pending, cands.take(3), false)))
    }
    @Test fun notFound() { val v = vm(); shot("21_not_found", false, v, Screen.NotFound(ScanService.Outcome.NotFound("strip not legible", 0))) }
    @Test fun search() { val v = vm(); v.search("dolo"); Thread.sleep(800); shot("22_search", false, v, Screen.Search) }
    @Test fun settings() { val v = vm(); shot("23_settings", false, v, Screen.Settings) }
    @Test fun working() { val v = vm(); shot("24_working", true, v, Screen.Working) }
}
