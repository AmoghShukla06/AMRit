package org.amrit.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import org.amrit.core.Aware
import org.amrit.core.CompItem
import org.amrit.core.Lang
import org.amrit.core.Texts

val sk: Skin @Composable @ReadOnlyComposable get() = LocalSkin.current

/** Text in the current skin's font, with sensible line height. */
@Composable
fun T(
    text: String, size: TextUnit = sk.body, weight: FontWeight = FontWeight.Normal, color: Color = sk.ink,
    modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null, lineHeight: Float = 1.35f,
) = Text(text, modifier, color = color, fontSize = size, fontWeight = weight, fontFamily = sk.font, lineHeight = size * lineHeight,
    maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)

@Composable
fun kindColors(k: Texts.Kind): Pair<Color, Color> {
    val s = sk
    return when (k) {
        Texts.Kind.DANGER -> s.danger to s.dangerBg
        Texts.Kind.WARNING -> s.warn to s.warnBg
        Texts.Kind.GOOD -> s.good to s.goodBg
        Texts.Kind.INFO -> s.info to s.infoBg
    }
}

fun kindIcon(k: Texts.Kind): ImageVector = when (k) {
    Texts.Kind.DANGER, Texts.Kind.WARNING -> AmIcons.Alert
    Texts.Kind.GOOD -> AmIcons.Check
    Texts.Kind.INFO -> AmIcons.Info
}

// ------------------------------------------------------------------------------------------------ buttons

