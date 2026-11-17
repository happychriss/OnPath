package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A multi-day tour's maps (docs/design/multi-day-tours.md §7, stage 0): a 10 km corridor, ALL its areas (never the
 * CORRIDOR_MAX_CELLS cut of a normal plan), the size from the server where known, else about 12.5 MB per area.
 */
class TourCorridorTest {
    /** München → Venedig as a straight line, ~560 km, a point every ~1 km. */
    private val tour = (0..560).map { GeoPoint(48.137 - it * (48.137 - 45.438) / 560, 11.575 + it * (12.335 - 11.575) / 560) }
    private val along: (List<GeoPoint>, Double) -> List<CellId> = { l, m -> CellId.alongLine(l, m) }

    @Test fun tenKilometresAndNothingOnThePhone() {
        val m = TourCorridor.of(tour, along, { it })
        assertEquals(CellId.alongLine(tour, 10_000.0), m.all)
        assertTrue("narrower than the 20 km bike corridor", m.all.size < CellId.alongLine(tour, 20_000.0).size)
        assertEquals(m.all, m.missing)
        assertEquals(m.all.size * TourCorridor.CELL_BYTES, m.bytes)
        assertTrue(!m.complete)
    }

    /** A long tour (1 500 km) needs more areas than a normal plan loads at once – all of them are listed. */
    @Test fun neverCutAtTheNormalCorridorLimit() {
        val long = (0..1500).map { GeoPoint(52.0 - it * 0.006, 8.0 + it * 0.004) }
        val m = TourCorridor.of(long, along, { it })
        assertTrue("${m.all.size} areas", m.all.size > CellTriggers.CORRIDOR_MAX_CELLS)
        assertEquals(m.all.size, m.missing.size)
    }

    @Test fun areasOnThePhoneAndKnownSizesCount() {
        val all = CellId.alongLine(tour, 10_000.0)
        val have = all.take(3).toSet()
        val known = mapOf(all[3] to 7_000_000L, all[4] to 0L)
        val m = TourCorridor.of(tour, along, { l -> l.filter { it !in have } }, { known[it] })
        assertEquals(all.size - 3, m.missing.size)
        // a size of 0 is "not known" (the estimate), a told size counts as told
        assertEquals(7_000_000L + (all.size - 4) * TourCorridor.CELL_BYTES, m.bytes)
        val done = TourCorridor.of(tour, along, { emptyList() })
        assertTrue(done.complete); assertEquals(0L, done.bytes)
    }

    @Test fun noLineNoMaps() {
        val m = TourCorridor.of(listOf(tour[0]), along, { it })
        assertTrue(m.all.isEmpty() && m.complete)
    }

    @Test fun aDaysStretch() {
        val s = TourCorridor.stretch(tour, 100_000.0, 200_000.0)
        val d = com.onpath.app.data.Geo.length(s)
        assertTrue("about 100 km: $d", d in 98_000.0..101_000.0)
        assertTrue(TourCorridor.stretch(tour, 900_000.0, 1_000_000.0).isEmpty())
    }
}
