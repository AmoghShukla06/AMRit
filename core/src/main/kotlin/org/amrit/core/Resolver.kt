package org.amrit.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

/** An ingredient name read off the strip's printed composition ("Each tablet contains Amoxycillin IP 500 mg"). */
data class FoundIngredient(
    val ingredient: Ingredient, val strength: Strength?,
    /** matched the vocabulary exactly at least once, or fuzzily on 2+ separate lines (corroborated) */
    val exact: Boolean,
    val line: Int,
    /** two different strengths read with equal support: don't trust either */
    val strengthConflict: Boolean = false,
)

/**
 * Reads ingredient names from OCR text against the ingredient alias vocabulary (~3k drugs, all spellings/salts).
 * Longest n-gram first, so "Atorvastatin Calcium" is one match and not also "Calcium". Tolerates typical OCR damage
 * ("Viamin E", "200 ng", "200 ma") and collects every reading, so a strength printed several times wins by vote.
 */
class CompositionReader(private val ref: RefData) {
    private val singleWordNoise = setOf(
        "calcium", "sodium", "potassium", "magnesium", "zinc", "iron", "water", "alcohol", "starch", "sugar", "lactose",
        "sucrose", "colour", "color", "talc", "gelatin", "tablet", "acid", "oil", "vitamin", "base", "light",
        // salt / ion words alone are not a drug ("...Acetate" must not become acetic acid)
        "acetate", "citrate", "lactate", "sulphate", "sulfate", "phosphate", "chloride", "carbonate", "gluconate",
        "hydrochloride", "maleate", "tartrate", "succinate", "fumarate", "bromide", "nitrate", "oxide", "hydroxide",
        // standard warning text on Indian strips ("not for pregnant or lactating women")
        "lactating", "lactation", "pregnant", "pregnancy",
    )
    private val cueWords = setOf("contains", "contain", "each", "ip", "bp", "usp", "composition")
    /** Preservatives, colours and coatings printed on strips; never reported as active ingredients. */
    private val excipients = listOf("methylparaben", "methyl paraben", "propylparaben", "propyl paraben", "sodium benzoate",
        "titanium dioxide", "sunset yellow", "tartrazine", "ponceau 4r", "carmoisine", "brilliant blue", "erythrosine",
        "quinoline yellow", "iron oxide", "red oxide of iron", "yellow oxide of iron", "colour", "color")
        .map { Normalize.ingredientKey(it) }.toHashSet()
    private val fuzzyIndex: BrandIndex by lazy { BrandIndex(ref.aliases.keys.filter { it.replace(" ", "").length >= 6 }) }

    private val noSpace: Map<String, Int> by lazy { ref.aliases.entries.associate { it.key.replace(" ", "") to it.value } }

    /** OCR sometimes glues words ("pregabalinmethylcobalamin"): peel known ingredient names off long tokens. */
    private fun unglue(tok: String, depth: Int = 0): List<String> {
        if (tok.length < 10 || depth > 3 || tok.any { it.isDigit() } || ref.ingredientByText(tok) != null) return listOf(tok)
        if (singleWordNoise.any { tok.startsWith(it) }) return listOf(tok)
        for (cut in tok.length - 3 downTo 7) {
            val head = tok.substring(0, cut)
            if (head in singleWordNoise) continue
            if (Normalize.ingredientKey(head).replace(" ", "") in noSpace) return listOf(head) + unglue(tok.substring(cut), depth + 1)
        }
        return listOf(tok)
    }

    private val mgTypos = Regex("""(\d)\s*(ng|ma|rng|mq|m9|mgs|rog)\b""", RegexOption.IGNORE_CASE)
    private val bigGrams = Regex("""\b(\d{2,4})\s*g\b""", RegexOption.IGNORE_CASE) // "200 g" on a strip is 200 mg misread
    private val glueUnit = Regex("""(\d) (mg|mcg|g|gm|iu|ml)\b""")
    private val lpDigits = Regex("""\b[il1|]p(\d)""", RegexOption.IGNORE_CASE) // "lP200" -> "IP 200"
    private val pharmacopoeia = Regex("""\b(?:I\.\s?P|B\.\s?P|U\.\s?S\.\s?P|Ph\.\s?Eur)\.?""", RegexOption.IGNORE_CASE)
    private val letterDigitJoin = Regex("""\b([a-z]) (\d{1,2})\b""")
    // "Zinc Sulphate Monohydrate USP eq. to Elemental Zinc 22.5 mg": one ingredient (the part after "eq. to").
    // The salt name is dropped only if it has no number of its own (Evion: "Tocopheryl Acetate 200 mg equivalent to
    // alpha tocopherol 91 mg" keeps both readings for the strength vote).
    private val eqTo = Regex("""(^|[,;:+(]|\band\b)([^,;:+()\d]*?)\b(?:eq\.?|equivalent|equiv\.?)\s*to\b""", RegexOption.IGNORE_CASE)

    private fun strengthAfter(toks: List<String>): Strength? {
        var t = toks.take(6).joinToString(" ")
        t = mgTypos.replace(t, "$1mg")
        t = bigGrams.replace(t) { m -> "${m.groupValues[1]}mg" }
        return Normalize.parseStrength(glueUnit.replace(t, "$1$2"))
    }

