package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The candidate marker (owner 2026-10-31): the search result / shared place is drawn as "suggested, not yet taken" –
 * dashed route blue, no orange (= recording) – with a dashed preview line to where adding it joins the route, decided
 * exactly as the add itself (pure part: WaypointInsertionTest.join…).
 */
class CandidateMarkerTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private val ctl = File("src/main/java/com/onpath/app/map/MapController.kt").readText()

    @Test fun noOrangeRingAnyMore() {
        val mark = ctl.substringAfter("fun setSearchMark(").substringBefore("\n    }\n")
        assertTrue(mark.contains("CandidateMarker.bitmap("))
        assertFalse(mark.contains("#F28C00"))
        assertFalse(mark.contains("#B35C00"))
    }

    /** Owner 2026-11-02: a pin, its tip on the place; the name is the chip under it, not a map label. */
    @Test fun thePinStandsOnThePlaceAndHasNoMapLabel() {
        val mark = ctl.substringAfter("fun setSearchMark(").substringBefore("\n    }\n")
        assertTrue(mark.contains("PF.iconAnchor(Property.ICON_ANCHOR_BOTTOM), PF.iconOffset(arrayOf(0f, CandidateMarker.TIP_MARGIN_DP))"))
        assertFalse("no name label on the map", mark.contains("textField"))
    }

    @Test fun previewLineUsesTheAddDecision() {
        // addPoint and the preview share one decision (insertionFor): the line never promises another place
        val add = vm.substringAfter("fun addPoint(").substringBefore("\n    }\n")
        assertTrue(add.contains("val ins = insertionFor(p, lineSnapM)"))
        val join = vm.substringAfter("fun candidateJoin(").substringBefore("\n    }\n")
        assertTrue(join.contains("insertionFor(p, 30.0)"))
        assertTrue(join.contains("WaypointInsertion.joinPoint(ins.points, p, ins.line, ins.decision)"))
        // only in Plan with a route, never for a place that already is a route point
        assertTrue(join.contains("if (!canEditMap || waypoints.size < 2) return null"))
        assertTrue(join.contains("if (waypoints.any { sameSpot(it, m.lat, m.lon) }) return null"))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("snapshotFlow { vm.candidateJoin() }.distinctUntilChanged().collect { c.setCandidateJoin(it?.first, it?.second) }"))
        // the eye hides the preview line with the other route lines
        assertTrue(ctl.contains("SRC_GAPS, SRC_SPOT, SRC_MARK_JOIN,"))
    }

    @Test fun takenPlaceDropsTheCandidate() {
        val place = vm.substringAfter("fun addPlace(").substringBefore("\n    }\n")
        assertTrue(place.contains("clearSearchMarkAt(place.lat, place.lon)"))
    }
}
