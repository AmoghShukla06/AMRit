package org.amrit.app.scan

import android.graphics.Bitmap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.amrit.app.AppGraph
import org.amrit.app.data.CabinetEntity
import org.amrit.app.data.LearnedAliasEntity
import org.amrit.app.data.LlmCacheEntity
import org.amrit.app.data.PendingEventEntity
import org.amrit.app.data.ScanLogEntity
import org.amrit.app.data.ScanPath
import org.amrit.app.net.ApiClient
import kotlinx.coroutines.launch
import org.amrit.app.data.LearnedProductEntity
import org.amrit.core.Band
import org.amrit.core.STRIP_READ_ID
import org.amrit.core.BrandIndex
import org.amrit.core.Candidate
import org.amrit.core.LlmReading
import org.amrit.core.Normalize
import org.amrit.core.OcrResult
import org.amrit.core.PackCode
import org.amrit.core.Resolution
import org.amrit.core.RuleEngine
import org.amrit.core.ScanEvent
import java.security.MessageDigest

/**
 * The scan pipeline: OCR -> on-device match -> (only if unsure) user pick -> (only if still unsure) cache -> vision LLM.
 * Whatever text source wins, the same matcher and the same rules produce the result.
 */
class ScanService(private val g: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What the UI shows while a scan runs. */
    enum class Stage { READING, MATCHING, ASKING_AI, CHECKING }

    /** Set by the UI before a scan; called from the worker thread. */
    @Volatile var onStage: (Stage) -> Unit = {}

    /** State carried from a scan into the confirm screen / fallback. */
    class Pending(
        val bitmap: Bitmap,
        val ocr: OcrResult,
        val textBox: android.graphics.Rect?,
        val t0: Long,
        val ocrMs: Long,
        var matchMs: Long,
        var apiMs: Long = 0,
        var apiCost: Double = 0.0,
        /** expiry printed on the strip, from OCR or the vision reading */
        var expiry: java.time.YearMonth? = org.amrit.core.Expiry.fromOcr(ocr),
        /** the pack's QR/DataMatrix code, if one was read */
        val packCode: PackCode? = null,
        /** lines of [bitmap] itself with their boxes (the live pool mixes frames, so it can't be drawn on one) */
        val frameLines: List<org.amrit.core.OcrLine> = ocr.lines.filter { it.box != null },
    ) {
        /** batch number: from the pack's code when present (exact), otherwise from the printed text */
        var batch: String? = packCode?.batch ?: org.amrit.core.Batch.fromOcr(ocr)

        init { packCode?.expiry?.let { expiry = it } }
    }

    /** Whether the pack's code names the same medicine that was identified. */
    enum class QrCheck { NONE, MATCH, MISMATCH }

    /** A frozen frame (long side 1280) with its OCR lines and the role each played in the answer. */
    class Seen(val frame: Bitmap, val lines: List<org.amrit.core.OcrLine>, val roles: Map<Int, org.amrit.core.WhatWeRead.Role>)

    sealed class Outcome {
        data class Done(val report: RuleEngine.StripReport, val path: ScanPath, val confidence: Double, val totalMs: Long,
                        val expiry: java.time.YearMonth? = null, val batch: String? = null,
                        val qr: QrCheck = QrCheck.NONE, val qrBrand: String? = null,
                        /** CDSCO alerts (NSQ / spurious) for this batch of this product; empty when none or no batch read */
                        val batchAlerts: List<org.amrit.core.BatchAlert> = emptyList(),
                        /** the anonymous event queued for this scan (null for search/opened results) */
                        val event: org.amrit.core.ScanEvent? = null,
                        /** "What we read": the frozen frame and the lines that gave this answer (memory only, never saved) */
                        val seen: Seen? = null) : Outcome()
        data class Confirm(val pending: Pending, val candidates: List<Candidate>, val afterApi: Boolean) : Outcome()
        /** [ayush]: the pack says it is Ayurvedic/homoeopathic/Unani/Siddha, which AMRit does not check. */
        data class NotFound(val reason: String, val totalMs: Long, val ayush: Boolean = false) : Outcome()
    }

    suspend fun scan(bitmapRaw: Bitmap, rotation: Int = 0): Outcome {
        val t0 = System.nanoTime()
        onStage(Stage.READING)
        val bmp = OcrEngine.scaleTo(bitmapRaw, OcrEngine.OCR_LONG_SIDE, rotation)
        val ocr0 = g.ocr.run(bmp)
        val code = g.codes.read(bmp)
        val ocr = if (code?.hasNames == true) ocr0.copy(result = ocr0.result.copy(lines = ocr0.result.lines +
            code.asOcrLines(ocr0.result.lines.maxOfOrNull { it.height } ?: 100f))) else ocr0
        // diagnostics: what OCR read (local device log only; read with `adb logcat -s AMRit`)
        android.util.Log.i("AMRit", "OCR ${ocr.ms}ms: " + ocr.result.lines.joinToString(" | ") { "${it.text} [h=${it.height.toInt()}]" })
        onStage(Stage.MATCHING)
        val resolver = g.resolver()
        val res = resolver.resolve(ocr.result)
        android.util.Log.i("AMRit", "match ${res.matchMs}ms band=${res.band} " + res.candidates.joinToString { "${it.product.name} %.3f".format(it.score) } +
            " found=" + res.found.values.joinToString { "${it.ingredient.name} ${it.strength}${if (it.strengthConflict) "(conflict)" else ""}" })
        return decide(Pending(bmp, ocr.result, ocr.textBox, t0, ocr.ms, res.matchMs, packCode = code), res)
    }

    /**
     * A demo strip picked from the list: the same flow as a scan of its printed label (batch, expiry, rules), so
     * every feature shows. Demo results are never sent to the surveillance data (see [finish]).
     */
    suspend fun demo(strip: DemoMode.DemoStrip): Outcome {
        val c = g.demo.candidateFor(strip) ?: return Outcome.NotFound("demo strip not in the database", 0)
        val ocr = OcrResult(g.demo.linesFor(strip))
        val p = Pending(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), ocr, null, System.nanoTime(), 0, 0)
        return finish(p, c, ScanPath.DEMO, 1.0)
    }

    /** The live scanner already ran OCR (over several frames) and the matcher; continue with the same decision flow. */
    suspend fun fromLive(bmp: Bitmap, ocr: OcrResult, textBox: android.graphics.Rect?, res: Resolution, t0: Long, ocrMs: Long,
                         packCode: PackCode? = null, frameLines: List<org.amrit.core.OcrLine> = emptyList()): Outcome {
        capture(bmp, ocr)
        return decide(Pending(bmp, ocr, textBox, t0, ocrMs, res.matchMs, packCode = packCode, frameLines = frameLines), res)
    }

    private suspend fun decide(p: Pending, res: Resolution): Outcome {
        if (g.settings.demoMode) g.demo.match(p.ocr)?.let { return finish(p, it, ScanPath.DEMO, 1.0) }
        // an AYUSH pack that didn't match an allopathic product: say so, and don't spend a fallback call on it
        if (res.band != Band.ACCEPT && org.amrit.core.PackKind.isAyush(p.ocr)) return notFound(p, "ayush", ayush = true)
        return when (res.band) {
            Band.ACCEPT -> finish(p, res.best!!, ScanPath.ON_DEVICE, res.confidence)
            Band.CONFIRM -> Outcome.Confirm(p, res.candidates.take(3), afterApi = false)
            Band.FALLBACK -> fallback(p)
        }
    }

    /** User tapped one of the offered candidates (zero API cost). */
    suspend fun confirm(p: Pending, c: Candidate, afterApi: Boolean): Outcome {
        learnAlias(p.ocr, c.product.baseKey, "user")
        return finish(p, c, if (afterApi) ScanPath.API else ScanPath.CONFIRMED, c.score)
    }

    /** "None of these": go to the vision fallback, or give up if it already ran. */
    suspend fun rejectCandidates(p: Pending, afterApi: Boolean): Outcome =
        if (afterApi) notFound(p, "none of the candidates matched") else fallback(p)

    private fun signature(ocr: OcrResult): String {
        val toks = ocr.lines.flatMap { Normalize.nameTokens(it.text) }.map { BrandIndex.fold(it) }.filter { it.length >= 3 }
            .distinct().sorted().take(40)
        val d = MessageDigest.getInstance("SHA-1").digest(toks.joinToString(" ").toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    suspend fun fallback(p: Pending): Outcome {
        if (g.settings.demoMode) return notFound(p, "demo mode: fallback disabled")
        val sig = signature(p.ocr)
        var path = ScanPath.CACHE_LOCAL
        var readings: List<LlmReading> = g.dao.getLlm(sig)?.let {
            runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LlmReading.serializer()), it.readingJson) }.getOrNull()
        } ?: emptyList()
        if (readings.isEmpty()) {
            onStage(Stage.ASKING_AI)
            val t = System.nanoTime()
            val resp = try {
                g.api.resolve(OcrEngine.cropForFallback(p.bitmap, p.textBox),
                    p.ocr.lines.flatMap { Normalize.nameTokens(it.text) }.distinct(), g.dbVersion)
            } catch (e: Exception) {
                ApiClient.ResolveResponse(source = "error", error = e.message ?: "network error")
            }
            p.apiMs = (System.nanoTime() - t) / 1_000_000
            p.apiCost = resp.cost_usd
            readings = resp.readings.ifEmpty { listOfNotNull(resp.reading) }
            path = if (resp.source == "server_cache") ScanPath.CACHE_SERVER else ScanPath.API
            if (readings.isEmpty()) return notFound(p, resp.error ?: "fallback unavailable")
        }
        val reading = readings.first()
        if (p.expiry == null) p.expiry = readings.firstNotNullOfOrNull { r -> r.expiry?.let { org.amrit.core.Expiry.parse("EXP $it") } }
        if (readings.none { it.legible && (!it.brand.isNullOrBlank() || it.ingredients.isNotEmpty()) }) {
            return notFound(p, "strip not legible")
        }
        val t = System.nanoTime()
        val res: Resolution = g.resolver().resolveReadings(readings, p.ocr)
        p.matchMs += (System.nanoTime() - t) / 1_000_000
        // cache a reading on this phone only if it led to an answer (a bad reading must not stick)
        if (res.band == Band.ACCEPT) g.dao.putLlm(LlmCacheEntity(sig,
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LlmReading.serializer()), readings), System.currentTimeMillis()))
        return when (res.band) {
            Band.ACCEPT -> {
                learnAlias(p.ocr, res.best!!.product.baseKey, "llm")
                learnProduct(reading, res.best!!)
                finish(p, res.best!!, path, res.confidence)
            }
            Band.CONFIRM -> Outcome.Confirm(p, res.candidates.take(3), afterApi = true)
            Band.FALLBACK -> notFound(p, "the text read from the strip matches nothing in the medicine database")
        }
    }

    /**
     * The fallback read a brand that is not in the DB, and its printed composition mapped exactly: remember it on this
     * phone right away (the next scan of it needs no API call) and report it; after 2 agreeing reports from different
     * phones the server shares it with everyone.
     */
    private suspend fun learnProduct(reading: LlmReading, c: Candidate) {
        if (c.product.id != STRIP_READ_ID) return
        val brand = reading.brand?.trim()?.takeIf { it.length >= 3 } ?: return
        val baseKey = Normalize.brandKeys(brand).second.takeIf { it.replace(" ", "").length >= 4 } ?: return
        val items = c.items.joinToString(";") { "${it.ingredient.id}:${it.strength?.let { s -> "${s.value}${s.unit}" } ?: ""}" }
        val name = "$brand (${c.items.joinToString(" + ") { "${it.ingredient.name} ${it.strength ?: ""}".trim() }})"
        g.dao.putLearned(LearnedProductEntity(baseKey, name, c.product.form, items, System.currentTimeMillis()))
        g.registerLearned(baseKey, name, c.product.form, items)
        g.reportLearnedAsync(baseKey, name, c.product.form, items)
    }

    /** Capture mode (hidden, for building the real-strip test set): send the frame + what OCR read to the laptop. */
    private fun capture(bmp: Bitmap, ocr: OcrResult) {
        if (!g.settings.captureMode) return
        g.scope.launch {
            runCatching {
                val name = "cap_${System.currentTimeMillis()}"
                val jpeg = java.io.ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it); it.toByteArray() }
                g.api.upload("capture", "$name.jpg", jpeg)
                g.api.upload("capture", "$name.json", json.encodeToString(OcrResult.serializer(), ocr).toByteArray())
            }
        }
    }

    private suspend fun learnAlias(ocr: OcrResult, baseKey: String, source: String) {
        if (baseKey.isBlank()) return // result read from the printed composition: no brand to learn
        val phrase = g.resolver().aliasPhraseFor(ocr, baseKey) ?: return
        g.dao.putAlias(LearnedAliasEntity(phrase, baseKey, source, System.currentTimeMillis()))
        g.resolver().index.addAlias(phrase, baseKey)
        g.reportAliasAsync(phrase, baseKey)
    }

    private suspend fun notFound(p: Pending, reason: String, ayush: Boolean = false): Outcome {
        val total = (System.nanoTime() - p.t0) / 1_000_000
        g.dao.log(ScanLogEntity(ts = System.currentTimeMillis(), path = ScanPath.NOT_FOUND.name, totalMs = total, ocrMs = p.ocrMs,
            matchMs = p.matchMs, apiMs = p.apiMs, apiCostUsd = p.apiCost, confidence = 0.0, productName = null))
        return Outcome.NotFound(reason, total, ayush)
    }

    private suspend fun finish(p: Pending, c: Candidate, path: ScanPath, confidence: Double): Outcome {
        onStage(Stage.CHECKING)
        val report = g.rules.evaluate(c.product, c.items)
        val total = (System.nanoTime() - p.t0) / 1_000_000
        g.dao.log(ScanLogEntity(ts = System.currentTimeMillis(), path = path.name, totalMs = total, ocrMs = p.ocrMs,
            matchMs = p.matchMs, apiMs = p.apiMs, apiCostUsd = p.apiCost, confidence = confidence, productName = c.product.name))
        val ev = ScanEvent.from(report, g.settings.pincode)
        // demo strips are staged, not real purchases: they must never reach the AMR surveillance data
        if (path != ScanPath.DEMO) {
            g.dao.queue(PendingEventEntity(ev.event_id, json.encodeToString(ev), System.currentTimeMillis()))
            g.requestSync()
        }
        val qrBrand = p.packCode?.brand
        val qr = when {
            qrBrand == null || c.product.baseKey.isBlank() -> QrCheck.NONE
            sameBrand(qrBrand, c.product.baseKey) -> QrCheck.MATCH
            else -> QrCheck.MISMATCH
        }
        val alerts = batchAlertsFor(p.batch, c.product, c.items)
        return Outcome.Done(report, path, confidence, total, p.expiry, p.batch, qr, qrBrand, alerts, ev, seen(p, c))
    }

    /** The frame scaled to 1280 px (boxes with it), and which of its lines were the brand, ingredients, batch, expiry. */
    private suspend fun seen(p: Pending, c: Candidate): Seen? = runCatching {
        if (p.frameLines.isEmpty()) return null
        val k = minOf(1f, 1280f / maxOf(p.bitmap.width, p.bitmap.height))
        val frame = OcrEngine.scaleTo(p.bitmap, 1280)
        val lines = p.frameLines.map { l -> l.copy(box = l.box?.map { (it * k).toInt() }) }
        val roles = org.amrit.core.WhatWeRead.roles(lines, c.product.copy(items = c.items), g.resolver().composition)
        if (roles.isEmpty()) null else Seen(frame, lines, roles)
    }.getOrNull()

    /** "DOLO 650" in the code vs product base key "dolo": same brand if one folded key starts with the other. */
    private fun sameBrand(codeBrand: String, baseKey: String): Boolean {
        val a = BrandIndex.fold(Normalize.brandKeys(codeBrand).second)
        val b = BrandIndex.fold(baseKey)
        return a.isNotEmpty() && b.isNotEmpty() && (a.startsWith(b) || b.startsWith(a))
    }

    suspend fun addToCabinet(r: RuleEngine.StripReport, profileId: Long, expiry: java.time.YearMonth?, batch: String? = null,
                             alerts: List<org.amrit.core.BatchAlert> = emptyList()) {
        // a strip-read result has no DB row: store all its ingredients
        val extras = r.items.filter { r.product.id == org.amrit.core.STRIP_READ_ID || it.ingredient.id !in r.product.ingredientIds }
            .joinToString(";") { "${it.ingredient.id}:${it.strength ?: ""}" }
        g.dao.addToCabinet(CabinetEntity(productId = r.product.id, displayName = r.product.name, extraIngredients = extras,
            addedAt = System.currentTimeMillis(), profileId = profileId, expiry = expiry?.toString(), batch = batch,
            alert = alerts.firstOrNull()?.let { "${it.kind}|${it.month}" }))
    }

    /** A saved medicine's report, rebuilt from the reference DB (+ ingredients read from the strip). */
    fun cabinetReport(row: CabinetEntity): RuleEngine.StripReport? {
        val ex = row.extraIngredients.split(';').filter { it.contains(':') }.mapNotNull { e ->
            val (id, st) = e.split(':', limit = 2)
            g.ref.ingredients[id.toIntOrNull() ?: return@mapNotNull null]?.let { org.amrit.core.CompItem(it, Normalize.parseStrength(st)) }
        }
        val p = if (row.productId == STRIP_READ_ID) org.amrit.core.Product(row.productId, row.displayName, "", null, "tablet", null, null,
            false, -1, false, ex)
        else g.ref.productById(row.productId) ?: return null
        val items = if (row.productId == STRIP_READ_ID) ex else p.items + ex
        return g.rules.evaluate(p.copy(name = row.displayName), items)
    }

    /** CDSCO alerts for this batch of this product: shipped records plus those synced since. */
    suspend fun batchAlertsFor(batch: String?, product: org.amrit.core.Product, items: List<org.amrit.core.CompItem>): List<org.amrit.core.BatchAlert> {
        if (batch == null) return emptyList()
        val synced = g.dao.alertsFor(org.amrit.core.BatchAlerts.norm(batch)).map {
            org.amrit.core.BatchAlert(it.batch, it.product, it.brand, it.manufacturer, it.result,
                if (it.kind == "SPURIOUS") org.amrit.core.BatchAlert.Kind.SPURIOUS else org.amrit.core.BatchAlert.Kind.NSQ, it.reportedBy, it.month)
        }
        return runCatching { org.amrit.core.BatchAlerts.check(g.ref, batch, product, items, synced) }.getOrDefault(emptyList())
    }

    /** Saved medicines whose batch has appeared in an alert since they were saved; marks them so each is reported once. */
    suspend fun recheckCabinet(): List<Pair<CabinetEntity, org.amrit.core.BatchAlert>> {
        val out = ArrayList<Pair<CabinetEntity, org.amrit.core.BatchAlert>>()
        for (row in g.dao.cabinetToRecheck()) {
            val r = cabinetReport(row) ?: continue
            val a = batchAlertsFor(row.batch, r.product, r.items).firstOrNull() ?: continue
            g.dao.setCabinetAlert(row.id, "${a.kind}|${a.month}")
            out += row to a
        }
        return out
    }
}