    private class Acc(val ing: Ingredient) {
        var exact = false
        val fuzzyLines = HashSet<Int>()
        var firstLine = Int.MAX_VALUE
        val strengths = ArrayList<Strength>()
    }

    fun read(ocr: OcrResult): Map<Int, FoundIngredient> {
        val acc = LinkedHashMap<Int, Acc>()
        ocr.lines.forEachIndexed { li, line ->
            var raw = Normalize.splitCamel(line.text)
            raw = lpDigits.replace(raw, "IP $1")
            raw = eqTo.replace(raw) { m -> m.groupValues[1] + " " }
            raw = pharmacopoeia.replace(raw) { m -> " " + m.value.filter { it.isLetter() } + " " } // "I.P." -> "IP"
            val toks = Normalize.nameTokens(raw).flatMap { unglue(it) }
            val used = BooleanArray(toks.size)
            // fuzzy (typo-tolerant) matching is the expensive part: only on lines that look like composition text
            val compositionLike = toks.any { t -> t.any(Char::isDigit) } || toks.any { it in cueWords }
            // pass 1: exact matches only (longest first); pass 2: typo-tolerant on what is left.
            // Otherwise a fuzzy 3-word match can swallow words that an exact 2-word match would have read correctly.
            for (fuzzyPass in listOf(false, true)) for (n in 4 downTo 1) {
                for (i in 0..toks.size - n) {
                    if ((i until i + n).any { used[it] }) continue
                    val slice = toks.subList(i, i + n)
                    if (slice.first().first().isDigit()) continue
                    val phrase = slice.joinToString(" ")
                    if (n == 1 && (phrase.length < 4 || phrase in singleWordNoise)) continue
                    var exact = true
                    // "vitamin b 12" (letters and digits were split) is "vitamin b12"
                    var ing = if (fuzzyPass) null else ref.ingredientByText(phrase) ?: ref.ingredientByText(letterDigitJoin.replace(phrase, "$1$2"))
                    val joined = phrase.replace(" ", "")
                    if (fuzzyPass && compositionLike && n <= 3 && joined.length >= 6 && slice.none { it.all(Char::isDigit) }) {
                        // OCR damage: "viamin e", "toconherol"
                        val hit = fuzzyIndex.search(Normalize.ingredientKey(phrase), 1, 0.84).firstOrNull()
                        ing = hit?.let { h -> ref.aliases[h.baseKey]?.let { ref.ingredients[it] } }
                        exact = false
                    }
                    if (ing == null) continue
                    // a single letter+number vitamin ("vitamin b") must not stop before its number ("12")
                    if (!fuzzyPass && i + n < toks.size && toks[i + n].all(Char::isDigit) && toks[i + n].length <= 2 &&
                        slice.last().length == 1 && ref.ingredientByText(phrase + toks[i + n]) != null) continue
                    if (ing.key in excipients || Normalize.ingredientKey(phrase) in excipients) { for (k in i until i + n) used[k] = true; continue }
                    for (k in i until i + n) used[k] = true
                    val a = acc.getOrPut(ing.id) { Acc(ing) }
                    if (exact) a.exact = true else a.fuzzyLines += li
                    a.firstLine = minOf(a.firstLine, li)
                    strengthAfter(toks.subList(i + n, toks.size))?.let { a.strengths += it }
                }
            }
        }
        val out = LinkedHashMap<Int, FoundIngredient>()
        for ((id, a) in acc) {
            // majority vote over every reading of this ingredient's strength
            val votes = a.strengths.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            val top = votes.firstOrNull()
            val tie = votes.size > 1 && votes[1].value == top!!.value
            out[id] = FoundIngredient(a.ing, top?.key, a.exact || a.fuzzyLines.size >= 2, a.firstLine, strengthConflict = tie)
        }
        return out
    }
}

@Serializable
data class Features(
    val brandSim: Double = 0.0,
    val margin: Double = 0.0,
    val compSeen: Double = 0.0,
    val compAgree: Double = 0.0,
    val compConflict: Double = 0.0,
    val strengthAgree: Double = 0.0,
    val variantAgree: Double = 0.0,
    val mfrFound: Double = 0.0,
    val ocrConf: Double = 0.0,
    val lineWeight: Double = 0.0,
    /** 1 = the strip's own words name this product's dosage form, 0.5 = no form word seen (solid oral prior), 0.2 = other form */
    val formAgree: Double = 0.0,
    /** share of the brand's own words found in the text: "Neurobion Forte" covers only 2/3 of "neurobion rf forte" */
    val baseCover: Double = 0.0,
) {
    fun vector() = doubleArrayOf(brandSim, margin, compSeen, compAgree, compConflict, strengthAgree, variantAgree, mfrFound, ocrConf, lineWeight, formAgree, baseCover)

    companion object {
        val NAMES = listOf("brandSim", "margin", "compSeen", "compAgree", "compConflict", "strengthAgree", "variantAgree", "mfrFound", "ocrConf", "lineWeight", "formAgree", "baseCover")
    }
}

