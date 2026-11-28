package com.onpath.app.ui

import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLimitKind
import com.onpath.app.routing.DayTable
import com.onpath.app.ui.routes.DayPick
import com.onpath.app.ui.routes.DayPickRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The app's day table (owner 2026-11-27, parity with the planner's table) – My routes' day sheet. */
class DayTableUiTest {
    private val saved = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(saved)

    private fun pick(n: Int, from: String?, to: String?, km: Double, up: Double, h: Double, end: DayEndKind?, over: Set<DayLimitKind> = emptySet()) =
        DayPickRow(n, from, to, km * 1000, up, h * 3600, end, null, 0, 0, over)

    @Test fun myRoutesDaysAsThePlannersTable() {
        val t = DayPick.table(listOf(
            pick(1, "Hilden", "Bonn", 87.1, 36.0, 4 + 10 / 60.0, DayEndKind.OVERNIGHT),
            pick(2, "Bonn", null, 85.2, 612.0, 5 + 5 / 60.0, DayEndKind.PROPOSAL, setOf(DayLimitKind.ASCENT, DayLimitKind.TIME)),
            pick(3, "Koblenz", "Bingen", 82.0, 159.0, 4 + 10 / 60.0, null),
        ))
        assertEquals(listOf("Tag 1", "Tag 2", "Tag 3"), t.lines.map { it.day })
        assertEquals("the shared DayRowText: von → nach, none with one end unknown", listOf("Hilden → Bonn", null, "Koblenz → Bingen"), t.lines.map { it.places })
        assertEquals(DayTable.Cells("87,1 km", "Σ 87 km", "↑ 36 m", "4 h 10"), t.lines[0].cells)
        assertEquals("Σ 254 km", t.lines[2].cells.sum)
        assertEquals(true, t.lines[1].over); assertEquals(true, t.lines[1].cells.overAscent); assertEquals(true, t.lines[1].cells.overTime)
        assertEquals(false, t.lines[0].over)
        assertEquals(listOf(DayEndKind.OVERNIGHT, DayEndKind.PROPOSAL, null), t.lines.map { it.end })
        assertEquals("Gesamt", t.totalLabel)
        assertEquals(DayTable.Cells("254,3 km", null, "↑ 807 m", "13 h 25"), t.total)
    }
}
