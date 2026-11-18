package org.amrit.core

/**
 * In-memory fuzzy index over brand base names ("augmentin", "dolo", "zifi cv"), ~180k keys.
 * Candidate retrieval by shared character trigrams, then a bounded Levenshtein check.
 * Both index and queries go through [Normalize.ocrFold] so typical OCR confusions (0/O, 1/l/I, rn/m) cost nothing.
 */
class BrandIndex(baseKeys: Collection<String>) {
    private val keys = ArrayList<String>(baseKeys.size)
    private val folded = ArrayList<String>(baseKeys.size)
    private val postings = HashMap<Int, IntList>(1 shl 18)
    /** learned aliases: folded OCR phrase -> base key (from LLM fallbacks / user confirmations) */
    private val aliasToKey = HashMap<String, String>()

    init {
        for (k in baseKeys) add(k)
    }

    val size get() = keys.size

    private fun add(key: String) {
        val f = fold(key)
        if (f.length < 2) return
        val idx = keys.size
        keys += key
        folded += f
        for (g in grams(f)) postings.getOrPut(g) { IntList() }.addUnique(idx)
    }

    /** Learned brands become first-class keys (same trigram index as shipped brands). */
    @Synchronized
    fun addKey(baseKey: String) { if (keys.none { it == baseKey }) add(baseKey) }

    /** Type-ahead: keys starting with the typed text (folded), shortest first. */
    fun prefix(query: String, limit: Int = 20): List<String> {
        val q = fold(query)
        if (q.length < 2) return emptyList()
        val out = ArrayList<String>()
        for (i in folded.indices) if (folded[i].startsWith(q)) out += keys[i]
        return out.sortedBy { it.length }.take(limit)
    }

    @Synchronized
    fun addAlias(phrase: String, baseKey: String) {
        val f = fold(phrase)
        if (f.length >= 3) aliasToKey[f] = baseKey
    }

    data class Hit(val baseKey: String, val similarity: Double, val viaAlias: Boolean = false)

    @Synchronized
    fun search(query: String, limit: Int = 5, minSim: Double = 0.6): List<Hit> {
        val q = fold(query)
        if (q.length < 3) return emptyList()
        val out = ArrayList<Hit>()
        aliasToKey[q]?.let { out += Hit(it, 1.0, viaAlias = true) }
        val qg = grams(q)
        val counts = HashMap<Int, Int>()
        for (g in qg) postings[g]?.forEach { counts.merge(it, 1, Int::plus) }
        val need = maxOf(1, (qg.size * 0.34).toInt())
        val cands = counts.entries.filter { it.value >= need }.sortedByDescending { it.value }.take(300)
        for ((idx, _) in cands) {
            val k = folded[idx]
            val maxLen = maxOf(k.length, q.length)
            val maxDist = ((1 - minSim) * maxLen).toInt()
            if (kotlin.math.abs(k.length - q.length) > maxDist) continue
            val d = Levenshtein.bounded(q, k, maxDist)
            if (d < 0) continue
            val sim = 1.0 - d.toDouble() / maxLen
            if (sim >= minSim) out += Hit(keys[idx], sim)
        }
        return out.sortedByDescending { it.similarity }.distinctBy { it.baseKey }.take(limit)
    }

    companion object {
        fun fold(s: String) = Normalize.ocrFold(s.lowercase().filter { it.isLetterOrDigit() })

        private fun grams(s: String): IntArray {
            val p = "^$s$"
            if (p.length < 3) return IntArray(0)
            val out = IntArray(p.length - 2)
            for (i in 0 until p.length - 2) out[i] = (p[i].code shl 16) xor (p[i + 1].code shl 8) xor p[i + 2].code
            return out.distinct().toIntArray()
        }
    }
}

class IntList {
    private var a = IntArray(2)
    private var n = 0
    fun addUnique(v: Int) {
        if (n > 0 && a[n - 1] == v) return
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    inline fun forEach(f: (Int) -> Unit) { for (i in 0 until size()) f(get(i)) }
    fun size() = n
    fun get(i: Int) = a[i]
}

object Levenshtein {
    /** Edit distance, or -1 if it exceeds [max]. */
    fun bounded(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        val n = a.length; val m = b.length
        if (kotlin.math.abs(n - m) > max) return -1
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return -1
            val t = prev; prev = cur; cur = t
        }
        return if (prev[m] <= max) prev[m] else -1
    }

    fun similarity(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        if (m == 0) return 1.0
        val d = bounded(a, b, m)
        return 1.0 - d.toDouble() / m
    }
}