/** Logistic confidence model + the two band thresholds. Weights are fitted by the eval harness (eval/), shipped as assets/scorer.json. */
@Serializable
data class ScorerConfig(
    val bias: Double = -7.0,
    val weights: List<Double> = listOf(5.0, 3.0, 0.0, 2.5, -3.0, 1.5, 1.0, 0.5, 0.5, 1.0, 1.5, 4.0),
    /** accept on device at or above this (conservative until the eval harness fits real weights) */
    val acceptAt: Double = 0.92,
    /** below this go straight to the vision fallback; between the two, ask the user to pick */
    val confirmAt: Double = 0.45,
    val source: String = "hand-set defaults",
) {
    fun score(f: Features): Double {
        val v = f.vector()
        var z = bias
        for (i in v.indices) z += weights.getOrElse(i) { 0.0 } * v[i]
        return 1.0 / (1.0 + exp(-z))
    }
}

/**
 * Why a candidate is believed. The decision is made on these tiers, not on a fitted probability:
 * a single strong signal (a brand name match) is never enough on its own if the strip could mean another product.
 */
enum class Evidence(val rank: Int) {
    /** composition printed on the strip, every ingredient matched exactly with a strength, composition wording present */
    COMPOSITION(5),
    /** whole brand name matched AND an independent signal agrees (ingredients, the product's number, strength, maker) */
    BRAND_CORROBORATED(4),
    /** whole brand name matched, nothing contradicts, and no other composition is sold under that name */
    BRAND_UNIQUE(3),
    /** plausible but not established: may be offered on the confirm screen */
    WEAK(1),
    /** never shown */
    NONE(0),
}

data class Candidate(
    val product: Product,
    val features: Features,
    val score: Double,
    /** Ingredients read from the strip that the DB composition is missing (only when the DB row may be truncated). */
    val extraIngredients: List<FoundIngredient> = emptyList(),
    val evidence: Evidence = Evidence.NONE,
) {
    val items: List<CompItem>
        get() = product.items + extraIngredients.filter { e -> product.items.none { it.ingredient.id == e.ingredient.id } }
            .map { CompItem(it.ingredient, it.strength) }
    val compositionSignature: String
        get() = items.sortedBy { it.ingredient.id }.joinToString("+") { "${it.ingredient.id}:${it.strength}" }
}

enum class Band { ACCEPT, CONFIRM, FALLBACK }

data class Resolution(
    val band: Band,
    val best: Candidate?,
    /** Distinct-composition alternatives, best first (for the confirm screen). */
    val candidates: List<Candidate>,
    val found: Map<Int, FoundIngredient>,
    val matchMs: Long,
) {
    val confidence: Double get() = best?.score ?: 0.0
}

/** Product id of a result built from the composition printed on the strip (brand not in the DB). */
const val STRIP_READ_ID = -1

/** OCR text -> best product + confidence. Pure function of (OCR, reference data, learned aliases, scorer config). */
class Resolver(private val ref: RefData, val index: BrandIndex, var config: ScorerConfig = ScorerConfig()) {
    private val comp = CompositionReader(ref)
    /** the reader this resolver uses, for marking ingredient lines in "What we read" */
    val composition: CompositionReader get() = comp

    private val stop = (
        "tablets tablet tab capsules capsule ip bp usp mg mcg ml each film coated contains contain mfd mfg manufactured by " +
            "marketed batch b no exp expiry date mrp rs incl of all taxes for use only store below the and schedule h h1 drug " +
            "warning to be sold retail on prescription registered medical practitioner lic keep out reach children dosage as " +
            "directed physician protect from light moisture place cool dry not exceed temperature strip pharma pharmaceuticals " +
            "ltd limited pvt private india healthcare laboratories labs lab co www com in uncoated dispersible enteric " +
            "release sustained extended prolonged oral use tm reg trademark gm g x lot exp mfd"
        ).split(' ').toHashSet()

    private data class Query(val text: String, val weight: Double, val conf: Double)

    /** Dosage-form groups named on the pack. A strip that says "Tablets" is never an injection. */
    private enum class FormGroup { SOLID, LIQUID, INJECTION, TOPICAL, EYE_EAR, INHALED }

    private fun groupOf(productForm: String) = when (productForm) {
        "tablet", "tablet_dispersible", "capsule", "sachet" -> FormGroup.SOLID
        "oral_liquid" -> FormGroup.LIQUID
        "injection" -> FormGroup.INJECTION
        "eye_ear_nose" -> FormGroup.EYE_EAR
        "topical" -> FormGroup.TOPICAL
        "inhaled" -> FormGroup.INHALED
        else -> null
    }

    private val formWords = mapOf(
        FormGroup.SOLID to setOf("tablet", "tablets", "tab", "tabs", "capsule", "capsules", "cap", "caps", "softgel", "softgels", "caplet", "caplets"),
        FormGroup.LIQUID to setOf("syrup", "suspension", "linctus", "elixir"),
        FormGroup.INJECTION to setOf("injection", "inj", "vial", "ampoule", "ampule", "intravenous", "intramuscular", "infusion"),
        FormGroup.TOPICAL to setOf("cream", "ointment", "gel", "lotion"),
        FormGroup.EYE_EAR to setOf("eye", "ear"),
        FormGroup.INHALED to setOf("inhaler", "inhalers", "respules", "rotacap", "rotacaps", "inhalation", "puffs", "mdi"),
    )

