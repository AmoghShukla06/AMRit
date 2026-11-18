package org.amrit.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.amrit.app.scan.LiveScanner
import org.amrit.app.scan.OcrEngine
import java.util.concurrent.Executors
import androidx.compose.ui.geometry.Size as GSize

/**
 * Live scanner: the camera stream is read continuously (no need to press anything); the screen shows what has been
 * read so far and locks on automatically. The shutter means "decide now", gallery and search are always one tap away.
 */
@Composable
fun CameraScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) { vm.seen = emptyList() }

    val worker = remember { Executors.newSingleThreadExecutor() }
    var hint by remember { mutableStateOf(org.amrit.core.ScanCoach.Hint.NONE) }
    var glareFrames by remember { mutableStateOf(0) }
    var exposureLowered by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    /** bound only if the phone can run it beside preview + analysis */
    var still by remember { mutableStateOf<ImageCapture?>(null) }
    val scannerRef = remember { arrayOfNulls<LiveScanner>(1) }
    val scanner = remember {
        LiveScanner(vm.g, object : LiveScanner.Listener {
            override fun onSeen(chips: List<String>) { view.post { vm.seen = chips } }
            override fun onHint(h: org.amrit.core.ScanCoach.Hint) {
                view.post {
                    hint = h
                    // glare that persists over two frames: turn the exposure down once, so the foil stops burning out
                    glareFrames = if (h == org.amrit.core.ScanCoach.Hint.GLARE) glareFrames + 1 else 0
                    val exp = camera?.cameraInfo?.exposureState
                    if (glareFrames >= 2 && !exposureLowered && exp != null && exp.isExposureCompensationSupported) {
                        camera?.cameraControl?.setExposureCompensationIndex(exp.exposureCompensationRange.lower / 2)
                        exposureLowered = true
                    }
                }
            }
            override fun onWantStill() {
                view.post {
                    val s = scannerRef[0] ?: return@post
                    val c = still ?: run { s.stillFailed(); return@post }
                    c.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val rot = image.imageInfo.rotationDegrees
                            val bmp = try { image.toBitmap() } finally { image.close() }
                            s.addStill(bmp, rot)   // in memory only, never written anywhere
                            bmp.recycle()
                        }
                        override fun onError(e: ImageCaptureException) { s.stillFailed() }
                    })
                }
            }
            override fun onLocked(result: LiveScanner.Locked) {
                view.post {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    // box mode: stay on the camera; re-arm after a pause so the strip just read can be put away
                    if (vm.boxMode) vm.liveBox(result) { beep(); view.postDelayed({ scannerRef[0]?.reset() }, 1200) }
                    else vm.live(result)
                }
            }
        }).also { scannerRef[0] = it; it.boxMode = vm.boxMode }
    }
    DisposableEffect(Unit) { onDispose { scanner.pause(); worker.shutdown() } }
    // leaving box mode with strips collected shows the report rather than losing them
    androidx.activity.compose.BackHandler(enabled = vm.boxMode && vm.box.isNotEmpty()) { vm.finishBox() }
    // 2.6: a sharp still for small composition print. ~12 MP cap: a 50 MP frame would not fit in memory as a bitmap
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            .build()
    }
    // ~2 MP analysis frames: small composition text stays readable, ML Kit stays ~3 frames/s on a mid-range phone
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .build().also { it.setAnalyzer(worker, scanner) }
    }
    var torch by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scanner.pause()
        worker.execute {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            view.post { if (bmp != null) vm.scan(bmp, 0, OcrEngine.scaleTo(bmp, 720)) else scanner.reset() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                val pv = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                    provider.unbindAll()
                    camera = try {
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture).also { still = capture }
                    } catch (e: IllegalArgumentException) {
                        // three streams not supported on this camera: live frames only, as before
                        provider.unbindAll(); still = null
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(c))
                pv.setOnTouchListener { v, ev ->
                    if (ev.action == android.view.MotionEvent.ACTION_UP) {
                        val pt = pv.meteringPointFactory.createPoint(ev.x, ev.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                        v.performClick()
                    }
                    true
                }
                pv
            })
            FrameGuide()
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.tr("scan.permission"), color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { perm.launch(Manifest.permission.CAMERA) }) { Text(vm.tr("scan.allow")) }
            }
        }

        val glass = Color(0x66000000)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(AmIcons.Back, vm.tr("common.back"), { if (vm.boxMode && vm.box.isNotEmpty()) vm.finishBox() else { vm.endBox(); vm.back() } },
                bg = glass, fg = Color.White)
            Spacer(Modifier.weight(1f))
            RoundIcon(AmIcons.Search, vm.tr("nav.search"), { vm.go(Screen.Search) }, bg = glass, fg = Color.White)
            Spacer(Modifier.width(8.dp))
            RoundIcon(AmIcons.Torch, if (torch) vm.tr("scan.torchOff") else vm.tr("scan.torch"),
                { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                bg = if (torch) sk.accent else glass, fg = if (torch) sk.onAccent else Color.White, enabled = camera?.cameraInfo?.hasFlashUnit() == true)
        }

        // feedback that text is being read, without showing raw OCR words
        // one coaching line: what to do so the strip can be read (or "hold still" while it is being read)
        AnimatedVisibility(hint != org.amrit.core.ScanCoach.Hint.NONE, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp, start = 24.dp, end = 24.dp)) {
            val act = hint != org.amrit.core.ScanCoach.Hint.HOLD
            val key = when (hint) {
                org.amrit.core.ScanCoach.Hint.GLARE -> "scan.glare"
                org.amrit.core.ScanCoach.Hint.CLOSER -> "scan.closer"
                org.amrit.core.ScanCoach.Hint.FLIP -> "scan.flip"
                else -> "scan.holdStill"
            }
            Text(vm.tr(key), color = if (act) sk.onAccent else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = sk.font,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(if (act) sk.accent else sk.brand.copy(alpha = 0.9f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (vm.boxMode) BoxStatus(vm)
            else Text(vm.tr("scan.hint"), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, fontFamily = sk.font,
                modifier = Modifier.padding(horizontal = 24.dp).background(Color(0x77000000), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RoundIcon(AmIcons.Gallery, vm.tr("scan.gallery"), { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        bg = glass, fg = Color.White, size = 56.dp, iconSize = 24.dp)
                    Text(vm.tr("scan.gallery"), color = Color.White, fontSize = 12.sp, fontFamily = sk.font, modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)) {
                    Button(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); scanner.lockNow() },
                        enabled = granted, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, disabledContainerColor = Color.Gray),
                        modifier = Modifier.fillMaxSize()) {}
                }
                if (vm.boxMode) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RoundIcon(AmIcons.Check, vm.tr("box.done"), { vm.finishBox() }, bg = sk.accent, fg = sk.onAccent, size = 56.dp, iconSize = 24.dp)
                    Text(vm.tr("box.done"), color = Color.White, fontSize = 12.sp, fontFamily = sk.font, modifier = Modifier.padding(top = 4.dp))
                } else Spacer(Modifier.size(width = 56.dp, height = 1.dp))
            }
            if (vm.g.settings.demoMode && vm.g.demo.strips.isNotEmpty()) {
                Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DEMO", color = Color(0xFFFFCC80), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically))
                    vm.g.demo.strips.forEach { s ->
                        OutlinedButton(onClick = { if (vm.boxMode) vm.demoPick(s) else { scanner.pause(); vm.demoPick(s) } }, border = BorderStroke(1.dp, Color(0xFFFFCC80))) { Text(s.label, color = Color.White) }
                    }
                }
            }
        }
    }
}