@Composable
fun RoundIcon(
    icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    bg: Color = sk.card, fg: Color = sk.ink, size: Dp = sk.touch, iconSize: Dp = 20.dp, enabled: Boolean = true,
) {
    Box(modifier.size(size).clip(CircleShape).background(bg)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = desc, onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    bg: Color = sk.brand, fg: Color = sk.onBrand, icon: ImageVector? = null,
) {
    val s = sk
    val shape = RoundedCornerShape(s.button / 2)
    // min height, not fixed: at the largest font sizes a label wraps to two lines instead of being cut off
    Row(modifier.heightIn(min = s.button).clip(shape).background(if (enabled) bg else s.line)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val c = if (enabled) fg else s.muted
        if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        T(text, s.body, FontWeight.ExtraBold, c, maxLines = 2, align = TextAlign.Center, lineHeight = 1.15f)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val s = sk
    val shape = RoundedCornerShape(s.button / 2)
    Row(modifier.heightIn(min = s.button - 4.dp).clip(shape).border(2.dp, s.line, shape).background(s.card)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = s.ink, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        T(text, s.body - 1.sp, FontWeight.Bold, maxLines = 2, align = TextAlign.Center, lineHeight = 1.15f)
    }
}

/** Soft, low-emphasis button (e.g. "Took today's dose"). */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val s = sk
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
        .background(if (enabled) s.brandSoft else s.line).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        T(text, s.small, FontWeight.Bold, if (enabled) s.onBrandSoft else s.muted, maxLines = 2, align = TextAlign.Center)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = sk.brandInk) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        T(text, sk.small + 1.sp, FontWeight.Bold, color, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------------ structure

/** Screen header below the status bar: round back button, title, actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Header(
    title: String?, subtitle: String? = null, onBack: (() -> Unit)? = null, backLabel: String = "Back",
    onTitleLongPress: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
) {
    val s = sk
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack != null) 12.dp else 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundIcon(AmIcons.Back, backLabel, onBack)
            Spacer(Modifier.width(12.dp))
        }
        // only a long-press target when there is one: a disabled clickable would still be an unlabelled TalkBack stop
        Column(Modifier.weight(1f).then(if (onTitleLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = onTitleLongPress) else Modifier)) {
            if (title != null) T(title, s.title + 3.sp, FontWeight.ExtraBold, maxLines = 2, lineHeight = 1.15f,
                modifier = Modifier.semantics { heading() })
            if (subtitle != null) T(subtitle, s.small - 1.sp, color = s.muted, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, 18.sp, FontWeight.ExtraBold, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** Sticky area above the navigation bar for a screen's main action. */
@Composable
fun BottomActions(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(sk.bg).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun CardBox(modifier: Modifier = Modifier, bg: Color = sk.card, border: Boolean = false, onClick: (() -> Unit)? = null,
            content: @Composable ColumnScope.() -> Unit) {
    val s = sk
    val shape = RoundedCornerShape(s.radius)
    var m = modifier.fillMaxWidth().clip(shape).background(bg)
    if (border) m = m.border(2.dp, s.line, shape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

// ------------------------------------------------------------------------------------------------ cards

@Composable
fun ruleColor(k: Texts.Kind): Color = when (k) {
    Texts.Kind.DANGER -> sk.ruleDanger
    Texts.Kind.WARNING -> sk.ruleWarn
    Texts.Kind.GOOD -> sk.ruleGood
    Texts.Kind.INFO -> sk.ruleInfo
}

/**
 * One finding with its explanation and source. Severity is a vertical rule down the left edge, after the red line
 * printed on Indian prescription packs: red for serious, amber for caution, green for fine, grey for information.
 * Serious and caution findings also sit on a tinted ground so they can't be skimmed past. [onListen]: listen button.
 */
@Composable
fun SignalCard(line: Texts.Line, modifier: Modifier = Modifier, onListen: (() -> Unit)? = null,
               listenLabel: String = "Listen", onClick: (() -> Unit)? = null) {
    val s = sk
    val (fg, tint) = kindColors(line.kind)
    val urgent = line.kind == Texts.Kind.DANGER || line.kind == Texts.Kind.WARNING
    val text = if (urgent) fg else s.ink
    val shape = RoundedCornerShape(16.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(if (urgent) tint else s.card)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(ruleColor(line.kind)))
        Row(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(line.title, if (urgent) 18.sp else 16.sp, if (urgent) FontWeight.ExtraBold else FontWeight.Bold, text, lineHeight = 1.25f)
                line.body?.let { T(it, 15.sp, color = if (urgent) fg else s.muted, lineHeight = 1.45f) }
                line.source?.let { T(it, 13.sp, FontWeight.SemiBold, (if (urgent) fg else s.muted).copy(alpha = 0.75f)) }
            }
            if (onListen != null) {
                Spacer(Modifier.width(12.dp))
                RoundIcon(AmIcons.Speaker, listenLabel, onListen, bg = if (urgent) fg else s.brandSoft, fg = if (urgent) tint else s.onBrandSoft,
                    size = 48.dp, iconSize = 22.dp)
            }
        }
    }
}

/** Checks that passed, as one quiet list: a tick, what was checked, and the source underneath. */
@Composable
fun CheckTiles(lines: List<Texts.Line>) {
    if (lines.isEmpty()) return
    val s = sk
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(s.card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEach { l ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(AmIcons.Check, null, tint = s.ruleGood, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    T(l.title, 16.sp, FontWeight.Bold, lineHeight = 1.25f)
                    l.source?.let { T(it, 13.sp, color = s.muted) }
                }
            }
        }
    }
}

/**
 * The scanned strip, decoded: a foil surface carrying the product name, what it's for, the composition as printed,
 * and the batch / expiry in a dot-matrix stamp. The one decorative element in the app; everything else stays plain.
 */
@Composable
fun FoilHero(name: String, headline: String, composition: @Composable () -> Unit, batch: String?, expiry: String?) {
    val s = sk
    val shape = RoundedCornerShape(s.heroRadius)
    Box(Modifier.fillMaxWidth().clip(shape)
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(s.foilTop, s.foilBottom, s.foilTop),
            start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(1400f, 900f)))
        .border(1.dp, s.foilBottom, shape)) {
        // perforation between strip pockets: a row of small holes along the top edge
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            repeat(14) { Box(Modifier.size(4.dp).clip(CircleShape).background(s.foilBottom)) }
        }
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T(name, 15.sp, FontWeight.Bold, s.foilMuted, maxLines = 2)
            T(headline, s.hero, FontWeight.ExtraBold, s.foilInk, lineHeight = 1.12f)
            composition()
            if (batch != null || expiry != null) {
                Spacer(Modifier.height(2.dp))
                Column {
                    batch?.let { Text("B.NO. $it", color = s.foilInk.copy(alpha = 0.82f), fontFamily = StampFamily, fontSize = 17.sp, letterSpacing = 1.sp) }
                    expiry?.let { Text("EXP. $it", color = s.foilInk.copy(alpha = 0.82f), fontFamily = StampFamily, fontSize = 17.sp, letterSpacing = 1.sp) }
                }
            }
        }
    }
}

