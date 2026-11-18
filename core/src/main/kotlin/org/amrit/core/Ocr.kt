package org.amrit.core

import kotlinx.serialization.Serializable

/** What the phone's OCR saw. Kept serialisable so the eval harness can replay exactly what the phone read. */
@Serializable
data class OcrLine(
    val text: String,
    /** Line height in pixels; brand names are the biggest text on a strip. */
    val height: Float,
    /** ML Kit confidence 0..1 (mean of elements); 1.0 for text that came from the vision-LLM fallback. */
    val confidence: Float = 1f,
    /** where the line sits in its frame (left, top, right, bottom px), for "What we read"; null when unknown */
    val box: List<Int>? = null,
)

@Serializable
data class OcrResult(val lines: List<OcrLine>, val width: Int = 0, val height: Int = 0) {
    val maxLineHeight: Float get() = lines.maxOfOrNull { it.height } ?: 1f
    val fullText: String get() = lines.joinToString("\n") { it.text }
}

@Serializable
data class LlmIngredient(val name: String, val strength: String? = null)

private val equivTo = Regex("""(?i)\b(?:eq\.?|equiv\.?|equivalent)\s*to\b\s*([^)]*)""")
private val parens = Regex("""\([^)]*\)""")

/** One list entry = one ingredient. "Zinc Sulphate Monohydrate (equiv. to 15 mg elemental Zinc)" -> "elemental Zinc";
 *  "Vitamin B12 I.P. (as Stablets 1:100)" -> "Vitamin B12 I.P.". */
internal fun activeName(name: String): String {
    equivTo.find(name)?.let { m ->
        val inner = m.groupValues[1].replace(Regex("""(?i)\d+(?:\.\d+)?\s*(mg|mcg|g|iu|ml)\b"""), " ").trim()
        if (inner.any { it.isLetter() }) return inner
    }
    return parens.replace(name, " ").replace(Regex("""\s+"""), " ").trim()
}

/** The strength that goes with [activeName]: the one inside "equiv. to 15 mg ..." if present, else the listed one. */
internal fun activeStrength(i: LlmIngredient): String? {
    equivTo.find(i.name)?.let { m -> Normalize.parseStrength(m.groupValues[1])?.let { return it.toString().replace(" ", "") } }
    return i.strength
}

/** Strict JSON the vision model must return (backend validates it too). It only transcribes; it never decides. */
@Serializable
data class LlmReading(
    val brand: String? = null,
    val strength: String? = null,
    val manufacturer: String? = null,
    val dosage_form: String? = null,
    val ingredients: List<LlmIngredient> = emptyList(),
    val composition_text: String? = null,
    /** expiry as printed ("08/2027"), if the model could read it */
    val expiry: String? = null,
    val legible: Boolean = true,
) {
    /**
     * Feed the transcription through the same matcher as OCR text, as if it were very clean OCR: brand as the biggest
     * line, then a composition statement ("Each tablet contains: X 500 mg"), so the same rules decide.
     */
    /** List entries that are real ingredients: "Equivalent to Metoprolol Tartrate 25 mg" restates the entry before it. */
    val activeIngredients: List<LlmIngredient>
        get() = ingredients.filterNot { Regex("""(?i)^\s*(eq\.?|equiv\.?|equivalent)\s*to\b""").containsMatchIn(it.name) }

    fun asOcr(original: OcrResult?): OcrResult {
        val lines = mutableListOf<OcrLine>()
        if (!brand.isNullOrBlank()) lines += OcrLine(listOfNotNull(brand, strength).joinToString(" "), 100f, 1f)
        if (!dosage_form.isNullOrBlank()) lines += OcrLine(dosage_form, 40f, 1f)
        if (!manufacturer.isNullOrBlank()) lines += OcrLine(manufacturer, 30f, 1f)
        if (!expiry.isNullOrBlank()) lines += OcrLine("EXP $expiry", 12f, 1f)
        if (ingredients.isNotEmpty()) {
            lines += OcrLine("Each ${dosage_form ?: "unit"} contains:", 25f, 1f)
            activeIngredients.forEach { lines += OcrLine(listOfNotNull(activeName(it.name), activeStrength(it)).joinToString(" "), 25f, 1f) }
        } else {
            composition_text?.split('\n', ';')?.filter { it.isNotBlank() }?.forEach { lines += OcrLine(it, 25f, 1f) }
        }
        // the phone's own OCR stays as weak extra evidence (tiny height => never used for brand queries)
        original?.lines?.forEach { lines += it.copy(height = 1f, confidence = it.confidence * 0.5f) }
        return OcrResult(lines, 0, 0)
    }
}
