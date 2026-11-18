package org.amrit.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amrit.app.data.CabinetEntity
import org.amrit.app.data.CourseEntity
import org.amrit.app.data.ProfileEntity
import org.amrit.app.data.ScanPath
import org.amrit.app.graph
import org.amrit.app.scan.DemoMode
import org.amrit.app.scan.EvalRunner
import org.amrit.app.scan.LiveScanner
import org.amrit.app.scan.ScanService
import org.amrit.core.Candidate
import org.amrit.core.CompItem
import org.amrit.core.I18n
import org.amrit.core.Lang
import org.amrit.core.Normalize
import org.amrit.core.Product
import org.amrit.core.RuleEngine
import org.amrit.core.STRIP_READ_ID
import org.amrit.core.Texts
import java.time.YearMonth
import java.time.temporal.ChronoUnit

sealed class Screen {
    data object Onboarding : Screen()
    data object Home : Screen()
    data object Medicines : Screen()
    data object Scan : Screen()
    data object Working : Screen()
    data class Result(val outcome: ScanService.Outcome.Done) : Screen()
    data class WhatWeRead(val outcome: ScanService.Outcome.Done) : Screen()
    data class Confirm(val outcome: ScanService.Outcome.Confirm) : Screen()
    data class NotFound(val outcome: ScanService.Outcome.NotFound) : Screen()
    data object Search : Screen()
    data object Stats : Screen()
    data object Settings : Screen()
    data object BoxReport : Screen()
    data object Passport : Screen()
    data object NearYou : Screen()
    data object Bill : Screen()
}

/** One medicine in a person's cabinet, with its rule report and expiry. */
/** [alert]: "SPURIOUS|AUG-2026" when CDSCO flagged this batch. */
data class MedItem(val id: Long, val report: RuleEngine.StripReport, val expiry: YearMonth?, val batch: String? = null,
                   val alert: String? = null, val addedAt: Long = 0)

