package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.plansync.SyncMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * My routes: the tag after a route's name (owner 2026-10-31, ui-style.md "Tags") – "New" while a route from the browser
 * was not opened, "Changed" when the browser changed it since it was last opened, none once opened. Same marks as
 * before (plansync/SyncMarks), planned routes and imported tracks alike.
 */
class RouteTagTest {
    private val marks = SyncMarks({ null }, {})
    private fun tour(uuid: String, kind: TourKind = TourKind.PLANNED) =
        Tour(1, kind, "Meran → Vellau", 0L, "HIKE", 2, 1.0, 0.0, 0.0, 0.0, "done", emptyList(), uuid = uuid)
    private fun tag(t: Tour) = RouteTag.of(t, marks.all())

    @Test fun newFromTheBrowserUntilOpened() {
        val t = tour("u-1")
        assertNull(tag(t))
        marks.arrived("u-1")
        assertEquals(RouteTag.NEW, tag(t))
        marks.changed("u-1") // changed again before it was opened: still new
        assertEquals(RouteTag.NEW, tag(t))
        marks.remove("u-1") // opened
        assertNull(tag(t))
    }

    @Test fun changedInTheBrowserSinceLastOpened() {
        val t = tour("u-2")
        marks.changed("u-2")
        assertEquals(RouteTag.CHANGED, tag(t))
        marks.remove("u-2") // opened
        assertNull(tag(t))
        marks.changed("u-2") // changed again in the browser
        assertEquals(RouteTag.CHANGED, tag(t))
    }

    @Test fun importedTracksGetTheSameTags() {
        val t = tour("u-3", TourKind.IMPORTED)
        marks.arrived("u-3")
        assertEquals(RouteTag.NEW, tag(t))
    }

    @Test fun noUuidNoTag() {
        marks.arrived("")
        assertNull(tag(tour("")))
    }

    @Test fun unpairClearsTheTags() {
        marks.arrived("u-1"); marks.changed("u-2")
        marks.clear()
        assertNull(tag(tour("u-1"))); assertNull(tag(tour("u-2")))
    }

    /** The tag is never cut: beside the name while the name keeps a fair share of the row, else under it. */
    @Test fun tagGoesBesideTheNameOrUnderIt() {
        // short name: always beside
        assertTrue(TagRow.sameRow(rowWidth = 600, titleWidth = 200, tagWidth = 150, gap = 20))
        // long name, normal font: beside, the name ellipsizes (it keeps 430 of 600)
        assertTrue(TagRow.sameRow(rowWidth = 600, titleWidth = 2000, tagWidth = 150, gap = 20))
        // long name, largest font: the tag would leave the name < 55 % → under it
        assertFalse(TagRow.sameRow(rowWidth = 600, titleWidth = 2000, tagWidth = 340, gap = 20))
        // big tag but the whole name still fits beside it
        assertTrue(TagRow.sameRow(rowWidth = 600, titleWidth = 200, tagWidth = 340, gap = 20))
    }
}
