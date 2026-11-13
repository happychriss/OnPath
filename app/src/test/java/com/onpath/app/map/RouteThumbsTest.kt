package com.onpath.app.map

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.routes.Favourites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * My routes' map pictures (owner 2026-11-13, L1): one small file per tour by uuid, made again when its line changes,
 * gone with the tour; which ones a background pass makes and in what order; a pass over unchanged tours does no database
 * work; nothing while a tour runs. The favourites filter of the list.
 */
class RouteThumbsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tour(id: Long, uuid: String = "u$id", kind: TourKind = TourKind.PLANNED, state: String = "done", created: Long = id,
                     waypoints: List<GpxWaypoint> = emptyList(), favourite: Boolean = false, distance: Double = 1.0) =
        Tour(id, kind, "T$id", created, "HIKE", 2, distance, 0.0, 0.0, 0.0, state, waypoints, uuid, favourite = favourite)

    @Test fun aPictureIsKeptWithItsLineAndGoesWithItsTour() {
        val th = RouteThumbs(tmp.newFolder("thumbs"))
        assertNull(th.storedKey("a"))
        assertTrue(th.write("a", "k1", byteArrayOf(1, 2, 3)))
        assertEquals("k1", th.storedKey("a"))
        assertTrue(th.file("a").isFile)
        // nothing to draw: the key is kept (not tried again), no picture – the row keeps its line
        assertTrue(th.write("b", "k2", null))
        assertEquals("k2", th.storedKey("b")); assertFalse(th.file("b").exists())
        // a new line: the same file, the new key; only the key: the picture stays
        th.write("a", "k3", byteArrayOf(4))
        assertEquals("k3", th.storedKey("a")); assertEquals(1L, th.file("a").length())
        th.writeKey("a", "k4"); assertEquals("k4", th.storedKey("a")); assertEquals(1L, th.file("a").length())
        // tours deleted for good lose their pictures; the others (recycle bin included by the caller) stay
        th.retain(setOf("b"))
        assertFalse(th.file("a").exists()); assertNull(th.storedKey("a")); assertEquals("k2", th.storedKey("b"))
    }

    @Test fun theKeyFollowsTheLineAndTheLook() {
        val t = tour(1)
        assertEquals(ThumbRules.key(t, "12:560.1:133.2"), ThumbRules.key(t.copy(name = "renamed", favourite = true), "12:560.1:133.2"))
        assertNotEquals(ThumbRules.key(t, "12:560.1:133.2"), ThumbRules.key(t, "13:560.1:133.2"))
        assertNotEquals("its colour", ThumbRules.key(t, "1:1:1"), ThumbRules.key(t.copy(kind = TourKind.RECORDED), "1:1:1"))
        assertTrue(ThumbRules.key(t, "1:1:1").startsWith("v${ThumbRules.VERSION} "))
        // the signature from the row alone: distance and via points move it, a rename or a star does not
        assertEquals(ThumbRules.sig(t), ThumbRules.sig(t.copy(name = "x", favourite = true)))
        assertNotEquals(ThumbRules.sig(t), ThumbRules.sig(t.copy(distanceM = 2.0)))
        val w = t.copy(waypoints = listOf(GpxWaypoint(46.6, 11.1, null), GpxWaypoint(46.7, 11.2, null)))
        assertNotEquals(ThumbRules.sig(w), ThumbRules.sig(w.copy(waypoints = w.waypoints.take(1))))
    }

    /** Only what is missing or shows an old line; the one just saved first, then newest first; never a running recording. */
    @Test fun aPassMakesOnlyWhatIsMissingOrOld() {
        val old = tour(1, created = 10); val fresh = tour(2, created = 30); val made = tour(3, created = 20)
        val running = tour(4, kind = TourKind.RECORDED, state = "active", created = 40); val noUuid = tour(5, uuid = "")
        val moved = tour(6, created = 5, distance = 7.0) // routed again: other distance, the same points
        val lines = mapOf(1L to "a", 2L to "b", 3L to "c", 4L to "d", 6L to "f")
        val stored = mapOf("u3" to ThumbRules.key(made, "c"), "u1" to ThumbRules.key(old.copy(distanceM = 0.5), "OLD LINE"),
            "u6" to ThumbRules.key(moved.copy(distanceM = 6.0), "f"))
        val plan = ThumbRules.plan(listOf(old, fresh, made, running, noUuid, moved), { stored[it] }, { lines[it.id] })
        assertEquals(listOf(2L, 1L), plan.make.map { it.first.id })
        assertEquals(ThumbRules.key(fresh, "b"), plan.make.first().second)
        assertEquals("the same line under a new signature: only the key", listOf(6L), plan.rekey.map { it.first.id })
        assertEquals("the unchanged one cost no query", 3, plan.queried)
        assertEquals(listOf(1L, 2L), ThumbRules.plan(listOf(old, fresh, made), { stored[it] }, { lines[it.id] }, first = "u1").make.map { it.first.id })
        // failed in this run: not again until the next start (or a new line)
        assertEquals(listOf(1L), ThumbRules.plan(listOf(old, fresh), { stored[it] }, { lines[it.id] },
            skip = setOf(ThumbRules.key(fresh, "b"))).make.map { it.first.id })
    }

    private class Maker(dir: java.io.File) {
        var busy = false
        var snaps = 0
        var shown = 0
        val thumbs = RouteThumbs(dir)
        val maker = ThumbMaker(thumbs, CoroutineScope(Dispatchers.Unconfined), binned = { emptySet() },
            lineKey = { t -> "${t.id}:${t.distanceM}:1" }, line = { listOf(listOf(GeoPoint(46.6, 11.1), GeoPoint(46.7, 11.2))) },
            style = { "{}" }, snap = { _, _, _ -> snaps++; byteArrayOf(1, 2) }, busy = { busy }, made = { shown++ },
            io = Dispatchers.Unconfined, pauseMs = 0)
    }

    /** Navigating or recording: no picture at all; afterwards each is made once, and a second pass reads nothing. */
    @Test fun noPictureWhileATourRunsAndNoWorkForUnchangedTours() {
        val m = Maker(tmp.newFolder("t"))
        val list = listOf(tour(1), tour(2), tour(3))
        m.busy = true
        m.maker.kick(list)
        assertTrue(m.maker.idle)
        assertEquals(0, m.snaps); assertEquals(0, m.maker.lineQueries)
        m.busy = false
        m.maker.kick(list)
        assertTrue(m.maker.idle)
        assertEquals(3, m.snaps); assertEquals(3, m.shown)
        assertTrue(list.all { m.thumbs.file(it.uuid).isFile })
        val queries = m.maker.lineQueries
        m.maker.kick(list); m.maker.kick(list)
        assertEquals("nothing made again", 3, m.snaps)
        assertEquals("no database work for unchanged tours", queries, m.maker.lineQueries)
        // a new line: that one only
        m.maker.kick(list.map { if (it.id == 2L) it.copy(distanceM = 9.0) else it })
        assertEquals(4, m.snaps)
    }

    /** A tour starting while pictures are made: the pass stops before the next one. */
    @Test fun aTourStartingStopsThePass() {
        val m = Maker(tmp.newFolder("t2"))
        var made = 0
        val maker = ThumbMaker(m.thumbs, CoroutineScope(Dispatchers.Unconfined), binned = { emptySet() }, lineKey = { "x" },
            line = { listOf(listOf(GeoPoint(46.6, 11.1), GeoPoint(46.7, 11.2))) }, style = { "{}" },
            snap = { _, _, _ -> made++; byteArrayOf(1) }, busy = { made >= 1 }, made = {}, io = Dispatchers.Unconfined, pauseMs = 0)
        maker.kick(listOf(tour(1), tour(2), tour(3)))
        assertEquals(1, made)
    }

    @Test fun thePillShowsOnlyFavourites() {
        val list = listOf(tour(1, favourite = true), tour(2), tour(3, favourite = true))
        assertEquals(list, Favourites.shown(list, false))
        assertEquals(listOf(1L, 3L), Favourites.shown(list, true).map { it.id })
        assertTrue(Favourites.shown(listOf(tour(2)), true).isEmpty())
    }
}