/** Everything the home and medicines screens show for the selected person. */
data class Cabinet(val meds: List<MedItem>, val alerts: List<RuleEngine.CabinetAlert>, val expiring: List<MedItem>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val g = app.graph
    private val stack = mutableStateListOf<Screen>(if (g.settings.onboarded) Screen.Home else Screen.Onboarding)
    val screen: Screen get() = stack.last()
    var navDirection by mutableStateOf(1); private set
    var lang by mutableStateOf(g.settings.language ?: Lang.EN)
    var evalProgress by mutableStateOf("")
    var capture by mutableStateOf<Bitmap?>(null); private set
    var stage by mutableStateOf(ScanService.Stage.READING); private set
    var seen by mutableStateOf<List<String>>(emptyList())
    var toast by mutableStateOf<String?>(null)

    fun tr(key: String, vararg args: Pair<String, Any?>) = I18n.t(lang, key, *args)

    private val tts by lazy { Tts(app) }
    /** Speaks in the current language; shows a hint if that voice isn't installed. */
    fun speak(text: String) { if (!tts.speak(text, lang) && lang != Lang.EN) toast = tr("res.ttsMissing") }
    fun stopSpeaking() = tts.stop()
    override fun onCleared() { tts.shutdown() }

    init { g.scans.onStage = { st -> viewModelScope.launch(Dispatchers.Main.immediate) { stage = st } } }

    // ------------------------------------------------------------------ navigation
    private val tabs = setOf(Screen.Home, Screen.Medicines, Screen.Search, Screen.Settings)
    fun go(s: Screen) { navDirection = 1; if (s is Screen.Home) stack.clear(); stack += s }
    /** bottom-nav tabs replace each other on top of Home instead of piling up */
    fun tab(s: Screen) { navDirection = 0; stack.clear(); stack += Screen.Home; if (s != Screen.Home) stack += s }
    fun replace(s: Screen) { navDirection = 1; if (stack.size > 1) stack.removeAt(stack.lastIndex); stack += s }
    /**
     * A scan's outcome. The camera, the progress screen and an answered "which one is it?" are finished steps, so they
     * leave the back stack: Back from a result goes to where the scan started (usually Home), not into the camera again.
     */
    fun showOutcome(s: Screen) {
        navDirection = 1
        while (stack.size > 1 && stack.last().let { it is Screen.Scan || it is Screen.Working || it is Screen.Confirm }) stack.removeAt(stack.lastIndex)
        stack += s
    }
    fun back(): Boolean { if (stack.size <= 1) return false; navDirection = -1; stack.removeAt(stack.lastIndex); return true }
    val isTab: Boolean get() = screen in tabs

    var largeText by mutableStateOf(g.settings.largeText); private set
    var autoRead by mutableStateOf(g.settings.autoRead); private set
    fun updateLargeText(on: Boolean) { g.settings.largeText = on; largeText = on }
    fun updateAutoRead(on: Boolean) { g.settings.autoRead = on; autoRead = on }

    fun setLanguage(l: Lang) { g.settings.language = l; lang = l }

    fun finishOnboarding(pin: String?) {
        g.settings.pincode = pin?.takeIf { it.length == 6 }
        if (g.settings.language == null) g.settings.language = lang
        g.settings.onboarded = true
        stack.clear(); stack += Screen.Home
    }

    // ------------------------------------------------------------------ profiles
    val profileId = MutableStateFlow(g.settings.profileId)
    val profiles: StateFlow<List<ProfileEntity>> = g.dao.profiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun selectProfile(id: Long) { g.settings.profileId = id; profileId.value = id }
    fun addProfile(name: String) = viewModelScope.launch {
        val id = g.dao.addProfile(ProfileEntity(name = name.trim().take(20), color = profiles.value.size % 5, createdAt = System.currentTimeMillis()))
        selectProfile(id)
    }
    fun profileName(p: ProfileEntity?) = if (p == null || p.name.isBlank()) tr("profile.me") else p.name
    val currentProfileName: String get() = profileName(profiles.value.firstOrNull { it.id == profileId.value })

    // ------------------------------------------------------------------ cabinet
    val cabinet: StateFlow<Cabinet> = profileId.flatMapLatest { g.dao.cabinetOf(it) }.map { rows ->
        withContext(Dispatchers.Default) {
            val meds = rows.mapNotNull { row -> g.scans.cabinetReport(row)?.let {
                MedItem(row.id, it, row.expiry?.let { e -> runCatching { YearMonth.parse(e) }.getOrNull() }, row.batch, row.alert, row.addedAt) } }
            val now = YearMonth.now()
            val expiring = meds.filter { it.expiry != null && ChronoUnit.MONTHS.between(now, it.expiry) <= 1 }.sortedBy { it.expiry }
            Cabinet(meds, g.rules.cabinet(meds.map { it.report }), expiring)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Cabinet(emptyList(), emptyList(), emptyList()))

    fun daysLeft(ym: YearMonth): Long = ChronoUnit.DAYS.between(java.time.LocalDate.now(), ym.atEndOfMonth())

    /** Alerts this strip would trigger with the selected person's medicines, shown before adding it. */
    fun previewAlerts(r: RuleEngine.StripReport): List<RuleEngine.CabinetAlert> {
        val existing = cabinet.value.meds.map { it.report }.filter { it.product.name != r.product.name }
        if (existing.isEmpty()) return emptyList()
        return g.rules.cabinet(existing + r).filter { a ->
            when (a) {
                is RuleEngine.CabinetAlert.Duplicate -> a.products.any { it.first == r.product.name }
                is RuleEngine.CabinetAlert.Interacts -> a.productA == r.product.name || a.productB == r.product.name
            }
        }
    }
    fun hasOtherMeds(r: RuleEngine.StripReport) = cabinet.value.meds.any { it.report.product.name != r.product.name }
    /** This antibiotic is already in the selected person's medicines as a leftover (saved 7+ days ago, no course running). */
    fun leftoverOf(r: RuleEngine.StripReport): MedItem? {
        val systemic = r.antibiotic != null && !r.antibiotic!!.topical
        val running = courses.value.any { it.productName == r.product.name }
        return cabinet.value.meds.firstOrNull { m ->
            m.report.product.name == r.product.name && org.amrit.core.Leftover.isLeftover(systemic, m.addedAt, running, System.currentTimeMillis())
        }
    }

    fun isInCabinet(r: RuleEngine.StripReport) = cabinet.value.meds.any { it.report.product.name == r.product.name }

    val courses: StateFlow<List<CourseEntity>> = profileId.flatMapLatest { g.dao.activeCoursesOf(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingEvents: StateFlow<Int> = g.dao.pendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ------------------------------------------------------------------ scanning
    private fun handle(o: ScanService.Outcome) = showOutcome(when (o) {
        is ScanService.Outcome.Done -> Screen.Result(o)
        is ScanService.Outcome.Confirm -> Screen.Confirm(o)
        is ScanService.Outcome.NotFound -> Screen.NotFound(o)
    })

    fun scan(bitmap: Bitmap, rotation: Int, preview: Bitmap? = null) {
        capture = preview; stage = ScanService.Stage.READING; go(Screen.Working)
        viewModelScope.launch(Dispatchers.Default) {
            val o = runCatching { g.scans.scan(bitmap, rotation) }.getOrElse { ScanService.Outcome.NotFound(it.message ?: "error", 0) }
            withContext(Dispatchers.Main) { handle(o) }
        }
    }

    /** Box mode: read the locked strip in the background and stay on the camera; [then] re-arms the scanner. */
    fun liveBox(l: LiveScanner.Locked, then: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val o = runCatching { g.scans.fromLive(l.bitmap, l.ocr, l.textBox, l.res, l.t0, l.ocrMs, l.packCode, l.frameLines) }
                .getOrElse { ScanService.Outcome.NotFound(it.message ?: "error", 0) }
            withContext(Dispatchers.Main) { onBoxOutcome(o); then() }
        }
    }

    fun live(l: LiveScanner.Locked) {
        capture = org.amrit.app.scan.OcrEngine.scaleTo(l.bitmap, 720); stage = ScanService.Stage.CHECKING; go(Screen.Working)
        viewModelScope.launch(Dispatchers.Default) {
            val o = runCatching { g.scans.fromLive(l.bitmap, l.ocr, l.textBox, l.res, l.t0, l.ocrMs, l.packCode, l.frameLines) }
                .getOrElse { ScanService.Outcome.NotFound(it.message ?: "error", 0) }
            withContext(Dispatchers.Main) { handle(o) }
        }
    }

    fun confirm(o: ScanService.Outcome.Confirm, c: Candidate?) {
        replace(Screen.Working)
        viewModelScope.launch(Dispatchers.Default) {
            val r = if (c != null) g.scans.confirm(o.pending, c, o.afterApi) else g.scans.rejectCandidates(o.pending, o.afterApi)
            withContext(Dispatchers.Main) { handle(r) }
        }
    }

    // ------------------------------------------------------------------ medicine-box audit
    var boxMode by mutableStateOf(false); private set
    val box = mutableStateListOf<org.amrit.core.BoxAudit.Item>()
    var boxUnknown by mutableStateOf(0); private set
    /** what was just added, for the camera's "added" line (null until the first strip) */
    var boxLast by mutableStateOf<String?>(null); private set

    /** "Check my medicine box": the camera stays open and every identified strip joins the list. */
    fun startBox() { box.clear(); boxUnknown = 0; boxLast = null; boxMode = true; go(Screen.Scan) }

    /**
     * One locked strip in box mode. A strip still held in view after a lock is read again: the same product as the
     * last one adds nothing. "Which one is it?" and "not found" count as unidentified; the report says how many.
     */
    fun onBoxOutcome(o: ScanService.Outcome) {
        when (o) {
            is ScanService.Outcome.Done -> {
                if (box.lastOrNull()?.report?.product?.id == o.report.product.id) return
                box += org.amrit.core.BoxAudit.Item(o.report, o.expiry, o.batch, o.batchAlerts)
                boxLast = o.report.product.name
            }
            else -> boxUnknown++
        }
    }

    fun finishBox() { showOutcome(Screen.BoxReport) }

    fun boxReport(): org.amrit.core.BoxAudit.Report =
        org.amrit.core.BoxAudit.audit(box.toList(), g.rules, YearMonth.now(), courses.value.map { it.productName }.toSet())

    /** Saves every identified strip to the selected person's medicines (one row per product) and ends box mode. */
    fun saveBox(): kotlinx.coroutines.Job {
        val items = boxReport().items
        val pid = profileId.value
        return viewModelScope.launch {
            // already in this person's medicines: not added again (a second row would show twice in the list)
            val have = g.dao.cabinetProductIds(pid).toSet()
            for (it in items) if (it.report.product.id !in have || it.report.product.id == org.amrit.core.STRIP_READ_ID)
                g.scans.addToCabinet(it.report, pid, it.expiry, it.batch, it.alerts)
            boxMode = false
            toast = tr("box.saved", "n" to items.size, "name" to currentProfileName)
            go(Screen.Home)
        }
    }
    fun endBox() { boxMode = false }

    // ------------------------------------------------------------------ antibiotics near you
    /** [live]: fetched just now; otherwise the saved snapshot from [fetchedAt] */
    data class NearYou(val d: org.amrit.core.Dashboard, val fetchedAt: Long, val live: Boolean, val myRegion: org.amrit.core.Dashboard.Region?)
    var nearYou by mutableStateOf<NearYou?>(null); private set
    var nearYouTried by mutableStateOf(false); private set
    /** network seam (tests replace it) */
    internal var fetchDashboard: suspend () -> String? = { withContext(Dispatchers.IO) { g.api.dashboardJson() } }
    /** Shows the saved snapshot at once, then replaces it with live data if the server answers. */
    fun loadNearYou(): kotlinx.coroutines.Job = viewModelScope.launch {
        fun wrap(text: String?, at: Long, live: Boolean) = text?.let { org.amrit.core.Dashboard.parse(it) }?.let { d ->
            NearYou(d, at, live, d.regionOf(g.settings.pincode))
        }
        if (nearYou == null) nearYou = wrap(g.settings.dashboardJson, g.settings.dashboardAt, false)
        val fresh = runCatching { fetchDashboard() }.getOrNull()
        val parsed = fresh?.let { wrap(it, System.currentTimeMillis(), true) }
        if (parsed != null) {
            g.settings.dashboardJson = fresh; g.settings.dashboardAt = parsed.fetchedAt
            nearYou = parsed
        }
        nearYouTried = true
    }

    // ------------------------------------------------------------------ bill saver
    data class BillView(val result: org.amrit.core.BillReader.Result, val savings: List<org.amrit.core.BillReader.Saving>,
                        val alerts: List<RuleEngine.CabinetAlert>, val reports: List<RuleEngine.StripReport>)
    var bill by mutableStateOf<BillView?>(null); private set
    var billBusy by mutableStateOf(false); private set

    /** Everything the bill screen shows, from the bill's OCR lines. */
    suspend fun billFrom(ocr: org.amrit.core.OcrResult): BillView = withContext(Dispatchers.Default) {
        val res = org.amrit.core.BillReader.read(ocr, g.ref, g.resolver().index)
        val reports = res.lines.distinctBy { it.product.id }.map { g.rules.evaluate(it.product, it.product.items) }
        BillView(res, org.amrit.core.BillReader.savings(res.lines, g.rules), g.rules.cabinet(reports), reports)
    }

    fun openBill() { bill = null; go(Screen.Bill) }

    /** A photo of a printed bill: read at high resolution (bills are dense), never stored. */
    fun readBill(bitmap: Bitmap, rotation: Int = 0) {
        billBusy = true
        viewModelScope.launch(Dispatchers.Default) {
            val ocr = runCatching { g.ocr.run(org.amrit.app.scan.OcrEngine.scaleTo(bitmap, 3000, rotation)).result }
                .getOrDefault(org.amrit.core.OcrResult(emptyList()))
            // diagnostics (local device log only): adb logcat -s AMRit
            android.util.Log.i("AMRit", "bill OCR: " + ocr.lines.joinToString(" | ") { "${it.text} ${it.box}" })
            withContext(Dispatchers.Main) { showBillFrom(ocr) }
        }
    }

    /** The bill screen for these OCR lines (the photo path above, and tests). An unreadable bill shows "nothing matched". */
    fun showBillFrom(ocr: org.amrit.core.OcrResult): kotlinx.coroutines.Job = viewModelScope.launch {
        billBusy = true
        bill = runCatching { billFrom(ocr) }.getOrElse {
            BillView(org.amrit.core.BillReader.Result(emptyList(), emptyList()), emptyList(), emptyList(), emptyList())
        }
        billBusy = false
    }

    fun saveBillToCabinet() = viewModelScope.launch {
        val b = bill ?: return@launch
        val have = g.dao.cabinetProductIds(profileId.value).toSet()
        for (r in b.reports) if (r.product.id !in have) g.scans.addToCabinet(r, profileId.value, null)
        toast = tr("box.saved", "n" to b.reports.size, "name" to currentProfileName)
    }

    // ------------------------------------------------------------------ search
    data class Hit(val product: Product, val subtitle: String)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Hit>>(emptyList())

    fun search(q: String) {
        query = q
        viewModelScope.launch(Dispatchers.Default) {
            val r = g.resolverNow ?: return@launch
            val keys = (r.index.prefix(q, 12) + r.index.search(q, 6, 0.75).map { it.baseKey }).distinct().take(15)
            val fq = org.amrit.core.BrandIndex.fold(q)
            fun rank(p: Product): Int { val fb = org.amrit.core.BrandIndex.fold(p.baseKey); return when { fb == fq -> 0; fb.startsWith(fq) -> 1; else -> 2 } }
            val products = g.ref.productsByBaseKeys(keys).groupBy { it.name }.map { it.value.first() }
                .sortedWith(compareBy<Product> { rank(it) }.thenBy { it.discontinued }
                    .thenBy { if (it.form.startsWith("tablet") || it.form == "capsule") 0 else 1 }.thenBy { it.name.length }).take(30)
            val out = products.map { Hit(it, it.items.joinToString { i -> listOfNotNull(i.ingredient.name, i.strength?.toString()).joinToString(" ") }) }
            withContext(Dispatchers.Main) { if (query == q) hits = out }
        }
    }

    fun openProduct(p: Product, path: ScanPath = ScanPath.SEARCH) {
        viewModelScope.launch(Dispatchers.Default) {
            val report = g.rules.evaluate(p, p.items)
            withContext(Dispatchers.Main) { go(Screen.Result(ScanService.Outcome.Done(report, path, 1.0, 0))) }
        }
    }

    /** A saved medicine's result, with its batch and any CDSCO alert for it. */
    fun openMed(m: MedItem) {
        viewModelScope.launch(Dispatchers.Default) {
            val alerts = g.scans.batchAlertsFor(m.batch, m.report.product, m.report.items)
            val o = ScanService.Outcome.Done(m.report, ScanPath.SEARCH, 1.0, 0, m.expiry, m.batch, batchAlerts = alerts)
            withContext(Dispatchers.Main) { go(Screen.Result(o)) }
        }
    }

    fun demoPick(s: DemoMode.DemoStrip) {
        viewModelScope.launch(Dispatchers.Default) {
            val o = g.scans.demo(s) as? ScanService.Outcome.Done ?: return@launch
            withContext(Dispatchers.Main) { if (boxMode) onBoxOutcome(o) else showOutcome(Screen.Result(o)) }
        }
    }

    // ------------------------------------------------------------------ actions
    fun addToCabinet(o: ScanService.Outcome.Done) = viewModelScope.launch {
        g.scans.addToCabinet(o.report, profileId.value, o.expiry, o.batch, o.batchAlerts)
        toast = tr("res.added", "name" to currentProfileName)
        go(Screen.Home)
    }

    /**
     * The anonymous "sold with a prescription?" answer travels inside this scan's own event: same random id, so the
     * server updates that one row (or the not-yet-uploaded copy is replaced). Nothing about the person is added.
     */
    fun answerPrescription(o: ScanService.Outcome.Done, prescribed: Boolean) = viewModelScope.launch {
        // kept on the phone for this person's passport (a course started from this result picks it up)
        val name = o.report.product.name
        rxAnswers[name] = prescribed
        g.dao.setCoursePrescribed(name, profileId.value, System.currentTimeMillis() - 86_400_000L, prescribed)
        if (o.path == ScanPath.DEMO) return@launch   // staged demo answers stay out of the surveillance data
        val ev = (o.event ?: return@launch).copy(prescribed = prescribed)
        g.dao.requeue(org.amrit.app.data.PendingEventEntity(ev.event_id,
            kotlinx.serialization.json.Json.encodeToString(org.amrit.core.ScanEvent.serializer(), ev), System.currentTimeMillis()))
        g.requestSync()
    }

    fun removeFromCabinet(id: Long) = viewModelScope.launch { g.dao.removeFromCabinet(id) }

    fun startCourse(o: ScanService.Outcome.Done, days: Int, perDay: Int) = viewModelScope.launch {
        val r = o.report
        g.dao.startCourse(CourseEntity(productName = r.product.name, antibiotic = r.antibiotic!!.antibiotics.joinToString { it.name },
            startedAt = System.currentTimeMillis(), days = days, dosesPerDay = perDay, profileId = profileId.value,
            aware = r.antibiotic?.aware?.name, prescribed = rxAnswers[r.product.name]))
        if (!isInCabinet(r)) g.scans.addToCabinet(r, profileId.value, o.expiry, o.batch, o.batchAlerts)
        toast = tr("course.started")
        go(Screen.Home)
    }

    fun takeDose(id: Long) = viewModelScope.launch { g.dao.takeDose(id) }
    fun endCourse(id: Long) = viewModelScope.launch { g.dao.endCourse(id, System.currentTimeMillis()) }

    /** prescription answers given this session, by product name, for courses started afterwards */
    private val rxAnswers = HashMap<String, Boolean>()

    /**
     * One person's antibiotic passport: every course (newest first), then antibiotics kept in their medicines with no
     * course started for them. Built on the phone from local data only.
     */
    suspend fun passport(pid: Long): List<org.amrit.core.Passport.Entry> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val courses = g.dao.coursesOf(pid)
        val fromCourses = courses.map { c ->
            org.amrit.core.Passport.Entry(c.productName, c.antibiotic, org.amrit.core.Aware.of(c.aware), c.startedAt, c.days,
                c.dosesTaken, c.days * c.dosesPerDay, c.prescribed,
                org.amrit.core.Passport.status(c.active, c.startedAt, c.days, c.dosesPerDay, c.dosesTaken, c.endedAt, now))
        }
        val named = courses.map { it.productName }.toSet()
        val inHouse = g.dao.cabinetOf(pid).first().mapNotNull { row ->
            val r = g.scans.cabinetReport(row) ?: return@mapNotNull null
            val a = r.antibiotic ?: return@mapNotNull null
            if (a.topical || r.product.name in named) return@mapNotNull null
            org.amrit.core.Passport.Entry(r.product.name, a.antibiotics.joinToString { it.name }, a.aware, row.addedAt,
                null, null, null, null, org.amrit.core.Passport.Status.IN_HOUSE)
        }
        fromCourses + inHouse
    }

    /** Share as a picture card in the user's language, with the text version as caption; text only if drawing fails. */
    fun shareIntent(r: RuleEngine.StripReport, extra: List<Texts.Line>, expiry: YearMonth?): Intent {
        val exp = expiry?.let { org.amrit.core.Expiry.format(it) }
        val ctx = getApplication<Application>()
        val bmp = runCatching { ShareCard.render(ctx, r, g.ref, lang, extra, exp) }.getOrNull()
        return ShareCard.shareIntent(ctx, bmp, Texts.share(r, g.ref, lang, extra, exp))
    }

    /** A map search near the user: by their saved PIN code when there is one, else around the phone's location. */
    private fun mapSearch(what: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(listOfNotNull(what, g.settings.pincode).joinToString(" "))))
    fun kendraIntent(): Intent = mapSearch("Jan Aushadhi Kendra")
    fun pharmacyIntent(): Intent = mapSearch("pharmacy")
    /** PMBI's official Kendra finder (search by PIN code or state and district) */
    fun kendraLocatorIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://janaushadhi.gov.in/KendraDetails.aspx"))

    /** Same salt, other brands, cheapest first (core `Alternatives`). */
    suspend fun alternatives(p: Product): org.amrit.core.Alternatives.Result =
        withContext(Dispatchers.Default) { org.amrit.core.Alternatives.sameSalt(p, g.ref, 5) }

    suspend fun refMeta(): Map<String, String> = withContext(Dispatchers.IO) { g.ref.meta }

    fun runEval(useApi: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { EvalRunner(g).run(useApi) { msg -> viewModelScope.launch(Dispatchers.Main) { evalProgress = msg } } }
                .onFailure { e -> withContext(Dispatchers.Main) { evalProgress = "failed: ${e.message}" } }
        }
    }
}
