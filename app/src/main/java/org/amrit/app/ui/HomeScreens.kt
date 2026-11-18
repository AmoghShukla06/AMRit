package org.amrit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amrit.app.data.CourseEntity
import org.amrit.core.Expiry
import org.amrit.core.I18n
import org.amrit.core.Lang
import org.amrit.core.Texts

// ------------------------------------------------------------------------------------------------ shared pieces

/** "What it's for" per medicine in the selected person's cabinet (DB lookups off the main thread). */
@Composable
private fun rememberHeadlines(vm: MainViewModel, cab: Cabinet): Map<Long, String> {
    val lang = vm.lang
    return produceState(emptyMap(), cab, lang) {
        value = withContext(Dispatchers.IO) {
            cab.meds.associate { m -> m.id to (Texts.headline(m.report, vm.g.ref, lang) ?: "") }
        }
    }.value
}

fun expiryLine(vm: MainViewModel, m: MedItem, lang: Lang = vm.lang): Texts.Line? {
    val ym = m.expiry ?: return null
    val days = vm.daysLeft(ym)
    val body = I18n.t(lang, "expiry.body", "product" to m.report.product.name, "date" to Expiry.format(ym))
    return if (days < 0) Texts.Line(Texts.Kind.DANGER, I18n.t(lang, "expiry.expired.title"), body)
    else Texts.Line(Texts.Kind.WARNING, I18n.t(lang, "expiry.soon.title", "days" to days), body)
}


/** A saved medicine whose batch CDSCO flagged (spurious or failed a quality test), as a warning line. */
fun flaggedLine(vm: MainViewModel, m: MedItem): Texts.Line? {
    val (kind, month) = (m.alert ?: return null).split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val title = vm.tr(if (kind == "SPURIOUS") "res.alert.spurious.title" else "res.alert.nsq.title")
    return Texts.Line(Texts.Kind.DANGER, title, listOfNotNull(m.report.product.name, m.batch?.let { vm.tr("res.batch", "batch" to it) }).joinToString("\n"),
        vm.tr("res.alert.source", "month" to month))
}

@Composable
fun ProfileRow(vm: MainViewModel) {
    val profiles by vm.profiles.collectAsState()
    val current by vm.profileId.collectAsState()
    var adding by remember { mutableStateOf(false) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        items(profiles, key = { it.id }) { p -> Avatar(vm.profileName(p), p.color, p.id == current, onClick = { vm.selectProfile(p.id) }) }
        item { AddAvatar(vm.tr("profile.add")) { adding = true } }
    }
    if (adding) AddProfileDialog(vm) { adding = false }
}

@Composable
fun AddProfileDialog(vm: MainViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AmDialog(vm.tr("profile.add"), close, vm.tr("profile.addCta") to { if (name.isNotBlank()) vm.addProfile(name); close() }, vm.tr("common.cancel")) {
        AmField(name, { name = it.take(20) }, vm.tr("profile.name"))
    }
}

@Composable
fun CourseCard(vm: MainViewModel, c: CourseEntity) {
    val s = sk
    val total = c.days * c.dosesPerDay
    val day = ((System.currentTimeMillis() - c.startedAt) / 86_400_000L + 1).toInt().coerceIn(1, c.days)
    val done = c.dosesTaken >= total
    CardBox(border = true) {
        T(vm.tr("course.title") + ": " + c.antibiotic, 16.sp, FontWeight.Bold)
        T(c.productName + ", " + vm.tr("course.day", "day" to day, "days" to c.days).replaceFirstChar { it.lowercase() }, s.small, color = s.muted)
        Spacer(Modifier.height(4.dp))
        SegmentBar(c.dosesTaken, total)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton(if (done) vm.tr("course.done") else vm.tr("course.took"), { vm.takeDose(c.id) }, Modifier.weight(1f), enabled = !done)
            Spacer(Modifier.width(8.dp))
            LinkButton(vm.tr("course.remove"), { vm.endCourse(c.id) }, color = s.muted)
        }
    }
}

