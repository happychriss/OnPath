package com.onpath.app.ui

import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLimitKind
import com.onpath.app.routing.DayLimits
import com.onpath.app.routing.DayTable
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.routes.DayPick
import com.onpath.app.ui.routes.DayPickRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The app's day table and multi-day panel texts (owner 2026-11-27, parity with the planner's table and panel). */
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

    @Test fun theSaveStateOfTheHead() {
        assertEquals(DaySaveState.NOT_SAVED, DaySaveState.of(null, unsaved = true))
        assertEquals(DaySaveState.CHANGED, DaySaveState.of("Rhein", unsaved = true))
        assertEquals(DaySaveState.SAVED, DaySaveState.of("Rhein", unsaved = false))
        assertEquals(listOf("Nicht gespeichert", "Geändert · nicht gespeichert", "Gespeichert"), DaySaveState.entries.map { it.text() })
    }

    @Test fun theHeadWithTheWholeTrip() {
        val h = MultiDayPanel.head("Rhein-Radweg", "Rhein-Radweg", false, 672_400.0, 35 * 3600.0 + 5 * 60, 1808.0, 1321.0)
        assertEquals("Rhein-Radweg", h.title)
        assertEquals(DaySaveState.SAVED, h.save)
        assertEquals("672,4 km · 35 h 05 · ↑ 1.808 m · ↓ 1.321 m", h.whole)
        val none = MultiDayPanel.head(" ", null, false, null, 0.0, 0.0, 0.0)
        assertEquals("Mehrtagestour", none.title); assertNull(none.whole)
    }

    @Test fun planningChipsOnlyForSetLimits() {
        assertEquals(PlanningUi("8 Tage", "4,5 h / Tag", "1.000 m ↑ / Tag"), MultiDayPanel.planning(8, DayLimits(270, 1000)))
        assertEquals(PlanningUi("1 Tag", null, null), MultiDayPanel.planning(1, DayLimits()))
        assertEquals(PlanningUi("3 Tage", "5 h / Tag", null), MultiDayPanel.planning(3, DayLimits(maxTimeMin = 300)))
    }

    @Test fun profileTitles() {
        assertEquals("Höhenprofil · gesamte Reise", MultiDayPanel.profileTitle(null))
        assertEquals("Höhenprofil · Tag 2", MultiDayPanel.profileTitle(2))
    }

    @Test fun aDaysProfileIsOnlyItsPart() {
        // 11 points evenly along a meridian; days end at 30 % and 70 %
        val p = (0..10).map { GeoPoint(50.0 + it * 0.01, 7.0, it * 10.0) }
        val cuts = listOf(0.3f, 0.7f)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0), MultiDayPanel.dayProfile(p, cuts, 1).map { it.ele })
        assertEquals(listOf(30.0, 40.0, 50.0, 60.0, 70.0), MultiDayPanel.dayProfile(p, cuts, 2).map { it.ele })
        assertEquals(listOf(70.0, 80.0, 90.0, 100.0), MultiDayPanel.dayProfile(p, cuts, 3).map { it.ele })
        assertSame("no day: the whole trip", p, MultiDayPanel.dayProfile(p, cuts, null))
        assertSame("an unknown day: the whole trip", p, MultiDayPanel.dayProfile(p, cuts, 4))
    }
}
