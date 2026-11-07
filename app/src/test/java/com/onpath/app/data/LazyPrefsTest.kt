package com.onpath.app.data

import com.onpath.app.setup.CheckId
import com.onpath.app.setup.LastSeen
import com.onpath.app.ui.MapLifecycleTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The camera and "last seen" no longer go through apply() (Android waits for pending apply() writes in onStop – 0.5–0.8 s
 * main-thread stalls on the Pixel, 2026-11-07): [LazyPrefs] keeps them in memory, coalesces and commits them on one
 * background thread.
 */
class LazyPrefsTest {
    private val dir = Files.createTempDirectory("lazyprefs").toFile()

    /** A writer whose delay never fires by itself (the test flushes). */
    private fun manual(sp: MapPrefs) = LazyPrefs(sp, delayMs = 60_000, exec = Executors.newSingleThreadScheduledExecutor())

    @Test fun manyCameraMovesBecomeOneWriteWithTheLastValue() {
        val sp = MapPrefs()
        val w = manual(sp)
        for (i in 1..50) w.put("camera", i) { e -> e.putInt("cam", i) }
        assertFalse("nothing on disk before the flush", sp.contains("cam"))
        w.flushNow()
        assertEquals(1, w.commits)
        assertEquals(50, sp.getInt("cam", 0))
        w.flushNow() // nothing pending: no second write
        assertEquals(1, w.commits)
    }

    @Test fun pendingValuesAnswerReadsBeforeTheyReachTheDisk() {
        val sp = MapPrefs()
        val w = manual(sp)
        assertSame(LazyPrefs.NONE, w.peek("camera"))
        w.put("camera", Triple(1.0, 2.0, 3.0)) { }
        assertEquals(Triple(1.0, 2.0, 3.0), w.peek("camera"))
        w.flushNow()
        assertSame(LazyPrefs.NONE, w.peek("camera"))
    }

    @Test fun severalKeysAreWrittenTogether() {
        val sp = MapPrefs()
        val w = manual(sp)
        w.put("a", 1) { e -> e.putInt("a", 1) }
        w.put("b", 2) { e -> e.putInt("b", 2) }
        w.flushNow()
        assertEquals(1, w.commits)
        assertEquals(1, sp.getInt("a", 0)); assertEquals(2, sp.getInt("b", 0))
    }

    @Test fun theDelayWritesWithoutAFlush() {
        val sp = MapPrefs()
        val exec = Executors.newSingleThreadScheduledExecutor()
        val w = LazyPrefs(sp, delayMs = 20, exec = exec)
        w.put("x", 7) { e -> e.putInt("x", 7) }
        exec.schedule({}, 200, TimeUnit.MILLISECONDS).get() // after the delay
        assertEquals(7, sp.getInt("x", 0))
    }

    @Test fun flushWritesOnTheBackgroundThreadNotTheCallers() {
        val sp = MapPrefs()
        val exec = Executors.newSingleThreadScheduledExecutor()
        val w = LazyPrefs(sp, delayMs = 60_000, exec = exec)
        w.put("x", 1) { e -> e.putInt("x", 1) }
        w.flush() // returns at once
        exec.submit {}.get(5, TimeUnit.SECONDS)
        assertEquals(1, sp.getInt("x", 0))
    }

    @Test fun prefsCameraIsReadBackAtOnceAndOnDiskAfterTheFlush() {
        val sp = MapPrefs()
        val p = Prefs(sp, dir)
        assertNull(p.camera)
        p.camera = Triple(51.17, 6.94, 14.0)
        val c = p.camera!!
        assertEquals(51.17, c.first, 1e-4); assertEquals(6.94, c.second, 1e-4); assertEquals(14.0, c.third, 1e-4)
        LazyPrefs.of(sp).flushNow()
        assertTrue(sp.contains("camLat"))
        assertEquals(51.17, Prefs(sp, dir).camera!!.first, 1e-4) // a new Prefs (restart) reads it from the file
    }

    @Test fun prefsLastSeenGoesTheSameWay() {
        val sp = MapPrefs()
        val p = Prefs(sp, dir)
        p.lastSeen = LastSeen(setOf(CheckId.entries.first()), 1234L)
        assertEquals(1234L, p.lastSeen!!.atMs)
        LazyPrefs.of(sp).flushNow()
        assertEquals(1234L, sp.getLong("lastSeenAt", 0L))
    }

    @Test fun onlySlowMapLifecycleCallsAreTraced() {
        assertFalse(MapLifecycleTiming.slow(40))
        assertTrue(MapLifecycleTiming.slow(MapLifecycleTiming.SLOW_MS))
        assertTrue(MapLifecycleTiming.slow(700))
    }
}
