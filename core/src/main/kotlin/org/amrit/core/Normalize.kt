package org.amrit.core

/**
 * Mirror of data-build/normalize.py. Keys built here must equal the keys stored in the reference DB;
 * NormalizeTest replays data-build/out/key_vectors.tsv to guarantee it.
 */
object Normalize {
    private fun set(s: String) = s.trim().split(Regex("\\s+")).toHashSet()

    private val DESCRIPTORS = set(
        """anhydrous monohydrate dihydrate trihydrate hemihydrate sesquihydrate pentahydrate heptahydrate hydrate
        dried elemental light heavy micronized micronised ip bp usp ep nf"""
    )
    private val SALTS = set(
        """hydrochloride hcl hydrobromide hbr sodium potassium maleate sulphate sulfate phosphate acetate citrate
        tartrate bitartrate mesylate mesilate besylate besilate succinate fumarate propionate dipropionate valerate butyrate
        furoate nitrate bromide gluconate lactate malate pamoate embonate stearate palmitate estolate ethylsuccinate axetil
        proxetil pivoxil cilexetil medoxomil disodium dihydrochloride bisulphate bisulfate hyclate oxalate napsylate tosylate
        xinafoate decanoate magnesium calcium benzoate tromethamine trometamol"""
    )
    private val IONS = set(
        """chloride carbonate bicarbonate hydroxide oxide citrate sulphate sulfate phosphate gluconate lactate acetate
        iodide fluoride picosulfate picosulphate trisilicate silicate stearate zinc ferrous ferric sodium potassium calcium magnesium
        aluminium aluminum ammonium lithium silver copper selenium chromium manganese bismuth"""
    )
    private val ESTER_PREFIXES = setOf("methyl", "ethyl", "benzyl", "propyl", "butyl", "isopropyl")

    val FORM_WORDS = set(
        """tablet tablets tab tabs capsule capsules cap caps syrup suspension injection inj drop drops cream gel
        ointment lotion solution spray inhaler respules respule rotacap rotacaps powder sachet sachets granules soap shampoo kit
        lozenge lozenges infusion oral dry eye ear nasal liquid emulsion paste serum mouthwash gargle jelly elixir linctus softgel
        softgels transcaps redimix vial ampoule bottle strip sugar free flavour flavor"""
    )
    private val UNIT_TOKENS = setOf("mg", "mcg", "gm", "g", "ml", "iu", "w", "v")

    private val icAcid = Regex("([a-z]{3,}ic)(acid)\\b")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val doubled = Regex("(.)\\1+")

    private fun words(s: String): List<String> {
        var t = s.lowercase().replace("µ", "u")
        t = icAcid.replace(t, "$1 $2")
        t = nonAlnum.replace(t, " ")
        return t.split(' ').filter { it.isNotEmpty() }
    }

    private fun phonetic(w0: String): String {
        if (w0.all { it.isDigit() }) return w0
        var w = w0.replace("ph", "f").replace("ae", "e").replace("oe", "e").replace("th", "t").replace("y", "i")
        w = doubled.replace(w, "$1")
        if (w.length > 4 && w.endsWith("e")) w = w.dropLast(1)
        return w
    }

    /** Spelling-insensitive ingredient key. "Amoxycillin Trihydrate IP" -> "amoxicilin". */
    fun ingredientKey(name: String): String {
        var ws = words(name)
        ws = ws.filter { it !in DESCRIPTORS }.ifEmpty { ws }
        val stem = ws.filter { it !in SALTS }
        if (stem.isNotEmpty() && !stem.all { it in IONS } && !(stem.size == 1 && stem[0] in ESTER_PREFIXES)) {
            ws = stem
        } else {
            ws = ws.filter { it !in setOf("ip", "bp", "usp", "ep", "nf") }
        }
        val out = ArrayList<String>(ws.size)
        var i = 0
        while (i < ws.size) {
            val w = ws[i]
            if (i + 1 < ws.size && ws[i + 1] == "acid" && w.endsWith("ic") && w.length > 4) {
                out.add(w.dropLast(2) + "ate"); i += 2; continue
            }
            out.add(w); i++
        }
        return out.joinToString(" ") { phonetic(it) }
    }

