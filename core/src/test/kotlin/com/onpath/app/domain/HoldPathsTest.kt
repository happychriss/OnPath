package com.onpath.app.domain

import com.onpath.app.routing.RoutingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A hold beside a plan measured on the real paths (owner 2026-11-17 „Always real paths“, F-PLAN-15): off the main thread,
 * at most [HoldPaths.TIMEOUT_MS]; a timeout or a failure gives the straight lines; a newer hold cancels the older measure.
 * A fake router stands in for BRouter ([PlaceAddMenuTest.Feldstrasse]).
 */
class HoldPathsTest {

    private val worker = Executors.newSingleThreadExecutor()
    private val dispatcher = worker.asCoroutineDispatcher()

    @After fun stop() { worker.shutdownNow() }

    private val plan = PlaceAddMenuTest.Feldstrasse.plan
    private val spot = PlaceAddMenuTest.Feldstrasse.spot
    private val straight = PlaceAddMenu.detours(plan, spot)!!

    /** Waits on the test's event loop until [check] holds (the measure's `done` runs there). */
    private suspend fun until(ms: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check() && System.currentTimeMillis() < end) delay(5)
    }

    @Test fun feldstrasseOnThePathsIsTheNewGoal() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var got: HoldMeasure.Outcome? = null
        paths.start(plan, spot, straight, { a, b, _ -> PlaceAddMenuTest.Feldstrasse.length(a, b) }) { got = it }
        assertTrue("running while the router works", paths.running)
        until { got != null }
        val o = got!!
        assertTrue(o.describe(), o.onPaths)
        assertEquals(120.0, o.detours.insertM, 1.0)
        assertEquals(60.0, o.detours.appendM, 0.1)
        assertEquals(PlaceAddMenu.HoldAction.NEW_GOAL, PlaceAddMenu.planningHold(100.0, o.detours))
        assertTrue(o.describe(), o.describe().startsWith("paths: insert +120 m"))
        assertFalse(paths.running)
    }

    @Test fun aTimeoutKeepsTheStraightLines() = runBlocking {
        val paths = HoldPaths(this, dispatcher, timeoutMs = 200)
        val stopped = CountDownLatch(1)
        var got: HoldMeasure.Outcome? = null
        // the router hangs until it is cancelled (as BRouter's cancel hook stops a search)
        paths.start(plan, spot, straight, { _, _, c ->
            val seen = CountDownLatch(1)
            c.onCancel { seen.countDown() }
            seen.await(10, TimeUnit.SECONDS)
            stopped.countDown()
            throw RoutingException("cancelled", kind = RoutingException.Kind.CANCELLED)
        }) { got = it }
        val t0 = System.currentTimeMillis()
        until { got != null }
        val o = got!!
        assertTrue("the decision came at the time limit, not after the router: ${System.currentTimeMillis() - t0} ms",
            System.currentTimeMillis() - t0 < 2_000)
        assertFalse(o.onPaths)
        assertEquals(straight, o.detours)
        assertTrue(o.describe(), o.describe().startsWith("paths failed: timeout"))
        assertEquals(PlaceAddMenu.HoldAction.ASK, PlaceAddMenu.planningHold(100.0, o.detours)) // today's straight decision
        assertTrue("the router was told to stop", stopped.await(2, TimeUnit.SECONDS))
    }

    @Test fun aFailureKeepsTheStraightLines() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var got: HoldMeasure.Outcome? = null
        paths.start(plan, spot, straight, { _, _, _ -> throw RoutingException("no data", kind = RoutingException.Kind.NO_DATA) }) { got = it }
        until { got != null }
        assertFalse(got!!.onPaths)
        assertEquals(straight, got!!.detours)
        assertTrue(got!!.describe(), got!!.describe().startsWith("paths failed: no_data"))
    }

    /** An engine bug (not a RoutingException) and a nonsense length are failures too – never a crash, never a stale number. */
    @Test fun anEngineBugOrNoLengthKeepsTheStraightLines() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        var got: HoldMeasure.Outcome? = null
        paths.start(plan, spot, straight, { _, _, _ -> throw IllegalStateException("boom") }) { got = it }
        until { got != null }
        assertFalse(got!!.onPaths)
        got = null
        paths.start(plan, spot, straight, { _, _, _ -> Double.NaN }) { got = it }
        until { got != null }
        assertFalse(got!!.onPaths)
        assertEquals(straight, got!!.detours)
    }

    @Test fun aSecondHoldCancelsTheFirst() = runBlocking {
        val paths = HoldPaths(this, dispatcher)
        val firstStopped = CountDownLatch(1)
        val outcomes = ArrayList<String>()
        paths.start(plan, spot, straight, { _, _, c ->
            val seen = CountDownLatch(1)
            c.onCancel { seen.countDown() }
            seen.await(10, TimeUnit.SECONDS)
            firstStopped.countDown()
            throw RoutingException("cancelled", kind = RoutingException.Kind.CANCELLED)
        }) { outcomes += "first" }
        delay(50)
        paths.start(plan, spot, straight, { a, b, _ -> PlaceAddMenuTest.Feldstrasse.length(a, b) }) { outcomes += "second" }
        assertTrue("the first router was stopped", firstStopped.await(2, TimeUnit.SECONDS))
        until { outcomes.isNotEmpty() }
        delay(100)
        assertEquals(listOf("second"), outcomes)
    }

    /** Undo, a plan change, leaving Plan: [HoldPaths.cancel] – the result is never applied. */
    @Test fun aCancelledMeasureIsNeverApplied() = runBlocking {
        val paths = HoldPaths(this, Dispatchers.IO)
        val release = CountDownLatch(1)
        var got: HoldMeasure.Outcome? = null
        paths.start(plan, spot, straight, { a, b, _ -> release.await(5, TimeUnit.SECONDS); PlaceAddMenuTest.Feldstrasse.length(a, b) }) { got = it }
        assertTrue(paths.cancel())
        assertFalse(paths.running)
        release.countDown()
        delay(300)
        assertNull(got)
        assertFalse("nothing running: cancel says so", paths.cancel())
        assertNotNull(straight)
    }
}
