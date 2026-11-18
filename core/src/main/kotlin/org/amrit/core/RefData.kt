package org.amrit.core

/** Minimal SQL surface so the same queries run on Android (SQLiteDatabase) and on the JVM (JDBC) in the eval harness. */
interface Sql {
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (Row) -> T): List<T>
}

interface Row {
    fun int(i: Int): Int
    fun long(i: Int): Long
    fun double(i: Int): Double?
    fun string(i: Int): String?
}

enum class Aware { Access, Watch, Reserve;
    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

data class Ingredient(
    val id: Int, val name: String, val key: String,
    val awareOral: Aware?, val awareParenteral: Aware?, val awareClass: String?, val isAntibiotic: Boolean,
)

data class CompItem(val ingredient: Ingredient, val strength: Strength?)

data class Product(
    val id: Int, val name: String, val baseKey: String, val manufacturer: String?, val form: String,
    val packQty: Double?, val mrp: Double?, val discontinued: Boolean, val compositionId: Int,
    val maybeIncomplete: Boolean, val items: List<CompItem>,
) {
    val nameKey: String by lazy { Normalize.brandKeys(name).first }
    val ingredientIds: Set<Int> get() = items.map { it.ingredient.id }.toSet()
}

data class BannedFdc(
    val id: Int, val ingrKey: String, val title: String, val notification: String?, val date: String?,
    val formReq: String?, val strengthVariants: List<Map<Int, Strength>>,
)

data class JanAushadhiItem(
    val code: Int, val name: String, val unitSize: String, val unitQty: Double?, val mrp: Double, val form: String,
    val ingrKey: String, val strengths: Map<Int, Strength>,
)

enum class Severity(val level: Int) { Minor(1), Moderate(2), Major(3);
    companion object { fun of(l: Int) = entries.first { it.level == l } }
}

data class Interaction(val a: Ingredient, val b: Ingredient, val severity: Severity)

fun ingrKeyOf(ids: Collection<Int>) = ids.toSortedSet().joinToString("+")

private val strengthToken = Regex("^(\\d+):(.*)$")

private fun parseStrengthMap(s: String?): Map<Int, Strength> {
    if (s.isNullOrBlank()) return emptyMap()
    return s.split(',', ';').mapNotNull { part ->
        val m = strengthToken.find(part.trim()) ?: return@mapNotNull null
        val st = Normalize.parseStrength(m.groupValues[2]) ?: return@mapNotNull null
        m.groupValues[1].toInt() to st
    }.toMap()
}

/** Read access to the shipped reference DB. Caches the small tables; products are queried on demand. */
class RefData(internal val sql: Sql) {

    val ingredients: Map<Int, Ingredient> by lazy {
        sql.query("SELECT id,name,key,aware_oral,aware_parenteral,aware_class,is_antibiotic FROM ingredient") {
            Ingredient(it.int(0), it.string(1)!!, it.string(2)!!, Aware.of(it.string(3)), Aware.of(it.string(4)),
                it.string(5), it.int(6) == 1)
        }.associateBy { it.id }
    }

    /** ingredient alias key -> ingredient id (spelling variants, salts, synonyms). */
    val aliases: Map<String, Int> by lazy {
        sql.query("SELECT key, ingredient_id FROM ingredient_alias") { it.string(0)!! to it.int(1) }.toMap()
    }

    fun ingredientByText(text: String): Ingredient? = aliases[Normalize.ingredientKey(text)]?.let { ingredients[it] }

    val meta: Map<String, String> by lazy { sql.query("SELECT k, v FROM meta") { it.string(0)!! to (it.string(1) ?: "") }.toMap() }

    fun allBaseKeys(): List<String> = sql.query("SELECT DISTINCT base_key FROM product") { it.string(0)!! }

    private val compCache = HashMap<Int, Pair<Boolean, List<CompItem>>>()

    private fun composition(id: Int): Pair<Boolean, List<CompItem>> = compCache.getOrPut(id) {
        val incomplete = sql.query("SELECT maybe_incomplete FROM composition WHERE id=?", listOf(id)) { it.int(0) == 1 }
            .firstOrNull() ?: false
        val items = sql.query("SELECT ingredient_id, strength, unit FROM composition_item WHERE composition_id=?", listOf(id)) {
            val st = it.double(1)?.let { v -> it.string(2)?.let { u -> Strength(v, u) } }
            CompItem(ingredients.getValue(it.int(0)), st)
        }
        incomplete to items
    }

