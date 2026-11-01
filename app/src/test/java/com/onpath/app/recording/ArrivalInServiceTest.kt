package com.onpath.app.recording

import com.onpath.app.routing.ArrivalGate
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review H2: arrival only worked with the app on screen; in the pocket the rider walked on to the car park and got
 * off-route alarms and re-routes. The service checks it on every GPS fix, then guidance is quiet, recording goes on.
 */
class ArrivalInServiceTest {
    private val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
    private val onFix = svc.substringAfter("private fun onFix(loc: Location)").substringBefore("\n    }\n")

    @Test fun theServiceChecksArrivalOnEveryFix() {
        assertTrue(onFix.contains("checkArrived(p.toGeo(), loc.accuracy.toDouble())"))
        // after the arrival: no off-route checks, no junction cues, no voice – the track is still recorded
        assertTrue(onFix.contains("moveWatch.moving(nowEl) && !arrived"))
        assertTrue(onFix.contains("if (!arrived) try { junctionCue(loc, nowEl) }"))
        assertTrue(svc.contains("if (!voiceOn() || arrived) return"))
        assertTrue(svc.contains("arrived = arrived,"))
    }

    @Test fun theAppAsksFromTheServiceStateOnly() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertFalse("no second gate in the app", vm.contains("ArrivalGate()"))
        assertTrue(vm.contains("onArrived(st?.arrived == true)"))
    }

    /** The gate the service uses: away first, then near the end with little left. */
    @Test fun gate() {
        val g = ArrivalGate()
        val end = GeoPoint(46.0, 11.0)
        assertFalse(g.onFix(end, end, 0.0, 5.0)) // never away yet (round trip start)
        assertFalse(g.onFix(GeoPoint(46.01, 11.0), end, 1100.0, 5.0))
        assertTrue(g.onFix(GeoPoint(46.0001, 11.0), end, 10.0, 5.0))
        assertFalse("asked once", g.onFix(end, end, 0.0, 5.0))
    }
}