    private fun formsNamed(tokens: Collection<String>): Set<FormGroup> =
        formWords.filter { (_, ws) -> tokens.any { it in ws } }.keys

    private fun queries(ocr: OcrResult): List<Query> {
        val maxH = ocr.maxLineHeight.toDouble().coerceAtLeast(1.0)
        val out = ArrayList<Query>()
        // brand names are among the biggest text on a pack: query only the 12 tallest lines (speed + fewer false hits)
        val big = ocr.lines.sortedByDescending { it.height }.take(12).toHashSet()
        for (line0 in ocr.lines) {
            if (line0 !in big) continue
            val line = line0.copy(text = Normalize.splitCamel(line0.text))
            val w = (line.height / maxH).coerceIn(0.0, 1.0)
            // two tokenisations: the DB's (splits 'pan40' -> 'pan 40') and raw words, so OCR-garbled
            // words with digits inside ('D0L0') stay whole and can be repaired by the OCR fold
            val raw = line.text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            for (toks in listOf(Normalize.nameTokens(line.text), raw).distinct()) for (n in 1..3) for (i in 0..toks.size - n) {
                val s = toks.subList(i, i + n)
                if (s.first().all { it.isDigit() }) continue
                if (s.all { it in stop || it.all(Char::isDigit) }) continue
                if (s.first() in stop) continue
                val q = s.joinToString(" ")
                if (q.replace(" ", "").length < 3) continue
                out += Query(q, w, line.confidence.toDouble())
            }
        }
        return out
    }

    fun resolve(ocr: OcrResult): Resolution = resolve(ocr, allowMissingStrength = false)

