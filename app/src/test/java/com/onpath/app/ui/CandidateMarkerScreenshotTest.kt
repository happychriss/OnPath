package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.map.CandidateMarker
import com.onpath.app.map.MapController
import com.onpath.app.poi.Poi
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.search.AddPreview
import com.onpath.app.search.Place
import com.onpath.app.ui.search.PlaceCard
import com.onpath.app.ui.search.PlaceCardActions
import com.onpath.app.ui.search.PlaceCardUi
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The candidate marker (owner 2026-10-31, replaces the orange search ring): a search result on the map, suggested but
 * not taken – dashed route-blue ring with "+" around the place's icon, its name, and the thin dashed preview line to
 * where it would join the route (WaypointInsertion.joinPoint); the place card below says what adding does. The map is
 * a stand-in; the marker is the map's own drawing ([CandidateMarker.draw]), the line ends where the app computes it.
 */
class CandidateMarkerScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    // a plan A → B east of Meran and a hut north of its middle (~0.8 km off the line)
    private val route = listOf(GeoPoint(46.660, 11.098), GeoPoint(46.662, 11.110), GeoPoint(46.659, 11.122),
        GeoPoint(46.661, 11.134), GeoPoint(46.660, 11.142))
    private val plan = listOf(route.first(), route.last())
    private val hut = Place("n1", "Meraner Hütte", "hut", 46.667, 11.120, ele = 1960,
        poi = Poi("n1", "hut", "Meraner Hütte", 46.667, 11.120, 1960))

    /** Screen dp of a point: a local metric projection, ~0.1 dp per metre, the plan in the upper half. */
    private fun at(p: GeoPoint, d: DrawScope): Offset = with(d) { atDp(p).let { Offset(it.x.dp.toPx(), it.y.dp.toPx()) } }

    /** The same in dp. */
    private fun atDp(p: GeoPoint): Offset {
        val s = 0.1
        return Offset((20 + (p.lon - 11.096) * 76_400 * s).toFloat(), (300 + (46.661 - p.lat) * 111_320 * s).toFloat())
    }

    private fun shot(locale: Locale) {
        val before = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val p = GeoPoint(hut.lat, hut.lon)
            val preview = AddPreview.of(plan, p, route, speedMps = 1.1)
            val join = WaypointInsertion.joinPoint(plan, p, route, preview.decision)!!
            paparazzi.snapshot {
                OnPathTheme {
                    Box(Modifier.fillMaxSize()) {
                        MapPlaceholder(Modifier.fillMaxSize())
                        Canvas(Modifier.fillMaxSize()) {
                            val blue = Color(android.graphics.Color.parseColor(MapController.ROUTE_COLOR))
                            val pts = route.map { at(it, this) }
                            pts.zipWithNext { a, b -> drawLine(Color.White, a, b, 9.dp.toPx(), StrokeCap.Round) }
                            pts.zipWithNext { a, b -> drawLine(blue, a, b, 6.dp.toPx(), StrokeCap.Round) }
                            // the preview line: thin, dashed (MapController.setCandidateJoin – width 2.5, dash 2 × width)
                            val w = CandidateMarker.JOIN_WIDTH.dp.toPx()
                            drawLine(blue.copy(alpha = 0.9f), at(p, this), at(join, this), w, StrokeCap.Round,
                                PathEffect.dashPathEffect(floatArrayOf(CandidateMarker.JOIN_DASH[0] * w, CandidateMarker.JOIN_DASH[1] * w)))
                            // route points 1 (start, green) and 2 (destination, red)
                            listOf(pts.first() to Color(0xFF2E7D32), pts.last() to Color(0xFFC62828)).forEach { (o, c) ->
                                drawCircle(Color.White, 15.dp.toPx(), o); drawCircle(c, 12.dp.toPx(), o)
                            }
                            // the hut's own map icon, then the pin on it (owner 2026-11-02: tip on the place)
                            val h = at(p, this)
                            drawCircle(Color.White, 10.dp.toPx(), h); drawCircle(Color(0xFF8D5524), 8.5.dp.toPx(), h)
                            drawIntoCanvas { cv ->
                                CandidateMarker.draw(cv.nativeCanvas, h.x, h.y, density, android.graphics.Color.parseColor(MapController.ROUTE_COLOR))
                            }
                        }
                        // its name chip under the tip, placed as the app places it (MapOverlayHost → PinChipAt)
                        val d = androidx.compose.ui.platform.LocalDensity.current
                        val hPx = with(d) { Offset(atDp(p).x.dp.toPx(), atDp(p).y.dp.toPx()) }
                        PinChipAt(hPx, topPx = with(d) { 100.dp.roundToPx() }, bottomPx = with(d) { 300.dp.roundToPx() }) {
                            PinChip(hut.title(), onClick = {})
                        }
                        Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                            PlanTopBar({}, PlanMenu(), Modifier.fillMaxWidth())
                            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp)) {
                                PlaceCard(PlaceCardUi(hut, null, plan.size, preview.subtitle, preview.label), PlaceCardActions())
                            }
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun candidateEn() = shot(Locale.US)
    @Test fun candidateDe() = shot(Locale.GERMANY)
}
