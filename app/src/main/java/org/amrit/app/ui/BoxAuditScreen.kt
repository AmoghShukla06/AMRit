package org.amrit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amrit.core.BoxAudit
import org.amrit.core.Lang
import org.amrit.core.Texts

private val throwReasons = setOf(BoxAudit.Reason.EXPIRED, BoxAudit.Reason.RECALLED_BATCH, BoxAudit.Reason.BANNED)

/** The report as lines: throw-away findings, then ask-your-doctor findings, then the fine medicines as checks. */
internal fun boxLines(r: BoxAudit.Report, lang: Lang): Triple<List<Texts.Line>, List<Texts.Line>, List<Texts.Line>> {
    val bin = r.findings.filter { it.reason in throwReasons }.map { Texts.boxFinding(it, r, lang) }
    // most serious first: a double dose (red) before a leftover antibiotic (amber)
    val ask = r.findings.filter { it.reason !in throwReasons }.map { Texts.boxFinding(it, r, lang) }.sortedBy { it.kind.ordinal }
    val fine = r.items.indices.filter { r.groups[it] == BoxAudit.Group.FINE }
        .map { Texts.Line(Texts.Kind.GOOD, r.items[it].report.product.name) }
    return Triple(bin, ask, fine)
}

/** "Check my medicine box": what to throw away, what to ask a doctor about, and what is fine, for the whole box. */
@Composable
fun BoxAuditScreen(vm: MainViewModel) {
    val s = sk
    val ctx = LocalContext.current
    val report = remember(vm.box.size) { vm.boxReport() }
    val (bin, ask, fine) = remember(report, vm.lang) { boxLines(report, vm.lang) }
    val nBin = report.groups.count { it.value == BoxAudit.Group.THROW_AWAY }
    val nAsk = report.groups.count { it.value == BoxAudit.Group.ASK_DOCTOR }
    val nFine = report.groups.count { it.value == BoxAudit.Group.FINE }
    val summary = vm.tr("box.summary", "bin" to nBin, "ask" to nAsk, "fine" to nFine)
    val share = {
        val card = ShareCard.Card(vm.tr("box.shareHeader"), vm.currentProfileName, vm.tr("box.checked", "n" to report.items.size),
            summary, null, bin + ask + fine)
        val text = (listOf(vm.tr("box.shareHeader"), summary) + (bin + ask).map { "• ${it.title}" } +
            fine.map { "✓ ${it.title}" } + listOf("", vm.tr("disclaimer"))).joinToString("\n")
        runCatching { ctx.startActivity(ShareCard.shareIntent(ctx, runCatching { ShareCard.renderCard(ctx, vm.lang, card) }.getOrNull(), text)) }
        Unit
    }
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("box.title"), vm.tr("box.subtitle", "n" to report.items.size, "name" to vm.currentProfileName),
            onBack = { vm.back() }, backLabel = vm.tr("common.back")) {
            RoundIcon(AmIcons.Share, vm.tr("box.share"), share)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // three counters, in the same severity colours as the cards below
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Counter(nBin, vm.tr("box.throw"), s.danger, s.dangerBg, Modifier.weight(1f))
                Counter(nAsk, vm.tr("box.ask"), s.warn, s.warnBg, Modifier.weight(1f))
                Counter(nFine, vm.tr("box.fine"), s.good, s.goodBg, Modifier.weight(1f))
            }
            if (report.items.isEmpty()) CardBox { T(vm.tr("box.empty"), s.body, color = s.muted) }
            if (bin.isNotEmpty()) {
                SectionTitle(vm.tr("box.throw"))
                bin.forEach { SignalCard(it) }
                CardBox {
                    T(vm.tr("box.dispose.title"), s.body, FontWeight.Bold)
                    T(vm.tr("box.dispose.body"), s.small, color = s.muted)
                }
            }
            if (ask.isNotEmpty()) {
                SectionTitle(vm.tr("box.ask"))
                ask.forEach { SignalCard(it) }
                if (report.findings.any { it.reason == BoxAudit.Reason.LEFTOVER_ANTIBIOTIC })
                    LinkButton(vm.tr("passport.open"), { vm.go(Screen.Passport) })
            }
            if (fine.isNotEmpty()) {
                SectionTitle(vm.tr("box.fine"))
                CheckTiles(fine)
            }
            if (vm.boxUnknown > 0) CardBox {
                T(vm.tr("box.unknown", "n" to vm.boxUnknown), s.body)
                LinkButton(vm.tr("nav.search"), { vm.go(Screen.Search) })
            }
            T(vm.tr("disclaimer"), s.small - 1.sp, color = s.muted)
        }
        if (report.items.isNotEmpty()) BottomActions {
            val profiles by vm.profiles.collectAsState()
            PrimaryButton(if (profiles.size > 1) vm.tr("box.save", "name" to vm.currentProfileName) else vm.tr("box.saveMine"), { vm.saveBox() }, Modifier.fillMaxWidth(), icon = AmIcons.Plus)
        }
    }
}

@Composable
private fun Counter(n: Int, label: String, fg: Color, bg: Color, modifier: Modifier) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(bg).padding(vertical = 14.dp, horizontal = 8.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$n $label" },
        horizontalAlignment = Alignment.CenterHorizontally) {
        T("$n", sk.display, FontWeight.ExtraBold, fg, align = TextAlign.Center)
        T(label, sk.small - 1.sp, FontWeight.Bold, fg, align = TextAlign.Center, maxLines = 2)
    }
}
