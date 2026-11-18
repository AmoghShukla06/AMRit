package org.amrit.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amrit.app.R
import org.amrit.core.Lang

val Teal = Color(0xFF0E5C58)

/** sp arithmetic for the skin's type scale (TextUnit has no operators). */
operator fun TextUnit.plus(o: TextUnit): TextUnit = (value + o.value).sp
operator fun TextUnit.minus(o: TextUnit): TextUnit = (value - o.value).sp

@OptIn(ExperimentalTextApi::class)
private fun atkinson(w: Int) = Font(R.font.atkinson_next, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Atkinson Hyperlegible Next: drawn for low-vision readers (distinct 1/l/I, 0/O), which suits elderly users. */
@OptIn(ExperimentalTextApi::class)
val AtkinsonFamily = FontFamily(atkinson(400), atkinson(500), atkinson(600), atkinson(700), atkinson(800))
val MuktaFamily = FontFamily(
    Font(R.font.mukta_regular, FontWeight.Normal), Font(R.font.mukta_medium, FontWeight.Medium),
    Font(R.font.mukta_semibold, FontWeight.SemiBold), Font(R.font.mukta_bold, FontWeight.Bold),
    Font(R.font.mukta_extrabold, FontWeight.ExtraBold),
)

/** Dot-matrix face, used only for the batch / expiry stamp, the way it is inkjet-printed on a strip. */
@OptIn(ExperimentalTextApi::class)
val StampFamily = FontFamily(Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))))

/**
 * The app's look: a pharmacy counter, not a lifestyle app. Cool tablet-white paper, pharmacopoeia-navy ink, teal for
 * actions, and severity drawn as a vertical rule like the red line on Indian prescription packs. The result hero is the
 * strip itself: a foil surface with the batch and expiry in a dot-matrix stamp.
 */
@Immutable
data class Skin(
    val dark: Boolean,
    val font: FontFamily,
    // surfaces
    val bg: Color, val card: Color, val ink: Color, val muted: Color, val line: Color,
    // brand
    /** teal for text and links on bg/card: the button teal is too dark to read on dark-mode cards */
    val brandInk: Color,
    val brand: Color, val onBrand: Color, val brandSoft: Color, val onBrandSoft: Color, val brandMuted: Color,
    val accent: Color, val onAccent: Color, val inverse: Color, val onInverse: Color, val onInverseMuted: Color,
    // signals: (fg on soft bg)
    val danger: Color, val dangerBg: Color, val warn: Color, val warnBg: Color,
    val good: Color, val goodBg: Color, val info: Color, val infoBg: Color,
    /** severity rules (the pack's red line, and its amber / green / grey siblings) */
    val ruleDanger: Color, val ruleWarn: Color, val ruleGood: Color, val ruleInfo: Color,
    /** foil strip surface for the result hero */
    val foilTop: Color, val foilBottom: Color, val foilInk: Color, val foilMuted: Color,
    // scale
    val body: TextUnit, val small: TextUnit, val title: TextUnit, val display: TextUnit, val hero: TextUnit,
    val radius: Dp, val heroRadius: Dp, val button: Dp, val touch: Dp,
)

private val B_LIGHT = Skin(
    dark = false, font = AtkinsonFamily,
    bg = Color(0xFFF7F8F6), card = Color(0xFFFFFFFF), ink = Color(0xFF14232E), muted = Color(0xFF56636C), line = Color(0xFFE1E6E8),
    brandInk = Teal, brand = Teal, onBrand = Color.White, brandSoft = Color(0xFFE3F0EE), onBrandSoft = Color(0xFF0A4441), brandMuted = Color(0xFFBFE3DD),
    accent = Color(0xFFFFD27A), onAccent = Color(0xFF14232E), inverse = Color(0xFF14232E), onInverse = Color.White, onInverseMuted = Color(0xFFC3CBD1),
    danger = Color(0xFF9B1C14), dangerBg = Color(0xFFFCEBE9), warn = Color(0xFF6E4700), warnBg = Color(0xFFFFF4DB),
    good = Color(0xFF1C5F38), goodBg = Color(0xFFE4F3E9), info = Color(0xFF33434E), infoBg = Color(0xFFEEF1F2),
    ruleDanger = Color(0xFFD0312D), ruleWarn = Color(0xFFE59A00), ruleGood = Color(0xFF2E8B57), ruleInfo = Color(0xFF9AA7AF),
    foilTop = Color(0xFFEDF0F2), foilBottom = Color(0xFFC6CED3), foilInk = Color(0xFF14232E), foilMuted = Color(0xFF47545D),
    body = 16.sp, small = 14.sp, title = 19.sp, display = 28.sp, hero = 30.sp,
    radius = 20.dp, heroRadius = 28.dp, button = 56.dp, touch = 44.dp,
)

