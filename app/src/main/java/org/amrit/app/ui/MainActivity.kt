package org.amrit.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // Android 15 enforces it for targetSdk 35; every screen pads for the system bars itself
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent {
            AmritTheme(vm.lang, vm.largeText) {
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
                BackHandler(enabled = vm.screen !is Screen.Home && vm.screen !is Screen.Onboarding) { if (!vm.back()) vm.tab(Screen.Home) }
                // Surface supplies the theme's content colour (dark mode) to every screen
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        AppFrame(vm, vm.screen) {
                            AnimatedContent(
                                targetState = vm.screen,
                                transitionSpec = {
                                    // camera <-> processing should feel like one continuous moment; tabs crossfade
                                    val d = vm.navDirection
                                    val instant = targetState is Screen.Working || initialState is Screen.Working
                                    if (instant || d == 0) fadeIn(tween(140)) togetherWith fadeOut(tween(110))
                                    else (slideInHorizontally(tween(220)) { it / 6 * d } + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(tween(180)) { -it / 8 * d } + fadeOut(tween(150)))
                                },
                                contentKey = { it::class },
                                label = "nav",
                            ) { s -> AppScreens(vm, s) }
                        }
                        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // wake a sleeping free-tier backend in the background while the user frames the strip
        vm.g.scope.launch(kotlinx.coroutines.Dispatchers.IO) { vm.g.api.wake() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** eval/run_on_device.sh: `am start ... --es eval api|offline [--es backend http://127.0.0.1:8000]` */
    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("backend")?.let { vm.g.settings.backendUrl = it }
        // QA: `--es scan_photo p001.jpg` fetches a test photo from the dev backend and runs the normal scan flow on it
        i?.getStringExtra("scan_photo")?.let { name ->
            Thread {
                val bytes = runCatching {
                    okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("${vm.g.settings.backendUrl}/eval/photo/$name").build())
                        .execute().use { it.body!!.bytes() }
                }.getOrNull()
                val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                runOnUiThread {
                    if (bmp == null) vm.toast = "Couldn't load $name"
                    else { vm.go(Screen.Home); vm.scan(bmp, 0, org.amrit.app.scan.OcrEngine.scaleTo(bmp, 720)) }
                }
            }.start()
        }
        when (i?.getStringExtra("eval")) {
            "api" -> { vm.go(Screen.Stats); vm.runEval(useApi = true) }
            "offline" -> { vm.go(Screen.Stats); vm.runEval(useApi = false) }
        }
    }
}
