package org.amrit.core

import java.time.YearMonth

/**
 * Expiry date printed on the strip ("EXP. 08/2027", "Exp Date: AUG.2027", "E 11/26"). Read from OCR text or the vision
 * reading; null when it isn't clearly there (never guessed). Manufacturing dates are ignored.
 */
object Expiry {
    private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val keyword = Regex("""(?i)\b(?:exp(?:iry)?\.?\s*(?:date)?|e\s*\.?)\s*[:.\-]?\s*""")
    private val numeric = Regex("""^(0?[1-9]|1[0-2])\s*[/.\-]\s*(\d{4}|\d{2})\b""")
    private val named = Regex("""(?i)^(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s*[/.\-]?\s*(\d{4}|\d{2})\b""")

    fun parse(text: String): YearMonth? {
        for (m in keyword.findAll(text)) {
            val rest = text.substring(m.range.last + 1).trimStart()
            val (mm, yy) = numeric.find(rest)?.let { it.groupValues[1].toInt() to it.groupValues[2] }
                ?: named.find(rest)?.let { months.indexOf(it.groupValues[1].uppercase()) + 1 to it.groupValues[2] }
                ?: continue
            val year = if (yy.length == 2) 2000 + yy.toInt() else yy.toInt()
            if (year in 2015..2045 && mm in 1..12) return YearMonth.of(year, mm)
        }
        return null
    }

    fun fromOcr(ocr: OcrResult): YearMonth? =
        ocr.lines.asSequence().mapNotNull { parse(it.text) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun format(ym: YearMonth) = "%02d/%d".format(ym.monthValue, ym.year)
}
