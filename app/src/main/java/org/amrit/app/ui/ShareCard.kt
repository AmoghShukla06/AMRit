package org.amrit.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import org.amrit.app.R
import org.amrit.core.Lang
import org.amrit.core.RefData
import org.amrit.core.RuleEngine
import org.amrit.core.Texts

/**
 * The result as a picture, for WhatsApp family groups where a forwarded image travels further than text. Drawn
 * with the app's own look (foil hero, severity rules) in the reader's language. It shows only the medicine and
 * its checks: no photo of the strip, no name, nothing about the person.
 */
object ShareCard {
    private const val W = 1080
    private const val PAD = 64f
    private const val INNER = 48f

    /**
     * What a card shows: [header] line, a foil hero ([heroName] small, [heroHead] big, [heroBody], [stamp]), the signal
     * [lines] (danger / warning first, then checks), an optional Jan Aushadhi [generic], and the disclaimer.
     */
    data class Card(val header: String, val heroName: String?, val heroHead: String?, val heroBody: String?, val stamp: String?,
                    val lines: List<Texts.Line>, val generic: Texts.Generic? = null)

    /** A scan result as a card. */
    fun render(ctx: Context, r: RuleEngine.StripReport, ref: RefData, lang: Lang, extra: List<Texts.Line>, expiry: String?): Bitmap =
        renderCard(ctx, lang, Card(Texts.t(lang, "res.shareHeader"), r.product.name, Texts.headline(r, ref, lang), Texts.contains(r, lang),
            expiry?.let { "EXP $it" },
            (extra + Texts.issues(r, lang)).filter { it.kind != Texts.Kind.INFO } + Texts.checks(r, lang, false),
            r.generic?.let { Texts.generic(it, r.product.form, lang) }))

