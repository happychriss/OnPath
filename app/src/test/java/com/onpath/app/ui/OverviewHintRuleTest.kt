package com.onpath.app.ui

import com.onpath.app.cells.CellId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field test 0.8.7: centre inside a downloaded area, half the screen overview only – the hint must show. */
class OverviewHintRuleTest {
    private val cell = CellId(543, 361)
    private val b = cell.bbox // west, south, east, north

    private fun detailed(south: Double, west: Double, north: Double, east: Double) =
        OverviewHintRule.samples(south, west, north, east).map { (lat, lon) -> CellId.at(lat, lon) == cell }

    @Test fun samplesCoverTheView() {
        val s = OverviewHintRule.samples(0.0, 0.0, 10.0, 10.0)
        assertEquals(OverviewHintRule.GRID * OverviewHintRule.GRID, s.size)
        assertTrue(s.all { (lat, lon) -> lat in 0.0..10.0 && lon in 0.0..10.0 })
    }

    @Test fun viewInsideTheCellHasNoHint() {
        val h = (b[3] - b[1]) / 4; val w = (b[2] - b[0]) / 4
        val c = cell.center
        assertFalse(OverviewHintRule.show(11.0, detailed(c.lat - h, c.lon - w, c.lat + h, c.lon + w)))
    }

    @Test fun centreInTheCellButHalfTheViewOutsideShowsTheHint() {
        // view centred a little inside the west edge: the left half is overview only
        val h = (b[3] - b[1]) / 4; val w = (b[2] - b[0]) / 4
        val lat = cell.center.lat; val lon = b[0] + w * 0.2
        val d = detailed(lat - h, lon - w, lat + h, lon + w)
        assertTrue(CellId.at(lat, lon) == cell)
        assertTrue(OverviewHintRule.show(10.2, d))
    }

    @Test fun aThinStripOfOverviewHasNoHint() {
        val h = (b[3] - b[1]) / 4; val w = (b[2] - b[0]) / 4
        val lat = cell.center.lat; val lon = b[0] + w * 0.9  // only ~5 % of the view west of the edge
        assertFalse(OverviewHintRule.show(10.5, detailed(lat - h, lon - w, lat + h, lon + w)))
    }

    @Test fun belowZoomTenNeverAHint() = assertFalse(OverviewHintRule.show(9.9, List(25) { false }))

    @Test fun noDetailedMapAtAllShowsTheHint() = assertTrue(OverviewHintRule.show(12.0, List(25) { false }))
}
