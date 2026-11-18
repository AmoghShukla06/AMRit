package org.amrit.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amrit.app.scan.ScanService
import org.amrit.core.Aware
import org.amrit.core.Expiry
import org.amrit.core.I18n
import org.amrit.core.Lang
import org.amrit.core.RuleEngine
import org.amrit.core.STRIP_READ_ID
import org.amrit.core.Texts

/** Everything the result shows, in one language, built from templates + data (DB reads happen off the main thread). */
private class ResultText(
    val headline: String?, val ingredientUses: List<String>, val contains: String,
    val notes: List<Texts.Line>, val alerts: List<Texts.Line>, val issues: List<Texts.Line>, val checks: List<Texts.Line>,
    val generic: Texts.Generic?,
)

private fun build(vm: MainViewModel, o: ScanService.Outcome.Done, preview: List<RuleEngine.CabinetAlert>, hasOthers: Boolean, lang: Lang,
                  leftover: MedItem? = null): ResultText {
    val r = o.report
    val ref = vm.g.ref
    val notes = ArrayList<Texts.Line>()
    // this exact batch is in a CDSCO alert: the most important thing on the screen
    o.batchAlerts.firstOrNull()?.let { al ->
        val spurious = al.kind == org.amrit.core.BatchAlert.Kind.SPURIOUS
        notes += Texts.Line(Texts.Kind.DANGER,
            I18n.t(lang, if (spurious) "res.alert.spurious.title" else "res.alert.nsq.title"),
            I18n.t(lang, "res.alert.body", "batch" to al.batch, "result" to (al.result?.trim()?.takeIf { it.isNotEmpty() } ?: "-"),
                "by" to (al.reportedBy ?: "CDSCO"), "month" to al.month.lowercase().replaceFirstChar { it.uppercase() }),
            I18n.t(lang, "res.alert.source", "month" to al.month))
    }
    // the pack's own code names another medicine
    if (o.qr == ScanService.QrCheck.MISMATCH) notes += Texts.Line(Texts.Kind.DANGER, I18n.t(lang, "res.qr.mismatch.title"),
        I18n.t(lang, "res.qr.mismatch.body", "brand" to (o.qrBrand ?: "")))
    // an antibiotic the family already has from before: don't start it again on your own, and dispose of it safely
    leftover?.let { m ->
        val saved = java.time.Instant.ofEpochMilli(m.addedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag(lang.tts)))
        notes += Texts.Line(Texts.Kind.WARNING, I18n.t(lang, "res.leftover.title"), I18n.t(lang, "res.leftover.body", "date" to saved))
        notes += Texts.Line(Texts.Kind.INFO, I18n.t(lang, "res.dispose.title"), I18n.t(lang, "res.dispose.body"))
    }
    if (r.product.id == STRIP_READ_ID) notes += Texts.Line(Texts.Kind.INFO, I18n.t(lang, "stripRead.title"), I18n.t(lang, "stripRead.body"))
    val batch = o.batch?.let { I18n.t(lang, "res.batch", "batch" to it) }
    val expiryWarned = o.expiry?.let { ym ->
        val days = vm.daysLeft(ym)
        val body = listOfNotNull(I18n.t(lang, "expiry.body", "product" to r.product.name, "date" to Expiry.format(ym)), batch).joinToString("\n")
        when {
            days < 0 -> Texts.Line(Texts.Kind.DANGER, I18n.t(lang, "expiry.expired.title"), body)
            days <= 31 -> Texts.Line(Texts.Kind.WARNING, I18n.t(lang, "expiry.soon.title", "days" to days), body)
            else -> null
        }
    }
    // batch and expiry otherwise appear as the stamp on the strip hero
    if (expiryWarned != null) notes += expiryWarned
    val interacts = preview.any { it is RuleEngine.CabinetAlert.Interacts }
    val qrCheck = if (o.qr == ScanService.QrCheck.MATCH)
        listOf(Texts.Line(Texts.Kind.GOOD, I18n.t(lang, "check.qr"), source = I18n.t(lang, "check.qr.source"))) else emptyList()
    // the batch was read and is in none of CDSCO's alerts
    val batchCheck = if (o.batch != null && o.batchAlerts.isEmpty()) listOf(Texts.Line(Texts.Kind.GOOD, I18n.t(lang, "check.batch"),
        source = I18n.t(lang, "check.batch.source", "n" to "%,d".format(org.amrit.core.BatchAlerts.recordCount(vm.g.ref))))) else emptyList()
    return ResultText(
        headline = Texts.headline(r, ref, lang),
        ingredientUses = Texts.ingredientUses(r, ref, lang),
        contains = Texts.contains(r, lang),
        notes = notes,
        alerts = preview.map { Texts.cabinetForStrip(it, r.product.name, lang) },
        issues = Texts.issues(r, lang),
        checks = batchCheck + qrCheck + Texts.checks(r, lang, hasOthers && !interacts),
        generic = r.generic?.let { Texts.generic(it, r.product.form, lang) },
    )
}

