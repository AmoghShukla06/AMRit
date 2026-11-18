package org.amrit.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.amrit.core.BillReader
import org.amrit.core.I18n
import org.amrit.core.Lang
import org.amrit.core.Texts
import java.io.File
import kotlin.math.roundToInt

private fun rupees(x: Double) = "₹" + (if (x >= 100) "%,.0f" else "%,.2f").format(java.util.Locale("en", "IN"), x)

/** Safety lines across the bill: double doses / interactions between its medicines, then banned and antibiotic items. */
internal fun billSafety(v: MainViewModel.BillView, lang: Lang): List<Texts.Line> =
    v.alerts.map { Texts.cabinet(it, lang) } +
        v.reports.filter { it.banned }.map { Texts.Line(Texts.Kind.DANGER, I18n.t(lang, "box.banned.title", "product" to it.product.name), I18n.t(lang, "ban.exact.body")) } +
        v.reports.filter { it.antibiotic?.let { a -> !a.topical } == true }.map { r ->
            Texts.Line(Texts.Kind.WARNING, I18n.t(lang, "bill.abx", "product" to r.product.name,
                "group" to (r.antibiotic?.aware?.let { Texts.awareName(it, lang) } ?: "")), I18n.t(lang, "bill.abxBody"))
        }

/** Bill saver: what a printed pharmacy bill would cost at Jan Aushadhi, and whether its medicines are safe together. */
@Composable
fun BillScreen(vm: MainViewModel) {
    val s = sk
    val ctx = LocalContext.current
    // camera: full-resolution photo to a cache file the app deletes as soon as it is read
    val photo = remember { File(ctx.cacheDir, "bill").apply { mkdirs() }.resolve("bill.jpg") }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) BitmapFactory.decodeFile(photo.path)?.let { vm.readBill(it, exifRotation(photo)) }
        photo.delete()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { u -> ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) } }?.let { vm.readBill(it) }
    }
    val kendra = { runCatching { ctx.startActivity(vm.kendraIntent()) }; Unit }
    val b = vm.bill
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("bill.title"), onBack = { vm.back() }, backLabel = vm.tr("common.back"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                vm.billBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp, color = s.brand, modifier = Modifier.padding(4.dp))
                    Spacer(Modifier.width(12.dp)); T(vm.tr("bill.reading"), s.body)
                }
                b == null -> {
                    T(vm.tr("bill.intro"), s.body, color = s.muted)
                    PrimaryButton(vm.tr("bill.photo"), { runCatching { take.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".share", photo)) } }, Modifier.fillMaxWidth(), icon = AmIcons.Scan)
                    SecondaryButton(vm.tr("bill.gallery"), { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.fillMaxWidth(), icon = AmIcons.Gallery)
                    T(vm.tr("bill.privacy"), s.small, color = s.muted)
                }
                else -> BillResult(vm, b)
            }
        }
        if (b != null && b.result.lines.isNotEmpty()) BottomActions {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(vm.tr("generic.findKendra"), kendra, Modifier.weight(1f), icon = AmIcons.Pin)
                Spacer(Modifier.width(10.dp))
                RoundIcon(AmIcons.Plus, vm.tr("bill.save"), { vm.saveBillToCabinet() }, bg = s.brandSoft, fg = s.onBrandSoft, size = s.button, iconSize = 22.dp)
            }
        }
    }
}

@Composable
private fun BillResult(vm: MainViewModel, b: MainViewModel.BillView) {
    val s = sk
    val (brand, generic) = remember(b) { BillReader.total(b.savings) }
    if (b.result.lines.isEmpty()) {
        CardBox { T(vm.tr("bill.none"), s.body, color = s.muted) }
    } else {
        // hero: the saving, on navy like the in-app Jan Aushadhi card
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(s.heroRadius)).background(if (s.dark) s.card else s.inverse).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fg = if (s.dark) s.ink else s.onInverse
            if (generic > 0 && brand > generic) {
                T(vm.tr("bill.saveLabel"), s.small, FontWeight.Bold, s.accent)
                T(rupees(brand - generic), 46.sp, FontWeight.ExtraBold, fg)
                T(vm.tr("bill.saveBody", "pct" to ((1 - generic / brand) * 100).roundToInt(), "brand" to rupees(brand), "generic" to rupees(generic)),
                    s.small, color = if (s.dark) s.muted else s.onInverseMuted)
            } else T(vm.tr("bill.noGeneric"), s.body, FontWeight.Bold, fg)
        }
        billSafety(b, vm.lang).forEach { SignalCard(it) }
        SectionTitle(vm.tr("bill.items", "n" to b.result.lines.size))
        CardBox {
            b.savings.forEach { sv ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        T(sv.line.product.name, s.body, FontWeight.Bold, modifier = Modifier.weight(1f))
                        sv.brandCost?.let { T(rupees(it), s.body, FontWeight.Bold) }
                    }
                    T(listOfNotNull(sv.line.qty?.let { vm.tr("bill.qty", "n" to it) }, if (sv.line.amount == null) vm.tr("bill.estimate") else null)
                        .joinToString(" · ").ifEmpty { " " }, s.small - 1.sp, color = s.muted)
                    val g = sv.generic
                    if (g != null && sv.genericCost != null) Row {
                        T(vm.tr("bill.janAushadhi", "name" to g.item.name), s.small, color = s.good, modifier = Modifier.weight(1f))
                        T(rupees(sv.genericCost!!), s.small, FontWeight.Bold, s.good)
                    } else T(vm.tr("bill.noGenericLine"), s.small, color = s.muted)
                }
            }
        }
        if (b.result.unrecognised.isNotEmpty()) CardBox {
            T(vm.tr("bill.unrecognised", "n" to b.result.unrecognised.size), s.body, FontWeight.Bold)
            b.result.unrecognised.take(8).forEach { T(it, s.small, color = s.muted) }
            T(vm.tr("bill.unrecognisedBody"), s.small - 1.sp, color = s.muted)
        }
        LinkButton(vm.tr("bill.again"), { vm.openBill() })
        T(vm.tr("bill.priceSource"), s.small - 1.sp, color = s.muted)
    }
}

/** Camera JPEGs are often stored sideways with an EXIF rotation; OCR needs the text upright. */
private fun exifRotation(f: File): Int = runCatching {
    when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)
