package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.SnapshotHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Plan ⇄ Navigate transition (ModeSwitch): the control layers animate, the map beside them is
 * neither recomposed, re-laid out nor recreated; at the end of each transition only the new mode's layer is left.
 * Frames run on the JVM (Paparazzi, no images kept), like SheetSmoothnessTest.
 */
class ModeSwitchTest {
    private object NoImages : SnapshotHandler {
        override fun newFrameHandler(snapshot: app.cash.paparazzi.Snapshot, frameCount: Int, fps: Int) =
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(SnapshotHandler.FrameHandler::class.java)) { _, _, _ -> null } as SnapshotHandler.FrameHandler
        override fun close() = Unit
    }

    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, snapshotHandler = NoImages)

    @Before fun on() { RecomposeProbe.reset(); RecomposeProbe.enabled = true }
    @After fun off() { RecomposeProbe.enabled = false }

    /** Like AppViewModel: an unstable object the map host gets (compared by instance). */
    private class FakeVm { var taps = 0 }

    /** Stands in for MapHost: counts compositions, measures and creations (a MapView is created once in remember). */
    @Composable
    private fun MapProbe(vm: FakeVm, onCamera: () -> Unit, modifier: Modifier) {
        RecomposeProbe.hit("map")
        remember { RecomposeProbe.hit("map-created"); Any() }
        Box(modifier.layout { m, c ->
            RecomposeProbe.hit("map-measure")
            val p = m.measure(c)
            layout(p.width, p.height) { RecomposeProbe.hit("map-place"); p.place(0, 0) }
        }) { Text("map ${vm.taps}") }
        DisposableEffect(Unit) { onDispose { onCamera() } }
    }

    @Composable
    private fun Layer(name: String) {
        DisposableEffect(Unit) { live.add(name); onDispose { live.remove(name) } }
        Box(Modifier.fillMaxSize()) {
            Text("$name top", Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP))
            Text("$name bottom", Modifier.align(Alignment.BottomCenter).modeEdge(ModeEdge.BOTTOM))
        }
    }

    private val live = mutableSetOf<String>()

    private data class Seen(val atMs: Long, val live: Set<String>)

    /** Runs Plan → Navigate → Plan; returns what was live at the given milliseconds after each switch. */
    private fun run(durationMs: Int, lookAt: List<Long>): Pair<List<Seen>, List<Seen>> {
        val toNav = mutableListOf<Seen>()
        val toPlan = mutableListOf<Seen>()
        val vm = FakeVm()
        paparazzi.gif("switch", 0, 2000, 60) {
            var mode by remember { mutableStateOf(AppMode.PLAN) }
            var cameraTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                for ((target, out) in listOf(AppMode.NAVIGATE to toNav, AppMode.PLAN to toPlan)) {
                    var t0 = -1L
                    withFrameNanos { t0 = it / 1_000_000; mode = target }
                    val pending = lookAt.toMutableList()
                    while (pending.isNotEmpty()) withFrameNanos { now ->
                        val dt = now / 1_000_000 - t0
                        while (pending.isNotEmpty() && dt >= pending.first()) { pending.removeAt(0); out.add(Seen(dt, live.toSet())) }
                    }
                }
            }
            OnPathTheme {
                // like MapScreen: the parent reads the mode, the map is a sibling of the switching layers
                Box(Modifier.fillMaxSize()) {
                    MapProbe(vm, onCamera = { cameraTick++ }, modifier = Modifier.fillMaxSize())
                    ModeSwitch(mode, plan = { Layer("plan") }, navigate = { Layer("navigate") }, durationMs = durationMs)
                    Text("mode $mode", Modifier.align(Alignment.Center))
                }
            }
        }
        return toNav to toPlan
    }

    private fun mapUntouched() {
        assertEquals("map composed once", 1, RecomposeProbe.count("map"))
        assertEquals("map created once", 1, RecomposeProbe.count("map-created"))
        assertEquals("map measured once", 1, RecomposeProbe.count("map-measure"))
        assertEquals("map placed once", 1, RecomposeProbe.count("map-place"))
    }

    @Test fun animatedSwitchLeavesTheMapAloneAndEndsWithOneLayer() {
        val (toNav, toPlan) = run(ModeMotion.DURATION_MS, listOf(100L, 600L))
        println("MODE toNav=$toNav toPlan=$toPlan map=${RecomposeProbe.count("map")}/${RecomposeProbe.count("map-measure")}")
        // mid-transition both layers are there (the leaving one fades, no flicker); at the end only the new one
        assertEquals(setOf("plan", "navigate"), toNav[0].live)
        assertEquals(setOf("navigate"), toNav[1].live)
        assertEquals(setOf("plan", "navigate"), toPlan[0].live)
        assertEquals(setOf("plan"), toPlan[1].live)
        mapUntouched()
    }

    @Test fun reducedMotionSwitchesAtOnce() {
        val (toNav, toPlan) = run(ModeMotion.durationMs(0f), listOf(40L))
        println("MODE instant toNav=$toNav toPlan=$toPlan")
        assertEquals(setOf("navigate"), toNav[0].live)
        assertEquals(setOf("plan"), toPlan[0].live)
        mapUntouched()
    }

    @Test fun durationFollowsTheAnimatorScale() {
        assertEquals(0, ModeMotion.durationMs(0f))
        assertEquals(0, ModeMotion.durationMs(Float.NaN))
        assertEquals(250, ModeMotion.durationMs(1f))
        // other scales: Compose itself stretches / shortens the 250 ms (MotionDurationScale)
        assertEquals(250, ModeMotion.durationMs(0.5f))
        assertTrue(ModeMotion.traceLabel(AppMode.NAVIGATE).endsWith("Navigate"))
        assertTrue(ModeMotion.traceLabel(AppMode.PLAN).endsWith("Plan"))
    }
}
