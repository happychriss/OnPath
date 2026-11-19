package com.onpath.app.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import com.onpath.app.ui.ActivityMapButton
import com.onpath.app.ui.CompassSpec
import com.onpath.app.ui.MapControls
import com.onpath.app.ui.MapFooter
import com.onpath.app.ui.MapLoadCallbacks
import com.onpath.app.ui.MapLoadPanel
import com.onpath.app.ui.MapLoadUi
import com.onpath.app.ui.MessagePlacement
import com.onpath.app.ui.MessageScreen
import com.onpath.app.ui.OnPathColors
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.PlanCallbacks
import com.onpath.app.ui.PlanControlsLayer
import com.onpath.app.ui.PlanMenu
import com.onpath.app.ui.PlanPanel
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.PlanTopBand
import com.onpath.app.ui.PlanTopBar
import com.onpath.app.ui.PlanUi
import com.onpath.app.ui.ScreenshotTest
import com.onpath.app.ui.SheetWithFooter
import com.onpath.app.ui.planTopBarDp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * SKETCH round (owner 2026-11-19, "one progress design, two forms" + "the wait window gets real progress"): pictures
 * for the owner, nothing is wired. A = "ich muss warten": one window over the map, one bar 0–100 over all steps, the
 * steps with ticks, time left, Cancel – the same frame for the first map, the map + routing data and a route. B = "ich
 * kann weitermachen": a small sign beside the map, a count for several loads, tap → details. "today" = the current
 * windows on the same map, for comparison. The map is a drawn stand-in (MapLibre does not render here).
 */
abstract class ProgressSketchShots(private val locale: Locale, fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    protected val de get() = Locale.getDefault().language == "de"
    protected fun t(deText: String, enText: String) = if (de) deText else enText

    private val mapTitle get() = t("Karte für deine Gegend", "Map for your area")

    /** A1: the first map of your area, early – the server prepares it (12 %, the server's time left). */
    protected val a1 get() = WaitWindowSketch(SketchWaitKind.MAP, mapTitle, 12, listOf(
        SketchStep(t("wird vorbereitet", "being prepared"), SketchStepState.NOW),
        SketchStep(t("laden", "load"), SketchStepState.LATER),
        SketchStep(t("einfügen", "add to the map"), SketchStepState.LATER),
    ), t("noch etwa 3 Minuten", "about 3 minutes left"))

    /** A2: the same window while it downloads (34 %), routing data missing = a fourth step. */
    protected val a2 get() = WaitWindowSketch(SketchWaitKind.MAP, mapTitle, 34, listOf(
        SketchStep(t("vorbereitet", "prepared"), SketchStepState.DONE),
        SketchStep(t("lädt …", "loading …"), SketchStepState.NOW),
        SketchStep(t("einfügen", "add to the map"), SketchStepState.LATER),
        SketchStep(t("Routendaten", "routing data"), SketchStepState.LATER),
    ), t("noch etwa 20 Sekunden", "about 20 seconds left"))

    /** A3: route calculation – the same frame, the legs as the steps, the check last. */
    protected val a3 get() = WaitWindowSketch(SketchWaitKind.ROUTE, t("Route wird berechnet", "Calculating the route"), 58, listOf(
        SketchStep("Algund → Stopp 2", SketchStepState.DONE),
        SketchStep("Stopp 2 → Leiter Alm", SketchStepState.NOW),
        SketchStep("Leiter Alm → Meran", SketchStepState.LATER),
        SketchStep(t("prüfen", "check"), SketchStepState.LATER),
    ), t("noch etwa 10 Sekunden", "about 10 seconds left"))

    @Test fun a1FirstMap() = shot(points = 0) { Scrim(); Window(a1) }
    @Test fun a2LoadingWithRoutingData() = shot(points = 0) { Scrim(); Window(a2) }
    @Test fun a3Route() = shot(points = 3) { Scrim(); Window(a3) }

    @Composable private fun Window(ui: WaitWindowSketch) = Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        WaitWindowSketchView(ui, onCancel = {})
    }

    /** The plan screen (as FirstMapCardScreenshotTest's frame) over the drawn map, then [over]. */
    protected fun shot(points: Int, over: @Composable () -> Unit) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                SketchMap(Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().background(PlanTopBand.Color).padding(top = 32.dp).height(planTopBarDp().dp))
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
                    ActivityMapButton(Activity.HIKE, 2, {}, Modifier.padding(top = rowTop.dp, start = 12.dp))
                    val sheetHeight = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    PlanControlsLayer({ sheetHeight.intValue }, planTopBarDp().dp) {
                        MapControls(CompassSpec(null, 0f) {}, showEye = false, onPeek = {}, gps = GpsStatus.GOOD, following = true,
                            onLocate = {}, onFitRoute = null, plan = true)
                    }
                    SheetWithFooter(planTopBarDp().dp, footer = { Column(Modifier.fillMaxWidth()) { MapFooter("500 m" to 64.dp) {} } },
                        sheet = { Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) {
                            PlanPanel(PlanUi(points, null, false, null, canUndo = points > 0, canRedo = false, activity = Activity.HIKE,
                                size = PlanSize.COMPACT), PlanCallbacks())
                        } })
                }
                over()
            }
        }
    }

    /** What a window over the map dims behind it (as Android's dialog). */
    @Composable protected fun Scrim() = Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
}

