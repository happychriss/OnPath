package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's slices on the planner's page (WebState): My routes' Zuletzt (RoutesState over the page's stored plans – the
 * one-plan rule, the bin, the one-time trim, restore, LivePlanRows) and Save's name dialog (SaveDialog). The page's
 * lines in, the store's writes out – what web/app/src/slices.ts applies to localStorage.
 */
class WebStateTest {
    private val T0 = 1_800_000_000_000.0
    /** A page line: saved routes first, then drafts newest first. */
    private fun line(id: Long, draft: Boolean, at: Long, name: String, pts: String, deleted: Long = -1) = "$id\t${if (draft) 1 else 0}\t$deleted\t$at\t$name\t$pts"
    private fun field(json: String, name: String) = Regex("\"$name\":(-?\\d+|true|false|null|\"[^\"]*\")").find(json)!!.groupValues[1]
    private fun writes(json: String) = Regex("\\{\"op\":\"(\\w+)\",\"id\":(-?\\d+)").findAll(json).map { it.groupValues[1] + " " + it.groupValues[2] }.toList()

    @Test fun theOneTimeTrimKeepsTheNewestDraft() {
        val t = listOf(line(2, true, 3000, "B", "3,3;4,4"), line(3, true, 2000, "C", "5,5;6,6"), line(1, true, 1000, "A", "1,1;2,2")).joinToString("\n")
        val r = WebState.trim(t, -1.0, T0)
        assertEquals("2", field(r, "result"))
        assertEquals(listOf("bin 3", "bin 1"), writes(r))
    }

    @Test fun aKeptPlanIsTheOneDraftTheOthersGoToTheBin() {
        val t = listOf(line(2, true, 3000, "B", "3,3;4,4")).joinToString("\n")
        val r = WebState.keep(t, 2.0, T0, "9,9;10,10", unsaved = true, savedId = -1.0, newId = 7.0)
        assertEquals("7", field(r, "result")); assertEquals("7", field(r, "recent"))
        assertEquals(listOf("bin 2"), writes(r))
        // the same points again: no second entry – that draft takes the place
        val again = WebState.keep(line(7, true, 4000, "Neu", "9,9;10,10"), 7.0, T0, "9,9;10,10", true, -1.0, 8.0)
        assertEquals("-1", field(again, "result")); assertEquals("7", field(again, "recent")); assertEquals(emptyList<String>(), writes(again))
        // one point is no plan
        assertEquals("-1", field(WebState.keep("", -1.0, T0, "1,1", true, -1.0, 9.0), "result"))
    }

    @Test fun aSavedPlanLeftAsItIsHoldsThePlaceAndATwinNeverMakesADraft() {
        val saved = line(50, false, 100, "S", "")
        // saved as it is: the saved route takes the place, the draft goes to the bin
        val r = WebState.keep(listOf(saved, line(2, true, 3000, "B", "3,3;4,4")).joinToString("\n"), 2.0, T0, "1,1;2,2", false, 50.0, 9.0)
        assertEquals("-1", field(r, "result")); assertEquals("50", field(r, "recent")); assertEquals(listOf("bin 2"), writes(r))
        // the saved route deleted (in the bin): nothing kept twice
        val binned = WebState.keep(line(50, false, 100, "S", "", deleted = 5), -1.0, T0, "1,1;2,2", false, 50.0, 9.0)
        assertEquals("-1", field(binned, "result")); assertEquals(emptyList<String>(), writes(binned))
        // changed, but exactly a saved route's points: that route takes the place
        val twin = WebState.keep(line(51, false, 100, "T", "1,1;2,2"), -1.0, T0, "1,1;2,2", true, -1.0, 9.0)
        assertEquals("-1", field(twin, "result")); assertEquals("51", field(twin, "recent"))
    }

