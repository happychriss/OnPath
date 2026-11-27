package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.data.LegFiles
import com.onpath.app.domain.RouteShape
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules My routes' rows and the browser's leg store ask the engine for (2026-11-27: no TypeScript copies of them any
 * more): Format.duration (planstats.ts), RouteShape.of (routeshape.ts) and LegFiles' bounds (legstore.ts) – the app's own
 * code, answered as the page reads it.
 */
class WebRowRulesTest {
    @Test fun theDurationIsTheAppsFormat() {
        for (s in listOf(0.0, 59.0, 25 * 60.0, 3600.0, 4 * 3600.0 + 5 * 60 + 30, 30 * 3600.0)) {
            assertEquals("\"" + Format.duration(s) + "\"", WebRules.duration(s))
        }
        assertEquals("\"4 h 05 min\"", WebRules.duration(4 * 3600.0 + 5 * 60))
        assertEquals("\"25 min\"", WebRules.duration(25 * 60.0))
    }

    @Test fun theShapeIsTheAppsRouteShape() {
        val a = listOf(GeoPoint(51.0, 7.0), GeoPoint(51.01, 7.02), GeoPoint(51.02, 7.01))
        val b = listOf(GeoPoint(51.02, 7.01), GeoPoint(51.03, 7.03))
        val text = listOf(a, b).joinToString("|") { seg -> seg.joinToString(";") { "${it.lat},${it.lon}" } }
        val json = WebRules.routeShape(text, 76.0, 76.0, 8.0)
        val app = RouteShape.of(listOf(a, b), 76f, 76f, 8f)!!
        fun n(v: Float) = Engine.num(v.toDouble(), 1)
        assertTrue(json, json.startsWith("{\"lines\":[[" + n(app.lines[0][0]) + "," + n(app.lines[0][1]) + ","))
        assertEquals(2, Regex("\\[\\[|\\],\\[").findAll(json).count())
        assertTrue(json, json.endsWith(",\"sx\":${n(app.startX)},\"sy\":${n(app.startY)},\"ex\":${n(app.endX)},\"ey\":${n(app.endY)}}"))
    }

    @Test fun oneSpotOrOnePointIsNoShape() {
        assertEquals("null", WebRules.routeShape("51,7", 76.0, 76.0, 8.0))
        assertEquals("null", WebRules.routeShape("51,7;51,7;51.000001,7", 76.0, 76.0, 8.0))
    }

    @Test fun theLegStoreBoundsAreThePhones() {
        assertEquals("{\"files\":${LegFiles.MAX_FILES},\"bytes\":${LegFiles.MAX_BYTES}}", legLimits())
    }
}
