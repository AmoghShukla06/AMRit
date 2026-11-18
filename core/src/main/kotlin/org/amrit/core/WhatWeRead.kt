package org.amrit.core

/**
 * "What we read" (3.1): which lines of the frozen frame gave the answer, so the user can see the app read the right
 * strip and tap anything misread. Only lines that support the shown answer are marked: an ingredient printed on the
 * strip but not in the answer stays unmarked rather than looking as if it was used.
 */
object WhatWeRead {
    enum class Role { BRAND, INGREDIENT, BATCH, EXPIRY }

    private val split = Regex("""[^\p{L}\p{N}]+""")
    private fun words(s: String) = s.lowercase().split(split).filter { it.isNotEmpty() }

    /** The brand word: the first word of the product name with 3+ letters ("Dolo 650 Tablet" -> "dolo"). */
    private fun brandWord(p: Product): String? = words(p.name).firstOrNull { w -> w.length >= 3 && w.all { it.isLetter() } }

    fun roles(lines: List<OcrLine>, product: Product, reader: CompositionReader): Map<Int, Role> {
        val out = LinkedHashMap<Int, Role>()
        val brand = brandWord(product)
        lines.forEachIndexed { i, l ->
            val role = when {
                brand != null && brand in words(l.text) -> Role.BRAND
                Expiry.parse(l.text) != null -> Role.EXPIRY
                Batch.parse(l.text) != null -> Role.BATCH
                else -> null
            }
            if (role != null) out[i] = role
        }
        // ingredients as the composition reader finds them in this frame, limited to the answer's own ingredients
        val wanted = product.ingredientIds
        for (f in reader.read(OcrResult(lines)).values) if (f.ingredient.id in wanted && f.line in lines.indices && f.line !in out) out[f.line] = Role.INGREDIENT
        return out.toSortedMap()
    }
}
