package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongRouteTest {
    private val meran = GeoPoint(46.67, 11.16)
    private val dorfTirol = GeoPoint(46.69, 11.15)
    private val munich = GeoPoint(48.14, 11.58)

    @Test fun aDayHikeIsNotAsked() = assertFalse(LongRoute.needsAsk(listOf(meran, dorfTirol), Activity.HIKE))

    /** Italy → Germany on foot (a point set there by mistake). */
    @Test fun italyToGermanyOnFootIsAsked() {
        assertTrue(LongRoute.needsAsk(listOf(meran, munich), Activity.HIKE))
        assertTrue(LongRoute.describe(LongRoute.estimateS(listOf(meran, munich), Activity.HIKE)).endsWith("days"))
    }

    @Test fun onePointNeverAsks() = assertFalse(LongRoute.needsAsk(listOf(meran), Activity.HIKE))

    @Test fun wordsForTheQuestion() {
        assertEquals("about 30 hours", LongRoute.describe(30 * 3600.0))
        assertEquals("about 29 days", LongRoute.describe(29 * 24 * 3600.0))
    }
}