    /**
     * [allowMissingStrength]: the vision model returned an explicit ingredient list; a strength it could not read
     * does not make the ingredient doubtful (phone OCR stays strict: an unexplained word without a number is noise).
     */
    fun resolve(ocr: OcrResult, allowMissingStrength: Boolean): Resolution {
        val t0 = System.nanoTime()
        val found = comp.read(ocr)
        val tokens = ocr.lines.flatMap { Normalize.nameTokens(it.text) }
        val foldedTokens = tokens.map { BrandIndex.fold(it) }.toHashSet()
        val numbers = tokens.filter { it.first().isDigit() }.mapNotNull { it.toDoubleOrNull() }.toHashSet()
        val named = formsNamed(tokens.toHashSet())
        val gluedLines = ocr.lines.map { BrandIndex.fold(Normalize.splitCamel(it.text)) }

        // 1. brand candidates: best (similarity x size weight) per base key
        data class BaseHit(val sim: Double, val weight: Double, val conf: Double)
        val bases = HashMap<String, BaseHit>()
        val searchCache = HashMap<String, List<BrandIndex.Hit>>()
        for (q in queries(ocr)) {
            val len = q.text.count { it.isLetterOrDigit() }
            for (h in searchCache.getOrPut(q.text) { index.search(q.text, 4, 0.6) }) {
                // a 3-4 letter fragment matching some short brand is weak evidence
                // a 3-4 letter fragment matching some short brand is weak evidence, unless it is an exact match of 4+
                val sim = h.similarity * if (len >= 5 || h.viaAlias || (len >= 4 && h.similarity >= 0.999)) 1.0 else 0.75
                val cur = bases[h.baseKey]
                val eff = sim * (0.6 + 0.4 * q.weight)
                if (cur == null || eff > cur.sim * (0.6 + 0.4 * cur.weight)) bases[h.baseKey] = BaseHit(sim, q.weight, q.conf)
            }
        }
        // a longer brand that matches as well as its own prefix wins: "mahacef cv" over "mahacef", "glucobay m" over
        // "glucobay". The suffix letters usually mean an extra ingredient, so picking the prefix would be a real error.
        for (shortKey in bases.keys.toList()) {
            val s0 = bases[shortKey] ?: continue
            val longer = bases.entries.firstOrNull { (k, v) -> k.startsWith("$shortKey ") && v.sim >= s0.sim - 0.05 && v.sim >= 0.85 }
            if (longer != null) bases[shortKey] = s0.copy(sim = s0.sim * 0.85)
        }
        val topBases = bases.entries.sortedByDescending { it.value.sim * (0.6 + 0.4 * it.value.weight) }.take(8)
        val products = ref.productsByBaseKeys(topBases.map { it.key })

        // 2. generic strips ("Paracetamol Tablets IP 500 mg"): products whose composition equals what was read exactly
        val exactFound = found.values.filter { it.exact }
        val foundIds = found.keys

        // raw brand lines (lowercase letters+digits, no OCR folding) for reading what follows a brand
        val rawLines = ocr.lines.map { Normalize.splitCamel(it.text).lowercase().filter { c -> c.isLetterOrDigit() } }
        // products that can be at most an option on the confirm screen (see the rules below)
        val capped = HashSet<Int>()

        // 3. features per product
        val cands = ArrayList<Candidate>()
        for (p in products) {
            val bh = bases[p.baseKey] ?: continue
            // hard rule: if the pack names its form and this product is a different form, it is not this product
            val g = groupOf(p.form)
            if (named.isNotEmpty() && g != null && g !in named) continue
            val formAgree = when {
                g != null && g in named -> 1.0
                named.isEmpty() && g == FormGroup.SOLID -> 0.5 // it's a strip scanner: solid oral is the prior
                else -> 0.2
            }
            val baseToks = p.baseKey.split(' ')
            val folded = BrandIndex.fold(p.baseKey)
            val baseCover = if (gluedLines.any { folded.length >= 4 && it.contains(folded) }) 1.0 else baseToks.count { t ->
                val ft = BrandIndex.fold(t)
                // a 1-letter brand word ("Paracetamol O") only counts when seen glued to the rest (handled above)
                if (ft.length < 2) return@count false
                // fuzzy only for same-length words (OCR substitutes letters; "evion" is not "zevion")
                ft in foldedTokens || (ft.length >= 5 && foldedTokens.any { it.length == ft.length && Levenshtein.similarity(it, ft) >= 0.8 })
            }.toDouble() / baseToks.size
            val pIds = p.ingredientIds
            val seen = if (foundIds.isNotEmpty()) 1.0 else 0.0
            val agree = if (pIds.isEmpty() || foundIds.isEmpty()) 0.0 else pIds.count { it in foundIds }.toDouble() / pIds.size
            // the strip clearly lists its ingredients and this product has none of them: a namesake, not this product
            if (exactFound.size >= 2 && pIds.none { id -> exactFound.any { it.ingredient.id == id } }) continue
            // the strip prints "Vitamin E ... 200 mg" and this product has 400 mg of it: a different product
            if (exactFound.any { f -> !f.strengthConflict && p.items.any { strengthClash(it, f) } }) continue
            // the product's own number (Zevion "400") appears nowhere on a strip that does print numbers ("200"): not it
            val ownNums = p.nameKey.split(' ').mapNotNull { it.toDoubleOrNull() }.toSet()
            if (ownNums.isNotEmpty() && ownNums.none { it in numbers } && numbers.any { it >= 10 }) continue
            // the strip states a composition (ingredient + strength) and this product shares none of it
            if (exactFound.any { it.strength != null } && pIds.none { id -> exactFound.any { it.ingredient.id == id } }) continue
            // share of the ingredients read off the strip that this product does NOT explain; half weight when the
            // DB row may be truncated (the extra ingredient might be the missing one, or it might be a different product)
            val conflictIds = exactFound.map { it.ingredient.id }.filter { it !in pIds }
            val unexplained = if (exactFound.isEmpty()) 0.0 else conflictIds.size.toDouble() / exactFound.size
            val conflict = if (p.maybeIncomplete) unexplained * 0.5 else unexplained
            val strengths = p.items.mapNotNull { it.strength?.value }
            val stAgree = if (strengths.isEmpty()) 0.5 else strengths.count { it in numbers }.toDouble() / strengths.size
            val variantToks = p.nameKey.split(' ').drop(p.baseKey.split(' ').size)
            val vAgree = if (variantToks.isEmpty()) 0.5 else variantToks.count { t ->
                if (t.first().isDigit()) t.toDoubleOrNull()?.let { it in numbers } ?: false else BrandIndex.fold(t) in foldedTokens
            }.toDouble() / variantToks.size
            // the brand is just the maker's name ("Cipla Inhaler" on any Cipla strip): the maker's name printed on the
            // strip is then the same evidence counted twice, and the brand match itself says little
            val mfrToks = p.manufacturer?.let { m -> Normalize.nameTokens(m).map { BrandIndex.fold(it) }.toSet() } ?: emptySet()
            val brandIsMaker = baseToks.all { BrandIndex.fold(it) in mfrToks }
            val mfr = !brandIsMaker && (p.manufacturer?.let { m ->
                Normalize.nameTokens(m).firstOrNull { it.length >= 4 && it !in stop }?.let { BrandIndex.fold(it) in foldedTokens }
            } ?: false)
            if (brandIsMaker) capped += p.id
            // the strip prints a variant right after this brand that the product's name lacks: "Amlopres Trio" is not
            // Amlopres 10, "Silofast D8" is not Silofast 8, "LAFORMINGV2" is not Laformin-G 2
            if (unexplainedVariant(rawLines, folded, variantToks)) capped += p.id
            // add strip-read ingredients to a possibly-truncated DB row only if the strip also shows all of the row's own
            // ingredients (otherwise the extra ingredient says "different product", not "missing ingredient")
            val extras = if (p.maybeIncomplete && pIds.all { it in foundIds })
                exactFound.filter { it.ingredient.id !in pIds && it.strength != null } else emptyList()
            val f = Features(
                brandSim = bh.sim, compSeen = seen, compAgree = agree, compConflict = conflict, strengthAgree = stAgree,
                variantAgree = vAgree, mfrFound = if (mfr) 1.0 else 0.0, ocrConf = bh.conf, lineWeight = bh.weight,
                margin = 0.0, formAgree = formAgree, baseCover = baseCover,
            )
            cands += Candidate(p, f, 0.0, extras)
        }

        // generic-name strips resolve through composition: any product with exactly that composition stands in
        if (exactFound.isNotEmpty() && exactFound.all { it.strength != null }) {
            val key = ingrKeyOf(exactFound.map { it.ingredient.id })
            val generic = genericProduct(exactFound)
            if (generic != null && cands.none { ingrKeyOf(it.product.ingredientIds) == key && it.features.brandSim > 0.9 }) {
                val bigLine = exactFound.minOf { it.line }.let { ocr.lines.getOrNull(it) }
                val w = ((bigLine?.height ?: 0f) / ocr.maxLineHeight).toDouble()
                val g = groupOf(generic.form)
                if (named.isEmpty() || g == null || g in named)
                    cands += Candidate(generic, Features(brandSim = 0.9, compSeen = 1.0, compAgree = 1.0, strengthAgree = 1.0,
                        variantAgree = 0.5, ocrConf = (bigLine?.confidence ?: 0f).toDouble(), lineWeight = w,
                        formAgree = if (g != null && g in named) 1.0 else 0.5, baseCover = 1.0), 0.0)
            }
        }

        // 4. margin = lead in match strength over the best candidate with a DIFFERENT composition (ranking only)
        fun strength(f: Features) = f.brandSim * (0.6 + 0.4 * f.lineWeight) * (0.5 + 0.5 * f.compAgree) * (0.75 + 0.25 * f.strengthAgree)
        val prelim = cands.map { it to strength(it.features) }.sortedByDescending { it.second }
        var scored = prelim.map { (c, s) ->
            val rival = prelim.firstOrNull { it.first.compositionSignature != c.compositionSignature }?.second ?: 0.0
            val f = c.features.copy(margin = (s - rival).coerceIn(0.0, 1.0))
            c.copy(features = f, score = config.score(f))
        }
        val cue = ocr.lines.any { compositionCue.containsMatchIn(it.text) }

        // 5. evidence tier per candidate
        val compsByBase = scored.groupBy { it.product.baseKey }.mapValues { (_, v) -> v.map { it.compositionSignature }.toSet() }
        scored = scored.map { c ->
            val f = c.features
            val viaComposition = c.product.id == STRIP_READ_ID || c.product.name.endsWith("(generic)")
            val fullBrand = f.baseCover >= 0.999 && f.brandSim >= 0.88
            val corroborated = (f.compAgree > 0.0 && f.compConflict < 0.5) || f.variantAgree >= 0.999 ||
                (f.strengthAgree >= 0.999 && c.product.items.any { it.strength != null }) || f.mfrFound >= 1.0
            // leaves an ingredient that the strip clearly prints unexplained (and didn't absorb it as a truncated row):
            // at most an option on the confirm screen, never an answer
            val unexplained = f.compConflict > 0.0 && c.extraIngredients.isEmpty()
            // the strip names a drug with its strength (even if OCR garbled it) and this product contains none of what
            // was read: the brand is a namesake ("Kelvin" paracetamol vs a Kelvin strip printing ciprofloxacin 500 mg).
            // A matching number ("500") is then not corroboration; it belongs to the other drug.
            val contradicts = !viaComposition && f.compAgree == 0.0 && found.values.any { it.strength != null }
            val ev = when {
                viaComposition -> if (cue) Evidence.COMPOSITION else Evidence.WEAK
                c.product.id in capped || contradicts -> if (f.brandSim >= 0.8 && f.compConflict < 0.5) Evidence.WEAK else Evidence.NONE
                unexplained -> if (f.brandSim >= 0.8 && f.baseCover >= 0.5 && f.compConflict < 0.5) Evidence.WEAK else Evidence.NONE
                fullBrand && corroborated -> Evidence.BRAND_CORROBORATED
                // a brand name alone is enough only if it is long enough to be distinctive, in a plausible form,
                // and only one composition is sold under it ("DARN" folding to the gel brand "dam" is not enough)
                fullBrand && f.brandSim >= 0.95 && f.lineWeight >= 0.6 && f.formAgree >= 0.5 &&
                    c.product.baseKey.count { it.isLetter() } >= 5 &&
                    (compsByBase[c.product.baseKey]?.size ?: 0) == 1 -> Evidence.BRAND_UNIQUE
                f.brandSim >= 0.8 && f.baseCover >= 0.5 && f.compConflict < 0.5 -> Evidence.WEAK
                else -> Evidence.NONE
            }
            c.copy(evidence = ev)
        }

        // Brand not in the DB (e.g. many supplements), but the strip prints a clean composition: the printed
        // ingredients + strengths, mapped exactly onto the curated vocabulary, are the answer. Labelled as such in the UI.
        if (scored.none { it.evidence.rank >= Evidence.BRAND_CORROBORATED.rank }) {
            stripRead(exactFound, ocr, named, allowMissingStrength)?.let { c -> scored = scored + c.copy(evidence = if (cue) Evidence.COMPOSITION else Evidence.WEAK) }
        }

        val ranked = scored.filter { it.evidence != Evidence.NONE }
            .sortedWith(compareByDescending<Candidate> { it.evidence.rank }.thenByDescending { it.score }
                .thenByDescending { it.features.formAgree }.thenBy { it.product.discontinued })
        val distinct = ranked.distinctBy { it.compositionSignature }.take(4)
        val best = distinct.firstOrNull()
        // accept only if the best is strong AND no different composition has equally strong evidence
        val strongRivals = distinct.drop(1).count {
            it.evidence.rank >= Evidence.BRAND_UNIQUE.rank && it.evidence.rank >= (best?.evidence?.rank ?: 0) && !ruledOut(it, best!!)
        }
        val band = when {
            best == null -> Band.FALLBACK
            best.evidence.rank >= Evidence.BRAND_UNIQUE.rank && strongRivals == 0 -> Band.ACCEPT
            else -> Band.CONFIRM
        }
        return Resolution(band, best, distinct, found, (System.nanoTime() - t0) / 1_000_000)
    }

