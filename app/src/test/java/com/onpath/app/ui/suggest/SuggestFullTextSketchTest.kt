package com.onpath.app.ui.suggest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import com.onpath.app.suggest.SuggestCard
import com.onpath.app.suggest.SuggestPill
import com.onpath.app.ui.ActivityMapButton
import com.onpath.app.ui.CompassSpec
import com.onpath.app.ui.MapControls
import com.onpath.app.ui.MapFooter
import com.onpath.app.ui.MessagePlacement
import com.onpath.app.ui.MessageScreen
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.PlanControlsLayer
import com.onpath.app.ui.PlanMenu
import com.onpath.app.ui.PlanTopBand
import com.onpath.app.ui.PlanTopBar
import com.onpath.app.ui.SheetWithFooter
import com.onpath.app.ui.planTopBarDp
import com.onpath.strings.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * SKETCH round (owner 2026-11-19, round trips: the card's text is cut – a tap shows it in full, „Dazu würde ich gerne
 * ein UI-Bild sehen vorher“): pictures for the owner, nothing is wired. The plan screen over a drawn map with the
 * chosen loop; „today“ = the row as it is, V1 = the card grows upward in place, V2 = a sheet with the full text. German,
 * and German at font size 1.3. The map is a drawn stand-in (MapLibre does not render here).
 */
abstract class SuggestFullTextSketchShots(fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val line = doubleArrayOf(7.2, 50.3, 7.21, 50.31, 7.2, 50.3)

    /** A Traumpfad-like card with a long generated text (5 sentences), and two more cards in the row. */
    protected fun cards() = listOf(
        SuggestCard(1, "Traumpfad Monrealer Ritterschlag", "Traumpfad", 14.1, 15.0, 4.6, 450,
            "Von Monreal hinauf zur Löwenburg und zur Philippsburg, dann über die Höhen mit weitem Blick ins Elztal. " +
                "Lange Abschnitte auf schmalen Pfaden durch Buchenwald, dazwischen offene Wiesen mit Bänken. " +
                "Am Elzbach entlang zurück, zweimal über kleine Holzbrücken. " +
                "Wenig Straße, nur am Anfang und am Ende ein kurzes Stück durch den Ort. " +
                "Einkehr in Monreal am Start und am Ziel.",
            listOf(SuggestPill(Txt.s(Res.string.suggest_pill_besonderes_good), true, 3), SuggestPill(Txt.s(Res.string.suggest_pill_ruhe_good), true, 2),
                SuggestPill(Txt.s(Res.string.suggest_pill_longer, "20"), false, 2)), line),
        SuggestCard(2, "Rund um Reudelsterz", null, null, 9.6, 2.7, 210,
            "Felder und Waldrand oberhalb von Reudelsterz, zurück durch das Elztal.",
            listOf(SuggestPill(Txt.s(Res.string.suggest_pill_natur_good), true, 2)), line),
        SuggestCard(3, "Elztal-Runde", null, null, 11.2, 3.1, 260, "Am Bach entlang und über die Höhe zurück.",
            listOf(SuggestPill(Txt.s(Res.string.suggest_pill_town, "25"), false, 1)), line),
    )

    protected fun ui() = SuggestPanelUi(SuggestPhase.CARDS, "", cards(), chosen = 0)

    /** The plan screen as MapScreen lays it out in the suggestions (top bar, activity, map controls, the panel in the sheet's place). */
    protected fun shot(panel: @Composable () -> Unit, over: @Composable () -> Unit = {}) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                LoopMap(Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().background(PlanTopBand.Color).padding(top = 32.dp).height(planTopBarDp().dp))
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
                    ActivityMapButton(Activity.HIKE, 2, {}, Modifier.padding(top = rowTop.dp, start = 12.dp))
                    val sheetHeight = remember { mutableIntStateOf(0) }
                    PlanControlsLayer({ sheetHeight.intValue }, planTopBarDp().dp) {
                        MapControls(CompassSpec(null, 0f) {}, showEye = false, onPeek = {}, gps = GpsStatus.GOOD, following = false,
                            onLocate = {}, onFitRoute = null, plan = true)
                    }
                    SheetWithFooter(planTopBarDp().dp, footer = { Column(Modifier.fillMaxWidth()) { MapFooter("500 m" to 64.dp) {} } },
                        sheet = { Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) { panel() } })
                }
                over()
            }
        }
    }

    /** Today: the row as it is (the text in one line, cut). */
    @Test fun today() = shot({ SuggestPanel(ui(), SuggestPanelCallbacks()) })

    /** V1: the chosen card grown upward in place, „▴ weniger“ folds it back. */
    @Test fun v1CardGrows() = shot({ SuggestPanelGrownSketch(ui(), grown = 0, less = "weniger") })

    /** V2: the sheet with the full text over the row (the map dimmed as under any Material sheet). */
    @Test fun v2Sheet() = shot({ SuggestPanel(ui(), SuggestPanelCallbacks()) }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SuggestFullTextSheetSketch(cards()[0]) }
    }
}

class SuggestFullTextSketchGermanTest : SuggestFullTextSketchShots(1f)
class SuggestFullTextSketchLargeFontGermanTest : SuggestFullTextSketchShots(1.3f)

