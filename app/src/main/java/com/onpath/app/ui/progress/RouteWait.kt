package com.onpath.app.ui.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.onpath.app.data.ProgressMemory
import com.onpath.app.debug.Problems
import com.onpath.app.domain.OneBar
import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.TimeLeft
import com.onpath.app.domain.WaitStep
import com.onpath.app.domain.WaitSteps
import com.onpath.app.routing.PlanPointRow
import com.onpath.strings.*

/**
 * The route's wait window while a calculation takes a while ([slow]) and no question is open; Cancel stops it (the change
 * that started it is taken back – PlanRunSlice.cancelRouting). Ticks every second; the tracker runs on for quick runs too
 * (it learns their check's share).
 */
@Composable
fun RouteWaitWindow(
    memory: ProgressMemory, routing: Boolean, slow: Boolean, progress: Pair<Int, Int>?, share: Float?, labels: List<String>?,
    gen: Int, onCancel: () -> Unit,
) {
    val wait = remember(memory) { RouteWait(memory) }
    var ui by remember { mutableStateOf<RouteWaitUi?>(null) }
    val p by rememberUpdatedState(progress)
    val s by rememberUpdatedState(share)
    val l by rememberUpdatedState(labels)
    val g by rememberUpdatedState(gen)
    LaunchedEffect(routing) {
        while (true) {
            ui = wait.update(routing, p, s, l, android.os.SystemClock.uptimeMillis(), g)
            if (!routing) break
            kotlinx.coroutines.delay(TICK_MS)
        }
    }
    val shown = ui ?: return
    if (!routing || !slow) return
    WaitWindowDialog(shown.window, listOf(WaitButton(str(Res.string.common_cancel), onCancel)))
}

/** How often the route's window moves (its bar creeps smoothly between the router's reports). */
private const val TICK_MS = 500L