    private fun productsWhere(where: String, args: List<Any?>): List<Product> =
        sql.query(
            "SELECT p.id,p.name,p.base_key,m.name,p.form,p.pack_qty,p.mrp,p.discontinued,p.composition_id " +
                "FROM product p LEFT JOIN manufacturer m ON m.id=p.manufacturer_id WHERE $where", args
        ) { r -> listOf(r.int(0), r.string(1), r.string(2), r.string(3), r.string(4), r.double(5), r.double(6), r.int(7), r.int(8)) }
            .map { r ->
                val (inc, items) = composition(r[8] as Int)
                Product(r[0] as Int, r[1] as String, r[2] as String, r[3] as String?, (r[4] as String?) ?: "other",
                    r[5] as Double?, r[6] as Double?, r[7] == 1, r[8] as Int, inc, items)
            }

    /** Every product with exactly this composition (one composition lookup for all of them). */
    fun productsByComposition(compositionId: Int): List<Product> {
        val (inc, items) = composition(compositionId)
        return sql.query("SELECT p.id,p.name,p.base_key,m.name,p.form,p.pack_qty,p.mrp,p.discontinued FROM product p " +
            "LEFT JOIN manufacturer m ON m.id=p.manufacturer_id WHERE p.composition_id=?", listOf(compositionId)) { r ->
            Product(r.int(0), r.string(1)!!, r.string(2) ?: "", r.string(3), r.string(4) ?: "other", r.double(5), r.double(6),
                r.int(7) == 1, compositionId, inc, items)
        }
    }

    /** a few products whose composition may be cut off (tests) */
    fun productsWhereIncomplete(n: Int): List<Product> =
        productsWhere("p.composition_id IN (SELECT id FROM composition WHERE maybe_incomplete=1 LIMIT ?)", listOf(n))

    fun productsByBaseKeys(keys: Collection<String>): List<Product> {
        if (keys.isEmpty()) return emptyList()
        return productsWhere("p.base_key IN (${keys.joinToString(",") { "?" }})", keys.toList()) +
            keys.flatMap { learned[it].orEmpty() }
    }

    fun productById(id: Int): Product? =
        if (id <= LEARNED_ID_BASE) learnedById[id] else productsWhere("p.id=?", listOf(id)).firstOrNull()

    // ------------------------------------------------------------------ learned products
    // Brands absent from the shipped DB, learned from printed compositions (fallback reading + 2 agreeing reports,
    // synced from the backend). They get negative ids and behave like any other product in matching and rules.
    private val learned = HashMap<String, MutableList<Product>>()
    private val learnedById = HashMap<Int, Product>()

    /** items: "ingredientId:strength;..." as stored by the backend. Returns null if any ingredient id is unknown. */
    @Synchronized
    fun addLearned(baseKey: String, displayName: String, form: String, items: String): Product? {
        val ci = items.split(';').filter { it.contains(':') }.map { e ->
            val (id, st) = e.split(':', limit = 2)
            CompItem(ingredients[id.toIntOrNull() ?: return null] ?: return null, Normalize.parseStrength(st))
        }
        if (ci.isEmpty()) return null
        learned[baseKey]?.firstOrNull { p -> p.items.toSet() == ci.toSet() }?.let { return it }
        val id = LEARNED_ID_BASE - learnedById.size - 1
        val p = Product(id, displayName, baseKey, null, form, null, null, false, -1, false, ci)
        learned.getOrPut(baseKey) { mutableListOf() } += p
        learnedById[id] = p
        return p
    }

    fun productsByName(name: String): List<Product> = productsWhere("p.name=?", listOf(name)) +
        learnedById.values.filter { it.name == name }

    companion object {
        /** learned products have ids at or below this */
        const val LEARNED_ID_BASE = -1000
    }

    /** Products with exactly this set of ingredients (any strength), capped. */
    fun productsByIngrKey(key: String): List<Product> =
        productsWhere("p.composition_id IN (SELECT id FROM composition WHERE ingr_key=?) LIMIT 300", listOf(key))


    fun bannedByIngrKey(key: String): List<BannedFdc> =
        sql.query("SELECT id,ingr_key,title,notification,date,form_req,strengths FROM banned_fdc WHERE ingr_key=?", listOf(key)) {
            BannedFdc(it.int(0), it.string(1)!!, it.string(2) ?: "", it.string(3), it.string(4), it.string(5),
                (it.string(6) ?: "").split('|').filter { v -> v.isNotBlank() }.map { v -> parseStrengthMap(v) })
        }

