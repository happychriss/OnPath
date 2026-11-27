package com.onpath.app.domain

import com.onpath.app.routing.RoutingException
import com.onpath.app.domain.PlaceAddMenu.Item
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * A held place's menu while planning (owner 2026-11-17: „wenn ich da nicht einfügen muss, dann braucht das auch im Menü
 * nicht angezeigt zu werden“, F-PLAN-15): its add rows come from the same measure as a hold on the empty map – on the
 * paths when the router answers ([HoldPaths]), else the straight lines – and the menu opens only with them. The place is
 * the Feldstraße spot ([PlaceAddMenuTest.Feldstrasse]): straight lines about equal (both rows), on the paths appending is
 * half of inserting (only „Als Ziel“).
 */
class PlaceMenuMeasureTest {

    private val worker = Executors.newSingleThreadExecutor()
    private val dispatcher = worker.asCoroutineDispatcher()

    @After fun stop() { worker.shutdownNow() }

    private val plan = PlaceAddMenuTest.Feldstrasse.plan
    private val spot = PlaceAddMenuTest.Feldstrasse.spot
    private val straight = PlaceAddMenu.detours(plan, spot)!!

    private suspend fun until(ms: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check() && System.currentTimeMillis() < end) delay(5)
    }

    @Test fun theMenuTakesTheMeasuredResult() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var rows: PlaceMenuMeasure.Rows? = null
        val measuring = PlaceMenuMeasure.start(paths, plan, spot, 100.0, straight,
            { a, b, _ -> PlaceAddMenuTest.Feldstrasse.length(a, b) }) { rows = it }
        assertTrue("measured on the paths first", measuring)
        assertNull("no menu before the decision – nothing jumps", rows)
        until { rows != null }
        val r = rows!!
        assertEquals(r.how, PlaceAddMenu.HoldAction.NEW_GOAL, r.action)
        assertEquals("only „Als Ziel“ (straight lines would have shown both)", listOf(Item.NEW_DESTINATION), r.menu.main)
        assertTrue(r.how, r.how.contains("paths: insert +120 m"))
    }

    @Test fun aFailureFallsBackToTheStraightLines() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var rows: PlaceMenuMeasure.Rows? = null
        PlaceMenuMeasure.start(paths, plan, spot, 100.0, straight,
            { _, _, _ -> throw RoutingException("no data", kind = RoutingException.Kind.NO_DATA) }) { rows = it }
        until { rows != null }
        val r = rows!!
        assertEquals(r.how, PlaceAddMenu.HoldAction.ASK, r.action)
        // straight: inserting +68 m, appending 63 m – about equal: both, „Als Ziel“ (the cheaper) first and tinted
        assertEquals(listOf(Item.NEW_DESTINATION, Item.ADD_STOP), r.menu.main)
        assertEquals(Item.NEW_DESTINATION, r.menu.emphasised)
        assertTrue(r.how, r.how.contains("paths failed: no_data"))
    }

    @Test fun aPlaceOnTheLineIsOnlyInsertAtOnce() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var asked = false
        var rows: PlaceMenuMeasure.Rows? = null
        val measuring = PlaceMenuMeasure.start(paths, plan, spot, 10.0, straight, { _, _, _ -> asked = true; 1.0 }) { rows = it }
        assertFalse("decided at once", measuring)
        assertFalse("the router is not asked", asked)
        assertFalse(paths.running)
        assertEquals(listOf(Item.ADD_STOP), rows!!.menu.main)
    }

    /** Another hold / place, Undo, a plan change, leaving Plan: the measure stops and its menu never opens. */
    @Test fun aCancelledMeasureNeverOpensTheMenu() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var rows: PlaceMenuMeasure.Rows? = null
        PlaceMenuMeasure.start(paths, plan, spot, 100.0, straight,
            { a, b, _ -> Thread.sleep(50); PlaceAddMenuTest.Feldstrasse.length(a, b) }) { rows = it }
        assertTrue(paths.cancel())
        delay(400)
        assertNull(rows)
    }
}
