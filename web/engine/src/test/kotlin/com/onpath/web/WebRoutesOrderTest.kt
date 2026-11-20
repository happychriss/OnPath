package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * My routes' search and order on the page (WebRoutesOrder): the app's RoutesTab.search and RouteSort called as they are –
 * the cases plans.ts / routesort.ts covered while they were TypeScript copies, plus the web's one addition (a plan without
 * numbers goes last for Länge / Dauer / Anstieg).
 */
class WebRoutesOrderTest {
    @Test fun searchFoldsCaseAccentsAndEszett() {
        val names = listOf("Rund um den Kofel", "Köln Dom", "Hauptstraße", "Meran").joinToString("\n")
        assertEquals("[1]", WebRoutesOrder.search(names, "koln"))
        assertEquals("[0]", WebRoutesOrder.search(names, "rund kofel"))
        assertEquals("[2]", WebRoutesOrder.search(names, "STRASSE"))
        assertEquals("[0,1,2,3]", WebRoutesOrder.search(names, "  "))
        assertEquals("[]", WebRoutesOrder.search(names, "kofel meran"))
    }

    /** "rank<TAB>at<TAB>m<TAB>s<TAB>up". */
    private fun row(rank: Int, at: Long, m: Double?, s: Double? = m, up: Double? = m) = "$rank\t$at\t${m ?: ""}\t${s ?: ""}\t${up ?: ""}"

    @Test fun sortKeysTiesAndTheRowsWithoutNumbers() {
        val rows = listOf(row(1, 100, 5000.0), row(2, 300, null), row(3, 200, 9000.0), row(4, 200, 5000.0), row(5, 50, null)).joinToString("\n")
        assertEquals("[1,3,2,0,4]", WebRoutesOrder.sort(rows, "NEWEST:down"))
        assertEquals("[4,0,2,3,1]", WebRoutesOrder.sort(rows, "OLDEST:down"))
        // biggest first, ties newest then the higher rank; no numbers last, newest first among themselves
        assertEquals("[2,3,0,1,4]", WebRoutesOrder.sort(rows, "LENGTH:down"))
        assertEquals("[3,0,2,1,4]", WebRoutesOrder.sort(rows, "LENGTH:up"))
        assertEquals("[3,0,2,1,4]", WebRoutesOrder.sort(rows, "ASCENT:up"))
        // unknown / nothing stored → Neueste zuerst
        assertEquals("[1,3,2,0,4]", WebRoutesOrder.sort(rows, "BOGUS:up"))
        assertEquals("[]", WebRoutesOrder.sort("", "NEWEST:down"))
    }

    @Test fun choiceAndKeys() {
        assertEquals("{\"key\":\"LENGTH\",\"biggestFirst\":false}", WebRoutesOrder.choice("LENGTH:up"))
        assertEquals("{\"key\":\"NEWEST\",\"biggestFirst\":true}", WebRoutesOrder.choice(""))
        assertEquals("[{\"key\":\"NEWEST\",\"dir\":false},{\"key\":\"OLDEST\",\"dir\":false},{\"key\":\"LENGTH\",\"dir\":true}," +
            "{\"key\":\"DURATION\",\"dir\":true},{\"key\":\"ASCENT\",\"dir\":true}]", WebRoutesOrder.keys())
    }
}