    fun whoNotRecommended(key: String): String? =
        sql.query("SELECT title FROM who_not_recommended WHERE ingr_key=?", listOf(key)) { it.string(0) }.firstOrNull()

    fun awareCombo(key: String): Pair<String, Aware>? =
        sql.query("SELECT title, aware FROM aware_combo WHERE ingr_key=?", listOf(key)) {
            (it.string(0) ?: "") to (Aware.of(it.string(1)) ?: Aware.Watch)
        }.firstOrNull()

    fun interactions(ids: Collection<Int>): List<Interaction> {
        if (ids.size < 2) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        val args = ids.toList() + ids.toList()
        return sql.query("SELECT a,b,level FROM interaction WHERE a IN ($ph) AND b IN ($ph)", args) {
            Interaction(ingredients.getValue(it.int(0)), ingredients.getValue(it.int(1)), Severity.of(it.int(2)))
        }
    }

    /** "What it's for": phrase ids for the exact ingredient set, else per ingredient (only ingredients that have one). */
    data class UseInfo(val phraseIds: List<Int>, val ingredient: Ingredient?)

    fun usesFor(ids: Set<Int>): List<UseInfo> {
        if (ids.isEmpty()) return emptyList()
        fun parse(s: String?) = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val whole = sql.query("SELECT phrase_ids FROM composition_use WHERE ingr_key=?", listOf(ingrKeyOf(ids))) { it.string(0) }.firstOrNull()
        if (whole != null) return listOf(UseInfo(parse(whole), null))
        return ids.mapNotNull { id ->
            sql.query("SELECT phrase_ids FROM ingredient_use WHERE ingredient_id=?", listOf(id)) { it.string(0) }.firstOrNull()
                ?.let { UseInfo(parse(it), ingredients[id]) }
        }
    }

    private val phrasesEn: Map<Int, String> by lazy { sql.query("SELECT id, en FROM use_phrase") { it.int(0) to it.string(1)!! }.toMap() }
    private val phrasesI18n = HashMap<Lang, Map<Int, String>>()

    fun usePhrase(id: Int, lang: Lang): String {
        if (lang != Lang.EN) {
            val m = phrasesI18n.getOrPut(lang) {
                sql.query("SELECT phrase_id, text FROM use_phrase_i18n WHERE lang=?", listOf(lang.code)) { it.int(0) to it.string(1)!! }.toMap()
            }
            m[id]?.let { return it }
        }
        return phrasesEn[id] ?: ""
    }

/** One ICMR AMRSN 2024 result: of [tested] isolates of [organism] from [specimen], [susceptible] responded to [antibiotic]. */
    data class Resistance(val organism: String, val infection: String, val specimen: String, val antibiotic: String,
                          val ingredientIds: Set<Int>, val susceptible: Int, val tested: Int, val pct: Double, val reportPage: Int, val table: String)

    private val resistanceRows: List<Resistance> by lazy {
        runCatching {  // an older DB without the table simply has no resistance facts
            sql.query("SELECT organism,infection,specimen,antibiotic,ingredient_ids,susceptible,tested,pct,report_page,tbl FROM resistance") {
                Resistance(it.string(0)!!, it.string(1)!!, it.string(2)!!, it.string(3)!!, it.string(4)!!.split('+').map(String::toInt).toSet(),
                    it.int(5), it.int(6), it.double(7) ?: 0.0, it.int(8), it.string(9)!!)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * ICMR rows for exactly this product's antibiotic(s): every drug in the row is in the product, and every antibiotic
     * in the product is in the row. Ciprofloxacin + tinidazole therefore gets nothing: ICMR tested ciprofloxacin alone.
     */
    fun resistance(items: List<CompItem>): List<Resistance> {
        val ids = items.map { it.ingredient.id }.toSet()
        val abx = items.filter { it.ingredient.isAntibiotic }.map { it.ingredient.id }.toSet()
        if (abx.isEmpty()) return emptyList()
        return resistanceRows.filter { r -> ids.containsAll(r.ingredientIds) && r.ingredientIds.containsAll(abx) }
    }

    fun janAushadhi(key: String): List<JanAushadhiItem> =
        sql.query("SELECT drug_code,name,unit_size,unit_qty,mrp,form,ingr_key,strengths FROM jan_aushadhi WHERE ingr_key=?", listOf(key)) {
            JanAushadhiItem(it.int(0), it.string(1) ?: "", it.string(2) ?: "", it.double(3), it.double(4) ?: 0.0,
                it.string(5) ?: "other", it.string(6)!!, parseStrengthMap(it.string(7)))
        }
}
