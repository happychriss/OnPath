package com.onpath.app.plansync

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlanLinks
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.PairCode
import com.onpath.app.sync.SyncKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CORE FEATURE (A-007): a pairing link of the web planner (`https://plan.onpath.earth/#pair=<code>`, its QR code read
 * by the phone's camera, a tapped or shared link) goes to pairing – never to a plan; a plan link stays a plan; a
 * cut-off pairing link is "broken" (Settings → Browser sync says so). The app link filter is the plan links' (A-005).
 */
class CorePairingLinkTest {
    private val secret = ByteArray(32) { (it * 3 + 5).toByte() }
    private val keys = SyncKeys(secret)
    private val a = GeoPoint(46.67171, 11.15003)
    private val b = GeoPoint(46.68512, 11.13470)

    @Test fun aPairingLinkFromTheCameraOrTheLinkFilterPairs() {
        val link = PairCode.link(PlanLinks.BASE, secret)
        assertTrue(link.startsWith("https://plan.onpath.earth/#pair="))
        val viaCamera = PlanLinks.fromIntent(PlanLinks.Via.OPENED, link, null, null, hasStream = false)
        assertTrue(secret.contentEquals((viaCamera as PlanLinks.Found.Pairing).secret))
        val shared = PlanLinks.fromIntent(PlanLinks.Via.SHARED, null, "text/plain", "Pair OnPath: $link", hasStream = false)
        assertTrue(secret.contentEquals((shared as PlanLinks.Found.Pairing).secret))
        assertEquals(keys.check, SyncKeys((viaCamera).secret).check)
        // a plan link stays a plan, a cut-off pairing link is broken, a bare planner link is not a pairing
        assertTrue(PlanLinks.fromLink(PlanCode.link(PlanLinks.BASE, SharedPlan(listOf(a, b), Activity.HIKE))) is PlanLinks.Found.Plan)
        assertEquals(PlanLinks.Found.BrokenPairing, PlanLinks.fromLink(link.dropLast(10)))
        assertEquals(PlanLinks.Found.Broken, PlanLinks.fromLink(PlanLinks.BASE))
    }
}