@Composable
private fun MedRow(vm: MainViewModel, m: MedItem, headline: String?) {
    val s = sk
    val sub = listOfNotNull(headline?.takeIf { it.isNotBlank() },
        m.report.items.joinToString(", ") { it.ingredient.name + (it.strength?.let { st -> " $st" } ?: "") }).joinToString("\n")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(s.card)
        .clickable(role = Role.Button) { vm.openMed(m) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(s.brandSoft), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(s.brand))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(m.report.product.name, 15.sp, FontWeight.Bold, maxLines = 1)
            T(sub, 13.sp, color = s.muted, maxLines = 2)
        }
        Icon(AmIcons.Chevron, null, tint = s.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DetailDialog(vm: MainViewModel, line: Texts.Line, close: () -> Unit) {
    AmDialog(line.title, close, vm.tr("res.listen") to { vm.speak(line.text) }, vm.tr("common.back")) {
        line.body?.let { T(it) }
        line.source?.let { T(it, sk.small, FontWeight.SemiBold, sk.muted) }
    }
}

@Composable
private fun Footer(vm: MainViewModel) {
    val s = sk
    // World AMR Awareness Week is 18-24 November; outside it the banner is noise
    val now = java.time.LocalDate.now()
    val awarenessWeek = now.monthValue == 11 && now.dayOfMonth in 18..24
    Column(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (awarenessWeek) T(vm.tr("home.awareness"), s.small, FontWeight.SemiBold, s.brandInk)
        T(vm.tr("disclaimer"), s.small - 1.sp, color = s.muted)
    }
}

// ------------------------------------------------------------------------------------------------ home

@Composable
fun HomeScreen(vm: MainViewModel) = HomeB(vm)

/** Avatars + language, teal scan card with a strip, attention tiles, my medicines. Same layout in every language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeB(vm: MainViewModel) {
    val s = sk
    val cab by vm.cabinet.collectAsState()
    val courses by vm.courses.collectAsState()
    val heads = rememberHeadlines(vm, cab)
    var detail by remember { mutableStateOf<Texts.Line?>(null) }
    var course by remember { mutableStateOf<CourseEntity?>(null) }
    val lang = vm.lang

    // tiles: what needs attention first, then the best saving
    val tiles = ArrayList<@Composable (Modifier) -> Unit>()
    cab.meds.forEach { med ->
        flaggedLine(vm, med)?.let { l ->
            val (fg, bg) = kindColors(l.kind)
            val short = vm.tr(if (med.alert?.startsWith("SPURIOUS") == true) "tile.spurious" else "tile.nsq")
            tiles += { m -> Tile(bg, fg, short, med.report.product.name, m, top = { Icon(AmIcons.Alert, null, tint = fg, modifier = Modifier.size(24.dp)) },
                onClick = { vm.openMed(med) }) }
        }
    }
    cab.alerts.forEach { a ->
        val l = Texts.cabinet(a, lang)
        val (fg, bg) = kindColors(l.kind)
        tiles += { m -> Tile(bg, fg, l.title, l.body, m, top = { Icon(AmIcons.Alert, null, tint = fg, modifier = Modifier.size(24.dp)) }, onClick = { detail = l }) }
    }
    cab.expiring.forEach { med ->
        expiryLine(vm, med)?.let { l ->
            val (fg, bg) = kindColors(l.kind)
            tiles += { m -> Tile(bg, fg, l.title, l.body, m, top = { Icon(AmIcons.Clock, null, tint = fg, modifier = Modifier.size(24.dp)) }, onClick = { detail = l }) }
        }
    }
    courses.forEach { c ->
        val total = c.days * c.dosesPerDay
        val day = ((System.currentTimeMillis() - c.startedAt) / 86_400_000L + 1).toInt().coerceIn(1, c.days)
        tiles += { m -> Tile(s.goodBg, s.good, vm.tr("course.title"), c.antibiotic + "\n" + vm.tr("course.day", "day" to day, "days" to c.days), m,
            top = { Box(Modifier.width(80.dp)) { SegmentBar(c.dosesTaken, total, s.good, s.good.copy(alpha = 0.25f), 8.dp) } }, onClick = { course = c }) }
    }
    cab.meds.mapNotNull { m -> m.report.generic?.savingPercent?.let { m to it } }.maxByOrNull { it.second }?.let { (med, pct) ->
        val bg = if (s.dark) s.card else s.inverse
        val fg = if (s.dark) s.ink else s.onInverse
        tiles += { m -> Tile(bg, fg, med.report.product.name, null, m, label = vm.tr("generic.title"),
            big = vm.tr("generic.less", "pct" to pct), onClick = { vm.openMed(med) }) }
    }

    LazyColumn(Modifier.fillMaxSize().background(s.bg).statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ProfileRow(vm) }
                Spacer(Modifier.width(8.dp))
                LangPill(vm)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).clip(RoundedCornerShape(s.heroRadius)).background(s.brand)
                .clickable(role = Role.Button) { vm.go(Screen.Scan) }) {
                StripArt(Modifier.align(Alignment.TopEnd).padding(top = 34.dp).offset(x = 18.dp))
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        T(vm.tr("home.scanTitle"), 27.sp, FontWeight.ExtraBold, s.onBrand, lineHeight = 1.15f)
                        T(vm.tr("home.scanBody"), s.small - 1.sp, color = s.brandMuted)
                    }
                    Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(s.accent).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(AmIcons.Scan, null, tint = s.onAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        T(vm.tr("home.scanCta"), 15.sp, FontWeight.Bold, s.onAccent, maxLines = 1)
                    }
                }
            }
        }
        item { HomeActions(vm) }
        if (tiles.isEmpty()) item {
            Tile(s.goodBg, s.good, vm.tr("home.allClear"), vm.tr("home.allClearBody"), Modifier.fillMaxWidth(),
                top = { Icon(AmIcons.Check, null, tint = s.good, modifier = Modifier.size(24.dp)) })
        } else item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { it(Modifier.weight(1f)) } }
                }
            }
        }
        item {
            SectionTitle(vm.tr("home.myMeds"), action = if (cab.meds.size > 3) ({ LinkButton(vm.tr("home.seeAll"), { vm.tab(Screen.Medicines) }) }) else null)
        }
        if (cab.meds.isEmpty()) item {
            CardBox(onClick = { vm.go(Screen.Search) }) {
                T(vm.tr("meds.empty"), s.body, FontWeight.Bold)
                T(vm.tr("meds.emptyBody"), s.small, color = s.muted)
            }
        }
        items(cab.meds.take(3), key = { it.id }) { m -> MedRow(vm, m, heads[m.id]) }
        item { Footer(vm) }
    }
    detail?.let { DetailDialog(vm, it) { detail = null } }
    course?.let { c ->
        AmDialog(vm.tr("course.title") + ": " + c.antibiotic, { course = null }, vm.tr("course.took") to { vm.takeDose(c.id); course = null }, vm.tr("common.back")) {
            SegmentBar(c.dosesTaken, c.days * c.dosesPerDay)
            LinkButton(vm.tr("course.remove"), { vm.endCourse(c.id); course = null }, color = s.muted)
        }
    }
}

// ------------------------------------------------------------------------------------------------ medicines tab

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicinesScreen(vm: MainViewModel) {
    val s = sk
    val cab by vm.cabinet.collectAsState()
    val courses by vm.courses.collectAsState()
    val heads = rememberHeadlines(vm, cab)
    val lang = vm.lang
    Column(Modifier.fillMaxSize().background(s.bg)) {
        val profiles by vm.profiles.collectAsState()
        val pid by vm.profileId.collectAsState()
        val named = profiles.firstOrNull { it.id == pid }?.name?.isNotBlank() == true
        Header(if (named) vm.tr("meds.title", "name" to vm.currentProfileName) else vm.tr("home.myMeds")) {
            LinkButton(vm.tr("passport.open"), { vm.go(Screen.Passport) })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ProfileRow(vm) }
            items(cab.meds.mapNotNull { m -> flaggedLine(vm, m) }) { l -> SignalCard(l) }
            items(cab.alerts.size) { i -> SignalCard(Texts.cabinet(cab.alerts[i], lang)) }
            items(courses, key = { "c${it.id}" }) { c -> CourseCard(vm, c) }
            if (cab.meds.isEmpty()) item {
                CardBox(Modifier.padding(top = 8.dp)) {
                    T(vm.tr("meds.empty"), s.title, FontWeight.Bold)
                    T(vm.tr("meds.emptyBody"), color = s.muted)
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton(vm.tr("home.scanCta"), { vm.go(Screen.Scan) }, Modifier.fillMaxWidth(), icon = AmIcons.Scan)
                }
            }
            items(cab.meds, key = { "m${it.id}" }) { m ->
                val r = m.report
                CardBox(onClick = { vm.openMed(m) }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            T(r.product.name, 16.sp, FontWeight.ExtraBold)
                            heads[m.id]?.takeIf { it.isNotBlank() }?.let { T(it, s.body, color = s.brandInk, weight = FontWeight.SemiBold) }
                            T(r.items.joinToString(", ") { it.ingredient.name + (it.strength?.let { st -> " $st" } ?: "") }, s.small, color = s.muted)
                        }
                        RoundIcon(AmIcons.Close, vm.tr("meds.remove"), { vm.removeFromCabinet(m.id) }, bg = s.bg, fg = s.muted, size = 40.dp, iconSize = 18.dp)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (m.alert != null) Pill(vm.tr("meds.alert"), s.danger, s.dangerBg)
                        if (vm.leftoverOf(r) != null) Pill(vm.tr("meds.leftover"), s.warn, s.warnBg)
                        r.antibiotic?.aware?.let { AwareBadge(it, lang) }
                        if (r.banned) Pill(vm.tr("ban.exact.title"), s.danger, s.dangerBg)
                        m.expiry?.let { ym ->
                            val soon = vm.daysLeft(ym) <= 31
                            Pill("EXP " + Expiry.format(ym), if (soon) s.warn else s.muted, if (soon) s.warnBg else s.line)
                        }
                    }
                }
            }
            item { T(vm.tr("disclaimer"), s.small - 1.sp, color = s.muted, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Second-tier actions under the scan hero: check the whole medicine box (and, later, a pharmacy bill). */
@Composable
private fun HomeActions(vm: MainViewModel) {
    val s = sk
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionCard(AmIcons.Meds, vm.tr("box.cta"), vm.tr("box.ctaSub"), Modifier.fillMaxWidth()) { vm.startBox() }
        ActionCard(AmIcons.Gallery, vm.tr("bill.cta"), vm.tr("bill.ctaSub"), Modifier.fillMaxWidth()) { vm.openBill() }
        ActionCard(AmIcons.Info, vm.tr("near.cta"), vm.tr("near.ctaSub"), Modifier.fillMaxWidth()) { vm.go(Screen.NearYou) }
    }
}

@Composable
internal fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = sk
    Row(modifier.heightIn(min = s.touch + 40.dp).clip(RoundedCornerShape(22.dp)).background(s.card)
        .clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(s.brandSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = s.onBrandSoft, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(title, s.body, FontWeight.ExtraBold)
            T(body, s.small - 1.sp, color = s.muted)
        }
        Icon(AmIcons.Chevron, null, tint = s.muted, modifier = Modifier.size(18.dp))
    }
}
