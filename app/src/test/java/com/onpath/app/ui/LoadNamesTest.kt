package com.onpath.app.ui

import com.onpath.app.cells.CellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human names for loading map areas (owner 2026-10-23): by reason, else the nearest overview town; grouped; shortened. */
class LoadNamesTest {
    private val mb = 1_048_576L

    private fun label(e: LoadEntry) = LoadNames.label(e)

    @Test fun everyReasonHasAPlainName() {
        assertEquals("around Bad Waldsee" to "Bad Waldsee", label(LoadEntry(LoadWhy.POINT, "Bad Waldsee", "Ravensburg")))
        assertEquals("along your route to Gasthof Taser Alm" to "Gasthof Taser Alm",
            label(LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", "Meran")))
        assertEquals("newer map near Meran" to "Meran", label(LoadEntry(LoadWhy.REFRESH, null, "Meran")))
        assertEquals("ahead on your tour", label(LoadEntry(LoadWhy.AHEAD, null, "Meran")).first)
    }

    @Test fun fallbackIsTheNearestTownThenMapArea() {
        assertEquals("near Ravensburg" to "Ravensburg", label(LoadEntry(null, null, "Ravensburg")))
        assertEquals("near Ravensburg", label(LoadEntry(LoadWhy.POINT, null, "Ravensburg")).first) // a point without a name
        assertEquals("map area" to null, label(LoadEntry(null, null, null)))
        assertEquals("along your route", label(LoadEntry(LoadWhy.ROUTE, null, null)).first)
        assertEquals("newer map near Taser Alm", label(LoadEntry(LoadWhy.REFRESH, "Taser Alm", null)).first)
        assertEquals("newer map", label(LoadEntry(LoadWhy.REFRESH, null, null)).first)
    }

    @Test fun groupedByNameMostImportantFirst() {
        val g = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.REFRESH, town = "Meran", bytes = 3 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(null, paths = true, bytes = 2 * mb),
            LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", bytes = 10 * mb, phase = LoadPhase.WAITING),
            LoadEntry(LoadWhy.POINT, "Bad Waldsee", bytes = 5 * mb),
            LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", bytes = 14 * mb, phase = LoadPhase.LOADING),
        ))
        assertEquals(listOf("along your route to Gasthof Taser Alm", "around Bad Waldsee", "newer map near Meran"), g.map { it.label })
        // the route-planning part's 2 MB join the most important line (never a line of its own, owner Q5)
        assertEquals("Along your route to Gasthof Taser Alm (2 areas) · 26 MB · loading", g[0].line)
        assertEquals("Around Bad Waldsee · 5 MB · loading", g[1].line)
        assertEquals("Newer map near Meran · 3 MB · being prepared", g[2].line)
    }

    @Test fun whatTheUserWaitsForComesFirst() {
        val g = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.ROUTE, "Taser Alm"),
            LoadEntry(LoadWhy.POINT, "Bad Waldsee", waiting = true),
        ))
        assertEquals("Bad Waldsee" to 1, LoadNames.chip(g))
    }

    @Test fun chipNamesTheFirstAndCountsTheOthers() {
        assertEquals("Map" to 0, LoadNames.chip(emptyList()))
        assertEquals("Map" to 0, LoadNames.chip(LoadNames.groups(listOf(LoadEntry(null, paths = true)))))
        val two = LoadNames.groups(listOf(LoadEntry(LoadWhy.ROUTE, "Taser Alm"), LoadEntry(LoadWhy.ROUTE, "Taser Alm"),
            LoadEntry(null, town = "Meran")))
        assertEquals("Taser Alm" to 1, LoadNames.chip(two))
        assertEquals("Your route" to 0, LoadNames.chip(LoadNames.groups(listOf(LoadEntry(LoadWhy.ROUTE)))))
        val ui = BackgroundDownloadUi(2, 0, 0, 34, groups = two)
        assertEquals("Taser Alm +1 · 34 %", ui.chipText)
        assertEquals("Bad Waldsee · 34 %", ui.copy(groups = LoadNames.groups(listOf(LoadEntry(LoadWhy.POINT, "Bad Waldsee")))).chipText)
    }

    @Test fun longNamesAreShortened() {
        assertEquals("Bad Waldsee", LoadNames.short("Bad Waldsee"))
        val s = LoadNames.short("Berggasthof Gampenalm am Hochplateau über Unsere Liebe Frau")
        assertTrue(s, s.length <= LoadNames.CHIP_NAME_MAX && s.endsWith("…"))
        assertEquals("Berggasthof Gampenalm…", s) // cut at a word end
        assertEquals("Abcdefghijklmnopqrstuvw…", LoadNames.short("Abcdefghijklmnopqrstuvwxyzabc")) // no word end: hard cut
        val chip = LoadNames.chip(LoadNames.groups(listOf(LoadEntry(LoadWhy.POINT, "Berggasthof Gampenalm am Hochplateau"))))
        assertEquals("Berggasthof Gampenalm…", chip.first)
    }

    @Test fun phaseFollowsTheAreaState() {
        assertEquals(LoadPhase.LOADING, LoadNames.phaseOf(CellState.Downloading(1, 2)))
        assertEquals(LoadPhase.PREPARING, LoadNames.phaseOf(CellState.Building("base", 0.5, 30)))
        assertEquals(LoadPhase.PREPARING, LoadNames.phaseOf(CellState.Queued(2, 60)))
        assertEquals(LoadPhase.WAITING, LoadNames.phaseOf(CellState.Requested))
        assertEquals(LoadPhase.FINISHING, LoadNames.phaseOf(CellState.Installing))
    }

    /** Owner texts Q5 (2026-10-23): route-planning data is part of "the map" – never a line or a word of its own. */
    @Test fun routePlanningDataIsNeverNamed() {
        assertTrue(LoadNames.groups(listOf(LoadEntry(null, paths = true, bytes = 9 * mb))).isEmpty()) // only in the total
        val g = LoadNames.groups(listOf(LoadEntry(null, paths = true, bytes = 2 * mb), LoadEntry(LoadWhy.POINT, "Bad Waldsee", bytes = 5 * mb)))
        assertEquals(listOf("Around Bad Waldsee · 7 MB · loading"), g.map { it.line })
        for (l in g.map { it.line }) assertTrue(l, listOf("routing", "route planning", "paths").none { it in l.lowercase() })
        val src = java.io.File("src/main/java/com/onpath/app/ui/BackgroundDownloads.kt").readText()
        assertTrue("details must not name route-planning data", "paths for route planning" !in src.lowercase())
    }

    /** Names are never technical (CLAUDE.md: no "cells" / "routing data" toward the user). */
    @Test fun noTechnicalWords() {
        val all = LoadNames.groups(listOf(LoadEntry(null), LoadEntry(null, paths = true), LoadEntry(LoadWhy.AHEAD),
            LoadEntry(LoadWhy.REFRESH), LoadEntry(LoadWhy.ROUTE), LoadEntry(LoadWhy.POINT)))
        for (g in all) assertTrue(g.line, listOf("cell", "routing", "tile").none { it in g.line.lowercase() })
    }
}
