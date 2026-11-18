package org.amrit.app.scan

import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.amrit.app.AppGraph
import org.amrit.core.Band
import org.amrit.core.LlmReading
import org.amrit.core.Normalize
import org.amrit.core.OcrResult
import java.io.File

/**
 * On-device evaluation over labelled photos (eval/run_on_device.sh pushes them to
 * /sdcard/Android/data/org.amrit.app/files/eval/). Runs the real OCR + matcher on this phone, measures latency,
 * and (optionally) calls the real fallback. Writes OCR dumps + LLM readings so the JVM harness can re-tune the
 * threshold offline without re-running the phone or paying for the API again.
 * Does not queue events or touch "My medicines".
 */
class EvalRunner(private val g: AppGraph) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    @Serializable
    data class Row(
        val file: String, val condition: String, val expected: List<String>,
        val band: String, val path: String, val predicted: List<String>, val predictedName: String?,
        val confidence: Double, val ocrMs: Long, val matchMs: Long, val apiMs: Long, val totalMs: Long, val costUsd: Double,
        val apiSource: String? = null,
    )

    fun dir(): File = File(g.app.filesDir, "eval")

    private val http = okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun get(url: String): ByteArray = http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
        require(it.isSuccessful) { "GET $url -> ${it.code}" }
        it.body!!.bytes()
    }

    private fun upload(kind: String, f: File) {
        val body = okhttp3.RequestBody.create(null, f.readBytes())
        val req = okhttp3.Request.Builder().url("${g.settings.backendUrl}/eval/upload/$kind/${f.name}").post(body).build()
        http.newCall(req).execute().close()
    }

    /** Photos + labels come from the laptop backend (dev-only /eval endpoints over `adb reverse`), because
     *  Android 17 hides files that adb pushes into app storage. Results go back the same way. */
    private fun download(d: File, progress: (String) -> Unit) {
        d.mkdirs()
        val base = g.settings.backendUrl
        val labels = String(get("$base/eval/labels.csv"))
        File(d, "labels.csv").writeText(labels)
        val files = labels.lines().drop(1).filter { it.isNotBlank() }.map { parseCsv(it)[0] }
        files.forEachIndexed { i, f ->
            val dst = File(d, f)
            if (!dst.exists()) { progress("downloading ${i + 1}/${files.size}"); dst.writeBytes(get("$base/eval/photo/$f")) }
        }
    }

    suspend fun run(useApi: Boolean, progress: (String) -> Unit): File {
        val d = dir()
        runCatching { download(d, progress) }.onFailure { progress("download failed (${it.message}); using local files") }
        File(d, "ocr").deleteRecursively(); File(d, "llm").deleteRecursively()
        val labels = File(d, "labels.csv").readLines().drop(1).map { parseCsv(it) }.filter { it.size >= 4 }
        File(d, "ocr").mkdirs(); File(d, "llm").mkdirs()
        val resolver = g.resolver()
        val rows = ArrayList<Row>()
        for ((i, l) in labels.withIndex()) {
            val (file, _, ingr, cond) = l
            val f = File(d, file)
            if (!f.exists()) continue
            progress("${i + 1}/${labels.size} $file")
            val expected = ingr.split('|').map { it.trim() }.sorted()
            val t0 = System.nanoTime()
            val bmp = OcrEngine.scaleTo(BitmapFactory.decodeFile(f.path), OcrEngine.OCR_LONG_SIDE)
            val ocr = g.ocr.run(bmp)
            File(d, "ocr/$file.json").writeText(json.encodeToString(OcrResult.serializer(), ocr.result))
            val res = resolver.resolve(ocr.result)
            fun names(c: org.amrit.core.Candidate?) = c?.items?.map { it.ingredient.name }?.distinct()?.sorted() ?: emptyList()
            var band = res.band.name
            var path: String
            var pred = emptyList<String>()
            var name: String? = null
            var conf = res.confidence
            var apiMs = 0L; var cost = 0.0; var src: String? = null
            when (res.band) {
                Band.ACCEPT -> { path = "ON_DEVICE"; pred = names(res.best); name = res.best?.product?.name }
                else -> {
                    // simulated user on the confirm screen: picks the right candidate only if it is offered
                    val offered = if (res.band == Band.CONFIRM) res.candidates.take(3).firstOrNull { names(it) == expected } else null
                    if (offered != null) { path = "CONFIRMED"; pred = names(offered); name = offered.product.name }
                    else if (!useApi) { path = "NEEDS_API" }
                    else {
                        val t = System.nanoTime()
                        val resp = runCatching {
                            g.api.resolve(OcrEngine.cropForFallback(bmp, ocr.textBox), ocr.result.lines.flatMap { Normalize.nameTokens(it.text) }.distinct(), g.dbVersion)
                        }.getOrNull()
                        apiMs = (System.nanoTime() - t) / 1_000_000
                        cost = resp?.cost_usd ?: 0.0; src = resp?.source
                        val readings: List<LlmReading> = resp?.readings?.ifEmpty { listOfNotNull(resp.reading) } ?: emptyList()
                        readings.firstOrNull()?.let { File(d, "llm/$file.json").writeText(json.encodeToString(LlmReading.serializer(), it)) }
                        val r2 = readings.takeIf { it.isNotEmpty() }?.let { resolver.resolveReadings(it, ocr.result) }
                        band = r2?.band?.name ?: "ERROR"
                        conf = r2?.confidence ?: 0.0
                        val pick = when (r2?.band) {
                            Band.ACCEPT -> r2.best
                            Band.CONFIRM -> r2.candidates.take(3).firstOrNull { names(it) == expected }
                            else -> null
                        }
                        path = if (pick != null) "API" else "NOT_FOUND"
                        pred = names(pick); name = pick?.product?.name
                    }
                }
            }
            val total = (System.nanoTime() - t0) / 1_000_000
            rows += Row(file, cond, expected, band, path, pred, name, conf, ocr.ms, res.matchMs, apiMs, total, cost, src)
        }
        val out = File(d, "results.json")
        out.writeText(json.encodeToString(mapOf("device" to android.os.Build.MODEL, "index_build_ms" to g.indexBuildMs.toString())) + "\n" +
            rows.joinToString("\n") { json.encodeToString(it) })
        progress("uploading results…")
        runCatching {
            File(d, "ocr").listFiles()?.forEach { upload("ocr", it) }
            File(d, "llm").listFiles()?.forEach { upload("llm", it) }
            upload("root", out) // last: its arrival tells the laptop script the run is complete
        }.onFailure { progress("upload failed: ${it.message}") }
        progress("done: ${rows.size} photos -> ${out.path}")
        return out
    }

    private fun parseCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (c in line) when {
            c == '"' -> q = !q
            c == ',' && !q -> { out += sb.toString(); sb.clear() }
            else -> sb.append(c)
        }
        out += sb.toString()
        return out
    }
}
