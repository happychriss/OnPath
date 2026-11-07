package com.onpath.app.ui.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2E 2026-11-07 (CoreFieldE2ETest.aSavedRouteTapped…, GitHub class runs; CorePlanE2ETest.aRouteAddedWhileMyRoutesIsOpen…
 * on the emulator): My routes opened with the list read before, the fresh read put the new route on top – but the list
 * kept its old first row at the top (a LazyColumn holds its first visible row by key) and the new route lay above the
 * screen. A list at its top stays at its top when its first row changes; a list scrolled down keeps its place.
 */
class ListTopTest {

    @Test fun aListAtItsTopShowsARowAddedBeforeItsFirst() {
        // seen: the list at the top with "Plan 12:27" first, then "E2E saved route" read before it
        assertEquals(0, ListTop.scrollTo(firstKeyBefore = 27L, firstKeyNow = 99L, wasAtTop = true))
    }

    @Test fun aListScrolledDownKeepsItsPlace() {
        assertNull(ListTop.scrollTo(firstKeyBefore = 27L, firstKeyNow = 99L, wasAtTop = false))
    }

    @Test fun theSameFirstRowMovesNothing() {
        assertNull(ListTop.scrollTo(firstKeyBefore = 27L, firstKeyNow = 27L, wasAtTop = true))
        assertNull("the first composition", ListTop.scrollTo(firstKeyBefore = null, firstKeyNow = 27L, wasAtTop = true))
    }

    @Test fun atTopMeansTheFirstRowWhole() {
        assertTrue(ListTop.atTop(firstVisibleIndex = 0, firstVisibleOffset = 0))
        assertFalse("the first row partly scrolled away", ListTop.atTop(0, 12))
        assertFalse(ListTop.atTop(1, 0))
    }
}
