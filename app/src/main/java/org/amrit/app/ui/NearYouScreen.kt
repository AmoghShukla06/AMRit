package org.amrit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"

/** A share as a horizontal bar, with its number for TalkBack. */
@Composable
private fun ShareBar(share: Double, fg: Color, bg: Color, label: String) {
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(bg).semantics { contentDescription = "$label ${pct(share)}" }) {
        Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(fg))
    }
}

/**
 * "Antibiotics near you": what the anonymous scans add up to, live from the server. Every scan of an antibiotic adds to
 * it, which is the point to show: one phone's scan becomes part of India's picture. Areas appear only with 5+ scans.
 */
@Composable
fun NearYouScreen(vm: MainViewModel) {
    val s = sk
    LaunchedEffect(Unit) { vm.loadNearYou() }
    val n = vm.nearYou
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("near.title"), n?.let {
            if (it.live) vm.tr("near.live") else vm.tr("near.saved", "date" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.fetchedAt)))
        }, onBack = { vm.back() }, backLabel = vm.tr("common.back")) {
            RoundIcon(AmIcons.Clock, vm.tr("near.refresh"), { vm.loadNearYou() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (n == null) {
                CardBox { T(if (vm.nearYouTried) vm.tr("near.none") else vm.tr("near.loading"), s.body, color = s.muted) }
                return@Column
            }
            val d = n.d
            // hero: the count every new scan moves
            // navy in light mode; in dark mode `inverse` turns light, so use a dark card (butter text stays readable)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(s.heroRadius)).background(if (s.dark) s.card else s.inverse).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(vm.tr("near.heroLabel"), s.small, FontWeight.Bold, s.accent)
                T("${d.antibiotic_scans}", 52.sp, FontWeight.ExtraBold, if (s.dark) s.ink else s.onInverse)
                T(vm.tr("near.heroBody", "total" to d.total_scans), s.small, color = if (s.dark) s.muted else s.onInverseMuted)
            }
            d.watch_reserve_share?.let { w ->
                CardBox {
                    T(vm.tr("near.watchTitle", "pct" to pct(w)), s.body, FontWeight.ExtraBold)
                    ShareBar(w, s.ruleWarn, s.warnBg, vm.tr("near.watchTitle", "pct" to ""))
                    T(vm.tr("near.watchBody"), s.small, color = s.muted)
                }
            }
            CardBox {
                val rx = d.without_prescription_share
                if (rx != null) {
                    T(vm.tr("near.rxTitle", "pct" to pct(rx)), s.body, FontWeight.ExtraBold)
                    ShareBar(rx, s.ruleDanger, s.dangerBg, vm.tr("near.rxTitle", "pct" to ""))
                    T(vm.tr("near.rxBody", "n" to d.rx_answered), s.small, color = s.muted)
                } else T(vm.tr("near.rxNotYet", "k" to d.k), s.small, color = s.muted)
            }
            n.myRegion?.let { r ->
                CardBox {
                    T(vm.tr("near.myArea", "region" to r.region), s.body, FontWeight.ExtraBold)
                    T(vm.tr("near.myAreaBody", "scans" to r.scans, "abx" to r.antibiotic_scans), s.small, color = s.muted)
                    r.watch_reserve_share?.let { ShareBar(it, s.ruleWarn, s.warnBg, vm.tr("near.watchTitle", "pct" to "")) }
                }
            }
            if (d.top_antibiotics.isNotEmpty()) {
                SectionTitle(vm.tr("near.top"))
                CardBox {
                    val max = d.top_antibiotics.maxOf { it.scans }.coerceAtLeast(1)
                    d.top_antibiotics.take(6).forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            T(a.name, s.small, FontWeight.SemiBold, modifier = Modifier.weight(0.45f))
                            Box(Modifier.weight(0.45f)) { ShareBar(a.scans.toDouble() / max, s.brand, s.brandSoft, a.name) }
                            T("${a.scans}", s.small, FontWeight.Bold, modifier = Modifier.weight(0.1f).padding(start = 8.dp))
                        }
                    }
                }
            }
            T(vm.tr("near.privacy", "k" to d.k), s.small - 1.sp, color = s.muted)
        }
    }
}
