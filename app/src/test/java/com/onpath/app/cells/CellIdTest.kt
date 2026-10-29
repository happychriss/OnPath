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
        assertEquals(c, CellId.ofTile(13, 543 * 8 + 3, 361 * 8 + 7)); assertNull(CellId.ofTile(6, 33, 22))
        assertEquals(CellId(67, 45, 7), CellId.ofTile(9, 271, 180)) // z7-9 tiles belong to a region package
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

    /** Region packages (map-cells.md §20): z7 tiles with the map zooms 7–9; same values as tools/cells/cells.py. */
    @Test fun regionPackages() {
        val p = CellId.parse("7-66-42")!!
        assertTrue(p.isPackage); assertFalse(CellId(531, 342).isPackage)
        assertEquals("7-66-42", p.id); assertEquals("7/66/42", p.path); assertEquals(p, CellId.parse("7/66/42"))
        assertNull(CellId.parse("7-128-1")); assertNull(CellId.parse("8-1-1")); assertNull(CellId.parse("9-1-1"))
        val b = p.bbox
        assertEquals(5.625, b[0], 1e-9); assertEquals(8.4375, b[2], 1e-9)
        assertEquals(50.736, b[1], 1e-3); assertEquals(52.483, b[3], 1e-3)
        // Hilden (cell 10-531-342) lies in it; Bonn just south of it
        assertEquals(p, CellId.packageOf(CellId(531, 342)))
        assertEquals(CellId(66, 43, 7), CellId.packageOf(CellId.at(50.73, 7.10)!!))
        // ownership: z7-9 under it, nothing at z10+ (the cells') or below z7 (the overview's)
        assertTrue(p.ownsTile(7, 66, 42)); assertTrue(p.ownsTile(9, 66 * 4 + 3, 42 * 4))
        assertFalse(p.ownsTile(10, 531, 342)); assertFalse(p.ownsTile(6, 33, 21)); assertFalse(p.ownsTile(8, 134, 84))
        assertFalse(CellId(531, 342).ownsTile(9, 265, 171))
        // cells first in sorted lists
        assertEquals(listOf(CellId(531, 342), p), listOf(p, CellId(531, 342)).sorted())
    }

    @Test fun packagesForCellsAddTheNeighbourAcrossAnEdge() {
        // 10-531-342: x 531 = 66·8 + 3, y 342 = 42·8 + 6 → inside, not on an edge
        assertEquals(listOf(CellId(66, 42, 7)), CellId.packagesFor(listOf(CellId(531, 342))))
        // 10-531-343: bottom row of 7/66/42 → plus the package south of it
        assertEquals(listOf(CellId(66, 42, 7), CellId(66, 43, 7)), CellId.packagesFor(listOf(CellId(531, 343))))
        // corner cell 10-535-343 (x = 66·8 + 7): east, south and south-east packages
        assertEquals(setOf(CellId(66, 42, 7), CellId(67, 42, 7), CellId(66, 43, 7), CellId(67, 43, 7)),
            CellId.packagesFor(listOf(CellId(535, 343))).toSet())
        assertEquals(CellId(66, 42, 7), CellId.packagesFor(listOf(CellId(535, 343))).first()) // own first
        assertTrue(CellId.packagesFor(listOf(CellId(66, 42, 7))).isEmpty()) // packages need no packages
    }
}
