package org.amrit.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.amrit.app.data.ScanLogEntity
import org.amrit.app.scan.ScanService
import org.amrit.core.Lang
import org.amrit.core.Texts

@Composable
fun AppScreens(vm: MainViewModel, s: Screen) {
    when (s) {
        Screen.Onboarding -> OnboardingScreen(vm)
        Screen.Home -> HomeScreen(vm)
        Screen.Medicines -> MedicinesScreen(vm)
        Screen.Scan -> CameraScreen(vm)
        Screen.Working -> WorkingScreen(vm)
        is Screen.Result -> ResultScreen(vm, s.outcome)
        is Screen.WhatWeRead -> WhatWeReadScreen(vm, s.outcome)
        Screen.BoxReport -> BoxAuditScreen(vm)
        Screen.Passport -> PassportScreen(vm)
        Screen.NearYou -> NearYouScreen(vm)
        Screen.Bill -> BillScreen(vm)
        is Screen.Confirm -> ConfirmScreen(vm, s.outcome)
        is Screen.NotFound -> NotFoundScreen(vm, s.outcome)
        Screen.Search -> SearchScreen(vm)
        Screen.Stats -> StatsScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
}

private val tabScreens = setOf(Screen.Home, Screen.Medicines, Screen.Search, Screen.Settings)

/** A screen plus the bottom navigation when it is one of the tabs. [content] lets the activity animate the screen. */
@Composable
fun AppFrame(vm: MainViewModel, s: Screen, content: @Composable () -> Unit = { AppScreens(vm, s) }) {
    Column(Modifier.fillMaxSize().background(sk.bg)) {
        Box(Modifier.weight(1f)) { content() }
        if (s in tabScreens) BottomNav(vm, s)
    }
}

@Composable
fun BottomNav(vm: MainViewModel, current: Screen) {
    val s = sk
    val items = buildList {
        add(Triple(Screen.Home, AmIcons.Home, "nav.home"))
        add(Triple(Screen.Medicines, AmIcons.Meds, "nav.meds"))
        add(Triple(Screen.Search, AmIcons.Search, "nav.search"))
        add(Triple(Screen.Settings, AmIcons.Settings, "nav.settings"))
    }
    run {
        Row(Modifier.fillMaxWidth().background(s.bg).navigationBarsPadding().height(72.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            items.forEach { (screen, icon, key) ->
                val sel = screen == current
                val label = vm.tr(key)
                if (sel) Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(s.inverse)
                    .clickable(role = Role.Tab) { vm.tab(screen) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = s.onInverse, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    T(label, 14.sp, FontWeight.Bold, s.onInverse, maxLines = 1)
                } else Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Tab, onClickLabel = label) { vm.tab(screen) }
                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = s.ink, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ language list

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChoice(vm: MainViewModel) {
    val s = sk
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Lang.EN, Lang.HI).forEach { l ->
                val sel = l == vm.lang
                val shape = RoundedCornerShape(s.radius)
                Box(Modifier.weight(1f).height(64.dp).clip(shape).background(if (sel) s.brand else s.card)
                    .border(2.dp, if (sel) s.brand else s.line, shape).clickable(role = Role.RadioButton) { vm.setLanguage(l) },
                    contentAlignment = Alignment.Center) {
                    T(l.nativeName, 20.sp, FontWeight.ExtraBold, if (sel) s.onBrand else s.ink, maxLines = 1)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Lang.entries.filter { it.beta }.forEach { l ->
                val sel = l == vm.lang
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (sel) s.brand else s.card)
                    .border(1.5.dp, if (sel) s.brand else s.line, RoundedCornerShape(50))
                    .clickable(role = Role.RadioButton) { vm.setLanguage(l) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T(l.nativeName, 15.sp, FontWeight.SemiBold, if (sel) s.onBrand else s.ink, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    T(vm.tr("settings.beta"), 11.sp, FontWeight.Bold, if (sel) s.brandMuted else s.muted, maxLines = 1)
                }
            }
        }
        T(vm.tr("settings.languageBeta"), s.small - 1.sp, color = s.muted)
    }
}

// ------------------------------------------------------------------------------------------------ onboarding

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val s = sk
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(s.bg).statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(s.brand), contentAlignment = Alignment.Center) {
                Icon(AmIcons.Pill, null, tint = s.onBrand, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(10.dp))
            T("AMRit", 24.sp, FontWeight.ExtraBold, s.brandInk)
        }
        T(vm.tr("onb.title"), s.display, FontWeight.ExtraBold, lineHeight = 1.15f)
        Spacer(Modifier.height(4.dp))
        T(vm.tr("onb.language"), s.title, FontWeight.Bold)
        LanguageChoice(vm)
        Spacer(Modifier.height(4.dp))
        CardBox {
            T(vm.tr("onb.privacyTitle"), s.title, FontWeight.ExtraBold)
            listOf("onb.privacy1", "onb.privacy2", "onb.privacy3").forEach {
                Row(Modifier.padding(top = 4.dp)) {
                    Icon(AmIcons.Check, null, tint = s.good, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    T(vm.tr(it), s.body)
                }
            }
        }
        AmField(pin, { pin = it.filter(Char::isDigit).take(6) }, "560001", label = vm.tr("onb.pincode"), number = true)
        PrimaryButton(vm.tr("onb.start"), { vm.finishOnboarding(pin) }, Modifier.fillMaxWidth())
        T(vm.tr("disclaimer"), s.small - 1.sp, color = s.muted)
    }
}

// ------------------------------------------------------------------------------------------------ working

@Composable
fun WorkingScreen(vm: MainViewModel) {
    val s = sk
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pulse by rememberInfiniteTransition(label = "scan").animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "p")
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
        vm.capture?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.5f)) }
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(3.dp)
            .offset { IntOffset(0, ((0.15f + 0.5f * pulse) * size.height).toInt()) }
            .clip(RoundedCornerShape(50)).background(s.accent))
        // one calm line; how the app works internally is not the user's concern
        val label = when (vm.stage) {
            ScanService.Stage.READING -> vm.tr("work.reading")
            ScanService.Stage.CHECKING -> vm.tr("work.checking")
            else -> vm.tr("work.identifying")
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp)
            .clip(RoundedCornerShape(50)).background(s.card).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.5.dp, color = s.brand, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            androidx.compose.animation.AnimatedContent(label, label = "stage") { T(it, s.body, FontWeight.Bold, maxLines = 1) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ confirm / not found

@Composable
fun ConfirmScreen(vm: MainViewModel, o: ScanService.Outcome.Confirm) {
    val s = sk
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("conf.title"), onBack = { vm.go(Screen.Home) }, backLabel = vm.tr("common.back"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(vm.tr("conf.sub"), s.body, color = s.muted)
            o.candidates.forEach { c ->
                CardBox(onClick = { vm.confirm(o, c) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T(c.product.name, s.title, FontWeight.ExtraBold)
                            c.product.manufacturer?.let { T(it, s.small, color = s.muted) }
                        }
                        Icon(AmIcons.Chevron, null, tint = s.muted)
                    }
                    Spacer(Modifier.height(4.dp))
                    IngredientChips(c.items)
                }
            }
        }
        BottomActions {
            SecondaryButton(vm.tr("conf.none"), { vm.confirm(o, null) }, Modifier.fillMaxWidth())
            LinkButton(vm.tr("nf.search"), { vm.go(Screen.Search) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun NotFoundScreen(vm: MainViewModel, o: ScanService.Outcome.NotFound) {
    val s = sk
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("nf.title"), onBack = { vm.go(Screen.Home) }, backLabel = vm.tr("common.back"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (o.ayush) {
                val l = Texts.Line(Texts.Kind.INFO, vm.tr("nf.ayush.title"), vm.tr("nf.ayush.body"))
                SignalCard(l, onListen = { vm.speak(l.text) }, listenLabel = vm.tr("res.listen"))
                return@Column
            }
            SignalCard(Texts.Line(Texts.Kind.INFO, vm.tr("notFound")), onListen = { vm.speak(vm.tr("notFound")) },
                listenLabel = vm.tr("res.listen"))
            SectionTitle(vm.tr("nf.tips"))
            listOf("nf.tip1", "nf.tip2", "nf.tip3").forEachIndexed { i, k ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(s.brandSoft), contentAlignment = Alignment.Center) {
                        T("${i + 1}", 14.sp, FontWeight.ExtraBold, s.onBrandSoft)
                    }
                    Spacer(Modifier.width(12.dp))
                    T(vm.tr(k), s.body, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        BottomActions {
            PrimaryButton(vm.tr("nf.tryAgain"), { vm.go(Screen.Home); vm.go(Screen.Scan) }, Modifier.fillMaxWidth(), icon = AmIcons.Scan)
            SecondaryButton(vm.tr("nf.search"), { vm.go(Screen.Search) }, Modifier.fillMaxWidth(), icon = AmIcons.Search)
        }
    }
}

// ------------------------------------------------------------------------------------------------ search

@Composable
fun SearchScreen(vm: MainViewModel) {
    val s = sk
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("search.title"))
        AmField(vm.query, { vm.search(it) }, vm.tr("search.hint"), Modifier.padding(horizontal = 20.dp).focusRequester(focus), leading = AmIcons.Search)
        LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.query.length < 2) item { T(vm.tr("search.tip"), s.body, color = s.muted) }
            if (vm.query.length >= 2 && vm.hits.isEmpty()) item {
                CardBox {
                    T(vm.tr("search.empty"), s.body, FontWeight.Bold)
                    T(vm.tr("search.tip"), s.small, color = s.muted)
                }
            }
            items(vm.hits, key = { it.product.id }) { h ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(s.card)
                    .clickable(role = Role.Button) { vm.openProduct(h.product) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(h.product.name, 16.sp, FontWeight.Bold, maxLines = 1)
                        T(h.subtitle, s.small, color = s.muted, maxLines = 2)
                    }
                    Icon(AmIcons.Chevron, null, tint = s.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val st = vm.g.settings
    val s = sk
    var pin by remember { mutableStateOf(st.pincode ?: "") }
    var adding by remember { mutableStateOf(false) }
    val profiles by vm.profiles.collectAsState()
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header(vm.tr("settings.title"), onBack = null, backLabel = vm.tr("common.back"),
            onTitleLongPress = { vm.go(Screen.Stats) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(vm.tr("settings.language"))
            LanguageChoice(vm)
            SectionTitle(vm.tr("settings.reading"))
            CardBox {
                listOf(Triple("settings.large", vm.largeText) { on: Boolean -> vm.updateLargeText(on) },
                    Triple("settings.autoRead", vm.autoRead) { on: Boolean -> vm.updateAutoRead(on) }).forEach { (key, on, set) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            T(vm.tr(key), s.body, FontWeight.Bold)
                            T(vm.tr(key + "Sub"), s.small, color = s.muted)
                        }
                        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = s.brand),
                            modifier = Modifier.semantics { contentDescription = vm.tr(key) })
                    }
                }
            }
            SectionTitle(vm.tr("settings.family"))
            CardBox {
                profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(vm.profileName(p), p.color, p.id == vm.profileId.value, onClick = { vm.selectProfile(p.id) })
                        Spacer(Modifier.width(10.dp))
                        T(vm.profileName(p), s.body, FontWeight.SemiBold)
                    }
                }
                LinkButton("+ " + vm.tr("profile.add"), { adding = true })
            }
            AmField(pin, { pin = it.filter(Char::isDigit).take(6); st.pincode = pin.takeIf { p -> p.length == 6 } }, "560001",
                label = vm.tr("settings.pincode"), number = true)
            T(vm.tr("settings.pincodeWhy"), s.small, color = s.muted)
            SectionTitle(vm.tr("settings.about"))
            CardBox {
                T("AMRit ${org.amrit.app.BuildConfig.VERSION_NAME}", s.body, FontWeight.Bold)
                T(vm.tr("settings.privacy"), s.small, color = s.muted)
                T(vm.tr("settings.sources"), s.small, color = s.muted)
            }
            T(vm.tr("disclaimer"), s.small - 1.sp, color = s.muted)
        }
    }
    if (adding) AddProfileDialog(vm) { adding = false }
}