/**
 * "Did you get this antibiotic with a doctor's prescription?" Asked once, after a real scan of a systemic antibiotic.
 * The answer is added to this scan's anonymous event (medicine group + area pincode + hour); skipping is fine.
 */
@Composable
private fun PrescriptionQuestion(vm: MainViewModel, o: ScanService.Outcome.Done) {
    val s = sk
    var answer by remember(o) { mutableStateOf<Boolean?>(null) }
    var skipped by remember(o) { mutableStateOf(false) }
    if (skipped) return
    val a = answer
    if (a != null) {
        SignalCard(Texts.Line(Texts.Kind.INFO, vm.tr("rx.thanks"), if (!a) vm.tr("rx.noBody") else null))
        return
    }
    CardBox {
        T(vm.tr("rx.question"), s.title, FontWeight.Bold, lineHeight = 1.25f)
        T(vm.tr("rx.private"), s.small, color = s.muted)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(vm.tr("rx.yes"), { answer = true; vm.answerPrescription(o, true) }, Modifier.weight(1f))
            SoftButton(vm.tr("rx.no"), { answer = false; vm.answerPrescription(o, false) }, Modifier.weight(1f))
        }
        LinkButton(vm.tr("rx.skip"), { skipped = true }, color = s.muted)
    }
}

