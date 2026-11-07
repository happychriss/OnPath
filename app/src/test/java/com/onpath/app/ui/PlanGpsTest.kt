package com.onpath.app.ui

import com.onpath.app.recording.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-06 23:35: "Beim Planen muss das Handy auch ruhig sein und keine GPS-Warnung machen." What Plan shows for
 * each GPS state – and that Navigate keeps its signs.
 */
class PlanGpsTest {
    private val all = GpsStatus.entries

    @Test fun planShowsNoGpsDotInAnyState_navigateKeepsIt() {
        assertFalse(PlanGps.showDot(plan = true))
        assertTrue(PlanGps.showDot(plan = false))
    }

    @Test fun planGreysLocateOnlyWithoutAPosition() {
        for (s in all) {
            assertTrue("no fix, $s", PlanGps.locateGreyed(plan = true, status = s, hasFix = false))
            assertFalse("Navigate never greys it, $s", PlanGps.locateGreyed(plan = false, status = s, hasFix = false))
        }
        assertFalse(PlanGps.locateGreyed(plan = true, status = GpsStatus.GOOD, hasFix = true))
        assertFalse("weak GPS is still a position – no amber, no grey", PlanGps.locateGreyed(plan = true, status = GpsStatus.WEAK, hasFix = true))
        assertTrue("an old fix while searching is no position to go to", PlanGps.locateGreyed(plan = true, status = GpsStatus.SEARCHING, hasFix = true))
    }

    @Test fun locateTapInPlan() {
        assertEquals(PlanGps.Tap.CENTRE, PlanGps.tap(plan = true, status = GpsStatus.GOOD, hasFix = true))
        assertEquals(PlanGps.Tap.CENTRE, PlanGps.tap(plan = true, status = GpsStatus.WEAK, hasFix = true))
        assertEquals(PlanGps.Tap.WAIT_AND_NOTE, PlanGps.tap(plan = true, status = GpsStatus.SEARCHING, hasFix = false))
        assertEquals(PlanGps.Tap.WAIT_AND_NOTE, PlanGps.tap(plan = true, status = GpsStatus.SEARCHING, hasFix = true))
        assertEquals(PlanGps.Tap.OPEN_SETTINGS, PlanGps.tap(plan = true, status = GpsStatus.OFF, hasFix = false))
        assertEquals(PlanGps.Tap.ASK_PERMISSION, PlanGps.tap(plan = true, status = GpsStatus.NO_PERMISSION, hasFix = false))
    }

    @Test fun locateTapInNavigateUnchanged() {
        assertEquals(PlanGps.Tap.CENTRE, PlanGps.tap(plan = false, status = GpsStatus.SEARCHING, hasFix = false))
        assertEquals(PlanGps.Tap.OPEN_SETTINGS, PlanGps.tap(plan = false, status = GpsStatus.OFF, hasFix = false))
        assertEquals(PlanGps.Tap.ASK_PERMISSION, PlanGps.tap(plan = false, status = GpsStatus.NO_PERMISSION, hasFix = false))
    }

    @Test fun compassWithoutPositionIsANeutralNoteInPlan_theErrorBoxInNavigate() {
        assertEquals(PlanGps.Note.TURN_NEEDS_POSITION, PlanGps.compassWithoutFix(plan = true))
        assertNull(PlanGps.compassWithoutFix(plan = false))
    }

    @Test fun theNoteGoesWhenAPositionComes() {
        assertTrue(PlanGps.noteGone(PlanGps.Note.SEARCHING, GpsStatus.GOOD, hasFix = true))
        assertFalse(PlanGps.noteGone(PlanGps.Note.SEARCHING, GpsStatus.SEARCHING, hasFix = false))
        assertFalse("no note, nothing to clear", PlanGps.noteGone(null, GpsStatus.GOOD, hasFix = true))
    }

    @Test fun theNoteStandsUnderTheActivityRow() {
        val row = 80
        assertEquals(row + OverviewPillPlace.HEIGHT_DP + MessagePlacement.GAP_DP, PlanGps.noteTopDp(row, stripBottomDp = null))
        assertTrue("under the download strip too", PlanGps.noteTopDp(row, stripBottomDp = 200) > 200)
    }

    @Test fun theNoteStandsBesideTheActivityButtonClearOfItsBadge() {
        // the T badge under the button grew (2026-11-07): a note under the row started at the edge and covered it
        assertEquals(OverviewPillPlace.BESIDE_ACTIVITY_DP, PlanGps.noteStartDp(activityButtonShown = true))
        assertEquals(OverviewPillPlace.EDGE_DP, PlanGps.noteStartDp(activityButtonShown = false))
        // the GPS note and the map's INFO line use the one rule (MapScreen) – one at a time, never stacked
        val screen = java.io.File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertEquals(2, Regex("""start = PlanGps\.noteStartDp\(""").findAll(screen).count())
        assertTrue(screen.contains("vm.mapNote?.takeIf { vm.mode == AppMode.PLAN && !vm.searchOpen && vm.planGpsNote == null }"))
    }

    @Test fun anEditWithoutAPositionIsANeutralNoteThatStaysLongerThanATapNote() {
        // were red boxes during a tour (route back / one point left without a position) – Plan is quiet about GPS
        for (n in listOf(PlanGps.Note.ROUTE_BACK_NO_POSITION, PlanGps.Note.ONE_POINT_NO_POSITION)) {
            assertTrue("$n: two sentences need longer than a tap note", PlanGps.noteMs(n) > PlanGps.noteMs(PlanGps.Note.SEARCHING))
            assertTrue("$n goes when a position comes", PlanGps.noteGone(n, GpsStatus.GOOD, hasFix = true))
        }
        assertEquals(PlanGps.NOTE_MS, PlanGps.noteMs(PlanGps.Note.TURN_NEEDS_POSITION))
    }
}
