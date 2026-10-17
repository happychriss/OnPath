package com.onpath.app.ui

import com.onpath.app.routing.Share
import org.junit.Assert.assertEquals
import org.junit.Test

class BreakdownTest {
    @Test fun smallSharesFoldIntoOther() {
        val f = foldSmall(listOf(Share("path", 9000.0), Share("road", 800.0), Share("steps", 100.0), Share("ferry", 100.0)))
        assertEquals(listOf(Share("path", 9000.0), Share("road", 800.0), Share("other", 200.0)), f)
    }

    @Test fun emptyStaysEmpty() = assertEquals(emptyList<Share>(), foldSmall(emptyList()))

    @Test fun summaryLineNamesLargestShares() = assertEquals(
        "Path 61 % · Natural trail 52 %",
        summaryLine(listOf(Share("track", 3000.0), Share("path", 6100.0), Share("road", 900.0)),
            listOf(Share("natural_trail", 5200.0), Share("gravel", 4800.0))))
}
