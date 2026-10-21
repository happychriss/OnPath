package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellIdTest {
    /** The shared test values of map-cells.md §2 (same as tools/cells/cells.py and the doorman). */
    @Test fun meranExample() {
        val c = CellId(543, 361)
        assertEquals("10-543-361", c.id)
        assertEquals("10/543/361", c.path)
        val b = c.bbox
        assertEquals(10.8984, b[0], 1e-4); assertEquals(46.5589, b[1], 1e-4)
        assertEquals(11.2500, b[2], 1e-4); assertEquals(46.8001, b[3], 1e-4)
        assertEquals(c, CellId.at(46.67, 11.16))
        assertEquals(c, CellId.parse("10-543-361")); assertEquals(c, CellId.parse("10/543/361"))
        assertNull(CellId.parse("11-543-361")); assertNull(CellId.parse("10-1024-1")); assertNull(CellId.parse("x"))
    }

    @Test fun halfOpenOwnerRule() {
        val c = CellId(543, 361)
        // west edge belongs to the cell, east edge to the neighbour
        assertEquals(c, CellId.at(46.7, CellId.lonOf(543)))
        assertEquals(CellId(544, 361), CellId.at(46.7, CellId.lonOf(544)))
        // north edge belongs to the cell (lat ≤ north), south edge to the cell below
        assertEquals(c, CellId.at(CellId.latOf(361), 11.0))
        assertEquals(CellId(543, 362), CellId.at(CellId.latOf(362), 11.0))
    }

    @Test fun tilesAndNeighbours() {
        val c = CellId(543, 361)
        assertTrue(c.ownsTile(10, 543, 361)); assertTrue(c.ownsTile(14, 543 * 16 + 15, 361 * 16))
        assertFalse(c.ownsTile(9, 271, 180)); assertFalse(c.ownsTile(14, 544 * 16, 361 * 16))
        assertEquals(c, CellId.ofTile(13, 543 * 8 + 3, 361 * 8 + 7)); assertNull(CellId.ofTile(9, 271, 180))
        // Meran centre: far from every border → own cell only
        assertEquals(listOf(c), CellId.near(c.center, 5000.0))
        // close to the east border → plus the east neighbour
        val east = CellId.near(GeoPoint(46.68, 11.24), 5000.0)
        assertEquals(c, east.first()); assertTrue(CellId(544, 361) in east)
    }

    @Test fun corridorAndBbox() {
        val line = listOf(GeoPoint(46.67, 11.16), GeoPoint(46.50, 11.35)) // Meran → Bozen
        val cells = CellId.alongLine(line, 2000.0)
        assertEquals(CellId(543, 361), cells.first())
        assertTrue(CellId(544, 362) in cells || CellId(544, 361) in cells)
        assertEquals(4, CellId.inBbox(46.6, 11.1, 46.9, 11.4).size)
    }
}
