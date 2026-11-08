package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellWhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Field bug 2026-11-08 (owner, fresh install): "Wenn man die App zum ersten Mal installiert und den Standort freigibt,
 * dann beginnt sie sofort noch mitten im Onboarding die Karte herunterzuladen. Da muss man warten …" – the first fix
 * after the location step loaded the map for here (T3) and, the empty world map counting as "view without detailed
 * map", opened the loading modal (a Dialog window) over the phone setup guide.
 */
class MapLoadWaitTest {
    private val own = CellId(543, 361)
    private val east = CellId(544, 361)

    /** What loadHere hands over: the T3 job, its own cell cleared ("no loading window – the user only opened the app"). */
    private val here = CellJob(listOf(own, east), null, CellWhy.HERE)

    @Test fun theMapForHereNeverOpensTheModal_evenOnAnEmptyWorldMap() {
        assertFalse("fresh install: the world map has no detailed map", MapLoadWait.waits(here, viewWithoutDetail = true))
        assertFalse(MapLoadWait.waits(here, viewWithoutDetail = false))
    }

    @Test fun theUsersOwnPointSearchOrTapStillWaits() {
        assertTrue(MapLoadWait.waits(CellJob(listOf(own), own, CellWhy.PLANNED), viewWithoutDetail = false))
        assertTrue(MapLoadWait.waits(CellJob(listOf(own), own, CellWhy.SEARCHED), viewWithoutDetail = false))
        assertTrue(MapLoadWait.waits(CellJob(listOf(own), own, CellWhy.TAPPED), viewWithoutDetail = false))
    }

    @Test fun aRouteComputedWhileTheViewHasNoDetailedMapStillWaits() {
        val route = CellJob(listOf(own, east), null, CellWhy.PLANNED)
        assertTrue(MapLoadWait.waits(route, viewWithoutDetail = true))
        assertFalse("a route with the map in view loads quietly", MapLoadWait.waits(route, viewWithoutDetail = false))
    }

    @Test fun noModalOverThePhoneSetup() {
        val ui = MapLoadUi(MapLoadUi.Kind.LOADING, 40, 12, regions = 2, bytes = 18L * 1_048_576)
        assertNull(MapLoadWait.shown(ui, setupOpen = true))
        assertEquals(ui, MapLoadWait.shown(ui, setupOpen = false))
        assertNull(MapLoadWait.shown(null, setupOpen = false))
    }

    /** The rule is what the ViewModel and the screen use (Compose / ViewModel wiring is not run on the JVM). */
    @Test fun wiring() {
        val flow = File("src/main/java/com/onpath/app/ui/MapDataFlow.kt").readText()
        val show = flow.substringAfter("private fun showCellJob(").substringBefore("\n    }\n")
        assertTrue(show, show.contains("val waits = MapLoadWait.waits(j, viewWithoutDetail)"))
        assertTrue(show, show.contains("routingForCells(j.cells, wait = waits)"))
        assertTrue(show, show.contains("if (waits) {"))
        assertFalse("no second, looser wait rule", show.contains("viewWithoutDetail) {"))
        // loadHere: the T3 job without its own cell, routing data without the modal
        val here = flow.substringAfter("private fun loadHere(").substringBefore("\n    }\n")
        assertTrue(here, here.contains("?.copy(own = null)"))
        assertTrue(here, here.contains("routingFor(listOf(at), wait = false)"))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("MapLoadModal(MapLoadWait.shown(loadUi, vm.setupOpen),"))
    }
}
