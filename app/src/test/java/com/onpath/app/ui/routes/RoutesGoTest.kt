package com.onpath.app.ui.routes

import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.routes.RoutesGo.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Los geht's" from My routes (owner 2026-11-07): the route is loaded as the plan, the tour starts by the normal start
 * path once its line is there – never on an old line, never on a plan the user changed meanwhile, never a second tour.
 */
class RoutesGoTest {
    private val loaded = listOf(GeoPoint(46.67, 11.16), GeoPoint(46.70, 11.20))

    private fun step(now: List<GeoPoint> = loaded, routing: Boolean = false, hasRoute: Boolean = true, recording: Boolean = false) =
        RoutesGo.step(loaded, now, routing, hasRoute, recording)

    @Test fun startsOnceTheLoadedRoutesLineIsThere() = assertEquals(Step.START, step())

    @Test fun waitsWhileTheLineIsCalculated() = assertEquals(Step.WAIT, step(routing = true, hasRoute = true))

    @Test fun aPlanChangedMeanwhileStartsNothing() =
        assertEquals(Step.DROP, step(now = loaded + GeoPoint(46.71, 11.21)))

    @Test fun noLineAfterCalculatingStartsNothing() =
        // no map / routing failed / the long-route question: the plan sheet says why, its own Los geht's stays
        assertEquals(Step.DROP, step(hasRoute = false))

    @Test fun aTourRunningAlreadyIsNeverStartedTwice() = assertEquals(Step.DROP, step(recording = true))
}