    @Test fun restoreSwapsDeleteBinsSaveDropsTheDraft() {
        val t = listOf(line(2, true, 3000, "B", "3,3;4,4"), line(1, true, 1000, "A", "1,1;2,2", deleted = 5000)).joinToString("\n")
        val r = WebState.restore(t, 2.0, T0, 1.0)
        assertEquals("true", field(r, "result")); assertEquals("1", field(r, "recent"))
        assertEquals(listOf("unbin 1", "bin 2"), writes(r))
        assertEquals(listOf("bin 2"), writes(WebState.toBin(t, 2.0, T0, 2.0)))
        assertEquals("false", field(WebState.toBin(t, 2.0, T0, 99.0), "result"))
        // saved from Zuletzt: the draft goes for good, the saved route holds the place
        val s = WebState.afterSaved(t, 2.0, T0, 60.0, 2.0, "")
        assertEquals(listOf("gone 2"), writes(s)); assertEquals("60", field(s, "recent"))
        // a saved route back from the bin: its twin draft goes to the bin, the route takes the place the twin held
        val tw = listOf(line(70, false, 100, "R", "8,8;9,9", deleted = 4000), line(3, true, 3000, "Twin", "8,8;9,9")).joinToString("\n")
        val back = WebState.restore(tw, 3.0, T0, 70.0)
        assertEquals(listOf("unbin 70", "bin 3"), writes(back)); assertEquals("70", field(back, "recent"))
    }

    @Test fun thePlaceNameRenamesOnlyAnUntouchedDraft() {
        val t = line(4, true, 1000, "Route 1 Nov", "1,1;2,2")
        assertTrue(WebState.renameDraft(t, 4.0, 4.0, "Route 1 Nov", "Algund → Lana").contains("\"name\":\"Algund → Lana\""))
        assertEquals("false", field(WebState.renameDraft(line(4, true, 1000, "Mine", "1,1;2,2"), 4.0, 4.0, "Route 1 Nov", "X"), "result"))
    }

    @Test fun theZuletztRowIsTheAppsLivePlanRows() {
        val saved = line(50, false, 100, "S", "")
        assertEquals("{\"live\":false,\"id\":50,\"saved\":true}", WebState.row(saved, 50.0, 0, -1.0, false))
        assertEquals("{\"live\":false,\"id\":-1,\"saved\":false}", WebState.row("", 50.0, 0, -1.0, false))
        // a draft never hides behind a saved route holding the place
        assertEquals("{\"live\":false,\"id\":2,\"saved\":false}", WebState.row(listOf(saved, line(2, true, 3000, "B", "3,3;4,4")).joinToString("\n"), 50.0, 0, -1.0, false))
        assertEquals("true", field(WebState.row(saved, -1.0, 3, 50.0, false), "saved"))
        assertEquals("false", field(WebState.row(saved, -1.0, 3, 50.0, true), "saved"))
    }

    @Test fun mostRecentOpeningStartsWithNewestFirst() {
        WebState.open(true)
        assertEquals("\"LENGTH:down\"", WebState.tapSort("LENGTH"))
        assertEquals("\"LENGTH:up\"", WebState.tapSort("LENGTH"))
        assertEquals("\"LENGTH:up\"", WebState.open(false)) // back while open: the choice holds
        assertEquals("\"NEWEST:down\"", WebState.open(true))
    }

    @Test fun saveDialogAsksNamesAndDecides() {
        val a = WebState.saveRequest(-1.0, "", "5", "Route 1 Nov, 10:12", "Waalweg")
        assertEquals("\"NEW\"", field(a, "kind")); assertEquals("\"Route 1 Nov, 10:12\"", field(a, "name"))
        val no = field(a, "no").toInt()
        assertEquals("\"Waalweg (2)\"", field(WebState.saveProposed(no, "Waalweg"), "name"))
        // blank keeps the proposal
        assertEquals("{\"write\":\"NEW\",\"routeId\":-1,\"name\":\"Waalweg (2)\"}", WebState.saveConfirm("  ", -1.0))
        assertEquals("null", WebState.saveConfirm("x", -1.0)) // not open any more
        assertEquals("null", WebState.saveProposed(no, "late")) // the dialog closed meanwhile
        // the open saved route still there: overwrite, renamed when the name changed
        assertEquals("\"OVERWRITE\"", field(WebState.saveRequest(5.0, "Waalweg", "5", "d", "Waalweg"), "kind"))
        assertEquals("{\"write\":\"OVERWRITE\",\"routeId\":5,\"name\":\"Neu\"}", WebState.saveConfirm(" Neu ", 5.0))
        assertEquals("\"NEW\"", field(WebState.saveRequest(5.0, "Waalweg", "6", "d", ""), "kind")) // gone meanwhile
        WebState.saveCancel()
        assertEquals("null", WebState.saveConfirm("x", 5.0))
        assertEquals("\"Waalweg (2)\"", field(WebState.saveRequestCopy("Waalweg", 5.0, "Waalweg"), "name"))
        assertEquals("{\"write\":\"COPY\",\"routeId\":5,\"name\":\"Waalweg (2)\"}", WebState.saveConfirm("", 5.0))
    }
}
