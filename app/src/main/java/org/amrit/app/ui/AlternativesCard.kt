package org.amrit.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amrit.core.Alternatives
import org.amrit.core.Product

/** "₹0.33 a tablet" / "₹0.52 per unit", by dosage form. */
internal fun unitPriceText(vm: MainViewModel, price: Double, form: String): String =
    vm.tr(if (form.startsWith("tablet") || form == "capsule") "alt.perTablet" else "alt.perUnit", "p" to "%.2f".format(price))

/**
 * Same salt, other brands, cheapest first, and where to buy near the user. Prices are the dataset's MRPs; the card
 * says that switching is the pharmacist's call.
 */
@Composable
fun AlternativesCard(vm: MainViewModel, p: Product) {
    val s = sk
    val ctx = LocalContext.current
    val alt by produceState<Alternatives.Result?>(null, p.id) { value = vm.alternatives(p) }
    val a = alt
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a != null && a.total > 0) {
            SectionTitle(vm.tr("alt.title"))
            T(vm.tr("alt.sub", "n" to a.total), s.small, color = s.muted)
            CardBox {
                a.options.forEach { o ->
                    Row(Modifier.fillMaxWidth().heightIn(min = s.touch).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = o.product.name) { vm.openProduct(o.product) }
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(o.product.name, s.body, FontWeight.Bold)
                            o.product.manufacturer?.let { T(it, s.small - 1.sp, color = s.muted, maxLines = 1) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            o.unitPrice?.let { T(unitPriceText(vm, it, o.product.form), s.small, FontWeight.Bold) }
                            o.savingPercent?.let { Pill(vm.tr("alt.cheaper", "pct" to it), s.good, s.goodBg) }
                        }
                    }
                }
                a.unitPrice?.let { T(vm.tr("alt.yours", "price" to unitPriceText(vm, it, p.form)), s.small - 1.sp, color = s.muted) }
                T(vm.tr("alt.priceNote"), s.small - 1.sp, color = s.muted)
            }
        }
        // where to buy: the user's area when a PIN code is saved; the official finder searches Kendras by PIN code
        SectionTitle(vm.tr("alt.where"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(vm.tr("alt.kendra"), { runCatching { ctx.startActivity(vm.kendraIntent()) } }, Modifier.weight(1f), icon = AmIcons.Pin)
            SecondaryButton(vm.tr("alt.pharmacy"), { runCatching { ctx.startActivity(vm.pharmacyIntent()) } }, Modifier.weight(1f), icon = AmIcons.Pin)
        }
        LinkButton(vm.tr("alt.locator"), { runCatching { ctx.startActivity(vm.kendraLocatorIntent()) } })
    }
}
