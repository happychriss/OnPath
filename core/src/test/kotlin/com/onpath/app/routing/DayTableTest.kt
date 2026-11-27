package com.onpath.app.routing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The multi-day day table (owner 2026-11-27): aligned cells with units, running total, total row, over-limit facts. */
class DayTableTest {
    private val saved = Locale.getDefault()
    @After fun back() = Locale.setDefault(saved)

    private fun day(n: Int, km: Double, up: Double, h: Double, over: Set<DayLimitKind> = emptySet()) =
        TourDay(n, 0.0, 0.0, km * 1000, up, 0.0, h * 3600, null, over)

    @Test fun cellsWithUnitsAndARunningTotal() {
        Locale.setDefault(Locale.GERMANY)
        val rows = DayTable.rows(listOf(day(1, 87.1, 36.0, 4 + 10 / 60.0), day(2, 85.2, 1783.0, 4 + 10 / 60.0)))
        assertEquals(DayTable.Cells("87,1 km", "Σ 87 km", "↑ 36 m", "4 h 10"), rows[0])
        assertEquals("Σ 172 km", rows[1].sum)
        assertEquals("↑ 1.783 m", rows[1].up)
        assertEquals("↓ 1.321 m", DayTable.down(1321.0))
    }

    @Test fun theTotalRow() {
        Locale.setDefault(Locale.GERMANY)
        val t = DayTable.total(listOf(day(1, 100.0, 1000.0, 20.0), day(2, 546.1, 783.0, 14 + 20 / 60.0)))
        assertEquals(DayTable.Cells("646,1 km", null, "↑ 1.783 m", "34 h 20"), t)
        assertEquals("Gesamt", DayTable.totalLabel())
    }

    @Test fun onlyTheExceededValuesAreMarkedAndSaidAsFacts() {
        Locale.setDefault(Locale.GERMANY)
        val d = day(2, 26.4, 1120.0, 7 + 10 / 60.0, setOf(DayLimitKind.TIME, DayLimitKind.ASCENT))
        val c = DayTable.rows(listOf(d))[0]
        assertEquals(true, c.overTime); assertEquals(true, c.overAscent)
        assertEquals("1 h 10 und 120 m über deinem Tag", DayTable.overLine(d, DayLimits(maxTimeMin = 360, maxAscentM = 1000)))
        val onlyTime = d.copy(over = setOf(DayLimitKind.TIME))
        assertEquals("1 h 10 über deinem Tag", DayTable.overLine(onlyTime, DayLimits(maxTimeMin = 360, maxAscentM = 1000)))
        assertNull(DayTable.overLine(d.copy(over = emptySet()), DayLimits(maxTimeMin = 360)))
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("1 h 10 and 120 m over your day", DayTable.overLine(d, DayLimits(maxTimeMin = 360, maxAscentM = 1000)))
    }

    @Test fun shortTimes() {
        assertEquals("45 min", DayTable.time(45 * 60.0))
        assertEquals("0 min", DayTable.time(0.0))
    }
}
