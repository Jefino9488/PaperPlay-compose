package io.max.paperplay.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Curated Tabler outline icons used by PaperPlay; sourced from https://tabler.io/icons under MIT. */
internal object TablerIcons {
    val activity_heartbeat: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_activity_heartbeat", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3 12h4.5l1.5 -6l4 12l2 -9l1.5 3h4.5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val album: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_album", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 6a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2l0 -12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 4v7l2 -2l2 2v-7").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val arrow_down: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_arrow_down", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 5l0 14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M18 13l-6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 13l6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val arrows_shuffle: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_arrows_shuffle", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M18 4l3 3l-3 3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M18 20l3 -3l-3 -3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 7h3a5 5 0 0 1 5 5a5 5 0 0 0 5 5h5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M21 7h-5a4.978 4.978 0 0 0 -3 1m-4 8a4.984 4.984 0 0 1 -3 1h-3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val arrow_up: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_arrow_up", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 5l0 14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M18 11l-6 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 11l6 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val blur: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_blur", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 21a9.01 9.01 0 0 0 2.32 -.302a9 9 0 0 0 1.74 -16.733a9 9 0 1 0 -4.06 17.035").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 3v17").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 12h9").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 9h8").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 6h6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 18h6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 15h8").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val chart_bar: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_chart_bar", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3 13a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 9a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -10").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v14a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 20h14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val check: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_check", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M5 12l5 5l10 -10").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val chevron_down: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_chevron_down", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M6 9l6 6l6 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val chevron_right: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_chevron_right", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M9 6l6 6l-6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val chevron_up: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_chevron_up", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M6 15l6 -6l6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val circle_check: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_circle_check", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 12l2 2l4 -4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val cloud: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_cloud", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M6.657 18c-2.572 0 -4.657 -2.007 -4.657 -4.483c0 -2.475 2.085 -4.482 4.657 -4.482c.393 -1.762 1.794 -3.2 3.675 -3.773c1.88 -.572 3.956 -.193 5.444 1c1.488 1.19 2.162 3.007 1.77 4.769h.99c1.913 0 3.464 1.56 3.464 3.486c0 1.927 -1.551 3.487 -3.465 3.487h-11.878").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val copy: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_copy", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M7 9.667a2.667 2.667 0 0 1 2.667 -2.667h8.666a2.667 2.667 0 0 1 2.667 2.667v8.666a2.667 2.667 0 0 1 -2.667 2.667h-8.666a2.667 2.667 0 0 1 -2.667 -2.667l0 -8.666").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4.012 16.737a2.005 2.005 0 0 1 -1.012 -1.737v-10c0 -1.1 .9 -2 2 -2h10c.75 0 1.158 .385 1.5 1").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val devices: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_devices", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M13 9a1 1 0 0 1 1 -1h6a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-10").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M18 8v-3a1 1 0 0 0 -1 -1h-13a1 1 0 0 0 -1 1v12a1 1 0 0 0 1 1h9").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M16 9h2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val dots_vertical: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_dots_vertical", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val download: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_download", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M7 11l5 5l5 -5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 4l0 12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val heart: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_heart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M19.5 12.572l-7.5 7.428l-7.5 -7.428a5 5 0 1 1 7.5 -6.566a5 5 0 1 1 7.5 6.572").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val history: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_history", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 8l0 4l2 2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3.05 11a9 9 0 1 1 .5 4m-.5 5v-5h5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val home: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_home", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M5 12l-2 0l9 -9l9 9l-2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2 -2v-7").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 21v-6a2 2 0 0 1 2 -2h2a2 2 0 0 1 2 2v6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val hourglass: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_hourglass", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M6.5 7h11").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6.5 17h11").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 20v-2a6 6 0 1 1 12 0v2a1 1 0 0 1 -1 1h-10a1 1 0 0 1 -1 -1").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 4v2a6 6 0 1 0 12 0v-2a1 1 0 0 0 -1 -1h-10a1 1 0 0 0 -1 1").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val key: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_key", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M16.555 3.843l3.602 3.602a2.877 2.877 0 0 1 0 4.069l-2.643 2.643a2.877 2.877 0 0 1 -4.069 0l-.301 -.301l-6.558 6.558a2 2 0 0 1 -1.239 .578l-.175 .008h-1.172a1 1 0 0 1 -.993 -.883l-.007 -.117v-1.172a2 2 0 0 1 .467 -1.284l.119 -.13l.414 -.414h2v-2h2v-2l2.144 -2.144l-.301 -.301a2.877 2.877 0 0 1 0 -4.069l2.643 -2.643a2.877 2.877 0 0 1 4.069 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 9h.01").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val layout_sidebar_right: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_layout_sidebar_right", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 6a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2l0 -12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 4l0 16").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val library: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_library", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M7 5.667a2.667 2.667 0 0 1 2.667 -2.667h8.666a2.667 2.667 0 0 1 2.667 2.667v8.666a2.667 2.667 0 0 1 -2.667 2.667h-8.666a2.667 2.667 0 0 1 -2.667 -2.667l0 -8.666").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4.012 7.26a2.005 2.005 0 0 0 -1.012 1.737v10c0 1.1 .9 2 2 2h10c.75 0 1.158 -.385 1.5 -1").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 7h5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 10h6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 13h3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val list_check: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_list_check", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3.5 5.5l1.5 1.5l2.5 -2.5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3.5 11.5l1.5 1.5l2.5 -2.5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3.5 17.5l1.5 1.5l2.5 -2.5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 6l9 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 12l9 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 18l9 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val maximize: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_maximize", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 8v-2a2 2 0 0 1 2 -2h2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 16v2a2 2 0 0 0 2 2h2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M16 4h2a2 2 0 0 1 2 2v2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M16 20h2a2 2 0 0 0 2 -2v-2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val minimize: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_minimize", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M5 9l4 0l0 -4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 3l6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M5 15l4 0l0 4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 21l6 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M19 9l-4 0l0 -4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 9l6 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M19 15l-4 0l0 4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 15l6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val menu_2: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_menu_2", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 6l16 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 12l16 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 18l16 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val messages: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_messages", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M21 14l-3 -3h-7a1 1 0 0 1 -1 -1v-6a1 1 0 0 1 1 -1h9a1 1 0 0 1 1 1v10").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M14 15v2a1 1 0 0 1 -1 1h-7l-3 3v-10a1 1 0 0 1 1 -1h2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val moon: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_moon", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454l0 .008").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val music: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_music", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M13 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 17v-13h10v13").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 8h10").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val palette: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_palette", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 21a9 9 0 0 1 0 -18c4.97 0 9 3.582 9 8c0 1.06 -.474 2.078 -1.318 2.828c-.844 .75 -1.989 1.172 -3.182 1.172h-2.5a2 2 0 0 0 -1 3.75a1.3 1.3 0 0 1 -1 2.25").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M7.5 10.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11.5 7.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15.5 10.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val player_pause: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_player_pause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M6 6a1 1 0 0 1 1 -1h2a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1l0 -12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M14 6a1 1 0 0 1 1 -1h2a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1l0 -12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val player_play: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_player_play", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M7 4v16l13 -8l-13 -8").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val player_skip_back: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_player_skip_back", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M20 5v14l-12 -7l12 -7").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 5l0 14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val player_skip_forward: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_player_skip_forward", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 5v14l12 -7l-12 -7").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M20 5l0 14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val playlist: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_playlist", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M11 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M17 17v-13h4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M13 5h-10").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 9l10 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 13h-6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val plus: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_plus", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M12 5l0 14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M5 12l14 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val refresh: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_refresh", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val repeat: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_repeat", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 12v-3a3 3 0 0 1 3 -3h13m-3 -3l3 3l-3 3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M20 12v3a3 3 0 0 1 -3 3h-13m3 3l-3 -3l3 -3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val repeat_once: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_repeat_once", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 12v-3a3 3 0 0 1 3 -3h13m-3 -3l3 3l-3 3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M20 12v3a3 3 0 0 1 -3 3h-13m3 3l-3 -3l3 -3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 11l1 -1v4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val restore: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_restore", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3.06 13a9 9 0 1 0 .49 -4.087").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 4.001v5h5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val robot: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_robot", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M6 6a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2l0 -4").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 2v2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 12v9").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 12v9").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M5 16l4 -2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 14l4 2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 18h6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M10 8v.01").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M14 8v.01").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val scale: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_scale", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M7 20l10 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 6l6 -1l6 1").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 3l0 17").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 12l-3 -6l-3 6a3 3 0 0 0 6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M21 12l-3 -6l-3 6a3 3 0 0 0 6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val search: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_search", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M21 21l-6 -6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val send: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_send", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M10 14l11 -11").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M21 3l-6.5 18a.55 .55 0 0 1 -1 0l-3.5 -7l-7 -3.5a.55 .55 0 0 1 0 -1l18 -6.5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val settings: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_settings", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val sort_ascending: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_sort_ascending", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 6l7 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 12l7 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M4 18l9 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M15 9l3 -3l3 3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M18 6l0 12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val sparkles: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_sparkles", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M16 18a2 2 0 0 1 2 2a2 2 0 0 1 2 -2a2 2 0 0 1 -2 -2a2 2 0 0 1 -2 2m0 -12a2 2 0 0 1 2 2a2 2 0 0 1 2 -2a2 2 0 0 1 -2 -2a2 2 0 0 1 -2 2m-7 12a6 6 0 0 1 6 -6a6 6 0 0 1 -6 -6a6 6 0 0 1 -6 6a6 6 0 0 1 6 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val sun: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_sun", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 12h1m8 -9v1m8 8h1m-9 8v1m-6.4 -15.4l.7 .7m12.1 -.7l-.7 .7m0 11.4l.7 .7m-12.1 -.7l-.7 .7").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val trash: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_trash", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 7l16 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M10 11l0 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M14 11l0 6").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val upload: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_upload", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M7 9l5 -5l5 5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M12 4l0 12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val user: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_user", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M8 7a4 4 0 1 0 8 0a4 4 0 0 0 -8 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val user_circle: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_user_circle", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6.168 18.849a4 4 0 0 1 3.832 -2.849h4a4 4 0 0 1 3.834 2.855").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val users: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_users", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M16 3.13a4 4 0 0 1 0 7.75").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M21 21v-2a4 4 0 0 0 -3 -3.85").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val volume: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_volume", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M15 8a5 5 0 0 1 0 8").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M17.7 5a9 9 0 0 1 0 14").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 15h-2a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1h2l3.5 -4.5a.8 .8 0 0 1 1.5 .5v14a.8 .8 0 0 1 -1.5 .5l-3.5 -4.5").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val volume_off: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_volume_off", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M15 8a5 5 0 0 1 1.912 4.934m-1.377 2.602a5 5 0 0 1 -.535 .464").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M17.7 5a9 9 0 0 1 2.362 11.086m-1.676 2.299a9 9 0 0 1 -.686 .615").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M9.069 5.054l.431 -.554a.8 .8 0 0 1 1.5 .5v2m0 4v8a.8 .8 0 0 1 -1.5 .5l-3.5 -4.5h-2a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1h2l1.294 -1.664").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M3 3l18 18").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val x: ImageVector by lazy {
        ImageVector.Builder(name = "Tabler_x", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString("M18 6l-12 12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(pathData = PathParser().parsePathString("M6 6l12 12").toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
}

internal fun tablerIconForMaterial(name: String): ImageVector? = when (name) {
    "Home" -> TablerIcons.home
    "Search", "ManageSearch" -> TablerIcons.search
    "LibraryMusic" -> TablerIcons.library
    "Person" -> TablerIcons.user
    "AccountCircle" -> TablerIcons.user_circle
    "TaskAlt" -> TablerIcons.list_check
    "Upload" -> TablerIcons.upload
    "MusicNote", "GraphicEq", "Lyrics" -> TablerIcons.music
    "Album" -> TablerIcons.album
    "Favorite", "FavoriteBorder" -> TablerIcons.heart
    "History" -> TablerIcons.history
    "Add" -> TablerIcons.plus
    "PlayArrow" -> TablerIcons.player_play
    "Pause" -> TablerIcons.player_pause
    "SkipNext" -> TablerIcons.player_skip_forward
    "SkipPrevious" -> TablerIcons.player_skip_back
    "Shuffle" -> TablerIcons.arrows_shuffle
    "Repeat" -> TablerIcons.repeat
    "RepeatOne" -> TablerIcons.repeat_once
    "ViewSidebar" -> TablerIcons.layout_sidebar_right
    "QueueMusic", "PlaylistPlay", "PlaylistAdd" -> TablerIcons.playlist
    "Devices" -> TablerIcons.devices
    "VolumeUp" -> TablerIcons.volume
    "VolumeOff" -> TablerIcons.volume_off
    "OpenInFull", "Fullscreen" -> TablerIcons.maximize
    "FullscreenExit", "CloseFullscreen" -> TablerIcons.minimize
    "KeyboardArrowDown", "ExpandMore" -> TablerIcons.chevron_down
    "ExpandLess" -> TablerIcons.chevron_up
    "ChevronRight" -> TablerIcons.chevron_right
    "Close" -> TablerIcons.x
    "MoreVert" -> TablerIcons.dots_vertical
    "Settings" -> TablerIcons.settings
    "Palette" -> TablerIcons.palette
    "AutoAwesome" -> TablerIcons.sparkles
    "Forum" -> TablerIcons.messages
    "BarChart" -> TablerIcons.chart_bar
    "Cloud" -> TablerIcons.cloud
    "HourglassBottom" -> TablerIcons.hourglass
    "Rule" -> TablerIcons.scale
    "ContentCopy" -> TablerIcons.copy
    "Sync" -> TablerIcons.refresh
    "Send" -> TablerIcons.send
    "Key" -> TablerIcons.key
    "SmartToy" -> TablerIcons.robot
    "MonitorHeart" -> TablerIcons.activity_heartbeat
    "DarkMode", "Bedtime" -> TablerIcons.moon
    "Gradient" -> TablerIcons.sparkles
    "CleaningServices" -> TablerIcons.trash
    "SaveAlt" -> TablerIcons.download
    "Restore" -> TablerIcons.restore
    "ArrowDownward" -> TablerIcons.arrow_down
    "ArrowUpward" -> TablerIcons.arrow_up
    "ArrowBack" -> TablerIcons.chevron_right
    "BlurOn" -> TablerIcons.sparkles
    "Check", "CheckCircle" -> TablerIcons.list_check
    "DirectionsCar" -> TablerIcons.devices
    "Equalizer" -> TablerIcons.music
    "FitnessCenter" -> TablerIcons.activity_heartbeat
    "MoreHoriz" -> TablerIcons.dots_vertical
    "NightsStay" -> TablerIcons.moon
    "Spa", "WbSunny", "WaterDrop", "Umbrella", "CenterFocusStrong", "Celebration" -> TablerIcons.sparkles
    "Storage" -> TablerIcons.library
    "SettingsEthernet" -> TablerIcons.devices
    "ViewAgenda" -> TablerIcons.layout_sidebar_right
    else -> null
}

