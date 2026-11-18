package org.amrit.core

/**
 * Every alert comes from here: deterministic rules over the curated reference data. No model is involved.
 */
class RuleEngine(private val ref: RefData) {

    data class AntibioticInfo(
        /** null for topical/eye/ear use: WHO AWaRe applies to systemic antibiotics */
        val aware: Aware?,
        val topical: Boolean,
        val antibiotics: List<Ingredient>,
        /** e.g. "Amoxicillin/clavulanic-acid" when WHO classifies the combination itself */
        val combinationTitle: String?,
        /** WHO 'not recommended' FDC title, if the whole composition is on that list */
        val whoNotRecommended: String?,
    )

    enum class BanMatch {
        /** ingredients, dosage form and (where the notification lists them) strengths all match */
        EXACT,
        /** same ingredients, but the notification is for a different dosage form or strength */
        OTHER_FORM_OR_STRENGTH,
    }

    data class Ban(val fdc: BannedFdc, val match: BanMatch)

    data class GenericOption(
        val item: JanAushadhiItem,
        val brandUnitPrice: Double?,
        val genericUnitPrice: Double?,
        val savingPercent: Int?,
    )

    data class StripReport(
        val product: Product,
        val items: List<CompItem>,
        val antibiotic: AntibioticInfo?,
        val bans: List<Ban>,
        val generic: GenericOption?,
        /** DB composition might be cut off at 2 ingredients (source data limitation) */
        val compositionMayBeIncomplete: Boolean,
        /** "what it's for" phrase ids (from data, never generated) */
        val uses: List<RefData.UseInfo> = emptyList(),
    ) {
        val banned: Boolean get() = bans.any { it.match == BanMatch.EXACT }
        val ingredientIds: Set<Int> get() = items.map { it.ingredient.id }.toSet()
    }

    sealed class CabinetAlert {
        data class Duplicate(val ingredient: Ingredient, val products: List<Pair<String, Strength?>>) : CabinetAlert()
        data class Interacts(val interaction: Interaction, val productA: String, val productB: String) : CabinetAlert()
    }

    private val systemic = setOf("tablet", "tablet_dispersible", "capsule", "oral_liquid", "sachet", "injection")

    fun evaluate(product: Product, items: List<CompItem>, incomplete: Boolean = product.maybeIncomplete): StripReport {
        val ids = items.map { it.ingredient.id }.toSet()
        val key = ingrKeyOf(ids)
        return StripReport(product, items, antibiotic(product, items, key), bans(product, items, key), generic(product, items, key), incomplete,
            ref.usesFor(ids))
    }

    private fun antibiotic(p: Product, items: List<CompItem>, key: String): AntibioticInfo? {
        val abx = items.map { it.ingredient }.filter { it.isAntibiotic }
        if (abx.isEmpty()) return null
        val topical = p.form !in systemic
        val parenteral = p.form == "injection"
        fun group(i: Ingredient) = if (parenteral) i.awareParenteral ?: i.awareOral else i.awareOral ?: i.awareParenteral
        // WHO classifies some combinations as a unit (amoxicillin/clavulanic acid = Access).
        val combo = (2..items.size).asSequence().flatMap { n -> subsets(items.map { it.ingredient.id }, n) }
            .mapNotNull { sub -> ref.awareCombo(ingrKeyOf(sub)) }.maxByOrNull { it.second.ordinal }
        val single = abx.mapNotNull { group(it) }.maxByOrNull { it.ordinal }
        val aware = listOfNotNull(combo?.second, single).maxByOrNull { it.ordinal }
        return AntibioticInfo(
            aware = if (topical) null else aware,
            topical = topical,
            antibiotics = abx,
            combinationTitle = combo?.first,
            whoNotRecommended = ref.whoNotRecommended(key),
        )
    }

    private fun bans(p: Product, items: List<CompItem>, key: String): List<Ban> =
        ref.bannedByIngrKey(key).map { fdc ->
            val formOk = when (val req = fdc.formReq) {
                null -> true
                "tablet" -> p.form.startsWith("tablet")
                else -> p.form == req
            }
            val strengthOk = fdc.strengthVariants.isEmpty() || fdc.strengthVariants.any { variant ->
                variant.all { (iid, st) -> items.any { it.ingredient.id == iid && it.strength != null && sameStrength(it.strength, st) } }
            }
            Ban(fdc, if (formOk && strengthOk) BanMatch.EXACT else BanMatch.OTHER_FORM_OR_STRENGTH)
        }

    private fun sameStrength(a: Strength, b: Strength) = a.unit.equals(b.unit, true) && kotlin.math.abs(a.value - b.value) < 1e-6

    private fun formGroup(f: String) = when (f) {
        "tablet", "tablet_dispersible", "capsule" -> "solid_oral"
        else -> f
    }

    private fun generic(p: Product, items: List<CompItem>, key: String): GenericOption? {
        val brandUnit = if (p.mrp != null && p.packQty != null && p.packQty > 0) p.mrp / p.packQty else null
        val options = ref.janAushadhi(key).filter { ja ->
            formGroup(ja.form) == formGroup(p.form) &&
                ja.strengths.isNotEmpty() &&
                items.all { it.strength != null && ja.strengths[it.ingredient.id]?.let { s -> sameStrength(s, it.strength) } == true }
        }
        val best = options.minByOrNull { it.mrp / (it.unitQty ?: 1.0) } ?: return null
        val genUnit = best.unitQty?.takeIf { it > 0 }?.let { best.mrp / it }
        val saving = if (brandUnit != null && genUnit != null && brandUnit > 0) (((brandUnit - genUnit) / brandUnit) * 100).toInt() else null
        if (saving != null && saving <= 0) return null // only suggest when actually cheaper
        return GenericOption(best, brandUnit, genUnit, saving)
    }

    /** Duplicate ingredients and DDInter interactions across everything in "My medicines". */
    fun cabinet(reports: List<StripReport>): List<CabinetAlert> {
        val alerts = ArrayList<CabinetAlert>()
        val byIngredient = LinkedHashMap<Int, MutableList<Pair<StripReport, CompItem>>>()
        for (r in reports) for (it in r.items) byIngredient.getOrPut(it.ingredient.id) { mutableListOf() } += r to it
        for ((_, list) in byIngredient) {
            if (list.map { it.first.product.name }.distinct().size >= 2) {
                alerts += CabinetAlert.Duplicate(list.first().second.ingredient, list.map { it.first.product.name to it.second.strength })
            }
        }
        val owner = HashMap<Int, MutableSet<String>>()
        for (r in reports) for (it in r.items) owner.getOrPut(it.ingredient.id) { mutableSetOf() } += r.product.name
        for (ix in ref.interactions(byIngredient.keys)) {
            // pairs inside one fixed-dose product are intended by its maker; flag only pairs across different strips
            val pa = owner[ix.a.id].orEmpty(); val pb = owner[ix.b.id].orEmpty()
            val pair = pa.flatMap { a -> pb.filter { it != a }.map { b -> a to b } }.firstOrNull() ?: continue
            alerts += CabinetAlert.Interacts(ix, pair.first, pair.second)
        }
        return alerts.sortedByDescending {
            when (it) { is CabinetAlert.Duplicate -> 10; is CabinetAlert.Interacts -> it.interaction.severity.level }
        }
    }

    private fun subsets(xs: List<Int>, n: Int): Sequence<List<Int>> = sequence {
        if (n == 0) { yield(emptyList()); return@sequence }
        for (i in xs.indices) for (rest in subsets(xs.drop(i + 1), n - 1)) yield(listOf(xs[i]) + rest)
    }
}