private fun formName(form: String) = form.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
fun ResultScreen(vm: MainViewModel, o: ScanService.Outcome.Done) {
    val r = o.report
    val lang = vm.lang
    val view = LocalView.current
    val ctx = LocalContext.current
    val cab by vm.cabinet.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val texts by produceState<ResultText?>(null, r, lang, cab) {
        value = withContext(Dispatchers.Default) { build(vm, o, vm.previewAlerts(r), vm.hasOtherMeds(r), lang, vm.leftoverOf(r)) }
    }
    LaunchedEffect(r) { if (r.banned || r.antibiotic?.aware == Aware.Reserve) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    // "Read results aloud": a new scan's result is spoken once, as soon as its text is ready (not for search or saved medicines)
    LaunchedEffect(o, texts != null) {
        val t0 = texts
        if (vm.autoRead && t0 != null && o.path != org.amrit.app.data.ScanPath.SEARCH) vm.speak(Texts.speech(r, vm.g.ref, lang, t0.alerts))
    }
    var courseDialog by remember { mutableStateOf(false) }
    val already = remember(r, cab) { vm.isInCabinet(r) }
    val addLabel = when {
        already -> vm.tr("res.inCabinet")
        profiles.size > 1 -> vm.tr("res.addTo", "name" to vm.currentProfileName)
        else -> vm.tr("res.add")
    }
    val t = texts
    val listen = { t?.let { vm.speak(Texts.speech(r, vm.g.ref, lang, it.alerts)) }; Unit }
    val share = { t?.let { runCatching { ctx.startActivity(vm.shareIntent(r, it.alerts, o.expiry)) } }; Unit }
    val kendra = { runCatching { ctx.startActivity(vm.kendraIntent()) }; Unit }
    val abx = r.antibiotic != null && !r.antibiotic!!.topical

    Column(Modifier.fillMaxSize().background(sk.bg)) {
        run {
            Header(null, onBack = { vm.back() }, backLabel = vm.tr("common.back")) {
                RoundIcon(AmIcons.Speaker, vm.tr("res.listen"), listen)
                RoundIcon(AmIcons.Share, vm.tr("res.share"), share)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (t != null) {
                BodyB(vm, o, t)
                // ICMR's 2024 resistance figures for exactly this antibiotic (systemic only; topical isn't what ICMR tested)
                if (abx) remember(r, lang) { Texts.resistance(vm.g.ref.resistance(r.items), lang) }?.let { SignalCard(it) }
                if (r.antibiotic != null && !r.antibiotic!!.topical && o.event != null) PrescriptionQuestion(vm, o)
                if (t.generic != null) GenericCard(t.generic, kendra, vm.tr("generic.findKendra"))
                AlternativesCard(vm, r.product)
                if (abx) SecondaryButton(vm.tr("res.track"), { courseDialog = true }, Modifier.fillMaxWidth(), icon = AmIcons.Clock)
                if (abx) LinkButton(vm.tr("passport.open"), { vm.go(Screen.Passport) })
                T(vm.tr("disclaimer"), sk.small - 1.sp, color = sk.muted)
                if (lang.beta) T(vm.tr("res.machineTranslated"), sk.small - 1.sp, color = sk.muted)
            }
        }
        BottomActions {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(addLabel, { vm.addToCabinet(o) }, Modifier.weight(1f), enabled = !already,
                    icon = if (already) AmIcons.Check else AmIcons.Plus)
                if (r.generic != null) {
                    Spacer(Modifier.width(10.dp))
                    RoundIcon(AmIcons.Pin, vm.tr("generic.findKendra"), kendra, bg = sk.accent, fg = sk.onAccent, size = sk.button, iconSize = 22.dp)
                }
            }
        }
    }
    if (courseDialog) CourseDialog(vm, r, o) { courseDialog = false }
}

/** Teal hero with the headline, then alerts, issues, check tiles. */
@Composable
private fun BodyB(vm: MainViewModel, o: ScanService.Outcome.Done, t: ResultText) {
    val s = sk
    val r = o.report
    val form = formName(r.product.form)
    val name = if (r.product.name.contains(form, ignoreCase = true)) r.product.name else "${r.product.name}, $form"
    FoilHero(
        name = if (t.headline != null) name else listOfNotNull(form, r.product.manufacturer).joinToString(", "),
        headline = t.headline ?: r.product.name,
        composition = {
            val unclear = if (r.product.id == STRIP_READ_ID) vm.tr("strength.unclear") else null
            // a long composition (multivitamins) is printed as a list on the strip; chips would crowd the foil
            if (r.items.size > 4) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                r.items.forEach { c ->
                    T(c.ingredient.name + ((c.strength?.toString() ?: unclear)?.let { " $it" } ?: ""), 16.sp, FontWeight.SemiBold, sk.foilInk)
                }
            } else IngredientChips(r.items, unclear = unclear, onHero = true)
        },
        batch = o.batch, expiry = o.expiry?.let { Expiry.format(it) },
    )
    // 3.1: the frozen frame with what was read, so a misread can be seen and corrected
    if (o.seen != null) LinkButton(vm.tr("seen.open"), { vm.go(Screen.WhatWeRead(o)) }, Modifier.padding(start = 4.dp))
    t.notes.forEach { SignalCard(it) }
    if (t.headline == null && t.ingredientUses.isNotEmpty())
        CardBox {
            T(vm.tr("res.uses"), s.body, FontWeight.ExtraBold)
            t.ingredientUses.forEach { T(it, s.small + 1.sp, color = s.muted) }
        }
    AnimatedVisibility(t.alerts.isNotEmpty(), enter = fadeIn()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { t.alerts.forEach { SignalCard(it) } }
    }
    t.issues.forEach { SignalCard(it) }
    CheckTiles(t.checks)
}

@Composable
private fun CourseDialog(vm: MainViewModel, r: RuleEngine.StripReport, o: ScanService.Outcome.Done, close: () -> Unit) {
    var days by remember { mutableIntStateOf(5) }
    var perDay by remember { mutableIntStateOf(2) }
    AmDialog(vm.tr("course.dialogTitle"), close, vm.tr("course.start") to { vm.startCourse(o, days, perDay); close() }, vm.tr("common.cancel")) {
        T(vm.tr("course.dialogBody"), sk.small + 1.sp, color = sk.muted)
        Stepper(vm.tr("course.days"), days, 1..30) { days = it }
        Stepper(vm.tr("course.perDay"), perDay, 1..4) { perDay = it }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, set: (Int) -> Unit) {
    val s = sk
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, s.body, FontWeight.SemiBold, modifier = Modifier.weight(1f))
        RoundIcon(AmIcons.Minus, "−", { if (value > range.first) set(value - 1) }, bg = s.brandSoft, fg = s.onBrandSoft, size = 44.dp)
        T("$value", 20.sp, FontWeight.ExtraBold, align = TextAlign.Center, modifier = Modifier.width(48.dp))
        RoundIcon(AmIcons.Plus, "+", { if (value < range.last) set(value + 1) }, bg = s.brandSoft, fg = s.onBrandSoft, size = 44.dp)
    }
}