private val B_DARK = B_LIGHT.copy(
    dark = true,
    bg = Color(0xFF0F1418), card = Color(0xFF1A2127), ink = Color(0xFFE9EEF1), muted = Color(0xFF9AA6AE), line = Color(0xFF273038),
    brandInk = Color(0xFF7FD3C8), brand = Color(0xFF14736E), brandSoft = Color(0xFF143A37), onBrandSoft = Color(0xFFC7ECE6), brandMuted = Color(0xFF9FDCD3),
    inverse = Color(0xFFE9EEF1), onInverse = Color(0xFF14232E), onInverseMuted = Color(0xFF4F5B63),
    danger = Color(0xFFFFB4A8), dangerBg = Color(0xFF3A1714), warn = Color(0xFFFFD580), warnBg = Color(0xFF382A08),
    good = Color(0xFF9AE3C0), goodBg = Color(0xFF12301F), info = Color(0xFFD0D8DD), infoBg = Color(0xFF20282E),
    ruleDanger = Color(0xFFFF6F61), ruleWarn = Color(0xFFFFC14D), ruleGood = Color(0xFF5CC98A), ruleInfo = Color(0xFF66737B),
    foilTop = Color(0xFF3B454D), foilBottom = Color(0xFF242B31), foilInk = Color(0xFFEEF2F4), foilMuted = Color(0xFFB4BEC5),
)

/**
 * One look for every language; only the text changes. Devanagari, Gurmukhi etc. need a font with those glyphs, so
 * Hindi uses Mukta (which also has Latin, so brand names stay in the same face); other scripts fall back to the
 * system's Noto fonts.
 */
/** [large]: the "Larger text" setting, for older eyes: every text size +20%, taller buttons and touch targets. */
fun skinFor(lang: Lang, dark: Boolean, large: Boolean = false): Skin {
    var s = if (dark) B_DARK else B_LIGHT
    if (lang == Lang.HI || lang == Lang.MR) s = s.copy(font = MuktaFamily)
    if (large) s = s.copy(body = s.body * 1.2f, small = s.small * 1.2f, title = s.title * 1.2f, display = s.display * 1.2f,
        hero = s.hero * 1.2f, button = s.button + 8.dp, touch = s.touch + 8.dp)
    return s
}

val LocalSkin = staticCompositionLocalOf { B_LIGHT }

@Composable
fun AmritTheme(lang: Lang = Lang.EN, large: Boolean = false, content: @Composable () -> Unit) {
    val s = skinFor(lang, isSystemInDarkTheme(), large)
    val base = Typography()
    fun st(size: TextUnit, w: FontWeight, lh: Float = 1.3f) = TextStyle(fontFamily = s.font, fontSize = size, fontWeight = w, lineHeight = size * lh)
    val type = base.copy(
        displaySmall = st(s.display, FontWeight.ExtraBold, 1.15f), headlineSmall = st(s.hero, FontWeight.ExtraBold, 1.15f),
        titleLarge = st(s.title + 2.sp, FontWeight.ExtraBold), titleMedium = st(s.title, FontWeight.Bold), titleSmall = st(s.body, FontWeight.Bold),
        bodyLarge = st(s.body + 1.sp, FontWeight.Normal, 1.4f), bodyMedium = st(s.body, FontWeight.Normal, 1.4f), bodySmall = st(s.small, FontWeight.Normal, 1.35f),
        labelLarge = st(s.body, FontWeight.Bold), labelMedium = st(s.small, FontWeight.SemiBold), labelSmall = st(s.small - 1.sp, FontWeight.Medium),
    )
    val scheme = if (s.dark) darkColorScheme(
        primary = s.brand, onPrimary = s.onBrand, primaryContainer = s.brandSoft, onPrimaryContainer = s.onBrandSoft,
        secondary = s.brand, onSecondary = s.onBrand, secondaryContainer = s.brandSoft, onSecondaryContainer = s.onBrandSoft,
        background = s.bg, onBackground = s.ink, surface = s.bg, onSurface = s.ink, surfaceVariant = s.card, onSurfaceVariant = s.muted,
        surfaceContainerLowest = s.card, surfaceContainerLow = s.card, surfaceContainer = s.card, surfaceContainerHigh = s.card,
        surfaceContainerHighest = s.card, outline = s.muted, outlineVariant = s.line, error = s.danger,
    ) else lightColorScheme(
        primary = s.brand, onPrimary = s.onBrand, primaryContainer = s.brandSoft, onPrimaryContainer = s.onBrandSoft,
        secondary = s.brand, onSecondary = s.onBrand, secondaryContainer = s.brandSoft, onSecondaryContainer = s.onBrandSoft,
        background = s.bg, onBackground = s.ink, surface = s.bg, onSurface = s.ink, surfaceVariant = s.card, onSurfaceVariant = s.muted,
        surfaceContainerLowest = s.card, surfaceContainerLow = s.card, surfaceContainer = s.card, surfaceContainerHigh = s.card,
        surfaceContainerHighest = s.card, outline = s.muted, outlineVariant = s.line, error = s.danger,
    )
    CompositionLocalProvider(LocalSkin provides s) {
        MaterialTheme(colorScheme = scheme, typography = type,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(s.radius), large = RoundedCornerShape(s.heroRadius)),
            content = content)
    }
}
