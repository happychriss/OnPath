package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.recording.GpsStatus
import com.onpath.app.recording.RecState
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/** JVM screenshots of the non-map UI at Pixel 8 size (map replaced by a placeholder). */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1080, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420),
        ),
        showSystemUi = false,
    )

    private val routePts = (0..120).map { i ->
        GeoPoint(46.67 + i * 0.0004, 11.16 + i * 0.0003, 350.0 + 600 * sin(i / 38.0) + (i % 7))
    }
    private val route = PlannedRoute(listOf(PlannedLeg(routePts, false)), 12_480.0, 842.0, 310.0, 4.6 * 3600, null)

    @Composable
    private fun Frame(bottom: @Composable () -> Unit) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 40.dp)) {
                    GpsChip(GpsStatus.GOOD, "± 4 m", onClick = {})
                    Spacer(Modifier.weight(1f))
                    ActivityChip(Activity.HIKE, 3, onClick = {})
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 10.dp)) {
                        Spacer(Modifier.weight(1f))
                        FloatingActionButton(onClick = {}, containerColor = Color.White, shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.size(60.dp)) { Icon(Icons.Filled.GpsFixed, null) }
                    }
                    bottom()
                }
            }
        }
    }

    @Test fun home() = paparazzi.snapshot {
        Frame { BrowseBar({}, {}, {}, followName = null, onStopFollow = {}) }
    }

    @Test fun plan() = paparazzi.snapshot {
        Frame {
            PlanPanel(3, route, routing = false, error = null, canUndo = true,
                {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun planError() = paparazzi.snapshot {
        Frame {
            PlanPanel(2, route.copy(legs = listOf(PlannedLeg(routePts.take(2), true))), routing = true,
                error = "Leg 1→2: No route found near this point. Shown as a straight dashed line. Try moving the point or changing the activity.",
                canUndo = true, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun recording() = paparazzi.snapshot {
        Frame {
            RecordPanel(
                RecState(1, false, 5320.0, 412.0, 120.0, 5025, false, 63.0, true, "Waalweg", emptyList()),
                GpsStatus.GOOD, {}, {}, {},
            )
        }
    }

    @Test fun track() = paparazzi.snapshot {
        Frame {
            TrackPanel(ShownTrack(1, "Waalweg Algund – Meran", listOf(routePts), emptyList(), 12_480.0, 842.0, 310.0, 4.6 * 3600),
                isFollowed = false, recording = false, {}, {}, {}, {})
        }
    }

    @Test fun tours() = paparazzi.snapshot {
        OnPathTheme {
            ToursScreen(
                listOf(
                    Tour(1, TourKind.PLANNED, "Route Meran – Hirzer", 1_760_000_000_000, "HIKE", 3, 14_200.0, 1210.0, 300.0, 6.1 * 3600, "done", emptyList()),
                    Tour(2, TourKind.RECORDED, "Tour 12 Oct 2026, 09:14", 1_760_200_000_000, "MTB", 3, 32_800.0, 940.0, 940.0, 3.2 * 3600, "done", emptyList()),
                    Tour(3, TourKind.IMPORTED, "Komoot – Waalweg Algund", 1_760_400_000_000, "HIKE", 3, 8_100.0, 210.0, 190.0, 2.1 * 3600, "done", emptyList()),
                ),
                {}, {}, {}, {}, {},
            )
        }
    }

    @Test fun missingData() = paparazzi.snapshot {
        OnPathTheme {
            MissingDataScreen(
                listOf(
                    DataFiles.Check("base.pmtiles", true, "Base map"),
                    DataFiles.Check("terrain.pmtiles", false, "Relief / elevation"),
                    DataFiles.Check("routes.geojson", true, "Marked route networks"),
                    DataFiles.Check("trails.geojson", false, "Trail difficulty"),
                    DataFiles.Check("segments/*.rd5", false, "Routing data"),
                ),
                "/storage/emulated/0/Android/data/com.onpath.app/files/onpath",
                {}, {}, {},
            )
        }
    }
}
