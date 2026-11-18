package org.amrit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class CoreTest {
    companion object {
        private val repo = File(System.getProperty("amrit.repo") ?: "..")
        lateinit var ref: RefData
        lateinit var resolver: Resolver
        lateinit var rules: RuleEngine

        @BeforeClass @JvmStatic
        fun setup() {
            ref = RefData(JdbcSql.open(File(repo, "app/src/main/assets/amrit_ref.db").absolutePath))
            val t = System.currentTimeMillis()
            resolver = Resolver(ref, BrandIndex(ref.allBaseKeys()))
            println("index built in ${System.currentTimeMillis() - t} ms, ${resolver.index.size} keys")
            rules = RuleEngine(ref)
        }

        fun ocr(vararg lines: Pair<String, Float>) = OcrResult(lines.map { OcrLine(it.first, it.second, 0.9f) })
    }

    @Test fun normaliserMatchesPythonBuild() {
        val f = File(repo, "data-build/out/key_vectors.tsv")
        var n = 0
        f.forEachLine { line ->
            val (kind, input, expected) = line.split('\t')
            val got = if (kind == "I") Normalize.ingredientKey(input) else Normalize.brandKeys(input).let { "${it.first}|${it.second}" }
            assertEquals("[$kind] $input", expected, got)
            n++
        }
        assertTrue(n > 1000)
    }

    @Test fun spellingVariantsShareOneIngredient() {
        val a = ref.ingredientByText("Amoxycillin Trihydrate IP")
        assertNotNull(a)
        assertEquals(a, ref.ingredientByText("amoxicillin"))
        assertEquals(ref.ingredientByText("Paracetamol"), ref.ingredientByText("Acetaminophen"))
        assertEquals(Strength(125.0, "mg/5ml"), Normalize.parseStrength("125 mg per 5 ml"))
    }

    @Test fun clearAugmentinStripResolvesOnDevice() {
        val r = resolver.resolve(ocr(
            "AUGMENTIN 625 DUO" to 80f,
            "Amoxycillin and Potassium Clavulanate Tablets IP" to 20f,
            "Each film coated tablet contains: Amoxycillin Trihydrate IP eq. to Amoxycillin 500 mg" to 14f,
            "Potassium Clavulanate Diluted IP eq. to Clavulanic Acid 125 mg" to 14f,
            "GlaxoSmithKline Pharmaceuticals Ltd" to 12f,
        ))
        println("augmentin: ${r.band} ${r.best?.product?.name} ${"%.3f".format(r.confidence)} ${r.best?.features} ${r.matchMs}ms")
        println("augmentin candidates: " + r.candidates.joinToString { "${it.product.name} ${it.evidence} ${it.items.map { i -> i.strength }}" })
        assertEquals("Augmentin 625 Duo Tablet", r.best!!.product.name)
        assertEquals(Band.ACCEPT, r.band)
        val rep = rules.evaluate(r.best!!.product, r.best!!.items)
        assertEquals(Aware.Access, rep.antibiotic!!.aware) // WHO classifies amoxicillin/clavulanic acid as a combination
    }

    @Test fun ocrNoiseStillMatches() {
        val r = resolver.resolve(ocr("D0L0-650" to 60f, "Paracetamol Tablets IP 650 mg" to 20f, "Micro Labs Limited" to 10f))
        println("dolo: ${r.band} ${r.best?.product?.name} ${"%.3f".format(r.confidence)} ${r.best?.features}")
        assertEquals(setOf(ref.ingredientByText("paracetamol")!!.id), r.best!!.product.ingredientIds)
        assertTrue(r.best!!.product.name.startsWith("Dolo 650"))
    }

    /** Regression: a vitamin tablet strip whose brand is only in the DB as an injection was accepted as the injection. */
    @Test fun tabletStripNeverResolvesToInjection() {
        val r = resolver.resolve(ocr(
            "NEUROBION FORTE" to 70f,
            "Vitamin B Complex with Vitamin B12 Tablets" to 22f,
            "Each sugar coated tablet contains: Thiamine Mononitrate IP 10 mg, Riboflavin IP 10 mg, Pyridoxine Hydrochloride IP 3 mg" to 12f,
            "Cyanocobalamin IP 15 mcg, Nicotinamide IP 45 mg, Calcium Pantothenate IP 50 mg" to 12f,
            "P&G Health" to 10f,
        ))
        println("neurobion forte: ${r.band} ${r.best?.product?.name} ${r.best?.product?.form} ${"%.3f".format(r.confidence)} ${r.best?.features}")
        assertTrue(r.candidates.none { it.product.form == "injection" })
        assertTrue(r.band != Band.ACCEPT || r.best!!.product.form != "injection")
    }

    /** Regression (reported on device): Evion 200 (not in the DB) showed "Zevion 400 Capsule". */
    @Test fun strengthOnStripExcludesOtherStrengths() {
        val r = resolver.resolve(ocr(
            "Evion" to 60f, "Vitamin E Capsules USP 200 mg" to 26f, "Nourish Your Cells" to 14f,
            "Each capsule contains: Tocopheryl Acetate USP 200 mg" to 10f, "Procter & Gamble Health Ltd" to 10f,
        ))
        println("evion: ${r.band} ${r.best?.product?.name} ${r.best?.items} ${"%.3f".format(r.confidence)}")
        assertTrue(r.candidates.none { c -> c.items.any { it.ingredient.name == "Vitamin E" && it.strength?.value == 400.0 } })
        if (r.best != null) assertEquals(listOf("Vitamin E"), r.best!!.items.map { it.ingredient.name })
    }

    /** The exact text ML Kit read from a real Evion 200 strip on the Moto Edge 60 Fusion (angled foil). */
    @Test fun realEvionStripFromPhone() {
        val raw = "Evion (200)Grected by physician. If pragnant, [h=35] | Cach sott oelate [h=11] | Toconher Acetate lP200 ma [h=12] | " +
            "ecuivalent to alpha tocopberol 91 mg [h=10] | uiset Yelow FCE Ponceac 4R [h=13] | Store in a dry nlen [h=17] | Evion an0 [h=30] | " +
            "Eaeh soft gelatin caps [h=13] | apsule fantains: [h=10] | Viamin E Capsules USP 200 g [h=42] | Nourish Your Cetts [h=16] | " +
            "Evion00) [h=27] | Each soft gelatin capsule contains: [h=15] | eCuvalent to aloha [h=9] | 200 m [h=10] | ha tocopherol [h=7] | " +
            "Procter LGamble Health Ltd. [h=10] | Lactatinnank [h=14] | Nourish Your Cells [h=14] | itamin E Capsules USP 200ng [h=37] | " +
            "Precter & Gamble Health Ltd [h=10] | 00 mg [h=38] | Evion Evion o Evion co0 [h=392]"
        val lines = raw.split(" | ").map { val m = Regex("""^(.*) \[h=(\d+)]$""").find(it)!!; OcrLine(m.groupValues[1], m.groupValues[2].toFloat(), 0.8f) }
        val r = resolver.resolve(OcrResult(lines))
        println("evion-real: ${r.band} ${r.candidates.map { it.product.name + " " + "%.2f".format(it.score) }} found=${r.found.values.map { "${it.ingredient.name} ${it.strength} exact=${it.exact} conflict=${it.strengthConflict}" }}")
        assertTrue(r.candidates.none { it.product.name.startsWith("Zevion") })
        assertTrue(r.candidates.none { c -> c.items.any { it.ingredient.name == "Acetic Acid" || it.ingredient.name == "Acetic acid" } })
        if (r.band == Band.ACCEPT) assertEquals(listOf("Vitamin E 200 mg"), r.best!!.items.map { "${it.ingredient.name} ${it.strength}" })
    }

    @Test fun whatItIsForComesFromData() {
        val dolo = ref.productsByName("Dolo 650 Tablet").first()
        val azi = ref.productsByName("Azithral 500 Tablet").first()
        val u1 = Texts.uses(rules.evaluate(dolo, dolo.items), ref, Lang.EN)
        val u2 = Texts.uses(rules.evaluate(azi, azi.items), ref, Lang.EN)
        println("uses: dolo=$u1 azithral=$u2")
        assertTrue(u1.single().contains("Fever", ignoreCase = true) || u1.single().contains("pain", ignoreCase = true))
        assertTrue(u2.single().contains("Bacterial infections", ignoreCase = true))
    }

    /** Every translated template must carry exactly the English placeholders, or the app would show "{name}". */
    @Test fun translationsKeepPlaceholders() {
        val ph = Regex("""\{\w+\}""")
        val en = File(repo, "core/src/main/resources/i18n/en.json").readText()
        val keys = Regex("\"([a-zA-Z.]+)\":").findAll(en).map { it.groupValues[1] }.toList()
        assertTrue(keys.size > 80)
        for (lang in Lang.entries) {
            if (lang == Lang.EN || !File(repo, "core/src/main/resources/i18n/${lang.code}.json").exists()) continue
            for (k in keys) if (I18n.has(lang, k)) {
                assertEquals("$lang $k", ph.findAll(I18n.t(Lang.EN, k)).map { it.value }.sorted().toList(),
                    ph.findAll(I18n.t(lang, k)).map { it.value }.sorted().toList())
            }
        }
    }

    /** Reported on device: Becosules Z (not in the DB). Typical multivitamin label with "eq. to" lines. */
    @Test fun becosulesStyleCompositionReadFromStrip() {
        val reading = LlmReading(brand = "BECOSULES Z", dosage_form = "Capsules", ingredients = listOf(
            LlmIngredient("Thiamine Mononitrate IP", "10 mg"), LlmIngredient("Riboflavin IP", "10 mg"),
            LlmIngredient("Pyridoxine Hydrochloride IP", "3 mg"), LlmIngredient("Cyanocobalamin IP", "15 mcg"),
            LlmIngredient("Niacinamide IP", "100 mg"), LlmIngredient("Calcium Pantothenate IP", "50 mg"),
            LlmIngredient("Folic Acid IP", "1.5 mg"), LlmIngredient("Biotin USP", "100 mcg"),
            LlmIngredient("Ascorbic Acid IP", "150 mg"),
            LlmIngredient("Zinc Sulphate Monohydrate USP eq. to Elemental Zinc", "22.5 mg")))
        val r = resolver.resolveReading(reading, null)
        println("becosules: ${r.band} ${r.best?.product?.name} ${r.best?.evidence}")
        assertEquals(Band.ACCEPT, r.band)
        assertEquals(10, r.best!!.items.size)
        assertTrue(r.best!!.items.any { it.ingredient.name == "Zinc" && it.strength == Strength(22.5, "mg") })
    }

    /** The real reading of the user's Becosules Z strip (photo sent from the Moto): 10 ingredients, zinc as "equiv. to". */
    @Test fun realBecosulesReading() {
        val text = CoreTest::class.java.getResource("/becosules_reading.json")!!.readText()
        val reading = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(LlmReading.serializer(), text)
        val r = resolver.resolveReading(reading, null)
        println("becosules-real lines: " + reading.asOcr(null).lines.joinToString(" | ") { it.text })
        println("becosules-real found: " + resolver.resolve(reading.asOcr(null), true).found.values.joinToString { "${it.ingredient.name} ${it.strength} ${it.exact}" })
        println("becosules-real: ${r.band} ${r.best?.product?.name} ${r.best?.items?.map { "${it.ingredient.name} ${it.strength}" }}")
        assertEquals(Band.ACCEPT, r.band)
        assertEquals("BECOSULES Z", r.best!!.product.name)
        assertEquals(10, r.best!!.items.size)
        assertTrue(r.best!!.items.any { it.ingredient.name == "Zinc" && it.strength == Strength(15.0, "mg") })
        assertTrue(r.best!!.items.any { it.ingredient.name == "Vitamin C" })
    }

    /** Two readers disagree on one strength (a real misread: niacinamide 100 mcg vs 100 mg): shown as unclear, not guessed. */
    @Test fun twoReadersCrossCheck() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val a = json.decodeFromString(LlmReading.serializer(), CoreTest::class.java.getResource("/becosules_reading.json")!!.readText())
        val b = a.copy(ingredients = a.ingredients.map { if (it.name.startsWith("Niacinamide")) it.copy(strength = "100 mg") else it })
        val r = resolver.resolveReadings(listOf(a, b), null)
        val nia = r.best!!.items.first { it.ingredient.name == "Niacinamide" }
        println("two-readers: ${r.band} niacinamide=${nia.strength}")
        assertEquals(Band.ACCEPT, r.band)
        assertNull(nia.strength)
        // a reader that contradicts itself (B12 misread as a second "Vitamin B6") is discarded in favour of the other
        val bad = a.copy(ingredients = a.ingredients.map { if (it.name.startsWith("Vitamin B12")) it.copy(name = "Vitamin B6 I.P.") else it })
        val r2 = resolver.resolveReadings(listOf(bad, b), null)
        assertEquals(Band.ACCEPT, r2.band)
        assertTrue(r2.best!!.items.any { it.ingredient.name == "Cyanocobalamin" })
    }

    @Test fun expiryReadFromStrip() {
        assertEquals(java.time.YearMonth.of(2027, 8), Expiry.parse("B.No. ABC123 MFD. 09/2025 EXP. 08/2027"))
        assertEquals(java.time.YearMonth.of(2026, 11), Expiry.parse("Exp Date: NOV.2026"))
        assertEquals(java.time.YearMonth.of(2026, 3), Expiry.parse("EXP 03/26"))
        assertNull(Expiry.parse("MFG 09/2025"))
        assertNull(Expiry.parse("Store below 25C"))
    }

    @Test fun hindiCopyIsInformational() {
        val dolo = ref.productsByName("Dolo 650 Tablet").first()
        val r = rules.evaluate(dolo, dolo.items)
        val h = Texts.headline(r, ref, Lang.HI)
        println("hindi headline: $h | en: ${Texts.headline(r, ref, Lang.EN)}")
        assertTrue(h!!.endsWith("में दी जाती है।"))
        val speech = Texts.speech(r, ref, Lang.HI)
        assertTrue(speech.contains("मिलीग्राम"))
    }

    @Test fun unreadableStripFallsBack() {
        val r = resolver.resolve(ocr("x7 ##" to 40f, "lot 22" to 10f))
        assertEquals(Band.FALLBACK, r.band)
    }

    @Test fun watchAntibioticBadge() {
        val p = ref.productsByName("Zifi 200 Tablet").first()
        val rep = rules.evaluate(p, p.items)
        assertEquals(Aware.Watch, rep.antibiotic!!.aware)
        assertTrue(Texts.issues(rep, Lang.EN).any { it.title.contains("Watch") })
    }

    @Test fun bannedDispersibleExactButPlainTabletOnlyRelated() {
        val dt = ref.productsByName("Nixar 100mg/500mg Tablet DT").first()
        assertTrue(rules.evaluate(dt, dt.items).banned)
        // the same two ingredients as an ordinary tablet are not covered by the dispersible-only notification
        val plain = ref.productsByBaseKeys(listOf("nise")).firstOrNull { it.form == "tablet" && it.items.size == 2 }
        if (plain != null) {
            val rep = rules.evaluate(plain, plain.items)
            assertTrue(!rep.banned)
        }
    }

    @Test fun aceclofenacParacetamolBanIsStrengthSpecific() {
        val legal = ref.productsByBaseKeys(listOf("zerodol p")).first { it.form == "tablet" }
        val rep = rules.evaluate(legal, legal.items)
        println("zerodol-p: ${legal.items} bans=${rep.bans.map { it.match }}")
        assertTrue(!rep.banned)
    }

    @Test fun duplicateParacetamolAcrossTwoBrands() {
        val dolo = ref.productsByName("Dolo 650 Tablet").first()
        val combi = ref.productsByName("Combiflam Tablet").first()
        val alerts = rules.cabinet(listOf(rules.evaluate(dolo, dolo.items), rules.evaluate(combi, combi.items)))
        assertTrue(alerts.any { it is RuleEngine.CabinetAlert.Duplicate && it.ingredient.name == "Paracetamol" })
    }

    @Test fun janAushadhiCheaperGeneric() {
        val crocin = ref.productsByName("Crocin Advance Tablet").first()
        val rep = rules.evaluate(crocin, crocin.items)
        println("crocin generic: ${rep.generic}")
        assertNotNull(rep.generic)
        assertTrue(rep.generic!!.savingPercent!! > 0)
    }

    @Test fun nonAntibioticHasNoBadge() {
        val p = ref.productsByName("Dolo 650 Tablet").first()
        assertNull(rules.evaluate(p, p.items).antibiotic)
    }

    @Test fun eventCarriesNoPersonalData() {
        val p = ref.productsByName("Azithral 500 Tablet").first()
        val e = ScanEvent.from(rules.evaluate(p, p.items), "201309")
        assertEquals(listOf("Azithromycin"), e.ingredients)
        assertEquals("Watch", e.aware_group)
        assertTrue(e.scanned_at.endsWith(":00:00Z"))
    }

    /**
     * First real on-device eval (Moto Edge 60 Fusion, ML Kit OCR): these were wrong answers. Each must no longer be
     * auto-accepted as the wrong product (asking or falling back is fine).
     */
    @Test fun deviceRegressionsNeverAcceptWrongSibling() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun res(f: String) = resolver.resolve(json.decodeFromString(OcrResult.serializer(),
            javaClass.getResource("/device_ocr/$f.jpg.json")!!.readText()), false)
        val wrong = mapOf(
            "p034_blur" to "Laformin-G 2 Tablet PR",   // strip: LAFORMIN GV 2
            "p056_glare" to "Cipla Inhaler",           // strip: Desirox 250 tablets by Cipla
            "n000" to "Amlopres 10 Tablet",            // strip: Amlopres Trio
            "n022" to "Silofast 8 Capsule",            // strip: Silofast D8
            "p021" to "Kelvin 500mg Tablet",           // DB Kelvin is paracetamol; strip prints ciprofloxacin 500 mg
        )
        for ((f, bad) in wrong) {
            val r = res(f)
            assertTrue("$f accepted $bad", !(r.band == Band.ACCEPT && r.best?.product?.name == bad))
        }
    }

    @Test fun ayushPacksAreRecognisedNotGuessed() {
        fun ocr(vararg l: String) = OcrResult(l.map { OcrLine(it, 20f) })
        assertTrue(PackKind.isAyush(ocr("LIV.52 DS", "Ayurvedic Proprietary Medicine")))
        assertTrue(PackKind.isAyush(ocr("Arnica Montana 30", "HOMOEOPATHIC MEDICINE")))
        assertTrue(!PackKind.isAyush(ocr("DOLO 650", "Paracetamol Tablets IP 650 mg")))
        assertTrue(!PackKind.isAyush(ocr("Herbal extract", "Cough syrup")))  // "herbal" alone is not enough
    }

    /** Encodings are format examples (the rule fixes content, not encoding); real packs validate them in capture mode. */
    @Test fun packCodesAreParsedNotGuessed() {
        val gs1 = PackCodes.parse("(01)08901234567890(17)270831(10)B23012")
        assertEquals("08901234567890", gs1.gtin); assertEquals("B23012", gs1.batch)
        assertEquals(java.time.YearMonth.of(2027, 8), gs1.expiry)
        // FNC1/GS-separated form, no brackets: (01) GTIN, (17) expiry, (10) batch <GS> (21) serial
        val raw = PackCodes.parse("010890123456789017270831" + "10DLP2201" + "\u001d" + "21SER9")
        assertEquals("DLP2201", raw.batch); assertEquals(java.time.YearMonth.of(2027, 8), raw.expiry)
        val url = PackCodes.parse("https://verify.example.in/p?batch=DLP2201&exp=08%2F2027")
        assertEquals("DLP2201", url.batch); assertEquals(java.time.YearMonth.of(2027, 8), url.expiry); assertTrue(url.url != null)
        val text = PackCodes.parse("Brand Name: DOLO 650\nGeneric Name: Paracetamol Tablets IP 650 mg\nBatch No: DLP2201\nExp: 08/2027")
        assertEquals("DOLO 650", text.brand); assertEquals("Paracetamol Tablets IP 650 mg", text.generic)
        assertEquals("DLP2201", text.batch); assertEquals(java.time.YearMonth.of(2027, 8), text.expiry)
        val unknown = PackCodes.parse("X7Q-99")
        assertTrue(unknown.brand == null && unknown.batch == null && unknown.gtin == null)
    }

    @Test fun batchNumbersReadFromPrintedText() {
        assertEquals("DLP2201", Batch.parse("B.No. DLP2201  Mfg. 09/2025"))
        assertEquals("AB-2301", Batch.parse("Batch No: AB-2301"))
        assertEquals("T23K07", Batch.parse("LOT T23K07"))
        assertEquals(null, Batch.parse("Batch tablets as directed"))  // no digit: not a batch number
        assertEquals(null, Batch.parse("Dolo 650 Tablets"))
    }

    /** Real CDSCO records from the shipped DB: a batch alone never flags a strip; the product must match too. */
    @Test fun batchAlertsNeedBatchAndProduct() {
        fun p(name: String) = ref.productsByName(name).first()
        fun hits(name: String, batch: String) = p(name).let { BatchAlerts.check(ref, batch, it, it.items) }
        // spurious Telma AM batch (CDSCO, Aug 2026)
        assertEquals(BatchAlert.Kind.SPURIOUS, hits("Telma-AM Tablet", "18230791").first().kind)
        assertEquals(1, hits("Telma-AM Tablet", "I823O791").size)        // OCR read 1 as I and 0 as O
        assertTrue(hits("Telma 40 Tablet", "18230791").isEmpty())          // sibling brand: not this product
        assertTrue(hits("Dolo 650 Tablet", "18230791").isEmpty())          // same batch code, different medicine
        // generic-named NSQ alert "Paracetamol Tablets IP 500 mg", batch PRS-520 (Nov 2023)
        assertEquals(BatchAlert.Kind.NSQ, hits("Calpol 500mg Tablet", "PRS-520").first().kind)
        assertTrue(hits("Dolo 650 Tablet", "PRS 520").isEmpty())           // 650 mg is not the 500 mg that failed
        assertTrue(hits("Dolo 650 Tablet", "ZZ99999").isEmpty())
        assertTrue(BatchAlerts.recordCount(ref) > 5000)
    }
}
