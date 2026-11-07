package com.onpath.app.ui

import com.onpath.strings.Res
import com.onpath.strings.plan_guide_find_title
import com.onpath.strings.plan_guide_go_title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The empty start sheet's guide (owner 2026-11-07, variant A without "Nur aufzeichnen"). */
class EmptyGuideTest {
    @Test fun showsOnlyForAnEmptyPlanWithoutATour() {
        assertTrue(EmptyGuide.shows(pointCount = 0, touring = false))
        assertFalse("a tour keeps its own empty-plan line", EmptyGuide.shows(pointCount = 0, touring = true))
        assertFalse(EmptyGuide.shows(pointCount = 1, touring = false))
        assertFalse(EmptyGuide.shows(pointCount = 3, touring = true))
    }

    @Test fun threeStepsAndOnlyTheFirstIsTappable() {
        assertEquals(listOf(1, 2, 3), EmptyGuide.steps.map { it.n })
        assertEquals(listOf(true, false, false), EmptyGuide.steps.map { it.tappable })
        assertEquals(Res.string.plan_guide_find_title, EmptyGuide.steps.first().title)
        // "Los geht's" is an explanation here, never a start button (no route yet)
        assertEquals(Res.string.plan_guide_go_title, EmptyGuide.steps.last().title)
        assertFalse(EmptyGuide.steps.last().tappable)
    }
}