// ------------------------------------------------------------------------------------------------ stats (hidden)

private fun pct(n: Int, d: Int) = if (d == 0) "–" else "%.0f%%".format(100.0 * n / d)
private fun percentile(xs: List<Long>, p: Double) = if (xs.isEmpty()) 0L else xs.sorted()[((xs.size - 1) * p).toInt()]

/** Hidden: long-press the Settings title. The numbers for the judges, plus dev switches. */
@Composable
fun StatsScreen(vm: MainViewModel) {
    val s = sk
    var refresh by remember { mutableIntStateOf(0) }
    val logs by produceState(emptyList<ScanLogEntity>(), refresh, vm.evalProgress) { value = vm.g.dao.allLogs() }
    val ctx = LocalContext.current
    val evalSummary by produceState<JsonObject?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { Json.parseToJsonElement(ctx.assets.open("eval_summary.json").bufferedReader().readText()) as JsonObject }.getOrNull()
        }
    }
    var capture by remember { mutableStateOf(vm.g.settings.captureMode) }
    var demo by remember { mutableStateOf(vm.g.settings.demoMode) }
    var url by remember { mutableStateOf(vm.g.settings.backendUrl) }
    val meta by produceState(emptyMap<String, String>()) { value = vm.refMeta() }
    Column(Modifier.fillMaxSize().background(s.bg)) {
        Header("Developer", onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val n = logs.size
            val zero = logs.count { it.path in listOf("ON_DEVICE", "CONFIRMED", "CACHE_LOCAL", "DEMO", "SEARCH") }
            val llm = logs.count { it.path == "API" }
            val backend = logs.count { it.path == "API" || it.path == "CACHE_SERVER" }
            val totals = logs.filter { it.path != "NOT_FOUND" }.map { it.totalMs }
            val onDevice = logs.filter { it.path == "ON_DEVICE" }.map { it.totalMs }
            val cost = logs.sumOf { it.apiCostUsd }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigStat(pct(zero, n), "zero API calls", Modifier.weight(1f))
                BigStat("${percentile(onDevice, 0.5)} ms", "on-device p50", Modifier.weight(1f))
                BigStat(if (n == 0) "–" else "$%.4f".format(cost / n * 100), "API $ / 100 scans", Modifier.weight(1f))
            }
            SectionTitle("This phone (live)")
            StatRow("Scans", "$n")
            StatRow("Resolved with zero API calls", pct(zero, n))
            StatRow("Hit the backend (LLM or shared cache)", pct(backend, n))
            StatRow("Vision-LLM calls", "$llm (${pct(llm, n)})")
            StatRow("Not found (said so, no guess)", pct(logs.count { it.path == "NOT_FOUND" }, n))
            StatRow("Latency p50 / p95, all resolved", "${percentile(totals, 0.5)} / ${percentile(totals, 0.95)} ms")
            StatRow("Brand index build at launch", "${vm.g.indexBuildMs} ms")
            SectionTitle("Evaluation set")
            if (evalSummary == null) T("No eval_summary.json bundled yet.", s.small, color = s.muted)
            evalSummary?.forEach { (k, v) -> StatRow(k, v.jsonPrimitive.content) }
            SectionTitle("Dev")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Capture mode", s.body, FontWeight.Bold)
                    T("Upload each scanned frame + OCR to the laptop to grow the real-strip test set", s.small, color = s.muted)
                }
                Switch(capture, { capture = it; vm.g.settings.captureMode = it }, colors = SwitchDefaults.colors(checkedTrackColor = s.brand))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Demo mode", s.body, FontWeight.Bold)
                    T("Saved results for the demo strips, no network needed", s.small, color = s.muted)
                }
                Switch(demo, { demo = it; vm.g.settings.demoMode = it }, colors = SwitchDefaults.colors(checkedTrackColor = s.brand))
            }
            AmField(url, { url = it; vm.g.settings.backendUrl = it }, "https://", label = "Backend URL")
            T("Data: built ${meta["built_at"] ?: "…"} · ${meta["aware_source"] ?: ""}", s.small, color = s.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Eval offline", { vm.runEval(false) })
                SoftButton("Eval + fallback", { vm.runEval(true) })
                SoftButton("Refresh", { refresh++ })
            }
            T(vm.evalProgress, s.small, color = s.muted)
        }
    }
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier) {
    val s = sk
    Column(modifier.clip(RoundedCornerShape(s.radius)).background(s.brand).padding(14.dp)) {
        T(value, 20.sp, FontWeight.ExtraBold, s.onBrand, maxLines = 1)
        T(label, 12.sp, color = s.brandMuted, maxLines = 2)
    }
}

@Composable
private fun StatRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        T(k, sk.small + 1.sp, color = sk.muted, modifier = Modifier.weight(1f))
        T(v, sk.small + 1.sp, FontWeight.Bold)
    }
}
