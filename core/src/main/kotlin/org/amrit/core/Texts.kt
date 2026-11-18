package org.amrit.core

/**
 * Result text from fixed, reviewed templates (resources/i18n/<lang>.json) and data. No generated prose: use phrases
 * come from the medicine dataset, alerts from the rule engine. Every card is title + explanation + source, so the
 * screen can show where each statement comes from.
 */
object Texts {
    enum class Kind { DANGER, WARNING, INFO, GOOD }

    data class Line(val kind: Kind, val title: String, val body: String? = null, val source: String? = null) {
        val text: String get() = listOfNotNull(title, body).joinToString(". ")
    }

    fun t(lang: Lang, key: String, vararg args: Pair<String, Any?>) = I18n.t(lang, key, *args)

    /** WHO's official group names are never translated. */
    @Suppress("UNUSED_PARAMETER")
    fun awareName(a: Aware, lang: Lang) = a.name
    fun notFound(lang: Lang) = t(lang, "notFound")
    fun disclaimer(lang: Lang) = t(lang, "disclaimer")

    private fun money(v: Double) = if (v >= 10) "%.0f".format(v) else "%.2f".format(v)

    /** "What it's for" as one line: "For pain relief and fever" / "यह दवा ... में दी जाती है।" (null if unknown). */
    fun headline(r: RuleEngine.StripReport, ref: RefData, lang: Lang): String? {
        val whole = r.uses.firstOrNull { it.ingredient == null } ?: return null
        val phrases = whole.phraseIds.map { ref.usePhrase(it, lang) }.filter { it.isNotBlank() }
        if (phrases.isEmpty()) return null
        val joined = if (lang == Lang.EN) phrases.mapIndexed { i, p -> if (i == 0) lowerFirst(p) else lowerFirst(p) }
            .let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + t(lang, "uses.and") + it.last() }
        else phrases.let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + t(lang, "uses.and") + it.last() }
        return t(lang, "uses.headline", "uses" to joined)
    }

    /** Per-ingredient uses when the exact combination has none (e.g. a multivitamin read from the strip). */
    fun ingredientUses(r: RuleEngine.StripReport, ref: RefData, lang: Lang): List<String> =
        r.uses.filter { it.ingredient != null }.map { u ->
            t(lang, "uses.ingredient", "ingredient" to u.ingredient!!.name,
                "uses" to u.phraseIds.joinToString(", ") { ref.usePhrase(it, lang) })
        }

    /** Kept for callers that want everything as sentences. */
    fun uses(r: RuleEngine.StripReport, ref: RefData, lang: Lang): List<String> =
        listOfNotNull(headline(r, ref, lang)) + ingredientUses(r, ref, lang)

    /** "A, B and C" in the given language. */
    fun list(items: List<String>, lang: Lang): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + t(lang, "and") + items.last()

    private fun lowerFirst(s: String) = if (s.length > 1 && s[1].isLowerCase()) s[0].lowercase() + s.substring(1) else s

    fun contains(r: RuleEngine.StripReport, lang: Lang): String =
        t(lang, "contains", "ingredients" to r.items.joinToString(", ") {
            it.ingredient.name + (it.strength?.let { s -> " $s" } ?: "")
        })

    /** Things to know about this strip on its own: bans, antibiotic group, data caveats. */
    fun issues(r: RuleEngine.StripReport, lang: Lang): List<Line> {
        val out = ArrayList<Line>()
        for (b in r.bans) out += when (b.match) {
            RuleEngine.BanMatch.EXACT -> Line(Kind.DANGER, t(lang, "ban.exact.title"), t(lang, "ban.exact.body"),
                t(lang, "ban.exact.source", "notification" to b.fdc.notification, "date" to b.fdc.date))
            RuleEngine.BanMatch.OTHER_FORM_OR_STRENGTH -> Line(Kind.WARNING, t(lang, "ban.related.title"),
                t(lang, "ban.related.body", "title" to b.fdc.title), t(lang, "ban.exact.source", "notification" to b.fdc.notification, "date" to b.fdc.date))
        }
        r.antibiotic?.let { a ->
            val names = a.antibiotics.joinToString(", ") { it.name }
            if (a.topical) out += Line(Kind.INFO, t(lang, "abx.local.title"), t(lang, "abx.local.body", "names" to names), t(lang, "abx.source"))
            else {
                val g = a.aware
                val title = if (g != null) t(lang, "abx.title", "group" to g.name) else t(lang, "abx.titleNoGroup")
                val body = when (g) { Aware.Access -> t(lang, "abx.access.body"); Aware.Watch -> t(lang, "abx.watch.body")
                    Aware.Reserve -> t(lang, "abx.reserve.body"); null -> null }
                out += Line(if (g == Aware.Access) Kind.WARNING else Kind.DANGER, title, body, t(lang, "abx.source"))
                out += Line(Kind.INFO, t(lang, "abx.course.title"), t(lang, "abx.course.body"))
            }
            a.whoNotRecommended?.let { out += Line(Kind.WARNING, t(lang, "abx.whoNotRec.title"), t(lang, "abx.whoNotRec.body", "title" to it), "WHO AWaRe 2025") }
        }
        if (r.compositionMayBeIncomplete) out += Line(Kind.INFO, t(lang, "incomplete.title"), t(lang, "incomplete.body"))
        return out
    }

    /** Checks that passed, each with its source: what a chatbot answer can't show. */
    fun checks(r: RuleEngine.StripReport, lang: Lang, checkedAgainstOthers: Boolean): List<Line> {
        val out = ArrayList<Line>()
        if (r.bans.isEmpty()) out += Line(Kind.GOOD, t(lang, "check.notBanned"), source = t(lang, "check.notBanned.source"))
        if (r.antibiotic == null) out += Line(Kind.GOOD, t(lang, "check.notAbx"), source = t(lang, "check.notAbx.source"))
        if (checkedAgainstOthers) out += Line(Kind.GOOD, t(lang, "check.noClash"), source = t(lang, "check.noClash.source"))
        return out
    }

    /** A cabinet alert as seen from one strip's result ("Also in your Dolo 650"). */
    fun cabinetForStrip(a: RuleEngine.CabinetAlert, self: String, lang: Lang): Line = when (a) {
        is RuleEngine.CabinetAlert.Duplicate -> {
            val other = a.products.map { it.first }.firstOrNull { it != self } ?: a.products.first().first
            Line(Kind.DANGER, t(lang, "dup.title", "product" to other, "ingredient" to a.ingredient.name),
                t(lang, "dup.body", "ingredient" to a.ingredient.name))
        }
        is RuleEngine.CabinetAlert.Interacts -> {
            val other = if (a.productA == self) a.productB else a.productA
            Line(kindOf(a.interaction.severity), t(lang, "ix.title", "severity" to sev(a.interaction.severity, lang), "product" to other),
                t(lang, "ix.body", "a" to a.interaction.a.name, "b" to a.interaction.b.name), t(lang, "ix.source"))
        }
    }

    /** A cabinet alert on the home screen. */
    fun cabinet(a: RuleEngine.CabinetAlert, lang: Lang): Line = when (a) {
        is RuleEngine.CabinetAlert.Duplicate -> Line(Kind.DANGER,
            t(lang, "dup.cabinetTitle", "ingredient" to a.ingredient.name),
            t(lang, "dup.cabinetBody", "ingredient" to a.ingredient.name,
                "products" to list(a.products.map { it.first }.distinct(), lang)))
        is RuleEngine.CabinetAlert.Interacts -> Line(kindOf(a.interaction.severity),
            t(lang, "ix.cabinetTitle", "severity" to sev(a.interaction.severity, lang)),
            "${a.productA} + ${a.productB}", t(lang, "ix.source"))
    }

    private fun kindOf(s: Severity) = when (s) { Severity.Major -> Kind.DANGER; Severity.Moderate -> Kind.WARNING; Severity.Minor -> Kind.INFO }
    private fun sev(s: Severity, lang: Lang) = t(lang, "sev." + s.name.lowercase())

    data class Generic(val title: String, val name: String, val less: String?, val perUnit: String?, val mrp: String)

    fun generic(g: RuleEngine.GenericOption, form: String, lang: Lang) = Generic(
        title = t(lang, "generic.title"), name = g.item.name,
        less = g.savingPercent?.let { t(lang, "generic.less", "pct" to it) },
        perUnit = if (g.genericUnitPrice != null && g.brandUnitPrice != null)
            t(lang, if (form.startsWith("tablet") || form == "capsule") "generic.perTablet" else "generic.perUnit",
                "g" to "%.2f".format(g.genericUnitPrice), "b" to "%.2f".format(g.brandUnitPrice)) else null,
        mrp = t(lang, "generic.mrp", "unit" to g.item.unitSize, "mrp" to money(g.item.mrp)),
    )

    /**
     * What "Listen" says: short sentences in reading order. Units are spoken in full; the app layer additionally
     * turns ALL-CAPS brand names into normal case so a TTS voice reads them as words instead of spelling them.
     */
    fun speech(r: RuleEngine.StripReport, ref: RefData, lang: Lang, extra: List<Line> = emptyList()): String {
        val parts = ArrayList<String>()
        parts += r.product.name
        headline(r, ref, lang)?.let { parts += it }
        parts += contains(r, lang)
        (extra + issues(r, lang)).forEach { parts += it.title; it.body?.let { b -> parts += b } }
        r.generic?.let { g -> generic(g, r.product.form, lang).let { parts += it.title + ": " + it.name; it.less?.let { l -> parts += l } } }
        parts += disclaimer(lang)
        return speakUnits(parts.joinToString(". ").replace("..", "."), lang)
    }

    private fun speakUnits(s: String, lang: Lang): String {
        val mg = if (lang == Lang.HI) "मिलीग्राम" else "milligrams"
        val mcg = if (lang == Lang.HI) "माइक्रोग्राम" else "micrograms"
        return s.replace(Regex("""(\d)\s*mcg\b"""), "$1 $mcg").replace(Regex("""(\d)\s*mg\b"""), "$1 $mg")
    }

    /** Plain-text card to send to a pharmacist (WhatsApp etc.). */
    fun share(r: RuleEngine.StripReport, ref: RefData, lang: Lang, extra: List<Line>, expiry: String?): String {
        val b = StringBuilder()
        b.appendLine(t(lang, "res.shareHeader")).appendLine()
        b.appendLine(r.product.name)
        b.appendLine(contains(r, lang))
        headline(r, ref, lang)?.let { b.appendLine(it) }
        expiry?.let { b.appendLine("EXP $it") }
        (extra + issues(r, lang)).filter { it.kind != Kind.INFO }.forEach { b.appendLine("• ${it.title}" + (it.source?.let { s -> " ($s)" } ?: "")) }
        checks(r, lang, false).forEach { b.appendLine("✓ ${it.title} (${it.source})") }
        r.generic?.let { g -> generic(g, r.product.form, lang).let { b.appendLine("${it.title}: ${it.name} · ${it.mrp}") } }
        b.appendLine().append(disclaimer(lang))
        return b.toString()
    }

    /** One finding of a medicine-box audit, in the same words as a single scan's alerts where they exist. */
    fun boxFinding(f: BoxAudit.Finding, report: BoxAudit.Report, lang: Lang): Line {
        val item = report.items[f.items.first()]
        val name = item.report.product.name
        return when (f.reason) {
            BoxAudit.Reason.EXPIRED -> Line(Kind.DANGER, t(lang, "box.expired.title", "product" to name,
                "date" to (item.expiry?.let { Expiry.format(it) } ?: "")), t(lang, "box.expired.body"))
            BoxAudit.Reason.RECALLED_BATCH -> {
                val al = item.alerts.first()
                Line(Kind.DANGER, t(lang, "box.recalled.title", "product" to name,
                    "what" to t(lang, if (al.kind == BatchAlert.Kind.SPURIOUS) "tile.spurious" else "tile.nsq")),
                    t(lang, "res.alert.body", "batch" to al.batch, "result" to (al.result?.trim()?.takeIf { it.isNotEmpty() } ?: "-"),
                        "by" to (al.reportedBy ?: "CDSCO"), "month" to al.month), t(lang, "res.alert.source", "month" to al.month))
            }
            BoxAudit.Reason.BANNED -> Line(Kind.DANGER, t(lang, "box.banned.title", "product" to name), t(lang, "ban.exact.body"))
            BoxAudit.Reason.LEFTOVER_ANTIBIOTIC -> Line(Kind.WARNING, t(lang, "box.leftover.title", "product" to name), t(lang, "box.leftover.body"))
            BoxAudit.Reason.DUPLICATE, BoxAudit.Reason.INTERACTION -> cabinet(f.alert!!, lang)
        }
    }
    fun passportInsight(i: Passport.Insight, lang: Lang): String = t(lang, i.key, *i.args.toTypedArray())
    /**
     * "What ICMR found in Indian hospitals": one line per infection, largest sample first, then what it does and doesn't
     * mean. A warning when fewer than half the bacteria in any row still responded.
     */
    fun resistance(rows: List<RefData.Resistance>, lang: Lang): Line? {
        if (rows.isEmpty()) return null
        val sorted = rows.sortedByDescending { it.tested }.take(3)
        fun pct(x: Double) = (if (x % 1.0 == 0.0) "%.0f" else "%.1f").format(java.util.Locale.ROOT, x) + "%"
        val body = sorted.joinToString("\n") { r ->
            "• " + t(lang, "resist.row.${r.infection}", "pct" to pct(r.pct), "drug" to r.antibiotic, "s" to "%,d".format(r.susceptible),
                "n" to "%,d".format(r.tested))
        } + "\n\n" + t(lang, "resist.note")
        val pages = sorted.map { it.reportPage }.distinct().sorted().joinToString(", ")
        return Line(if (sorted.any { it.pct < 50 }) Kind.WARNING else Kind.INFO, t(lang, "resist.title"), body,
            t(lang, "resist.source", "pages" to pages))
    }
}