    private val letterDigit = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")
    private val nonNameChars = Regex("[^a-z0-9.]+")
    private val strayDots = Regex("(?<![0-9])\\.|\\.(?![0-9])")

    fun nameTokens(s: String): List<String> {
        var t = s.lowercase().replace("&", " ")
        t = letterDigit.replace(t, " ")
        t = nonNameChars.replace(t, " ")
        t = strayDots.replace(t, " ")
        return t.split(' ').filter { it.isNotEmpty() }
    }

    /** ("augmentin 625 duo", "augmentin") for "Augmentin 625 Duo Tablet". */
    fun brandKeys(productName: String): Pair<String, String> {
        var toks = nameTokens(productName)
        var cut = toks.size
        for (i in toks.indices) if (i > 0 && toks[i] in FORM_WORDS) { cut = i; break }
        val head = toks.subList(0, cut)
        // drop a unit only right after a number ("500 mg"); "Laformin-G" keeps its "g"
        toks = head.filterIndexed { i, t -> !(t in UNIT_TOKENS && i > 0 && head[i - 1][0].isDigit()) }
        var base = toks
        for (i in toks.indices) if (i > 0 && toks[i].any { it.isDigit() }) { base = toks.subList(0, i); break }
        return toks.joinToString(" ") to base.joinToString(" ")
    }

    private val UNIT = mapOf("mg" to "mg", "mcg" to "mcg", "ug" to "mcg", "g" to "g", "gm" to "g", "iu" to "IU", "ml" to "ml", "%" to "%")
    private val strengthRe = Regex("(\\d+(?:\\.\\d+)?)\\s*(mg|mcg|ug|gm|g|iu|ml|%)(?![a-z])", RegexOption.IGNORE_CASE)
    private val perRe = Regex("^\\s*(?:/|per)\\s*(\\d+(?:\\.\\d+)?)?\\s*(ml|g|gm)\\b")

    /** "500mg" -> 500 mg; "15mg/5ml" -> 15 mg/5ml; "1g" -> 1000 mg. */
    fun parseStrength(s0: String?): Strength? {
        val s = (s0 ?: "").trim().lowercase().replace("µg", "mcg")
        val m = strengthRe.find(s) ?: return null
        var v = m.groupValues[1].toDouble()
        var unit = UNIT[m.groupValues[2].lowercase()] ?: m.groupValues[2].lowercase()
        if (unit == "g") { v *= 1000.0; unit = "mg" }
        val rest = s.substring(m.range.last + 1)
        perRe.find(rest)?.let { pm ->
            val n = pm.groupValues[1].ifEmpty { "1" }
            unit = "$unit/$n${UNIT[pm.groupValues[2]] ?: pm.groupValues[2]}"
        }
        return Strength(Math.round(v * 10000.0) / 10000.0, unit)
    }

    private val camel = Regex("(?<=[a-z])(?=[A-Z])")

    /** "GlucobayM50" -> "Glucobay M50", "KetoflamP" -> "Ketoflam P" (OCR drops spaces on tightly kerned print). */
    fun splitCamel(s: String): String = camel.replace(s, " ")

    /** Fold characters OCR commonly confuses, applied to both query and index so 'AUGMENT1N' ~ 'augmentin'. */
    fun ocrFold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(
            when (c) {
                '0' -> 'o'; '1', 'l', '|', '!' -> 'i'; '5' -> 's'; '8' -> 'b'; '6' -> 'g'; '2' -> 'z'; 'v' -> 'u'
                else -> c
            }
        )
        return sb.toString().replace("rn", "m").replace("vv", "w")
    }
}

data class Strength(val value: Double, val unit: String) {
    override fun toString(): String {
        val v = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
        return "$v $unit"
    }
}
