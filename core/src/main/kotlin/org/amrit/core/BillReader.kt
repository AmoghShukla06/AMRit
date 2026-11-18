package org.amrit.core

/**
 * Bill saver: a printed pharmacy bill, read line by line. A line becomes an item only when its first word(s) are a
 * known brand exactly (after OCR folding, so "D0LO" is Dolo but "DOLOMITE" is not) and every number in the chosen
 * product's name is printed on the line ("PAN 40", not a guess between PAN 20 and PAN 40). Anything else that looks
 * like an item is listed as not recognised; nothing is guessed. Handwritten prescriptions are out of scope.
 */
object BillReader {
    data class Line(val text: String, val product: Product, val qty: Int?, val amount: Double?)
    data class Result(val lines: List<Line>, val unrecognised: List<String>)
    /** [brandCost]: what was paid (the bill's amount) or, without one, the brand's unit price for the units bought */
    data class Saving(val line: Line, val generic: RuleEngine.GenericOption?, val brandCost: Double?, val genericCost: Double?)

    private val serial = Regex("""^\s*\d{1,3}\s*[.)]?\s+""")
    /** expiry / bill dates ("08/27", "02/2028", "15/11/2026"): never a quantity */
    private val date = Regex("""\b\d{1,2}[/-]\d{2,4}(?:[/-]\d{2,4})?\b""")
    private val formWords = setOf("tab", "tabs", "tablet", "tablets", "cap", "caps", "capsule", "capsules", "syp", "syr", "inj", "t", "c")
    private val notItems = setOf("total", "subtotal", "gst", "cgst", "sgst", "igst", "tax", "amount", "discount", "net", "round",
        "roundoff", "bill", "invoice", "date", "patient", "dr", "doctor", "phone", "mobile", "cash", "paid", "balance", "mrp", "qty", "rate")
    private val decimal = Regex("""^\d+\.\d{1,2}$""")
    private val integer = Regex("""^\d{1,6}$""")
    private val nameNumber = Regex("""\d+(?:\.\d+)?""")

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}.]+""")).map { it.trim('.') }.filter { it.isNotEmpty() }

    fun read(ocr: OcrResult, ref: RefData, index: BrandIndex): Result {
        val items = ArrayList<Line>()
        val unknown = ArrayList<String>()
        for (raw in rows(ocr.lines)) {
            if (raw.isEmpty()) continue
            val ws = words(date.replace(serial.replace(raw, ""), " ")).dropWhile { it in formWords }
            if (ws.isEmpty()) continue
            val item = match(raw, ws, ref, index)
            when {
                item != null -> items += item
                looksLikeItem(ws) -> unknown += raw
            }
        }
        return Result(items, unknown)
    }

    /**
     * The bill's text as table rows. OCR reads a printed table column by column (product names in one block, the Qty and
     * Amount columns in others); pieces whose vertical centres fall within half a line of each other are one row, joined
     * left to right. Lines without a position (typed text, tests) are taken as they come.
     */
    internal fun rows(lines: List<OcrLine>): List<String> {
        val placed = lines.filter { it.box != null && it.box.size == 4 }
        if (placed.size < lines.size / 2 || placed.isEmpty()) return lines.map { it.text.trim() }
        val loose = lines.filter { it.box == null || it.box.size != 4 }.map { it.text.trim() }
        // a photo is rarely square to the page: allow a row to drift by most of a line height across the width
        // (rows sit ~2 line heights apart, so neighbouring rows still separate)
        val lineH = placed.map { (it.box!![3] - it.box[1]).toDouble() }.sorted().let { it[it.size / 2] }
        fun centre(l: OcrLine) = (l.box!![1] + l.box[3]) / 2.0
        val out = ArrayList<MutableList<OcrLine>>()
        for (l in placed.sortedBy { centre(it) }) {
            val c = centre(l)
            val row = out.lastOrNull()?.takeIf { r -> kotlin.math.abs(c - r.map { centre(it) }.average()) <= 0.8 * lineH }
            if (row != null) row += l else out += mutableListOf(l)
        }
        return out.map { r -> r.sortedBy { it.box!![0] }.joinToString("   ") { it.text.trim() } } + loose
    }

    private fun looksLikeItem(ws: List<String>): Boolean =
        ws.none { it in notItems } && ws.any { w -> w.length >= 3 && w.all { it.isLetter() } } && ws.any { decimal.matches(it) || integer.matches(it) }

    private fun match(raw: String, ws: List<String>, ref: RefData, index: BrandIndex): Line? {
        // brand: the longest run of leading words that is exactly a base key (folded), at most 3 words
        for (k in minOf(3, ws.size) downTo 1) {
            val phrase = ws.take(k).joinToString(" ")
            if (ws.take(k).any { w -> w.all { it.isDigit() } }) continue
            val key = index.search(phrase, 3, 0.99).firstOrNull { BrandIndex.fold(it.baseKey) == BrandIndex.fold(phrase) }?.baseKey ?: continue
            val rest = ws.drop(k)
            val lineNumbers = rest.filter { nameNumber.matches(it) }
            val candidates = ref.productsByBaseKeys(listOf(key))
            val tabletLine = rest.any { it in formWords }
            // numbers in the product's name that aren't part of the brand itself ("PAN 40" -> 40)
            fun numbersOf(p: Product) = nameNumber.findAll(p.name).map { it.value }.toList().filter { n -> !BrandIndex.fold(key).contains(n) }
            val fitting = candidates.filter { p -> numbersOf(p).all { it in lineNumbers } }
            if (fitting.isEmpty()) return null
            val best = fitting.maxWith(compareBy<Product> { numbersOf(it).size }
                .thenBy { if (tabletLine == (it.form.startsWith("tablet") || it.form == "capsule")) 1 else 0 }
                .thenBy { if (it.form.startsWith("tablet") || it.form == "capsule") 1 else 0 }
                .thenByDescending { it.name.length })
            // a line naming no strength can't choose between strengths
            if (numbersOf(best).isEmpty() && candidates.any { numbersOf(it).isNotEmpty() }) return null
            val used = numbersOf(best).toMutableList()
            val after = rest.filter { w -> if (w in used) { used.remove(w); false } else true }
            // amount: the last decimal on the line; quantity: the last small whole number before it (the Qty column)
            val amountAt = after.indexOfLast { decimal.matches(it) }
            val amount = after.getOrNull(amountAt)?.toDoubleOrNull()
            val qty = (if (amountAt >= 0) after.take(amountAt) else after).filter { integer.matches(it) }
                .mapNotNull { it.toIntOrNull() }.lastOrNull { it in 1..200 }
            return Line(raw, best, qty, amount)
        }
        return null
    }

    fun savings(lines: List<Line>, rules: RuleEngine): List<Saving> = lines.map { l ->
        val g = rules.evaluate(l.product, l.product.items).generic
        val units = (l.qty ?: l.product.packQty?.toInt())?.toDouble()
        val brand = l.amount ?: if (units != null) g?.brandUnitPrice?.let { it * units } ?: l.product.mrp else l.product.mrp
        val generic = if (units != null) g?.genericUnitPrice?.let { it * units } else null
        Saving(l, g, brand, generic)
    }

    /** (what the brands cost, what Jan Aushadhi generics would cost) over the lines that have a generic and both prices. */
    fun total(s: List<Saving>): Pair<Double, Double> {
        val ok = s.filter { it.generic != null && it.brandCost != null && it.genericCost != null }
        return ok.sumOf { it.brandCost!! } to ok.sumOf { it.genericCost!! }
    }
}
