package org.amrit.core

/** One CDSCO alert record: a batch declared Not of Standard Quality or spurious (see data-build/fetch_cdsco_alerts.py). */
data class BatchAlert(
    val batch: String, val product: String, val brand: String?, val manufacturer: String?, val result: String?,
    val kind: Kind, val reportedBy: String?, val month: String,
) {
    enum class Kind { NSQ, SPURIOUS }
}

/**
 * Checks the batch printed on a strip against CDSCO's monthly alerts. Batch codes are not unique across companies, so
 * an alert applies only when the batch matches AND the product does: the identified brand appears in the alert's
 * product/brand text, or every identified active ingredient does. A batch-only match is never shown.
 */
object BatchAlerts {
    /** Same folding as data-build/build_db.py: upper-case letters+digits, O->0, I/L->1 (common OCR confusions). */
    fun norm(batch: String): String =
        batch.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

    fun lookup(ref: RefData, batch: String): List<BatchAlert> {
        val n = norm(batch)
        if (n.length < 4) return emptyList()  // "110", "124": shared by unrelated products
        return ref.sql.query("SELECT batch, product, brand, manufacturer, result, kind, reported_by, month FROM batch_alert WHERE batch_norm=?",
            listOf(n)) {
            BatchAlert(it.string(0)!!, it.string(1)!!, it.string(2), it.string(3), it.string(4),
                if (it.string(5) == "SPURIOUS") BatchAlert.Kind.SPURIOUS else BatchAlert.Kind.NSQ, it.string(6), it.string(7)!!)
        }
    }

    /** Alerts that apply to this product (spurious first). */
    fun matching(ref: RefData, alerts: List<BatchAlert>, product: Product, items: List<CompItem>): List<BatchAlert> {
        if (alerts.isEmpty()) return emptyList()
        val keyToId = ref.ingredients.values.associate { it.key to it.id } + ref.aliases
        return alerts.filter { a -> sameProduct(a, product, items, keyToId) }.sortedBy { if (it.kind == BatchAlert.Kind.SPURIOUS) 0 else 1 }
    }

    private fun sameProduct(a: BatchAlert, p: Product, items: List<CompItem>, keyToId: Map<String, Int>): Boolean {
        val text = Normalize.nameTokens(listOfNotNull(a.product, a.brand).joinToString(" "))
        if (brandNamed(text, p)) return true
        // generic-named alerts ("Paracetamol Tablets IP 500 mg"): every active ingredient of the product must be named
        if (items.isEmpty()) return false
        val words = text.toSet()
        val allNamed = items.all { ci ->
            val main = Normalize.nameTokens(ci.ingredient.name).filter { it.length >= 5 }
            main.isNotEmpty() && main.all { it in words }
        }
        if (!allNamed) return false
        // ... and no other active ingredient: "Telmisartan 40 mg and Amlodipine 5 mg" is not a telmisartan-only product
        val mineIds = items.map { it.ingredient.id }.toSet()
        val named = text.filter { it.length >= 5 && it.all(Char::isLetter) }.mapNotNull { keyToId[Normalize.ingredientKey(it)] }.toSet()
        if (named.any { it !in mineIds }) return false
        // ... and if the alert states strengths, one of the product's must be among them (500 mg is not 650 mg)
        val alertNums = text.mapNotNull { it.toDoubleOrNull() }.toSet()
        val mine = items.mapNotNull { it.strength?.value }
        return alertNums.isEmpty() || mine.isEmpty() || mine.any { it in alertNums }
    }

    /**
     * The product's brand words appear as whole words in the alert ("telma am" in "... IP (TELMA AM)"). A short variant
     * word right after them that the product doesn't have means a sibling ("Telma" is not "Telma AM").
     */
    private fun brandNamed(alert: List<String>, p: Product): Boolean {
        val brand = p.baseKey.split(' ').filter { it.isNotEmpty() }.map { BrandIndex.fold(it) }
        if (brand.isEmpty() || brand.joinToString("").length < 4) return false
        val folded = alert.map { BrandIndex.fold(it) }
        val own = Normalize.nameTokens(p.name).map { BrandIndex.fold(it) }.toSet()
        for (i in 0..folded.size - brand.size) {
            if (folded.subList(i, i + brand.size) != brand) continue
            val next = folded.getOrNull(i + brand.size)
            if (next != null && next.length <= 3 && next.all { it.isLetter() } && next !in own && next !in setOf("ip", "bp", "usp", "and", "tab", "cap")) continue
            return true
        }
        return false
    }

    /** [extra]: records synced after the DB was built (the phone passes those stored for this batch). */
    fun check(ref: RefData, batch: String?, product: Product, items: List<CompItem>, extra: List<BatchAlert> = emptyList()): List<BatchAlert> {
        if (batch == null || norm(batch).length < 4) return emptyList()
        val all = (lookup(ref, batch) + extra.filter { norm(it.batch) == norm(batch) })
            .distinctBy { listOf(it.kind, norm(it.batch), it.product, it.month) }
        return matching(ref, all, product, items)
    }

    /** How many alert records are on the phone, for the "checked against N flagged batches" line. */
    fun recordCount(ref: RefData): Int = ref.meta["batch_alerts"]?.toIntOrNull() ?: 0
}
