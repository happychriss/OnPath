package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.SnapshotHandler
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Download progress ticks must not recompose the plan sheet (it janks while map areas download); only a really changed
 * card does (rememberDerived).
 */
class CellStateReadsTest {
    private object NoImages : SnapshotHandler {
        override fun newFrameHandler(snapshot: app.cash.paparazzi.Snapshot, frameCount: Int, fps: Int) =
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(SnapshotHandler.FrameHandler::class.java)) { _, _, _ -> null } as SnapshotHandler.FrameHandler
        override fun close() = Unit
    }

    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, snapshotHandler = NoImages)

    @Before fun on() { RecomposeProbe.reset(); RecomposeProbe.enabled = true }
    @After fun off() { RecomposeProbe.enabled = false }

    private val a = CellId(543, 361)

    @Test fun downloadTicksDoNotRecomposeTheSheet() {
        val states = MutableStateFlow<Map<CellId, CellState>>(emptyMap())
        val installed = MutableStateFlow(listOf<Int>())
        var ticks = 0
        paparazzi.gif("ticks", 0, 1500, 60) {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                // 50 download ticks of one area (bytes grow), then it is installed (the card changes once)
                for (k in 1..50) withFrameNanos { states.value = mapOf(a to CellState.Downloading(k * 100_000L, 10_000_000L)); ticks++ }
                withFrameNanos { states.value = mapOf(a to CellState.Ready("v2")) }
            }
            Sheet(states, installed)
        }
        assertEquals(50, ticks)
        assertEquals("the sheet composed once for 50 download ticks", 1, RecomposeProbe.count("sheet"))
        // "ready" (nothing loads) → "loading" for all 50 ticks → "ready": the card composes for the two changes only
        assertEquals(3, RecomposeProbe.count("card"))
    }

    @Composable
    private fun Sheet(states: MutableStateFlow<Map<CellId, CellState>>, installed: MutableStateFlow<List<Int>>) {
        RecomposeProbe.hit("sheet")
        val card = rememberDerived(states, installed) { s, _ -> if (s.values.any { it is CellState.Downloading }) "loading" else "ready" }
        Box { Card(card) }
    }

    @Composable
    private fun Card(text: androidx.compose.runtime.State<String>) {
        RecomposeProbe.hit("card")
        Text(text.value)
    }
}
