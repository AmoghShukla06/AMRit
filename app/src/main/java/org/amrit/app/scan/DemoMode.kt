package org.amrit.app.scan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.amrit.app.AppGraph
import org.amrit.core.BrandIndex
import org.amrit.core.Candidate
import org.amrit.core.Features
import org.amrit.core.Levenshtein
import org.amrit.core.Normalize
import org.amrit.core.OcrResult

/**
 * Venue-proof demo: a fixed set of demo strips (assets/demo/demo_strips.json) resolves from saved results with no
 * network at all. Results still go through the same rule engine, so the alerts shown are the real ones.
 * If the camera itself misbehaves, the strips can be picked from a list.
 */
class DemoMode(private val g: AppGraph) {
    @Serializable
    data class DemoStrip(val label: String, val product_name: String, val keywords: List<String>, val note: String = "",
                         /** printed on the demo label, so batch and expiry checks run as on a real strip */
                         val batch: String? = null, val expiry: String? = null)

    /** What the camera would have read off this demo label. */
    fun linesFor(s: DemoStrip): List<org.amrit.core.OcrLine> = listOfNotNull(
        org.amrit.core.OcrLine(s.label, 80f), s.batch?.let { org.amrit.core.OcrLine("B.No. $it", 14f) },
        s.expiry?.let { org.amrit.core.OcrLine("EXP. $it", 14f) })

    fun stripFor(ocr: OcrResult): DemoStrip? {
        val words = ocr.lines.flatMap { Normalize.nameTokens(it.text) }.map { BrandIndex.fold(it) }.filter { it.length >= 3 }
        return strips.firstOrNull { s -> s.keywords.any { kw -> val k = BrandIndex.fold(kw); words.any { Levenshtein.similarity(it, k) >= 0.8 } } }
    }

    val strips: List<DemoStrip> by lazy {
        runCatching {
            g.app.assets.open("demo/demo_strips.json").bufferedReader().use {
                Json { ignoreUnknownKeys = true }.decodeFromString<List<DemoStrip>>(it.readText())
            }
        }.getOrDefault(emptyList())
    }

    fun candidateFor(strip: DemoStrip): Candidate? =
        g.ref.productsByName(strip.product_name).firstOrNull()?.let { Candidate(it, Features(brandSim = 1.0), 1.0) }

    fun match(ocr: OcrResult): Candidate? {
        val words = ocr.lines.flatMap { Normalize.nameTokens(it.text) }.map { BrandIndex.fold(it) }.filter { it.length >= 3 }
        for (s in strips) {
            val hit = s.keywords.any { kw -> val k = BrandIndex.fold(kw); words.any { Levenshtein.similarity(it, k) >= 0.8 } }
            if (hit) return candidateFor(s)
        }
        return null
    }
}