    private val compositionCue = Regex("\\b(contains?|each|ip|bp|usp|composition)\\b", RegexOption.IGNORE_CASE)

    private fun stripRead(found: List<FoundIngredient>, ocr: OcrResult, named: Set<FormGroup>, allowMissingStrength: Boolean): Candidate? {
        if (found.isEmpty()) return null
        if (if (allowMissingStrength) found.none { it.strength != null } else found.any { it.strength == null }) return null
        // the strip disagrees with itself about a strength: show nothing rather than pick one
        if (found.any { it.strengthConflict }) return null
        val form = when {
            FormGroup.SOLID in named -> if (ocr.lines.any { Regex("(?i)\\bcap(sule)?s?\\b").containsMatchIn(it.text) }) "capsule" else "tablet"
            FormGroup.LIQUID in named -> "oral_liquid"
            FormGroup.INJECTION in named -> "injection"
            FormGroup.TOPICAL in named -> "topical"
            FormGroup.EYE_EAR in named -> "eye_ear_nose"
            else -> "tablet"
        }
        val items = found.sortedBy { it.line }.map { CompItem(it.ingredient, it.strength) }
        val p = Product(id = STRIP_READ_ID, name = items.joinToString(" + ") { "${it.ingredient.name} ${it.strength}" },
            baseKey = "", manufacturer = null, form = form, packQty = null, mrp = null, discontinued = false,
            compositionId = -1, maybeIncomplete = false, items = items)
        val f = Features(compSeen = 1.0, compAgree = 1.0, strengthAgree = 1.0, formAgree = if (named.isEmpty()) 0.5 else 1.0)
        return Candidate(p, f, 0.95)
    }

