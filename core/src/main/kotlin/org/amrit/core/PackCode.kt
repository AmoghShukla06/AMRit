package org.amrit.core

import java.time.YearMonth

/**
 * What a pack's machine-readable code says. Since Aug 2023 the top-300 brands (Schedule H2, Drugs Rules rule 96(6),(7))
 * must carry a QR/bar code holding the product code, proper/generic name, brand name, manufacturer, batch, dates and
 * licence number. The rule fixes the content, not the encoding, so three common encodings are handled:
 *  - GS1 element strings (DataMatrix/QR): (01) GTIN, (17) expiry YYMMDD, (11) mfg date, (10) batch, (21) serial
 *  - a URL (a manufacturer's verification page): batch/expiry query parameters if present, and the URL itself
 *  - labelled text ("Brand Name: …", "Batch No: …", "Exp: …"), one field per line or separated by | ; ,
 * Anything not recognised is kept as [raw] and never guessed at.
 */
data class PackCode(
    val raw: String,
    val gtin: String? = null,
    val brand: String? = null,
    val generic: String? = null,
    val manufacturer: String? = null,
    val batch: String? = null,
    val expiry: YearMonth? = null,
    val url: String? = null,
) {
    /** Names from the code, as text lines the matcher reads like the biggest print on the pack. */
    fun asOcrLines(height: Float): List<OcrLine> = listOfNotNull(brand, generic).map { OcrLine(it, height, 1f) }

    val hasNames: Boolean get() = brand != null || generic != null
}

object PackCodes {
    private const val GS = '\u001d'

    fun parse(raw0: String): PackCode {
        val raw = raw0.trim()
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return fromUrl(raw)
        gs1(raw)?.let { return it }
        return fromText(raw)
    }

    // ------------------------------------------------------------------------------------------------ GS1

    private val fixedLen = mapOf("01" to 14, "11" to 6, "17" to 6, "15" to 6)

    /** "(01)08901234567890(17)270831(10)B23012" or the same with FNC1/GS separators and no brackets. */
    private fun gs1(s0: String): PackCode? {
        var s = s0.removePrefix("]d2").removePrefix("]Q3").removePrefix("]C1")
        val fields = HashMap<String, String>()
        if (s.startsWith("(")) {
            Regex("""\((\d{2,4})\)([^(]*)""").findAll(s).forEach { fields[it.groupValues[1]] = it.groupValues[2].trim() }
        } else {
            if (!s.startsWith("01") || s.length < 16 || !s.substring(2, 16).all(Char::isDigit)) return null
            while (s.isNotEmpty()) {
                val ai = s.take(2)
                val n = fixedLen[ai]
                if (n != null) {
                    if (s.length < 2 + n) return null
                    fields[ai] = s.substring(2, 2 + n); s = s.substring(2 + n)
                } else if (ai == "10" || ai == "21") {
                    val end = s.indexOf(GS, 2).let { if (it < 0) s.length else it }
                    fields[ai] = s.substring(2, end); s = s.substring(minOf(end + 1, s.length))
                } else return if (fields.isEmpty()) null else build(s0, fields)
                if (s.startsWith(GS)) s = s.substring(1)
            }
        }
        return if (fields.containsKey("01")) build(s0, fields) else null
    }

    private fun build(raw: String, f: Map<String, String>) = PackCode(
        raw = raw, gtin = f["01"]?.takeIf { it.length == 14 && it.all(Char::isDigit) },
        batch = f["10"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 20 },
        expiry = f["17"]?.let(::yymmdd) ?: f["15"]?.let(::yymmdd),
    )

    private fun yymmdd(s: String): YearMonth? {
        if (s.length != 6 || !s.all(Char::isDigit)) return null
        val y = 2000 + s.take(2).toInt(); val m = s.substring(2, 4).toInt()
        return if (m in 1..12) YearMonth.of(y, m) else null
    }

    // ------------------------------------------------------------------------------------------------ URL

    private fun fromUrl(url: String): PackCode {
        val q = url.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=').lowercase(); val v = java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            if (v.isBlank()) null else k to v
        }.toMap()
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { q[it] }
        return PackCode(raw = url, url = url,
            batch = pick("batch", "batchno", "batch_no", "bno", "lot")?.take(20),
            expiry = pick("exp", "expiry", "expdate")?.let { Expiry.parse("EXP $it") })
    }

    // ------------------------------------------------------------------------------------------------ labelled text

    private val label = Regex(
        """(?i)^\s*(brand(?:\s*name)?|product(?:\s*name)?|trade\s*name|generic(?:\s*name)?|proper\s*name|composition|""" +
            """manufactured\s*by|mfd\.?\s*by|mfg\.?\s*by|manufacturer|marketed\s*by|batch(?:\s*no\.?|\s*number)?|b\.?\s*no\.?|lot(?:\s*no\.?)?|""" +
            """exp(?:iry)?(?:\s*date)?|use\s*before)\s*[:=\-]\s*(.+)$""")

    private fun fromText(raw: String): PackCode {
        var brand: String? = null; var generic: String? = null; var mfr: String? = null; var batch: String? = null; var exp: YearMonth? = null
        for (part in raw.split('\n', '|', ';')) {
            val m = label.find(part) ?: continue
            val k = m.groupValues[1].lowercase().replace(Regex("""[\s.]"""), ""); val v = m.groupValues[2].trim().take(120)
            when {
                k.startsWith("brand") || k.startsWith("product") || k.startsWith("trade") -> brand = brand ?: v
                k.startsWith("generic") || k.startsWith("proper") || k.startsWith("composition") -> generic = generic ?: v
                k.startsWith("manufactured") || k.startsWith("mfd") || k.startsWith("mfgby") || k.startsWith("manufacturer") ||
                    k.startsWith("marketed") -> mfr = mfr ?: v
                k.startsWith("batch") || k.startsWith("bno") || k.startsWith("lot") -> batch = batch ?: Batch.clean(v)
                k.startsWith("exp") || k.startsWith("use") -> exp = exp ?: Expiry.parse("EXP $v")
            }
        }
        return PackCode(raw = raw, brand = brand, generic = generic, manufacturer = mfr, batch = batch, expiry = exp)
    }
}

/** The batch number printed on a strip ("B.No. ABC2301", "Batch No: DLP-2201"). Null when it isn't clearly there. */
object Batch {
    private val printed = Regex("""(?i)\b(?:b\s*\.?\s*no|batch\s*(?:no|number)?|lot\s*(?:no)?)\s*\.?\s*[:\-.]?\s*([A-Z0-9][A-Z0-9/\-]{2,19})""")

    /** Upper-case, no spaces; must contain a digit (a word like "TABLETS" after "Batch" is not a batch number). */
    fun clean(v: String): String? = v.trim().split(Regex("""\s+""")).first().uppercase().trimEnd('.', ',', ';')
        .takeIf { it.length in 3..20 && it.any(Char::isDigit) && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '/' } }

    fun parse(text: String): String? = printed.findAll(text).firstNotNullOfOrNull { clean(it.groupValues[1]) }

    /** Most frequent batch read across the (pooled) OCR lines. */
    fun fromOcr(ocr: OcrResult): String? =
        ocr.lines.mapNotNull { parse(it.text) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
