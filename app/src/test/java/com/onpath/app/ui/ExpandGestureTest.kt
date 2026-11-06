package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tap and drag both open / close (owner 2026-11-06 23:42): Navigate's card and the plan sheet's handle. */
class ExpandGestureTest {
    private val t = 60f

    @Test fun pullingTheClosedCardDownOpensIt() = assertTrue(ExpandGesture.cardAfterDrag(false, 120f, t))

    @Test fun pushingTheOpenCardUpClosesIt() = assertFalse(ExpandGesture.cardAfterDrag(true, -120f, t))

    @Test fun aShortWobbleChangesNothing() {
        assertFalse(ExpandGesture.cardAfterDrag(false, 30f, t))
        assertTrue(ExpandGesture.cardAfterDrag(true, -30f, t))
        assertFalse(ExpandGesture.cardAfterDrag(false, 0f, t))
    }

    @Test fun pullingTheOpenCardFurtherDownKeepsItOpen() = assertTrue(ExpandGesture.cardAfterDrag(true, 200f, t))

    @Test fun pushingTheClosedCardUpKeepsItClosed() = assertFalse(ExpandGesture.cardAfterDrag(false, -200f, t))

    @Test fun aTapToggles() {
        assertTrue(ExpandGesture.cardAfterTap(false))
        assertFalse(ExpandGesture.cardAfterTap(true))
    }

    @Test fun theHandleTapWalksTheSteps() {
        // rolled → one row → profile → points → back to profile (the established order, PlanSize.tapped)
        assertEquals(PlanSize.MINI, PlanSize.COLLAPSED.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.MINI.tapped())
        assertEquals(PlanSize.FULL, PlanSize.COMPACT.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.FULL.tapped())
    }

    @Test fun theHandleSaysBiggerExceptOnTheLargestStep() {
        assertTrue(ExpandGesture.handleGrows(PlanSize.COLLAPSED))
        assertTrue(ExpandGesture.handleGrows(PlanSize.MINI))
        assertTrue(ExpandGesture.handleGrows(PlanSize.COMPACT))
        assertFalse(ExpandGesture.handleGrows(PlanSize.FULL))
    }
}
