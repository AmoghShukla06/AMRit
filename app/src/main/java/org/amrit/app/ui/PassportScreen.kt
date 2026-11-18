package org.amrit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amrit.core.Aware
import org.amrit.core.I18n
import org.amrit.core.Lang
import org.amrit.core.Passport
import org.amrit.core.Texts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun date(ms: Long, lang: Lang): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag(if (lang == Lang.EN) "en-IN" else lang.name.lowercase() + "-IN"))
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** One entry's status line, e.g. "Day 2 of 5" or "Stopped early: 3 of 10 doses". */
internal fun passportStatus(e: Passport.Entry, lang: Lang, nowMs: Long = System.currentTimeMillis()): String = when (e.status) {
    Passport.Status.ONGOING -> I18n.t(lang, "passport.dayOf", "d" to ((nowMs - e.startedAt) / 86_400_000L + 1), "n" to (e.daysPlanned ?: 0))
    Passport.Status.COMPLETED -> I18n.t(lang, "passport.done", "done" to (e.dosesDone ?: 0), "total" to (e.dosesPlanned ?: 0))
    Passport.Status.STOPPED_EARLY -> I18n.t(lang, "passport.stopped", "done" to (e.dosesDone ?: 0), "total" to (e.dosesPlanned ?: 0))
    Passport.Status.ENDED -> I18n.t(lang, "passport.ended", "done" to (e.dosesDone ?: 0), "total" to (e.dosesPlanned ?: 0))
    Passport.Status.IN_HOUSE -> I18n.t(lang, "passport.inHouse")
}

private fun rxText(rx: Boolean?, lang: Lang) = when (rx) {
    true -> I18n.t(lang, "passport.rx.yes"); false -> I18n.t(lang, "passport.rx.no"); null -> null
}

/** The doctor's version: always English (Indian clinical records are), last 12 months, newest first. */
internal fun doctorCard(name: String, entries: List<Passport.Entry>, nowMs: Long = System.currentTimeMillis()): ShareCard.Card {
    val en = Lang.EN
    val year = entries.filter { nowMs - it.startedAt <= 365 * 86_400_000L }
    val lines = year.map { e ->
        val kind = when (e.aware) { Aware.Reserve -> Texts.Kind.DANGER; Aware.Watch -> Texts.Kind.WARNING; else -> Texts.Kind.INFO }
        Texts.Line(kind, "${date(e.startedAt, en)}: ${e.product}",
            listOfNotNull(e.antibiotics, e.aware?.let { "WHO ${it.name}" }, passportStatus(e, en, nowMs), rxText(e.prescribed, en)).joinToString(" · "))
    }
    return ShareCard.Card(I18n.t(en, "doctor.title"), I18n.t(en, "doctor.patient", "name" to name), I18n.t(en, "doctor.count", "n" to year.size),
        I18n.t(en, "doctor.period"), null, lines.ifEmpty { listOf(Texts.Line(Texts.Kind.GOOD, I18n.t(en, "doctor.none"))) } +
            Texts.Line(Texts.Kind.INFO, I18n.t(en, "doctor.note")))
}

/** The family antibiotic passport for the selected person. */
@Composable
fun PassportScreen(vm: MainViewModel) {
    val s = sk
    val ctx = LocalContext.current
    val name = vm.currentProfileName
    var entries by remember { mutableStateOf<List<Passport.Entry>?>(null) }
    LaunchedEffect(vm.profileId.value) { entries = vm.passport(vm.profileId.value) }
    val list = entries.orEmpty()
    val insights = remember(list) { Passport.insights(list, System.currentTimeMillis()) }
    val share = {
        val card = doctorCard(name, list)
        val text = (listOf(card.header, card.heroName, card.heroHead) + card.lines.map { "• ${it.title}: ${it.body ?: ""}" })
            .filterNotNull().joinToString("\n")
        runCatching { ctx.startActivity(ShareCard.shareIntent(ctx, runCatching { ShareCard.renderCard(ctx, Lang.EN, card) }.getOrNull(), text)) }
        Unit
    }
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("passport.title"), name, onBack = { vm.back() }, backLabel = vm.tr("common.back")) {
            if (list.isNotEmpty()) RoundIcon(AmIcons.Share, vm.tr("passport.doctor"), share)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(vm.tr("passport.intro"), s.small, color = s.muted)
            if (entries != null && list.isEmpty()) CardBox { T(vm.tr("passport.empty", "name" to name), s.body, color = s.muted) }
            if (insights.isNotEmpty()) SignalCard(Texts.Line(Texts.Kind.WARNING, vm.tr("passport.insightsTitle"),
                insights.joinToString("\n\n") { "• " + Texts.passportInsight(it, vm.lang) }))
            list.forEach { e -> PassportRow(vm, e) }
            T(vm.tr("passport.private"), s.small - 1.sp, color = s.muted)
        }
        if (list.isNotEmpty()) BottomActions {
            PrimaryButton(vm.tr("passport.doctor"), share, Modifier.fillMaxWidth(), icon = AmIcons.Share)
        }
    }
}

@Composable
private fun PassportRow(vm: MainViewModel, e: Passport.Entry) {
    val s = sk
    val rule = when (e.aware) { Aware.Reserve -> s.ruleDanger; Aware.Watch -> s.ruleWarn; Aware.Access -> s.ruleGood; null -> s.ruleInfo }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(s.radius)).background(s.card)
        .semantics(mergeDescendants = true) {}) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(rule))
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                T(e.product, s.body, FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                e.aware?.let { Spacer(Modifier.width(8.dp)); AwareBadge(it, vm.lang) }
            }
            T(e.antibiotics, s.small, color = s.muted)
            T(vm.tr("passport.started", "date" to date(e.startedAt, vm.lang)) + " · " + passportStatus(e, vm.lang), s.small, FontWeight.SemiBold,
                if (e.status == Passport.Status.STOPPED_EARLY) s.warn else s.ink)
            rxText(e.prescribed, vm.lang)?.let { T(it, s.small, color = if (e.prescribed == false) s.warn else s.muted) }
        }
    }
}