/** B and today: German and English (the order asks large font for A only). */
abstract class ProgressSketchBesideShots(locale: Locale) : ProgressSketchShots(locale, 1f) {
    /** B1: one load beside the map (maps along a long tour): the ring with its %, "lädt 34 %". */
    @Test fun b1Sign() = shot(points = 3) { Sign(BesideSignSketch(t("lädt 34 %", "loading 34 %"), 34)) }

    /** B2: several loads = the same sign with a count. */
    @Test fun b2SignCount() = shot(points = 3) { Sign(BesideSignSketch(t("lädt 34 %", "loading 34 %"), 34, count = 3)) }

    /** B3: the details after a tap – a bottom sheet, one row per load, each with the three texts. */
    @Test fun b3Details() = shot(points = 3) {
        Scrim()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = OnPathColors.Paper, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).background(OnPathColors.InkMuted.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                    }
                    BesideDetailsSketchView(t("Lädt im Hintergrund", "Loading in the background"), listOf(
                        BesideRowSketch(t("Etappe 2 · Brixen – Klausen", "Stage 2 · Brixen – Klausen"), t("lädt 34 %", "loading 34 %"), 34),
                        BesideRowSketch(t("Etappe 3 · Klausen – Bozen", "Stage 3 · Klausen – Bozen"), t("wird vorbereitet", "being prepared"), null),
                        BesideRowSketch(t("Routendaten", "Routing data"), t("fast fertig", "almost done"), null),
                    ), onCancelAll = {}, onClose = {})
                    Box(Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable private fun Sign(ui: BesideSignSketch) = Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
        BesideSignSketchView(ui, onClick = {}, modifier = Modifier.align(Alignment.TopEnd)
            .padding(top = MessagePlacement.CHIP_TOP_DP.dp, end = 12.dp))
    }

    /** Today: the map-load window (a Dialog over the map), the download at 34 %. */
    @Test fun todayMapLoad() = shot(points = 0) {
        Scrim()
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            MapLoadPanel(MapLoadUi(MapLoadUi.Kind.LOADING, 34, 20, regions = 1, bytes = 9L * 1_048_576), MapLoadCallbacks())
        }
    }

    /** Today: the route wait inside the plan sheet ("Abschnitt 2 von 4 wird berechnet…") – ScreenshotTest's picture. */
    @Test fun todayRouteWait() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }.planRoutingSlowProgress()
}

class ProgressSketchGermanTest : ProgressSketchBesideShots(Locale.GERMANY)
class ProgressSketchEnglishTest : ProgressSketchBesideShots(Locale.US)
class ProgressSketchLargeFontGermanTest : ProgressSketchShots(Locale.GERMANY, 2f)

