package com.onpath.app.map

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.ui.routes.Favourites
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
 * gone with the tour; which ones a background pass makes and in what order. The favourites filter of the list.
 */
class RouteThumbsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tour(id: Long, uuid: String = "u$id", kind: TourKind = TourKind.PLANNED, state: String = "done", created: Long = id,
                     waypoints: List<GpxWaypoint> = emptyList(), favourite: Boolean = false) =
        Tour(id, kind, "T$id", created, "HIKE", 2, 1.0, 0.0, 0.0, 0.0, state, waypoints, uuid, favourite = favourite)

    @Test fun aPictureIsKeptWithItsLineAndGoesWithItsTour() {
        val th = RouteThumbs(tmp.newFolder("thumbs"))
        assertNull(th.storedKey("a"))
        assertTrue(th.write("a", "k1", byteArrayOf(1, 2, 3)))
        assertEquals("k1", th.storedKey("a"))
        assertTrue(th.file("a").isFile)
        // nothing to draw: the key is kept (not tried again), no picture – the row keeps its line
        assertTrue(th.write("b", "k2", null))
        assertEquals("k2", th.storedKey("b")); assertFalse(th.file("b").exists())
        // a new line: the same file, the new key
        th.write("a", "k3", byteArrayOf(4))
        assertEquals("k3", th.storedKey("a")); assertEquals(1L, th.file("a").length())
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
        // no stored points: its via points, as the list draws it
        val w = t.copy(waypoints = listOf(GpxWaypoint(46.6, 11.1, null), GpxWaypoint(46.7, 11.2, null)))
        assertNotEquals(ThumbRules.key(w, null), ThumbRules.key(w.copy(waypoints = w.waypoints.take(1)), null))
    }

    /** Only what is missing or shows an old line; the one just saved first, then newest first; never a running recording. */
    @Test fun aPassMakesOnlyWhatIsMissingOrOld() {
        val old = tour(1, created = 10); val fresh = tour(2, created = 30); val made = tour(3, created = 20)
        val running = tour(4, kind = TourKind.RECORDED, state = "active", created = 40); val noUuid = tour(5, uuid = "")
        val keys = mapOf(1L to "a", 2L to "b", 3L to "c", 4L to "d")
        val stored = mapOf("u3" to ThumbRules.key(made, "c"), "u1" to ThumbRules.key(old, "OLD LINE"))
        val todo = ThumbRules.todo(listOf(old, fresh, made, running, noUuid), keys, { stored[it] })
        assertEquals(listOf(2L, 1L), todo.map { it.first.id })
        assertEquals(ThumbRules.key(fresh, "b"), todo.first().second)
        assertEquals(listOf(1L, 2L), ThumbRules.todo(listOf(old, fresh, made), keys, { stored[it] }, first = "u1").map { it.first.id })
        // failed in this run: not again until the next start (or a new line)
        assertEquals(listOf(1L), ThumbRules.todo(listOf(old, fresh), keys, { stored[it] }, skip = setOf(ThumbRules.key(fresh, "b"))).map { it.first.id })
    }

    @Test fun thePillShowsOnlyFavourites() {
        val list = listOf(tour(1, favourite = true), tour(2), tour(3, favourite = true))
        assertEquals(list, Favourites.shown(list, false))
        assertEquals(listOf(1L, 3L), Favourites.shown(list, true).map { it.id })
        assertTrue(Favourites.shown(listOf(tour(2)), true).isEmpty())
    }
}
