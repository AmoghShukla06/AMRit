package org.amrit.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import org.amrit.core.Lang
import java.util.Locale

/**
 * Android's built-in TTS engine, per language. Works offline only if that language's voice is installed.
 * Text spoken before the engine is ready is kept and spoken once it is.
 */
class Tts(ctx: Context) {
    private var ready = false
    private var pending: Pair<String, Lang>? = null
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) pending?.let { (t, l) -> pending = null; say(t, l) }
    }

    /** Returns false if the language's voice is not installed (English is used instead, and the caller shows a hint). */
    fun speak(text: String, lang: Lang): Boolean {
        val t = forSpeech(text)
        if (!ready) { pending = t to lang; return true }
        return say(t, lang)
    }

    private fun say(text: String, lang: Lang): Boolean {
        val loc = Locale.forLanguageTag(lang.tts)
        val ok = tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE
        tts.language = if (ok) loc else Locale("en", "IN")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "amrit")
        return ok
    }

    fun stop() { pending = null; if (ready) tts.stop() }
    fun shutdown() = tts.shutdown()

    companion object {
        /** Acronyms that are meant to be spelled out. */
        private val keepCaps = setOf("WHO", "CDSCO", "AMR", "MRP", "DDINTER", "EXP", "IP", "USP", "BP", "SR", "ER", "XR", "CR", "DS", "MR")

        /**
         * Strip brands are printed in capitals ("BECOSULES Z"); voices spell ALL-CAPS words letter by letter.
         * Normal case makes them read the brand as a word.
         */
        fun forSpeech(s: String): String = Regex("""\b[A-Z][A-Z0-9\-]{2,}\b""").replace(s) { m ->
            if (m.value in keepCaps) m.value else m.value.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}