/** Darkens everything outside a strip-shaped window so users frame the name, not the whole box. */
@Composable
private fun FrameGuide() {
    Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        drawRect(Color(0x88000000))
        val w = size.width * 0.88f
        val h = w * 0.62f
        val tl = Offset((size.width - w) / 2, (size.height - h) / 2 - size.height * 0.05f)
        drawRoundRect(Color.Transparent, tl, GSize(w, h), CornerRadius(36f), blendMode = BlendMode.Clear)
        drawRoundRect(Color.White, tl, GSize(w, h), CornerRadius(36f), style = Stroke(5f))
    }
}

/** Box mode's line above the shutter: how many strips are in, and the one just added. */
@Composable
private fun BoxStatus(vm: MainViewModel) {
    Column(Modifier.padding(horizontal = 24.dp).background(Color(0xCC14232E), RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (vm.box.isEmpty()) vm.tr("box.hintScan") else vm.tr("box.count", "n" to vm.box.size), color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = sk.font, textAlign = TextAlign.Center)
        vm.boxLast?.let { Text(vm.tr("box.added", "product" to it), color = sk.accent, fontSize = 14.sp, fontFamily = sk.font, textAlign = TextAlign.Center) }
        if (vm.boxUnknown > 0) Text(vm.tr("box.unknownCount", "n" to vm.boxUnknown), color = Color(0xFFC3CBD1), fontSize = 13.sp, fontFamily = sk.font)
    }
}

/** A short acknowledgement tone for each strip added in box mode (the phone may be on the table, out of sight). */
private fun beep() = runCatching {
    android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70).apply {
        startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release() }, 400)
    }
}
