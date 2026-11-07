package com.onpath.app.routing

import com.onpath.app.routing.WaypointInsertion.Kind
import com.onpath.app.search.AddPreview
import com.onpath.app.ui.MapGestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field report 2026-11-07 10:45 (Pixel, demo tour "Gewerbepark Süd → Unterstand", led to ① from Hilden): a hold on a
 * place on the way there opened a menu whose action read "Als Start setzen" – the owner: "warum ist das Hinzufügen
 * dieses neuen Punktes ein Start-Button und fügt den Punkt nicht als Zwischenpunkt auf der aktuellen Route hinzu?".
 * During a tour a held point is always a stop on the route the tour follows, never a new start, and the first-point /
 * empty-plan menus offer no "Hier starten" (the tour already runs from here).
 */
class HoldDuringTourTest {
    // a 2-point plan like the field one, its start ① ~1.8 km south-east of "here" (being led there, never reached)
    private val start = GeoPoint(51.1550, 6.9400)
    private val goal = GeoPoint(51.1450, 6.9600)
    private val plan = listOf(start, goal)
    private val here = GeoPoint(51.1700, 6.9250)
    private val onTheWayThere = GeoPoint(51.1625, 6.9330) // beside the lead here → ①

    @Test fun aHoldOnTheWayThereIsAStopNotANewStart() {
        val d = WaypointInsertion.decide(plan, onTheWayThere, null, 30.0, here = here)
        assertEquals("before ①, the tour goes via it", 0, d.index)
        assertNotEquals("never \"Als Start setzen\" during a tour", Kind.START, d.kind)
        assertEquals(Kind.ON_THE_WAY, d.kind)
    }

    @Test fun theMenuSaysStopOnTheWay() {
        val d = WaypointInsertion.decide(plan, onTheWayThere, null, 30.0, here = here)
        assertEquals("Add as a stop on the way", AddPreview.label(d, plan.size))
    }

    @Test fun beyondTheGoalItStaysANewDestination() {
        val beyond = GeoPoint(51.1350, 6.9800)
        assertEquals(Kind.DESTINATION, WaypointInsertion.decide(plan, beyond, null, 30.0, here = here).kind)
    }

    @Test fun withoutATourAnEmptyPlanStillStartsHere() {
        assertEquals(Kind.START, WaypointInsertion.decide(emptyList(), onTheWayThere, null, 30.0).kind)
    }

    @Test fun noStartHereInTheHoldMenusDuringATour() {
        assertTrue(MapGestures.offersStartHere(touring = false))
        assertFalse(MapGestures.offersStartHere(touring = true))
    }
}