    fun renderCard(ctx: Context, lang: Lang, card: Card): Bitmap {
        val s = skinFor(lang, dark = false)
        val f = Fonts(ctx, lang)
        // pass 1 measures, pass 2 draws onto a bitmap of exactly that height
        val h = draw(null, s, f, lang, card)
        return Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), s, f, lang, card) }
    }

    /** Share [bmp] as a PNG (via the app's FileProvider) with [text] as caption; text only if writing the file fails. */
    fun shareIntent(ctx: Context, bmp: Bitmap?, text: String): android.content.Intent {
        val uri = bmp?.let { b -> runCatching {
            val file = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }.resolve("amrit_share.png")
            file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
        }.onFailure { android.util.Log.w("AMRit", "share card failed, sending text only", it) }.getOrNull() }
        return android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            if (uri != null) {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(null, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else type = "text/plain"
        }, null)
    }

    private class Fonts(ctx: Context, lang: Lang) {
        private val indic = lang == Lang.HI || lang == Lang.MR
        private val atk = runCatching { ResourcesCompat.getFont(ctx, R.font.atkinson_next) }.getOrNull() ?: Typeface.DEFAULT
        val regular: Typeface = if (indic) font(ctx, R.font.mukta_regular) else atk
        val bold: Typeface = if (indic) font(ctx, R.font.mukta_bold) else atk
        /** Atkinson is a variable font: weight comes from its axis, not a separate file */
        val boldAxis: String? = if (indic) null else "'wght' 750"
        private fun font(ctx: Context, id: Int) = runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull() ?: Typeface.DEFAULT
    }

    private fun paint(f: Fonts, size: Float, color: Color, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (bold) f.bold else f.regular
        if (bold) f.boldAxis?.let { fontVariationSettings = it }
        textSize = size; this.color = color.toArgb()
    }

    private fun layout(text: String, p: TextPaint, width: Float, spacing: Float = 1.18f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing).setIncludePad(false).build()

    /** Draws when [c] is non-null; always returns the total height. */
    private fun draw(c: Canvas?, s: Skin, f: Fonts, lang: Lang, card: Card): Int {
        c?.drawColor(s.bg.toArgb())
        var y = PAD
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(l: StaticLayout, x: Float) { c?.let { it.save(); it.translate(x, y); l.draw(it); it.restore() }; y += l.height }

        // header: the app's name, so people know where the card came from
        text(layout(card.header, paint(f, 34f, s.brand, true), W - 2 * PAD), PAD)
        y += 28f

        // foil hero: product, what it's for, ingredients, expiry stamp
        val heroTop = y; val tw = W - 2 * PAD - 2 * INNER
        val name = card.heroName?.let { layout(it, paint(f, 40f, s.foilMuted, true), tw) }
        val head = card.heroHead?.let { layout(it, paint(f, 74f, s.foilInk, true), tw, if (lang == Lang.EN) 1.05f else 0.92f) }
        val has = card.heroBody?.let { layout(it, paint(f, 36f, s.foilInk, false), tw) }
        val exp = card.stamp?.let { layout(it, paint(f, 34f, s.foilMuted, true), tw) }
        val heroH = INNER + (name?.height ?: 0) + (head?.let { 16f + it.height } ?: 0f) + (has?.let { 22f + it.height } ?: 0f) +
            (exp?.let { 18f + it.height } ?: 0f) + INNER
        c?.let {
            fill.shader = LinearGradient(PAD, heroTop, W - PAD, heroTop + heroH, s.foilTop.toArgb(), s.foilBottom.toArgb(), Shader.TileMode.CLAMP)
            it.drawRoundRect(RectF(PAD, heroTop, W - PAD, heroTop + heroH), 56f, 56f, fill); fill.shader = null
        }
        y += INNER; name?.let { text(it, PAD + INNER) }
        head?.let { y += 16f; text(it, PAD + INNER) }
        has?.let { y += 22f; text(it, PAD + INNER) }
        exp?.let { y += 18f; text(it, PAD + INNER) }
        y = heroTop + heroH + 32f

        // signals first (danger / warning), then the clean checks: same severity rule as the result screen
        for (line in card.lines) {
            val (rule, ink) = when (line.kind) {
                Texts.Kind.DANGER -> s.ruleDanger to s.danger
                Texts.Kind.WARNING -> s.ruleWarn to s.warn
                Texts.Kind.GOOD -> s.ruleGood to s.ink
                Texts.Kind.INFO -> s.ruleInfo to s.ink
            }
            val bg = when (line.kind) { Texts.Kind.DANGER -> s.dangerBg; Texts.Kind.WARNING -> s.warnBg; else -> s.card }
            val w = W - 2 * PAD - INNER - 36f
            val title = layout((if (line.kind == Texts.Kind.GOOD) "✓  " else "") + line.title, paint(f, 40f, ink, true), w)
            val body = line.body?.takeIf { line.kind != Texts.Kind.GOOD }?.let { layout(it, paint(f, 33f, ink, false), w) }
            val src = line.source?.let { layout(it, paint(f, 28f, if (line.kind == Texts.Kind.GOOD) s.muted else ink, line.kind != Texts.Kind.GOOD), w) }
            val top = y; val ch = 36f + title.height + (body?.let { 10f + it.height } ?: 0f) + (src?.let { 8f + it.height } ?: 0f) + 36f
            c?.let {
                fill.color = bg.toArgb(); it.drawRoundRect(RectF(PAD, top, W - PAD, top + ch), 40f, 40f, fill)
                fill.color = rule.toArgb(); it.save(); it.clipRect(PAD, top, PAD + 12f, top + ch)
                it.drawRoundRect(RectF(PAD, top, W - PAD, top + ch), 40f, 40f, fill); it.restore()
            }
            y += 36f; text(title, PAD + INNER)
            body?.let { y += 10f; text(it, PAD + INNER) }
            src?.let { y += 8f; text(it, PAD + INNER) }
            y = top + ch + 20f
        }

        // the cheaper generic, on navy like the in-app card
        card.generic?.let { g ->
            val w = W - 2 * PAD - 2 * INNER
            val t1 = layout(g.title, paint(f, 32f, s.accent, true), w)
            val t2 = layout(g.name, paint(f, 42f, s.onInverse, true), w)
            val t3 = layout(listOfNotNull(g.less, g.perUnit).joinToString(" · "), paint(f, 32f, s.onInverseMuted, false), w)
            val top = y + 12f; val ch = INNER + t1.height + 10f + t2.height + 12f + t3.height + INNER
            c?.let { fill.color = s.inverse.toArgb(); it.drawRoundRect(RectF(PAD, top, W - PAD, top + ch), 48f, 48f, fill) }
            y = top + INNER; text(t1, PAD + INNER); y += 10f; text(t2, PAD + INNER); y += 12f; text(t3, PAD + INNER)
            y = top + ch + 20f
        }

        y += 16f
        text(layout(Texts.disclaimer(lang), paint(f, 28f, s.muted, false), W - 2 * PAD), PAD)
        return (y + PAD).toInt()
    }
}
