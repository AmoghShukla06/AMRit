package org.amrit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amrit.app.scan.ScanService
import org.amrit.core.WhatWeRead.Role as ReadRole

/** Highlighter colours over the photo: brand butter, ingredients green, the printed batch / expiry stamp blue. */
private fun roleColor(r: ReadRole, s: Skin): Color = when (r) {
    ReadRole.BRAND -> s.accent
    ReadRole.INGREDIENT -> s.ruleGood
    ReadRole.BATCH, ReadRole.EXPIRY -> Color(0xFF5B9BE6)
}

/** Text for the search box from a tapped line: words only, as the user would type them ("DOLO-650" -> "DOLO 650"). */
internal fun searchText(line: String): String =
    line.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.joinToString(" ").take(40)

/**
 * 3.1 "What we read": the frozen frame with the lines that gave the answer highlighted. Anything misread can be
 * tapped to search for the right medicine; nothing here changes the answer by itself. The frame lives in memory only.
 */
@Composable
fun WhatWeReadScreen(vm: MainViewModel, o: ScanService.Outcome.Done) {
    val s = sk
    val seen = o.seen ?: return
    val correct = { text: String -> vm.search(searchText(text)); vm.go(Screen.Search) }
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("seen.title"), onBack = { vm.back() }, backLabel = vm.tr("common.back"))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(vm.tr("seen.sub"), s.small, color = s.muted)
            val bmp = seen.frame
            Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(s.radius))
                .background(Color.Black)) {
                Image(bmp.asImageBitmap(), vm.tr("seen.title"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().pointerInput(seen) {
                    detectTapGestures { p ->
                        val k = size.width.toFloat() / bmp.width
                        seen.roles.keys.firstOrNull { i ->
                            val b = seen.lines[i].box ?: return@firstOrNull false
                            p.x / k in (b[0] - 16f)..(b[2] + 16f) && p.y / k in (b[1] - 16f)..(b[3] + 16f)
                        }?.let { correct(seen.lines[it].text) }
                    }
                }) {
                    val k = size.width / bmp.width
                    drawRect(Color(0x55000000))  // dim the photo so the marked lines stand out
                    for ((i, role) in seen.roles) {
                        val b = seen.lines.getOrNull(i)?.box ?: continue
                        val c = roleColor(role, s)
                        val tl = Offset(b[0] * k - 6f, b[1] * k - 4f); val sz = Size((b[2] - b[0]) * k + 12f, (b[3] - b[1]) * k + 8f)
                        drawRoundRect(c.copy(alpha = 0.30f), tl, sz, CornerRadius(10f))
                        drawRoundRect(c, tl, sz, CornerRadius(10f), style = Stroke(5f))
                    }
                }
            }
            // legend, then the same lines as a list (TalkBack reads this; each row can be tapped like its box)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(ReadRole.BRAND to "seen.brand", ReadRole.INGREDIENT to "seen.ingredient", ReadRole.BATCH to "seen.stamp").forEach { (r, key) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(roleColor(r, s)))
                        Spacer(Modifier.width(6.dp)); T(vm.tr(key), s.small - 1.sp, FontWeight.SemiBold, s.muted)
                    }
                }
            }
            CardBox {
                seen.roles.forEach { (i, role) ->
                    val text = seen.lines.getOrNull(i)?.text ?: return@forEach
                    val label = vm.tr(when (role) { ReadRole.BRAND -> "seen.brand"; ReadRole.INGREDIENT -> "seen.ingredient"; else -> "seen.stamp" })
                    Row(Modifier.fillMaxWidth().heightIn(min = s.touch).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = vm.tr("seen.searchThis")) { correct(text) }
                        .semantics { contentDescription = "$label: $text" }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(roleColor(role, s)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            T(text, s.body, FontWeight.Bold)
                            T(label, s.small - 1.sp, color = s.muted)
                        }
                        androidx.compose.material3.Icon(AmIcons.Search, null, tint = s.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            T(vm.tr("seen.hint"), s.small, color = s.muted)
        }
    }
}