/** A drawn stand-in for the map (wood, contours, a stream, villages, roads, paths) with the chosen loop in route blue. */
@Composable
private fun LoopMap(modifier: Modifier = Modifier) = Canvas(modifier.background(Color(0xFFF2EFE6))) {
    val w = size.width; val h = size.height
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    fun path(vararg pts: Pair<Float, Float>, closed: Boolean = false) = Path().apply {
        moveTo(w * pts[0].first, h * pts[0].second)
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            quadraticTo(w * x0, h * y0, w * (x0 + x1) / 2, h * (y0 + y1) / 2)
        }
        lineTo(w * pts.last().first, h * pts.last().second)
        if (closed) close()
    }
    val wood = Color(0xFFD5E5C3)
    drawPath(path(0f to 0.08f, 0.4f to 0.1f, 0.36f to 0.22f, 0.18f to 0.3f, 0f to 0.32f, closed = true), wood)
    drawPath(path(0.55f to 0.1f, 1f to 0.08f, 1f to 0.46f, 0.78f to 0.4f, 0.6f to 0.26f, closed = true), wood)
    drawPath(path(0f to 0.55f, 0.2f to 0.5f, 0.32f to 0.66f, 0.2f to 0.9f, 0f to 0.95f, closed = true), wood)
    drawPath(path(0.7f to 0.62f, 0.95f to 0.58f, 1f to 0.85f, 0.78f to 0.92f, closed = true), wood)
    val contour = Color(0x30A0784E)
    for (i in 0 until 9) {
        val y = 0.1f + i * 0.1f
        drawPath(path(0f to y, 0.25f to y - 0.04f, 0.5f to y + 0.03f, 0.75f to y - 0.02f, 1f to y + 0.04f), contour, style = Stroke(1.dp.toPx()))
    }
    val water = Color(0xFFAAD0E2)
    drawPath(path(0f to 0.42f, 0.2f to 0.38f, 0.42f to 0.44f, 0.6f to 0.36f, 0.8f to 0.42f, 1f to 0.36f), water,
        style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
    val town = Color(0xFFE7DCD5)
    fun village(x: Float, y: Float) {
        for ((dx, dy) in listOf(0f to 0f, 0.035f to 0.01f, 0.01f to 0.022f, 0.05f to 0.03f, -0.02f to 0.03f))
            drawRect(town, topLeft = p(x + dx, y + dy), size = Size(w * 0.03f, h * 0.012f))
    }
    village(0.4f, 0.37f); village(0.2f, 0.26f); village(0.8f, 0.6f)
    val main = path(0f to 0.5f, 0.3f to 0.47f, 0.45f to 0.4f, 0.8f to 0.6f, 1f to 0.66f)
    drawPath(main, Color(0xFFD8C9A8), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
    drawPath(main, Color(0xFFF7E7A6), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    val side = path(0.45f to 0.4f, 0.3f to 0.28f, 0.15f to 0.22f, 0f to 0.15f)
    drawPath(side, Color(0xFFD9D2C6), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(side, Color.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    val trail = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    drawPath(path(0.8f to 0.6f, 0.9f to 0.5f, 0.86f to 0.4f, 1f to 0.3f), Color(0xFFB4825C), style = trail)
    drawPath(path(0.15f to 0.22f, 0.05f to 0.4f, 0f to 0.48f), Color(0xFFB4825C), style = trail)
    // the chosen loop (route blue, white casing), from Monreal up to the castles, over the heights and back by the stream
    val loop = path(0.43f to 0.38f, 0.36f to 0.33f, 0.33f to 0.26f, 0.37f to 0.22f, 0.33f to 0.17f, 0.42f to 0.14f, 0.5f to 0.165f,
        0.58f to 0.13f, 0.68f to 0.17f, 0.77f to 0.2f, 0.8f to 0.27f, 0.74f to 0.31f, 0.79f to 0.35f, 0.68f to 0.37f, 0.56f to 0.41f,
        0.43f to 0.38f)
    drawPath(loop, Color.White, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(loop, OnPathColors.Route, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(Color.White, 9.dp.toPx(), p(0.43f, 0.38f))
    drawCircle(OnPathColors.Route, 6.dp.toPx(), p(0.43f, 0.38f))
    names(this, listOf(Triple("Monreal", 0.36f, 0.42f), Triple("Löwenburg", 0.3f, 0.14f), Triple("Reudelsterz", 0.16f, 0.32f),
        Triple("Elztal", 0.74f, 0.66f)))
}

private fun names(scope: DrawScope, labels: List<Triple<String, Float, Float>>) = with(scope) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true; color = android.graphics.Color.rgb(0x55, 0x55, 0x4C); textSize = 13.dp.toPx()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val halo = android.graphics.Paint(paint).apply {
        color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3.dp.toPx()
    }
    for ((s, x, y) in labels) {
        drawContext.canvas.nativeCanvas.drawText(s, size.width * x, size.height * y, halo)
        drawContext.canvas.nativeCanvas.drawText(s, size.width * x, size.height * y, paint)
    }
}
