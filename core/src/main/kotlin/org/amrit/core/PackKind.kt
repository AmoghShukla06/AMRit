package org.amrit.core

/**
 * AYUSH packs (Ayurveda, Unani, Siddha, Homoeopathy) are licensed under a different schedule and must say so on the
 * label ("Ayurvedic Proprietary Medicine", "Homoeopathic Medicine"). AMRit's checks are for allopathic medicines, so
 * such a pack gets an honest "not checked" instead of a vague "not found" (and no paid fallback call).
 * "Herbal" alone is not used: plenty of allopathic and cosmetic packs say it.
 */
object PackKind {
    private val ayush = Regex("""(?i)\b(ayurvedic|ayurveda|homoeopathic|homeopathic|homoeopathy|homeopathy|unani|siddha|ayush)\b""")

    fun isAyush(ocr: OcrResult): Boolean = ocr.lines.any { ayush.containsMatchIn(it.text) }
}
