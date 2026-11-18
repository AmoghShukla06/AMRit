package org.amrit.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Supported languages. Names stay in each language's own script; [tts] is the BCP-47 tag for Android TextToSpeech. */
enum class Lang(val code: String, val nativeName: String, val tts: String) {
    EN("en", "English", "en-IN"),
    HI("hi", "हिन्दी", "hi-IN"),
    BN("bn", "বাংলা", "bn-IN"),
    MR("mr", "मराठी", "mr-IN"),
    TE("te", "తెలుగు", "te-IN"),
    TA("ta", "தமிழ்", "ta-IN"),
    GU("gu", "ગુજરાતી", "gu-IN"),
    KN("kn", "ಕನ್ನಡ", "kn-IN"),
    ML("ml", "മലയാളം", "ml-IN"),
    PA("pa", "ਪੰਜਾਬੀ", "pa-IN");

    /** Everything except English is machine-translated at build time until a native speaker has reviewed it. */
    val reviewed: Boolean get() = this == EN

    /** English and Hindi are first-class (hand-written copy); the rest are machine-translated and labelled beta. */
    val beta: Boolean get() = this != EN && this != HI

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: EN
    }
}

/**
 * Fixed, reviewed-copy templates (core/src/main/resources/i18n/<code>.json) with {placeholders}.
 * Missing keys fall back to English, so a partially translated language never shows a blank.
 */
object I18n {
    private val cache = HashMap<Lang, Map<String, String>>()

    private fun load(lang: Lang): Map<String, String> = cache.getOrPut(lang) {
        val stream = I18n::class.java.getResourceAsStream("/i18n/${lang.code}.json") ?: return@getOrPut emptyMap()
        val obj = Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }) as JsonObject
        obj.mapValues { it.value.jsonPrimitive.content }
    }

    fun has(lang: Lang, key: String) = load(lang).containsKey(key)

    fun t(lang: Lang, key: String, vararg args: Pair<String, Any?>): String {
        var s = load(lang)[key] ?: load(Lang.EN)[key] ?: key
        for ((k, v) in args) s = s.replace("{$k}", v?.toString() ?: "")
        return s
    }
}