/** Jan Aushadhi generic: dark card with a saving badge. Tapping opens the Kendra map. */
@Composable
fun GenericCard(g: Texts.Generic, onKendra: () -> Unit, kendraLabel: String) {
    val s = sk
    run {
        val bg = if (s.dark) s.card else s.inverse
        val fg = if (s.dark) s.ink else s.onInverse
        val muted = if (s.dark) s.muted else s.onInverseMuted
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg)
            .clickable(role = Role.Button, onClickLabel = kendraLabel, onClick = onKendra).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                T(g.title, s.small - 1.sp, FontWeight.Bold, s.accent)
                T(g.name, s.body - 1.sp, FontWeight.Bold, fg)
                T(g.perUnit ?: g.mrp, s.small - 1.sp, color = muted)
                if (g.perUnit != null) T(g.mrp, s.small - 2.sp, color = muted)
            }
            val less = g.less
            if (less != null) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(84.dp).clip(CircleShape).background(s.accent).padding(8.dp), contentAlignment = Alignment.Center) {
                    T(less, 17.sp, FontWeight.ExtraBold, s.onAccent, align = TextAlign.Center, maxLines = 2, lineHeight = 1.1f)
                }
            }
        }
    }
}

/** Home-screen tile: coloured square with a glyph, a bold title and one line. */
@Composable
fun Tile(bg: Color, fg: Color, title: String, body: String?, modifier: Modifier = Modifier, label: String? = null,
         big: String? = null, top: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    var m = modifier.heightIn(min = 112.dp).clip(RoundedCornerShape(22.dp)).background(bg)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        top?.invoke()
        label?.let { T(it, 13.sp, FontWeight.Bold, sk.accent, maxLines = 1) }
        big?.let { T(it, 26.sp, FontWeight.ExtraBold, fg, maxLines = 1, lineHeight = 1.1f) }
        T(title, 16.sp, FontWeight.ExtraBold, fg, maxLines = 2, lineHeight = 1.2f)
        body?.let { T(it, 13.sp, color = fg.copy(alpha = 0.85f), maxLines = 3, lineHeight = 1.3f) }
    }
}

/** Progress in segments (antibiotic course days); falls back to a bar for long courses. */
@Composable
fun SegmentBar(done: Int, total: Int, color: Color = sk.brand, track: Color = sk.brandMuted.copy(alpha = 0.45f), height: Dp = 10.dp) {
    if (total <= 0) return
    if (total <= 14) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(total) { i -> Box(Modifier.weight(1f).height(height).clip(RoundedCornerShape(50)).background(if (i < done) color else track)) }
    } else Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(done.toFloat() / total).height(height).clip(RoundedCornerShape(50)).background(color))
    }
}

// ------------------------------------------------------------------------------------------------ small pieces

private val avatarColors = listOf(
    Color(0xFF0E5C58) to Color.White, Color(0xFFFFE1D2) to Color(0xFF8A3A1C), Color(0xFFD7F2E7) to Color(0xFF145A44),
    Color(0xFFFFF1C2) to Color(0xFF5C3D00), Color(0xFFE6E1FF) to Color(0xFF3B2E8A),
)

@Composable
fun Avatar(name: String, color: Int, selected: Boolean, onClick: () -> Unit, size: Dp = 40.dp) {
    val (bg, fg) = avatarColors[color.mod(avatarColors.size)]
    val ring = sk.accent
    Box(Modifier.size(size + 6.dp).clip(CircleShape).background(if (selected) ring else Color.Transparent).padding(3.dp)
        .clip(CircleShape).background(bg).clickable(role = Role.Button, onClickLabel = name, onClick = onClick)
        .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        T(name.trim().take(1).uppercase().ifEmpty { "•" }, 15.sp, FontWeight.Bold, fg, maxLines = 1)
    }
}