    /**
     * A rival the strip itself rules out, so it shouldn't force the "which one is it?" question:
     *  - none of its strengths is printed while all of the best's are ("Paracetamol Tablet" next to PACIMOL 650:
     *    the big generic-name line matched a generic-named product, but its strength isn't on the strip)
     *  - a less specific sibling of the same brand when the strip prints the best one's variant ("Atorva" vs "Atorva 5")
     *  - a form that fits the strip worse (a syrup of the same brand on a tablet strip)
     */
    private fun ruledOut(rival: Candidate, best: Candidate): Boolean {
        val r = rival.features; val b = best.features
        if (r.strengthAgree == 0.0 && b.strengthAgree >= 0.999 && rival.product.items.any { it.strength != null }) return true
        val sameBrand = rival.product.baseKey == best.product.baseKey
        if (sameBrand && b.variantAgree >= 0.999 && r.variantAgree < 0.999) return true
        if (sameBrand && r.formAgree < b.formAgree) return true
        return false
    }

    /** Words that may follow a brand on its line without naming a different variant. */
    private val brandTail = (formWords.values.flatten() + listOf("ip", "bp", "usp", "mg", "mcg", "ml", "gm", "sr", "er", "xr", "cr",
        "od", "dt", "md", "tm", "rx", "strip", "and", "with")).toSet()

