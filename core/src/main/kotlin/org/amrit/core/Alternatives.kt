package org.amrit.core

import kotlin.math.roundToInt

/**
 * "Same medicine, other brands": products with exactly the scanned composition and strength, the same dosage form and
 * the same release type (a sustained-release tablet is never offered for a plain one), cheapest per unit first.
 * Prices are the MRPs in the medicine dataset; shop prices vary. Switching brands is the pharmacist's call: the app
 * shows the options, it doesn't recommend one.
 */
object Alternatives {
    data class Option(val product: Product, val unitPrice: Double?, val savingPercent: Int?)
    /** [total]: how many brands match; [unitPrice]: the scanned product's price per unit */
    data class Result(val options: List<Option>, val total: Int, val unitPrice: Double?)

    private val release = Regex("""(?i)\b(SR|ER|CR|XR|MR|OD|XL|PR|LA|DR|EC|CD|retard)\b""")
    private fun releaseOf(name: String) = release.findAll(name).map { it.value.uppercase() }.toSet()

    fun unitPrice(p: Product): Double? = if (p.mrp != null && p.packQty != null && p.packQty > 0) p.mrp / p.packQty else null

    fun sameSalt(p: Product, ref: RefData, limit: Int = 5): Result {
        val mine = unitPrice(p)
        // a strip read from its printed composition has no DB row; a possibly truncated composition could match wrongly
        if (p.id == STRIP_READ_ID || p.maybeIncomplete) return Result(emptyList(), 0, mine)
        val kind = releaseOf(p.name)
        val all = ref.productsByComposition(p.compositionId)
            .filter { it.id != p.id && !it.discontinued && it.form == p.form && releaseOf(it.name) == kind && !it.name.equals(p.name, true) }
            .distinctBy { it.name.lowercase() }
            .sortedWith(compareBy<Product> { unitPrice(it) == null }.thenBy { unitPrice(it) ?: 0.0 }.thenBy { it.name })
        val options = all.take(limit).map { o ->
            val u = unitPrice(o)
            Option(o, u, if (mine != null && u != null && u < mine) ((1 - u / mine) * 100).roundToInt() else null)
        }
        return Result(options, all.size, mine)
    }
}
