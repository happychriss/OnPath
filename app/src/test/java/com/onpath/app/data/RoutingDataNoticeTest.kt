package com.onpath.app.data

import com.onpath.app.routing.BRouterEngine
import com.onpath.strings.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The plan's routing-data notice after its move out of RoutingDataLoader.kt (shared with the web planner): the same
 * answer as the old code for every input, in English and German.
 */
class RoutingDataNoticeTest {
    /** The code as it was in RoutingDataLoader.kt before the move (frozen). */
    private object Old {
        val LOADING: String get() = Txt.s(Res.string.maps_routing_loading)
        val NOT_YET: String get() = Txt.s(Res.string.maps_routing_not_yet)
        fun text(loading: Boolean): String = if (loading) LOADING else NOT_YET
        fun isNotice(s: String?): Boolean = s == LOADING || s == NOT_YET
        fun forRoute(error: String?, missing: Boolean, loading: Boolean): String? = when {
            missing -> text(loading)
            error != null && (error.contains("No routing data") || error.contains(BRouterEngine.NO_DATA) ||
                error.contains(".rd5")) -> text(loading)
            else -> error
        }
    }

    private val errors: List<String?> get() = listOf(null, "", "No route found.", "No routing data for this area.",
        "Leg 1→2: ${BRouterEngine.NO_DATA}", "missing E10_N45.rd5 in /storage/x/segments", "Keine Routingdaten",
        RoutingDataNotice.LOADING, RoutingDataNotice.NOT_YET, "Route too long")

    private fun inBoth(block: () -> Unit) {
        val before = Locale.getDefault()
        try {
            for (l in listOf(Locale.US, Locale.GERMANY)) { Locale.setDefault(l); block() }
        } finally { Locale.setDefault(before) }
    }

    @Test fun sameAsBefore() = inBoth {
        assertEquals(Old.LOADING, RoutingDataNotice.LOADING)
        assertEquals(Old.NOT_YET, RoutingDataNotice.NOT_YET)
        for (loading in listOf(true, false)) {
            assertEquals(Old.text(loading), RoutingDataNotice.text(loading))
            for (missing in listOf(true, false)) for (e in errors) {
                assertEquals("$e/$missing/$loading", Old.forRoute(e, missing, loading), RoutingDataNotice.forRoute(e, missing, loading))
            }
        }
        for (e in errors) assertEquals(e, Old.isNotice(e), RoutingDataNotice.isNotice(e))
    }

    @Test fun kindAndKeys() = inBoth {
        assertEquals(RoutingDataNotice.Kind.LOADING, RoutingDataNotice.kindForRoute(null, missing = true, loading = true))
        assertEquals(RoutingDataNotice.Kind.NOT_YET, RoutingDataNotice.kindForRoute("x.rd5", missing = false, loading = false))
        assertNull(RoutingDataNotice.kindForRoute("No route found.", missing = false, loading = true))
        assertEquals("maps_routing_loading", RoutingDataNotice.Kind.LOADING.res.key)
        assertEquals("maps_routing_not_yet", RoutingDataNotice.Kind.NOT_YET.res.key)
    }
}