    /**
     * True when a line starts with this brand and the brand is directly followed by letters that are neither the
     * product's own variant ("cv", "650", "forte") nor a form/unit word. Only lines that START with the brand count,
     * so a brand-like run inside another word is never read as a variant.
     */
    private fun unexplainedVariant(rawLines: List<String>, foldedBase: String, variantToks: List<String>): Boolean {
        if (foldedBase.length < 4) return false
        val own = variantToks.map { it.lowercase().filter { c -> c.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        for (raw in rawLines) {
            // brand length in raw characters (OCR folding can merge "rn" -> "m", so try a couple of lengths)
            val k = (foldedBase.length - 1..foldedBase.length + 2).firstOrNull { it in 1..raw.length && BrandIndex.fold(raw.take(it)) == foldedBase }
                ?: continue
            var rest = raw.substring(k)
            for (v in own) if (rest.startsWith(v)) rest = rest.substring(v.length)
            val letters = rest.takeWhile { it.isLetter() }
            if (letters.isEmpty() || letters.length > 8) continue
            if (brandTail.any { letters == it || (it.length >= 3 && letters.startsWith(it)) }) continue
            if (own.any { it.startsWith(letters) }) continue
            // OCR often repeats the brand on one line ("Parawel- 650 Parauel- 65"): a copy of the brand is not a variant
            val f = BrandIndex.fold(letters)
            if (f.length >= 4 && Levenshtein.similarity(f, foldedBase.take(f.length)) >= 0.7) continue
            return true
        }
        return false
    }

    private fun strengthClash(item: CompItem, f: FoundIngredient): Boolean {
        val a = item.strength ?: return false
        val b = f.strength ?: return false
        return item.ingredient.id == f.ingredient.id && a.unit.equals(b.unit, true) && kotlin.math.abs(a.value - b.value) > 1e-6
    }

    /**
     * The vision fallback states how many active ingredients it read. A composition-based answer that could not map
     * all of them would silently drop an ingredient, so it is never shown; brand candidates must explain what mapped.
     */
    fun resolveReading(reading: LlmReading, original: OcrResult?): Resolution {
        // only the model's transcription: mixing in the phone's noisy OCR let excipients ("Propyl paraben") into
        // compositions. [original] is kept for API compatibility.
        val r0 = resolve(reading.asOcr(null), allowMissingStrength = true)
        // a result built from the printed composition carries the brand the model read as its title
        val brand = reading.brand?.trim()?.takeIf { it.length >= 3 }
        val r = if (brand == null) r0 else r0.copy(
            candidates = r0.candidates.map { if (it.product.id == STRIP_READ_ID) it.copy(product = it.product.copy(name = brand)) else it },
            best = r0.best?.let { if (it.product.id == STRIP_READ_ID) it.copy(product = it.product.copy(name = brand)) else it })
        val listed = reading.activeIngredients.size
        if (listed == 0) return r
        val keep = r.candidates.filter { c ->
            val viaComposition = c.product.id == STRIP_READ_ID || c.product.name.endsWith("(generic)")
            !(viaComposition && c.items.size < listed)
        }.map { c ->
            // the strip lists N active ingredients; a product with a different number is at most an option to confirm
            if (c.items.size != listed && c.evidence.rank > Evidence.WEAK.rank) c.copy(evidence = Evidence.WEAK) else c
        }
        if (keep == r.candidates) return r
        val best = keep.firstOrNull()
        val band = when {
            best == null -> Band.FALLBACK
            best.evidence.rank >= Evidence.BRAND_UNIQUE.rank && keep.drop(1).none { it.evidence.rank >= best.evidence.rank } -> Band.ACCEPT
            else -> Band.CONFIRM
        }
        return r.copy(band = band, best = best, candidates = keep)
    }

    /**
     * Two independent vision readers read the same strip. Each reading goes through the same matcher and rules, then:
     * - one resolves, the other does not (e.g. it contradicts itself): use the one that resolves;
     * - both resolve to the same ingredients: accept; a strength they read differently is shown as unclear (null);
     * - they resolve to different ingredients: never pick one; offer both on the confirm screen.
     */
    fun resolveReadings(readings: List<LlmReading>, original: OcrResult?): Resolution {
        val rs = readings.map { resolveReading(it, original) }
        if (rs.size <= 1) return rs.firstOrNull() ?: Resolution(Band.FALLBACK, null, emptyList(), emptyMap(), 0)
        val ok = rs.filter { it.band == Band.ACCEPT && it.best != null }
        if (ok.isEmpty()) {
            val cands = rs.flatMap { it.candidates }.distinctBy { it.compositionSignature }.take(3)
            return Resolution(if (cands.isEmpty()) Band.FALLBACK else Band.CONFIRM, cands.firstOrNull(), cands, rs.first().found, rs.sumOf { it.matchMs })
        }
        if (ok.size == 1) return ok.first()
        val a = ok[0].best!!; val b = ok[1].best!!
        val ida = a.items.map { it.ingredient.id }.toSet(); val idb = b.items.map { it.ingredient.id }.toSet()
        if (ida != idb) return Resolution(Band.CONFIRM, a, listOf(a, b), ok[0].found, rs.sumOf { it.matchMs })
        if (a.product.id != STRIP_READ_ID) return ok[0] // a DB product: its composition comes from the database anyway
        val merged = a.items.map { ia ->
            val sb = b.items.first { it.ingredient.id == ia.ingredient.id }.strength
            if (ia.strength == sb) ia else CompItem(ia.ingredient, null) // readers disagree: strength unclear
        }
        val c = a.copy(product = a.product.copy(items = merged))
        return ok[0].copy(best = c, candidates = listOf(c))
    }

    /** A real DB product whose composition is exactly what the strip prints (generic strips, or brands missing from
     *  the DB like "Evion 200": the printed composition still gives the right ingredients). */
    private fun genericProduct(found: List<FoundIngredient>): Product? {
        // prefers a solid oral product: this is a strip scanner
        val ids = found.map { it.ingredient.id }.toSet()
        val name = found.joinToString(" + ") { "${it.ingredient.name} ${it.strength}" }
        val pool = ref.productsByIngrKey(ingrKeyOf(ids))
        val match = pool.sortedBy { if (groupOf(it.form) == FormGroup.SOLID) 0 else 1 }.firstOrNull { p ->
            p.ingredientIds == ids && found.all { f -> p.items.any { it.ingredient.id == f.ingredient.id && it.strength == f.strength } }
        } ?: return null
        return match.copy(name = "$name (generic)")
    }

    /** OCR phrase that best resembles [baseKey] — what to learn as an alias after a fallback resolved it. */
    fun aliasPhraseFor(ocr: OcrResult, baseKey: String): String? {
        val target = BrandIndex.fold(baseKey)
        return queries(ocr).filter { it.weight > 0.5 }.map { it.text to Levenshtein.similarity(BrandIndex.fold(it.text), target) }
            .filter { it.second in 0.35..0.95 }.maxByOrNull { it.second }?.first
    }
}
