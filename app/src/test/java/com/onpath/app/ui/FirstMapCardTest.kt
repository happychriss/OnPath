package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first map of the area (owner 2026-11-08, sketch A2; 2026-11-19 form A): after the phone setup the map for here
 * loads – the wait window shows it (the card's content went into it), never over the setup (field bug: the user had to
 * wait inside the onboarding); offline, the small card says so until the map is there.
 */
class FirstMapCardTest {
    private val own = CellId(543, 361)
    private val east = CellId(544, 361)
    private val mb = 1_048_576L
    private val here = CellJob(listOf(own, east), null, CellWhy.HERE)
    private val downloading = mapOf<CellId, CellState>(own to CellState.Downloading(6 * mb, 9 * mb), east to CellState.Queued(null, null))

    private fun of(job: CellJob? = here, states: Map<CellId, CellState> = downloading, sizes: Map<CellId, Long> = emptyMap(),
                   view: Boolean = true, setup: Boolean = false, plan: Boolean = true, available: Boolean = true) =
        FirstMapCard.of(job, states, sizes, view, setup, plan, available)

    @Test fun theWaitWindowShowsWhileTheMapForHereLoadsAfterTheSetup() {
        assertEquals(FirstMapCard.Ui.Loading, of())
        assertTrue(FirstMapCard.waits(here, downloading, emptyMap(), viewWithoutDetail = true, available = true))
        assertFalse("offline: the card, no window",
            FirstMapCard.waits(here, mapOf(own to CellState.Offline("x"), east to CellState.Offline("x")), emptyMap(), true, true))
    }

    @Test fun neverOverThePhoneSetup() = assertNull(of(setup = true))

    @Test fun goesByItselfWhenTheMapIsThere() {
        assertNull(of(states = mapOf(own to CellState.Ready("1"), east to CellState.Ready("1"))))
        assertNull("installed areas (no state any more)", of(states = emptyMap(), sizes = mapOf(own to 9 * mb, east to 8 * mb)))
    }

    @Test fun notWhereTheViewHasTheDetailedMap() = assertNull(of(view = false))

    @Test fun offlineSaysSo() {
        val off = mapOf<CellId, CellState>(own to CellState.Offline("no connection"), east to CellState.Offline("no connection"))
        assertEquals(FirstMapCard.Ui.Offline, of(states = off))
        // one area there, the other waits for the network: still offline
        assertEquals(FirstMapCard.Ui.Offline, of(states = mapOf(own to CellState.Ready("1"), east to CellState.Offline("x"))))
    }

    @Test fun preparedOnTheServerIsWaitedForToo() =
        assertEquals(FirstMapCard.Ui.Loading, of(states = mapOf(own to CellState.Building("base", 0.4, 60), east to CellState.Requested)))

    @Test fun onlyTheMapForHere_notAPointSearchOrRoute() {
        assertNull(of(job = CellJob(listOf(own), own, CellWhy.PLANNED)))
        assertNull(of(job = CellJob(listOf(own), own, CellWhy.SEARCHED)))
        assertNull(of(job = null))
    }

    @Test fun theBoxesAskForTheRest() {
        val failed = mapOf<CellId, CellState>(own to CellState.Failed("server"), east to CellState.Failed("server"))
        assertNull("failure: the HINWEIS box with Try again", of(states = failed))
        val wifi = mapOf<CellId, CellState>(own to CellState.WaitingForWifi(9 * mb), east to CellState.WaitingForWifi(8 * mb))
        assertNull("waiting for Wi-Fi: the mobile-data question", of(states = wifi))
    }

    @Test fun onlyInPlanWithDownloads() {
        assertNull(of(plan = false))
        assertNull(of(available = false))
    }

    /**
     * It replaces the grey offline pill, the "Overview only" pill and the sign beside while it shows; the wait window is
     * the map wait's ([MapDataFlow.mapLoadUi]), its Cancel stops the job.
     */
    @Test fun wiring() {
        val flow = File("src/main/java/com/onpath/app/ui/MapDataFlow.kt").readText()
        assertTrue(flow.contains("FirstMapCard.waits(job, states, sizes, viewWithoutDetail, cells.downloadsAvailable)"))
        assertTrue(flow.contains("if (firstMapWait) { firstMapWait = false;"))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("FirstMapCardView(firstCard"))
        assertTrue(map.contains("if (overviewOnly && firstCardTarget == null && firstCard == null"))
        assertTrue(map.contains("mapNoticeKind(it) == null && firstCard == null }"))
        assertTrue(map.contains("message == null && notice == null && firstCard == null"))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("FirstMapCard.of(mapData.cellJob, states, installed.associate { it.id to it.totalBytes }, mapData.viewWithoutDetail, setup.setupOpen,"))
    }
}
