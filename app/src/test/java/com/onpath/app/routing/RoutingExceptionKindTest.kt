package com.onpath.app.routing

import com.onpath.strings.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Code decides by [RoutingException.kind], never by the (translated) message (EN + DE, owner 2026-10-26). */
class RoutingExceptionKindTest {
    @Test fun kindFromTheAppsOwnTexts() {
        assertEquals(RoutingException.Kind.NO_DATA, RoutingException(BRouterEngine.NO_DATA).kind)
        assertEquals(RoutingException.Kind.CANCELLED, RoutingException(BRouterEngine.CANCELLED).kind)
        assertEquals(RoutingException.Kind.BUSY, RoutingException(BRouterEngine.BUSY, timedOut = true).kind)
        assertEquals(RoutingException.Kind.TOO_COMPLEX, RoutingException(Txt.s(Res.string.plan_err_memory)).kind)
        assertEquals(RoutingException.Kind.UNAVAILABLE, RoutingException(Txt.s(Res.string.plan_err_engine)).kind)
        assertEquals(RoutingException.Kind.TIMEOUT, RoutingException(Txt.s(Res.string.plan_err_timeout, 30), timedOut = true).kind)
        assertEquals(RoutingException.Kind.START_POINT,
            RoutingException(Txt.s(Res.string.plan_err_too_far, Txt.s(Res.string.plan_err_start_point), 400, "Hike")).kind)
        assertEquals(RoutingException.Kind.OTHER, RoutingException("No route found.").kind)
        assertEquals(RoutingException.Kind.NO_DATA, RoutingException("x", kind = RoutingException.Kind.NO_DATA).kind)
    }

    @Test fun germanTextsKeepTheirKind() {
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertEquals("Keine Routing-Daten für dieses Gebiet.", BRouterEngine.NO_DATA)
            assertEquals(RoutingException.Kind.NO_DATA, RoutingException(BRouterEngine.NO_DATA).kind)
            assertEquals(RoutingException.Kind.START_POINT,
                RoutingException(Txt.s(Res.string.plan_err_too_far, Txt.s(Res.string.plan_err_start_point), 400, "Wandern")).kind)
            assertEquals("Punkt 1 → 2: kein Weg gefunden", Planner.legError(1, 2, RoutingException("x")))
        } finally { java.util.Locale.setDefault(old) }
    }
}