@Composable
fun AddAvatar(label: String, onClick: () -> Unit) {
    val s = sk
    Box(Modifier.padding(3.dp).size(40.dp).clip(CircleShape).border(2.dp, s.line, CircleShape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(AmIcons.Plus, null, tint = s.muted, modifier = Modifier.size(20.dp))
    }
}

/** Quick switch between the two first-class languages; the full list (with beta languages) is in Settings. */
@Composable
fun LangPill(vm: MainViewModel) {
    val s = sk
    val target = if (vm.lang == Lang.EN) Lang.HI else Lang.EN
    val shape = RoundedCornerShape(50)
    var m = Modifier.height(40.dp).clip(shape)
    m = m.background(s.inverse)
    Box(m.clickable(role = Role.Button) { vm.setLanguage(target) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        T(target.nativeName, 14.sp, FontWeight.Bold, s.onInverse, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    T(text, 12.sp, FontWeight.Bold, fg, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun AwareBadge(a: Aware, lang: Lang) {
    val s = sk
    val (fg, bg) = when (a) { Aware.Access -> s.warn to s.warnBg; Aware.Watch -> s.danger to s.dangerBg; Aware.Reserve -> s.danger to s.dangerBg }
    Pill("WHO " + Texts.awareName(a, lang), fg, bg)
}

/** Ingredients as chips. [onHero]: butter chips on the teal hero (B). [unclear]: label when a strength isn't known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IngredientChips(items: List<CompItem>, unclear: String? = null, onHero: Boolean = false) {
    val s = sk
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { c ->
            val st = c.strength?.toString() ?: unclear
            val text = c.ingredient.name + (st?.let { " $it" } ?: "")
            // on the foil: printed like the composition on a strip (ink outline), elsewhere a soft teal chip
            val chip = if (onHero) Modifier.clip(RoundedCornerShape(10.dp)).border(1.5.dp, s.foilInk.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                else Modifier.clip(RoundedCornerShape(10.dp)).background(s.brandSoft)
            T(text, 15.sp, FontWeight.Bold, if (onHero) s.foilInk else s.onBrandSoft, maxLines = 1,
                modifier = chip.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** The tilted blister strip on the B scan card. */
@Composable
fun StripArt(modifier: Modifier = Modifier) {
    val pocket = Color.White
    // foil, like the result hero: the strip people hold
    Column(modifier.size(150.dp, 112.dp).rotate(-12f).clip(RoundedCornerShape(20.dp))
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFEDF0F2), Color(0xFFC6CED3), Color(0xFFE4E8EB))))
        .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { col ->
                    Box(Modifier.weight(1f).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (row == 1 && col == 1) Color(0xFFFFD27A) else pocket))
                }
            }
        }
    }
}

@Composable
fun AmField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
            leading: ImageVector? = null, number: Boolean = false, label: String? = null) {
    val s = sk
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), singleLine = true,
        placeholder = { T(placeholder, s.body, color = s.muted, maxLines = 1) },
        label = label?.let { { T(it, s.small) } },
        leadingIcon = leading?.let { { Icon(it, null, tint = s.muted) } },
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = s.font, fontSize = s.body + 1.sp, color = s.ink),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = s.card, unfocusedContainerColor = s.card,
            focusedBorderColor = s.brand, unfocusedBorderColor = s.line, cursorColor = s.brand))
}

@Composable
fun AmDialog(title: String, onDismiss: () -> Unit, confirm: Pair<String, () -> Unit>, dismiss: String? = null,
             content: @Composable ColumnScope.() -> Unit) {
    val s = sk
    AlertDialog(onDismissRequest = onDismiss, containerColor = s.card, shape = RoundedCornerShape(s.heroRadius),
        title = { T(title, s.title, FontWeight.ExtraBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(onClick = confirm.second) { T(confirm.first, s.body, FontWeight.Bold, s.brandInk) } },
        dismissButton = dismiss?.let { { TextButton(onClick = onDismiss) { T(it, s.body, FontWeight.SemiBold, s.muted) } } })
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** Small dropdown used where a full list is needed (e.g. choosing a language in Settings is a list instead). */
@Composable
fun <T> Menu(items: List<T>, label: (T) -> String, onPick: (T) -> Unit, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        anchor { open = true }
        DropdownMenu(open, { open = false }) {
            items.forEach { i -> DropdownMenuItem(text = { Text(label(i), fontFamily = sk.font) }, onClick = { onPick(i); open = false }) }
        }
    }
}

