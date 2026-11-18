package org.amrit.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.amrit.core.Band
import org.amrit.core.BrandIndex
import org.amrit.core.Candidate
import org.amrit.core.JdbcSql
import org.amrit.core.LlmReading
import org.amrit.core.Normalize
import org.amrit.core.OcrResult
import org.amrit.core.RefData
import org.amrit.core.Resolver
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * Evaluation harness. Replays saved OCR output (from the phone, or a desktop proxy) through the exact core code the
 * app runs, with the app's own decision rules, and reports: brand-to-ingredient accuracy, wrong answers, % of scans
 * that hit the API, latency p50/p95, API cost per 100 scans.
 *
 *   ./gradlew :eval:run --args="--source eval/out/device [--call-llm http://localhost:8000] [--write-assets]"
 *
 * <source>/ocr/<photo>.json   OcrResult per photo (EvalRunner on the phone writes these)
 * <source>/llm/<photo>.json   vision-model reading per photo that needed the fallback (saved, so reruns are free)
 * <source>/results.json       on-device timings + per-call API cost (optional; needed for latency)
 * --call-llm URL              call the real fallback through the backend for photos that need it and have no reading yet
 */
fun main(args: Array<String>) {
    val a = args.toList()
    fun opt(k: String, d: String?) = a.indexOf(k).let { if (it >= 0 && it + 1 < a.size) a[it + 1] else d }
    val source = File(opt("--source", "eval/out/device")!!)
    val labelsFile = File(opt("--labels", "data/test_photos/labels.csv")!!)
    val photos = labelsFile.parentFile
    val out = File(opt("--out", source.path)!!)
    val writeAssets = "--write-assets" in a
    val llmUrl = opt("--call-llm", null)

    val ref = RefData(JdbcSql.open(File("app/src/main/assets/amrit_ref.db").absolutePath))
    val resolver = Resolver(ref, BrandIndex(ref.allBaseKeys()))
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class Label(val file: String, val product: String, val ingredients: List<String>, val condition: String) {
        /** product absent from the reference DB: correct = "not found", or the right ingredients via the printed composition */
        val negative get() = condition == "not_in_db"
    }
    val labels = labelsFile.readLines().drop(1).map { csv(it) }.filter { it.size >= 4 }
        .map { Label(it[0], it[1], it[2].split('|').map(String::trim).filter { x -> x.isNotEmpty() }.sorted(), it[3]) }
        .filter { File(source, "ocr/${it.file}.json").exists() }
    require(labels.isNotEmpty()) { "no OCR dumps under ${source.path}/ocr" }

    val resultsFile = File(source, "results.json")
    val device = resultsFile.takeIf { it.exists() }?.readLines()?.drop(1)?.filter { it.isNotBlank() }
        ?.map { json.parseToJsonElement(it) as JsonObject }?.associateBy { it["file"]!!.jsonPrimitive.content } ?: emptyMap()
    val deviceName = resultsFile.takeIf { it.exists() }?.readLines()?.firstOrNull()
        ?.let { Regex("\"device\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    fun names(c: Candidate?) = c?.items?.map { it.ingredient.name }?.distinct()?.sorted() ?: emptyList()
    val llmDir = File(source, "llm").apply { mkdirs() }
    val llmCosts = ArrayList<Double>()

    val readingList = kotlinx.serialization.builtins.ListSerializer(LlmReading.serializer())
    fun fallbackReading(l: Label, ocr: OcrResult): Pair<List<LlmReading>?, Long?> {
        val multi = File(llmDir, "${l.file}.readings.json")
        val f = File(llmDir, "${l.file}.json")
        if (multi.exists() || (f.exists() && llmUrl == null)) {
            File(llmDir, "${l.file}.cost").takeIf { it.exists() }?.readText()?.toDoubleOrNull()?.let { if (it > 0) llmCosts += it }
            return (if (multi.exists()) json.decodeFromString(readingList, multi.readText())
                    else listOf(json.decodeFromString(LlmReading.serializer(), f.readText()))) to null
        }
        if (llmUrl == null) return null to null
        val img = Base64.getEncoder().encodeToString(File(photos, l.file).readBytes())
        val toks = ocr.lines.flatMap { Normalize.nameTokens(it.text) }.distinct().take(80)
        val body = """{"image_jpeg_b64":"$img","ocr_tokens":${kotlinx.serialization.json.JsonArray(toks.map { kotlinx.serialization.json.JsonPrimitive(it) })},"db_version":"eval"}"""
        val t = System.currentTimeMillis()
        val conn = URI("$llmUrl/v1/resolve").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val resp = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()) as JsonObject
        val ms = System.currentTimeMillis() - t
        resp["cost_usd"]?.jsonPrimitive?.double?.let { if (it > 0) { llmCosts += it; File(llmDir, "${l.file}.cost").writeText(it.toString()) } }
        val rds = resp["readings"]?.let { json.decodeFromJsonElement(readingList, it) }?.ifEmpty { null }
            ?: resp["reading"]?.takeIf { it.toString() != "null" }?.let { listOf(json.decodeFromJsonElement(LlmReading.serializer(), it)) }
            ?: return null to ms
        multi.writeText(json.encodeToString(readingList, rds))
        return rds to ms
    }

    data class Sim(val l: Label, val path: String, val correct: Boolean, val wrong: Boolean, val ms: Long?, val predicted: String?, val evidence: String)
    val sims = labels.map { l ->
        val ocr = json.decodeFromString(OcrResult.serializer(), File(source, "ocr/${l.file}.json").readText())
        val r = resolver.resolve(ocr)
        val d = device[l.file]
        val onDeviceMs = d?.let { it["ocrMs"]!!.jsonPrimitive.long + it["matchMs"]!!.jsonPrimitive.long }
        fun ok(c: Candidate?) = c != null && names(c) == l.ingredients
        when {
            r.band == Band.ACCEPT -> Sim(l, "ON_DEVICE", ok(r.best), !ok(r.best), onDeviceMs, r.best?.product?.name, r.best!!.evidence.name)
            // simulated user on the confirm screen: picks the right one only if it is offered, else taps "None of these"
            r.band == Band.CONFIRM && r.candidates.take(3).any { ok(it) } ->
                Sim(l, "CONFIRMED", true, false, onDeviceMs, r.candidates.first { ok(it) }.product.name, "user")
            else -> {
                val (readings, apiMs) = fallbackReading(l, ocr)
                if (readings == null) Sim(l, if (llmUrl == null && !File(llmDir, "${l.file}.json").exists()) "NEEDS_API" else "API_NOT_FOUND",
                    l.negative, false, null, null, "-")
                else {
                    val r2 = resolver.resolveReadings(readings, ocr)
                    val pick = when (r2.band) {
                        Band.ACCEPT -> r2.best
                        Band.CONFIRM -> r2.candidates.take(3).firstOrNull { ok(it) }
                        else -> null
                    }
                    val correct = if (l.negative) pick == null || ok(pick) else ok(pick)
                    Sim(l, if (pick == null) "API_NOT_FOUND" else "API", correct, pick != null && !ok(pick),
                        onDeviceMs?.let { it + (apiMs ?: d["apiMs"]?.jsonPrimitive?.long ?: 0) }, pick?.product?.name, pick?.evidence?.name ?: "-")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- report
    fun pct(n: Int, d: Int) = if (d == 0) "–" else "%.1f%%".format(100.0 * n / d)
    fun pctl(xs: List<Long>, p: Double) = if (xs.isEmpty()) "n/a" else xs.sorted()[((xs.size - 1) * p).toInt()].toString() + " ms"
    // per-call cost: measured on this run's calls (sidecar files), else the backend's measured average (--per-call-cost)
    opt("--per-call-cost", null)?.toDoubleOrNull()?.let { if (llmCosts.isEmpty()) llmCosts += it }
    val perCall = (llmCosts + device.values.mapNotNull { it["costUsd"]?.jsonPrimitive?.double }.filter { it > 0 }).average().takeIf { !it.isNaN() }
    val sb = StringBuilder()
    sb.appendLine("# AMRit evaluation report").appendLine()
    sb.appendLine("Source: `${source.path}` · measured on: ${deviceName ?: "desktop replay (no phone timings)"}  ")
    sb.appendLine("Photos: ${sims.size} (${sims.groupingBy { it.l.condition }.eachCount()})  ")
    sb.appendLine("Decisions use the app's evidence rules (core/Resolver.kt): accept only on corroborated evidence, otherwise ask or fall back.").appendLine()
    sb.appendLine("Accuracy = right ingredients for products in the DB; for products not in the DB, saying not found or showing the right ingredients.  ")
    sb.appendLine("Wrong = the app would show another product's ingredients. A simulated user on the confirm screen picks the right option only if it is offered.").appendLine()
    sb.appendLine("| Set | n | Accuracy | Wrong | Zero-API | Hit API | Not resolved | p50 | p95 |")
    sb.appendLine("|---|---|---|---|---|---|---|---|---|")
    val summary = LinkedHashMap<String, String>()
    for ((name, set) in listOf("all" to sims, "clear" to sims.filter { it.l.condition in setOf("clear", "own") },
        "glare+blur" to sims.filter { it.l.condition in setOf("glare", "blur") }, "real strips" to sims.filter { it.l.condition == "own" },
        "not in DB" to sims.filter { it.l.negative })) {
        if (set.isEmpty()) continue
        val n = set.size
        val ms = set.mapNotNull { it.ms }
        val api = set.count { it.path.startsWith("API") || it.path == "NEEDS_API" }
        sb.appendLine("| $name | $n | ${pct(set.count { it.correct }, n)} | ${pct(set.count { it.wrong }, n)} | " +
            "${pct(set.count { it.path == "ON_DEVICE" || it.path == "CONFIRMED" }, n)} | ${pct(api, n)} | " +
            "${pct(set.count { it.path == "API_NOT_FOUND" || it.path == "NEEDS_API" }, n)} | ${pctl(ms, 0.5)} | ${pctl(ms, 0.95)} |")
        if (name == "all") {
            summary["Photos evaluated"] = "$n"
            summary["Brand→ingredient accuracy"] = pct(set.count { it.correct }, n)
            summary["Wrong answers"] = "${set.count { it.wrong }} (${pct(set.count { it.wrong }, n)})"
            summary["Zero API calls"] = pct(set.count { it.path == "ON_DEVICE" || it.path == "CONFIRMED" }, n)
            summary["Hit the API"] = pct(api, n)
            summary["Latency p50 / p95"] = "${pctl(ms, 0.5)} / ${pctl(ms, 0.95)}"
        }
        if (name == "not in DB") summary["Unknown products wrongly matched"] = "${set.count { it.wrong }}"
    }
    val calls = sims.count { it.path.startsWith("API") || it.path == "NEEDS_API" }
    val costPer100 = perCall?.let { it * calls * 100.0 / sims.size }
    sb.appendLine()
    sb.appendLine("API cost per 100 scans: " + (costPer100?.let { "$%.4f (%d of %d scans call the API × $%.6f measured per call)".format(it, calls, sims.size, perCall) }
        ?: "n/a (no measured cost yet; run with --call-llm)"))
    summary["API cost / 100 scans"] = costPer100?.let { "$%.4f".format(it) } ?: "n/a"
    summary["Measured on"] = deviceName ?: "desktop replay"
    sb.appendLine().appendLine("## Paths").appendLine()
    sims.groupingBy { it.path }.eachCount().forEach { (k, v) -> sb.appendLine("- $k: $v") }
    sb.appendLine().appendLine("## Wrong answers").appendLine()
    sims.filter { it.wrong }.forEach { sb.appendLine("- ${it.l.file}: expected ${it.l.product} (${it.l.ingredients}), got ${it.predicted} via ${it.path}/${it.evidence}") }
    if (sims.none { it.wrong }) sb.appendLine("None.")
    sb.appendLine().appendLine("## Per photo").appendLine()
    sb.appendLine("| file | cond | expected | path | predicted | evidence |").appendLine("|---|---|---|---|---|---|")
    sims.forEach { s ->
        sb.appendLine("| ${s.l.file} | ${s.l.condition} | ${s.l.product} | ${s.path}${if (s.wrong) " ❌" else if (s.correct) " ✓" else ""} | ${s.predicted ?: "–"} | ${s.evidence} |")
    }
    out.mkdirs()
    File(out, "report.md").writeText(sb.toString())
    val sumJson = "{\n" + summary.entries.joinToString(",\n") { "  \"${it.key}\": \"${it.value}\"" } + "\n}\n"
    File(out, "summary.json").writeText(sumJson)
    if (writeAssets) File("app/src/main/assets/eval_summary.json").writeText(sumJson)
    println(sb.lines().take(16).joinToString("\n"))
    println("-> ${File(out, "report.md").path}")
}

fun csv(line: String): List<String> {
    val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
    for (c in line) when {
        c == '"' -> q = !q
        c == ',' && !q -> { out += sb.toString(); sb.clear() }
        else -> sb.append(c)
    }
    out += sb.toString()
    return out
}
