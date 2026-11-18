package org.amrit.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.amrit.core.LlmReading
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Talks only to our own backend proxy. The OpenRouter key never ships in the app.
 * No identifiers are sent: no account, no device id. The image is a cropped, downscaled text region and the
 * backend does not store it.
 */
class ApiClient(private val baseUrl: () -> String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    @Serializable
    data class ResolveRequest(val image_jpeg_b64: String, val ocr_tokens: List<String>, val db_version: String)

    @Serializable
    data class ResolveResponse(
        val reading: LlmReading? = null,
        val readings: List<LlmReading> = emptyList(),
        /** "server_cache" | "llm" | "error" */
        val source: String = "error",
        val cost_usd: Double = 0.0,
        val error: String? = null,
    )

    /**
     * Free hosting puts the backend to sleep when idle and takes up to a minute to wake it. Called when the app
     * comes to the foreground, so the server is usually awake before a scan needs the fallback. Never throws.
     */
    fun wake() {
        runCatching {
            http.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(95, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("${baseUrl()}/health").build()).execute().close()
        }
    }

    fun resolve(jpeg: ByteArray, ocrTokens: List<String>, dbVersion: String): ResolveResponse {
        val body = json.encodeToString(ResolveRequest(Base64.getEncoder().encodeToString(jpeg), ocrTokens.take(80), dbVersion))
        val req = Request.Builder().url("${baseUrl()}/v1/resolve").post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) return ResolveResponse(source = "error", error = "HTTP ${r.code}: ${text.take(200)}")
            return json.decodeFromString(ResolveResponse.serializer(), text)
        }
    }

    /** Upload queued events. Returns the ids the server accepted (duplicates count as accepted). */
    fun postEvents(eventsJson: List<String>): Boolean {
        val body = "[" + eventsJson.joinToString(",") + "]"
        val req = Request.Builder().url("${baseUrl()}/v1/events").post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    @Serializable data class AliasReport(val phrase: String, val base_key: String, val db_version: String)
    @Serializable data class Alias(val phrase: String, val base_key: String)
    @Serializable data class AliasList(val aliases: List<Alias> = emptyList(), val now: Long = 0)

    fun reportAlias(a: AliasReport) {
        val req = Request.Builder().url("${baseUrl()}/v1/aliases").post(json.encodeToString(a).toRequestBody(JSON)).build()
        http.newCall(req).execute().close()
    }

    @Serializable data class LearnedReport(val brand_key: String, val display_name: String, val form: String, val items: String, val db_version: String)
    @Serializable data class LearnedItem(val brand_key: String, val display_name: String, val form: String, val items: String)
    @Serializable data class LearnedList(val products: List<LearnedItem> = emptyList(), val now: Long = 0)

    fun reportLearned(r: LearnedReport) {
        val req = Request.Builder().url("${baseUrl()}/v1/learned").post(json.encodeToString(r).toRequestBody(JSON)).build()
        http.newCall(req).execute().close()
    }

    fun fetchLearned(since: Long, dbVersion: String): LearnedList {
        val req = Request.Builder().url("${baseUrl()}/v1/learned?since=$since&db_version=$dbVersion").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return LearnedList()
            return json.decodeFromString(LearnedList.serializer(), r.body!!.string())
        }
    }

    @Serializable data class AlertItem(val id: Long, val batch_norm: String, val batch: String, val product: String, val brand: String? = null,
                                       val manufacturer: String? = null, val result: String? = null, val kind: String,
                                       val reported_by: String? = null, val month: String)
    @Serializable data class AlertList(val alerts: List<AlertItem> = emptyList(), val last_id: Long = 0)

    /** CDSCO alert records newer than [sinceId]: the whole list, so no batch numbers ever leave the phone. */
    /** The public AMR summary as raw JSON (parsed by core `Dashboard`); null when unreachable. */
    fun dashboardJson(): String? = runCatching {
        http.newCall(Request.Builder().url("${baseUrl()}/v1/dashboard").get().build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    fun fetchBatchAlerts(sinceId: Long): AlertList {
        val req = Request.Builder().url("${baseUrl()}/v1/batch-alerts?since=$sinceId").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return AlertList(last_id = sinceId)
            return json.decodeFromString(AlertList.serializer(), r.body!!.string())
        }
    }

    /** Dev-only (capture mode / eval): send a file to the laptop backend's /eval/upload. */
    fun upload(kind: String, name: String, bytes: ByteArray) {
        val req = Request.Builder().url("${baseUrl()}/eval/upload/$kind/$name").post(bytes.toRequestBody(null)).build()
        http.newCall(req).execute().close()
    }

    fun fetchAliases(since: Long, dbVersion: String): AliasList {
        val req = Request.Builder().url("${baseUrl()}/v1/aliases?since=$since&db_version=$dbVersion").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return AliasList()
            return json.decodeFromString(AliasList.serializer(), r.body!!.string())
        }
    }
}
