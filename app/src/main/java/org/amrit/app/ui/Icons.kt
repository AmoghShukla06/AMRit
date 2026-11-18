package org.amrit.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons matching the design mockups (24-unit grid, round caps). Tint with Icon(tint = ...). */
object AmIcons {
    private fun icon(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) addPath(PathParser().parsePathString(p).toNodes(), stroke = SolidColor(Color.Black),
                strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()

    val Scan by lazy { icon("scan", "M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3M7 12h10", width = 2.2f) }
    val Home by lazy { icon("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    val Meds by lazy { icon("meds", "M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 8h6M9 12h6M9 16h4") }
    val Search by lazy { icon("search", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "m20 20-3.5-3.5") }
    val Settings by lazy { icon("settings", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z",
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z", width = 1.8f) }
    val Speaker by lazy { icon("speaker", "M11 5 6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13") }
    val Share by lazy { icon("share", "M18 2a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM6 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM18 16a3 3 0 1 1 0 6a3 3 0 1 1 0-6z", "m8.6 13.5 6.8 4M15.4 6.5l-6.8 4") }
    val Pin by lazy { icon("pin", "M12 7a3 3 0 1 1 0 6a3 3 0 1 1 0-6z", "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z") }
    val Back by lazy { icon("back", "M15 18l-6-6 6-6", width = 2.4f) }
    val Check by lazy { icon("check", "M20 6 9 17l-5-5", width = 2.6f) }
    val Alert by lazy { icon("alert", "M12 7v6M12 17h.01", width = 2.6f) }
    val Info by lazy { icon("info", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M12 11v5M12 8h.01") }
    val Clock by lazy { icon("clock", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M12 7v5l3 2") }
    val Minus by lazy { icon("minus", "M5 12h14", width = 2.2f) }
    val Plus by lazy { icon("plus", "M12 5v14M5 12h14", width = 2.2f) }
    val Close by lazy { icon("close", "M6 6l12 12M18 6 6 18") }
    val Chevron by lazy { icon("chevron", "m9 6 6 6-6 6") }
    val Torch by lazy { icon("torch", "M13 2 4 14h7l-1 8 9-12h-7z") }
    val Gallery by lazy { icon("gallery", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8.5 7a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3z", "m21 15-5-5L5 21") }
    val Pill by lazy { icon("pill", "M10.5 3.5a5 5 0 0 1 7 7l-7 7a5 5 0 0 1-7-7z", "m7 10 7 7") }
}
