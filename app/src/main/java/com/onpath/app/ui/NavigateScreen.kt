package com.onpath.app.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.data.Format
import com.onpath.app.data.FollowProgress
import com.onpath.app.recording.RecState
import com.onpath.app.routing.GeoPoint

/** What the Navigate screen shows (stateless, so screenshot tests can build it). */
data class NavUi(
    /** What is left of the followed line; null for free recording or without a fix. */
    val toGo: ToGo? = null,
    /** Recorded so far; null when no recording runs (e.g. an unfinished tour is being asked about). */
    val recordedM: Double? = null,
    val elapsedS: Long = 0,
    val paused: Boolean = false,
    val waitingGps: Boolean = false,
    /** Distance off the route while the off-route warning is active. */
    val offRouteM: Int? = null,
)

data class ToGo(val distanceM: Double, val ascentM: Double, val timeS: Double, val offM: Double?)

/**
 * Navigate mode: full map following the user, what is left on top, slide-to-plan at the bottom.
 * Map taps change nothing here (MapHost checks AppViewModel.canEditMap).
 */
@Composable
fun NavigateOverlay(
    ui: NavUi,
    footer: @Composable () -> Unit,
    onSlideToPlan: () -> Unit,
    onResume: () -> Unit = {},
    slideStartFraction: Float = 0f,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.toGo?.let { RemainingStrip(it.distanceM, it.ascentM, it.timeS, it.offM) }
            ui.recordedM?.let { RecordingChip(it, ui.elapsedS, ui.paused, ui.waitingGps, onResume) }
            ui.offRouteM?.let { OffRouteChip(it) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            footer()
            SlideToPlan(onSlideToPlan, Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                startFraction = slideStartFraction)
        }
    }
}

/** "● Recording · 5.3 km · 1:23:45"; while paused with a Resume button. */
@Composable
private fun RecordingChip(distanceM: Double, elapsedS: Long, paused: Boolean, waitingGps: Boolean, onResume: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 4.dp) {
        Row(Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = if (paused) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (paused) OnPathColors.Grey else OnPathColors.Rec, CircleShape))
            Spacer(Modifier.width(8.dp))
            val status = when { paused -> "Paused"; waitingGps -> "Waiting for GPS"; else -> "Recording" }
            Text("$status · ${Format.distance(distanceM)} · ${Format.clock(elapsedS)}", fontSize = 13.sp)
            if (paused) TextButton(onClick = onResume) { Text("Resume") }
        }
    }
}

@Composable
private fun OffRouteChip(offM: Int) {
    Surface(shape = RoundedCornerShape(16.dp), color = OnPathColors.Bad, shadowElevation = 4.dp) {
        Text("Off route · $offM m away", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** Stateful wrapper: remaining distance along the followed line and the arrival check. */
@Composable
internal fun NavigateMode(vm: AppViewModel, rec: RecState?, location: Location?, footer: @Composable () -> Unit) {
    val line = vm.followLine
    val progress = remember(line) { line?.let { FollowProgress(it) } }
    val hint = remember(line) { intArrayOf(0) }
    val here = location?.let { GeoPoint(it.latitude, it.longitude) }
    val remaining = remember(progress, location) {
        if (progress == null || here == null) null else progress.remaining(here, hint[0])?.also { hint[0] = it.index }
    }
    LaunchedEffect(location) { if (here != null) vm.checkArrival(here, remaining?.distanceM) }
    val ui = NavUi(
        toGo = remaining?.let { ToGo(it.distanceM, it.ascentM, FollowProgress.timeS(it, vm.activity), it.offM) },
        recordedM = rec?.distanceM, elapsedS = rec?.elapsedS ?: 0, paused = rec?.paused == true,
        waitingGps = rec?.gpsLost == true,
        offRouteM = rec?.offRouteM?.takeIf { rec.offRouteAlert }?.toInt(),
    )
    NavigateOverlay(ui, footer, onSlideToPlan = vm::switchToPlan, onResume = { vm.pauseRecording(false) })
}
