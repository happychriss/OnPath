package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-03 (comparison card 3): the plan's mode pill says whether the route works without a network –
 * "PLANEN · Ohne Netz ✓" (map along the route + the corridor's routing data on the phone), "⟳" (all that is missing is
 * on its way), "⚠" (something missing and not loading). A tap explains it in one line.
 */
class OfflineReadyTest {
    private val a = GeoPoint(51.1673, 6.9274)
    private val b = GeoPoint(51.1700, 6.9350)
    private val line = listOf(a, b)
    private val cellA = CellId.at(a)!!
    private val here = mapOf(cellA to OfflineReady.Cell.HERE)

    @Test fun noRouteNoLabel() {
        assertNull(OfflineReady.of(listOf(a), here, emptyList(), { true }, emptySet()))
    }

    @Test fun mapAndWaysOnThePhoneIsReady() {
        val r = OfflineReady.of(line, here, listOf("E5_N50_1_2"), { true }, emptySet())!!
        assertEquals(OfflineReady.State.READY, r.state)
        assertFalse(r.waysMissing)
    }

    @Test fun everythingMissingOnItsWayIsLoading() {
        val r = OfflineReady.of(line, mapOf(cellA to OfflineReady.Cell.LOADING), listOf("p1", "p2"), { it == "p1" },
            setOf("p2"), loadingBytes = 12L * 1_048_576)!!
        assertEquals(OfflineReady.State.LOADING, r.state)
        assertTrue(r.waysMissing)
        assertEquals(12L * 1_048_576, r.loadingBytes)
    }

    @Test fun somethingMissingAndNotLoadingIsMissing() {
        // a routing part not in the loader's list (no network, Wi-Fi only, failed)
        assertEquals(OfflineReady.State.MISSING,
            OfflineReady.of(line, here, listOf("p1"), { false }, emptySet())!!.state)
        // a map region waiting for Wi-Fi beside one that downloads
        assertEquals(OfflineReady.State.MISSING,
            OfflineReady.of(line, mapOf(cellA to OfflineReady.Cell.MISSING), emptyList(), { true }, emptySet())!!.state)
    }

    @Test fun cellStatesMapToHereLoadingMissing() {
        assertEquals(OfflineReady.Cell.HERE, OfflineReady.cell(null, onPhone = true))
        assertEquals(OfflineReady.Cell.HERE, OfflineReady.cell(CellState.Downloaded, onPhone = false))
        assertEquals(OfflineReady.Cell.LOADING, OfflineReady.cell(CellState.Downloading(1, 10), onPhone = false))
        assertEquals(OfflineReady.Cell.LOADING, OfflineReady.cell(CellState.Building("base", 0.4, null), onPhone = false))
        assertEquals(OfflineReady.Cell.MISSING, OfflineReady.cell(CellState.WaitingForWifi(null), onPhone = false))
        assertEquals(OfflineReady.Cell.MISSING, OfflineReady.cell(CellState.Offline("no net"), onPhone = false))
        assertEquals(OfflineReady.Cell.MISSING, OfflineReady.cell(null, onPhone = false))
    }

    @Test fun missingMapIsMeasuredAlongTheLine() {
        val r = OfflineReady.of(line, mapOf(cellA to OfflineReady.Cell.MISSING), emptyList(), { true }, emptySet())!!
        assertEquals(Geo.distance(a, b), r.missingMapM, 1.0)
        assertEquals(0.0, OfflineReady.lengthOver(line, emptySet()), 0.0)
    }

    @Test fun theTapExplainsInPlainWords() {
        assertTrue(OfflineReady.explain(OfflineReady.Result(OfflineReady.State.READY, 0.0, false, 0)).contains("without a network"))
        assertTrue(OfflineReady.explain(OfflineReady.Result(OfflineReady.State.LOADING, 0.0, true, 12L * 1_048_576)).contains("12 MB"))
        val missing = OfflineReady.explain(OfflineReady.Result(OfflineReady.State.MISSING, 3_000.0, false, 0))
        assertTrue(missing, missing.contains("3") && missing.contains("map"))
        assertTrue(OfflineReady.explain(OfflineReady.Result(OfflineReady.State.MISSING, 0.0, true, 0)).contains("Ways"))
    }

    @Test fun thePillCarriesItAndATapExplains() {
        val bar = File("src/main/java/com/onpath/app/ui/PlanTopBar.kt").readText()
        val label = bar.substringAfter("internal fun PlanModeLabel(").substringBefore("\n}\n")
        assertTrue(label.contains("OfflineReady.pillWord") && label.contains("onClick = onOffline"))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("offline = vm.offlineReady?.state") && map.contains("onOffline = vm::toggleOfflineInfo"))
        assertTrue("off the main thread", map.contains("withContext(Dispatchers.IO) { vm.computeOfflineReady(l) }"))
    }
}