/** A drawn stand-in for the map: land, wood, a river and lake, contour lines, roads, paths, villages and their names. */
@Composable
fun SketchMap(modifier: Modifier = Modifier) = Canvas(modifier.background(Color(0xFFF2EFE6))) {
    val w = size.width; val h = size.height
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    fun path(vararg pts: Pair<Float, Float>) = Path().apply {
        moveTo(w * pts[0].first, h * pts[0].second)
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            quadraticTo(w * x0, h * y0, w * (x0 + x1) / 2, h * (y0 + y1) / 2)
        }
        lineTo(w * pts.last().first, h * pts.last().second)
    }
    fun area(vararg pts: Pair<Float, Float>) = path(*pts).apply { close() }
    val wood = Color(0xFFD5E5C3)
    drawPath(area(0f to 0f, 0.45f to 0f, 0.38f to 0.12f, 0.22f to 0.2f, 0f to 0.26f), wood)
    drawPath(area(0.62f to 0.05f, 1f to 0f, 1f to 0.42f, 0.8f to 0.36f, 0.66f to 0.2f), wood)
    drawPath(area(0f to 0.55f, 0.18f to 0.5f, 0.3f to 0.66f, 0.2f to 0.9f, 0f to 0.95f), wood)
    drawPath(area(0.7f to 0.62f, 0.95f to 0.58f, 1f to 0.85f, 0.78f to 0.92f), wood)
    // contour lines
    val contour = Color(0x30A0784E)
    for (i in 0 until 9) {
        val y = 0.08f + i * 0.1f
        drawPath(path(0f to y, 0.25f to y - 0.04f, 0.5f to y + 0.03f, 0.75f to y - 0.02f, 1f to y + 0.04f), contour,
            style = Stroke(1.dp.toPx()))
    }
    // river and lake
    val water = Color(0xFFAAD0E2)
    drawPath(path(0.1f to 1f, 0.22f to 0.78f, 0.42f to 0.62f, 0.5f to 0.45f, 0.72f to 0.3f, 1f to 0.18f), water,
        style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
    drawOval(water, topLeft = p(0.06f, 0.33f), size = Size(w * 0.16f, h * 0.06f))
    // villages
    val town = Color(0xFFE7DCD5)
    fun village(x: Float, y: Float) {
        for ((dx, dy) in listOf(0f to 0f, 0.035f to 0.01f, 0.01f to 0.022f, 0.05f to 0.03f, -0.02f to 0.03f))
            drawRect(town, topLeft = p(x + dx, y + dy), size = Size(w * 0.03f, h * 0.012f))
    }
    village(0.55f, 0.5f); village(0.28f, 0.3f); village(0.78f, 0.72f)
    // roads: casing, then fill
    val main = path(0f to 0.47f, 0.3f to 0.44f, 0.56f to 0.52f, 0.8f to 0.74f, 1f to 0.8f)
    val side = path(0.56f to 0.52f, 0.5f to 0.3f, 0.3f to 0.31f, 0.2f to 0.12f, 0.25f to 0f)
    drawPath(main, Color(0xFFD8C9A8), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
    drawPath(main, Color(0xFFF7E7A6), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(side, Color(0xFFD9D2C6), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(side, Color.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    // paths
    val trail = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    drawPath(path(0.3f to 0.31f, 0.4f to 0.2f, 0.6f to 0.14f, 0.85f to 0.08f, 1f to 0.1f), Color(0xFFB4825C), style = trail)
    drawPath(path(0.8f to 0.74f, 0.9f to 0.6f, 0.85f to 0.45f, 1f to 0.38f), Color(0xFFB4825C), style = trail)
    drawPath(path(0.2f to 0.12f, 0.08f to 0.2f, 0f to 0.3f), Color(0xFFB4825C), style = trail)
    names(this, listOf(Triple("Algund", 0.53f, 0.49f), Triple("Partschins", 0.22f, 0.29f), Triple("Meran", 0.76f, 0.71f),
        Triple("Leiter Alm", 0.62f, 0.2f)))
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
