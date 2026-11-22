package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The planner as a reader of detour anchors (docs/design/detour-stitch.md §6 step 3): it keeps them, it never makes them. */
class WebAnchorsTest {
    private val a = GeoPoint(47.100001, 11.100002)
    private val p = GeoPoint(47.300003, 11.300004)
    private val b = GeoPoint(47.500005, 11.500006)
    private val anchors = mapOf(1 to Anchors(listOf(GeoPoint(47.25, 11.25)), listOf(GeoPoint(47.35, 11.35))))
    private val code = PlanCode.encode(SharedPlan(listOf(a, p, b), Activity.HIKE, 3, anchors = anchors))
    private val pts = "47.100001,11.100002;47.300003,11.300004;47.500005,11.500006"

    private fun anchorsText() = Regex("\"anchors\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(Engine.planParse("p=$code"))!!
        .groupValues[1].replace("\\\"", "\"")
    private fun encode(points: String = pts, activity: String = "HIKE", sac: Int = 3) =
        Engine.planCode(points, activity, sac, "", "", "", Engine.anchorsLive(points, ""))

    @Before fun open() {
        Engine.historyImport("#")
        Engine.planArrive(pts, anchorsText())
    }

    @Test fun aLinkKeepsItsExactPointsAndGoesOutTheSame() {
        assertTrue(Engine.planParse("p=$code"), Engine.planParse("p=$code").contains("[47.300003,11.300004]"))
        assertEquals(code, encode())
    }

    @Test fun anotherProfileNeitherRoutesNorSavesThemAndTheWayBackDoes() {
        assertEquals(null, PlanCode.decode(encode(sac = 2))!!.anchors.takeIf { it.isNotEmpty() })
        assertEquals(null, PlanCode.decode(encode(activity = "BIKE_TOURING", sac = 0))!!.anchors.takeIf { it.isNotEmpty() })
        assertEquals(code, encode())
    }

    @Test fun aSnapKeepsThemOnTheirPoint() {
        val snapped = "47.100011,11.100012;47.300013,11.300014;47.500015,11.500016"
        Engine.historySnap(pts, snapped)
        assertEquals(anchors, PlanCode.decode(encode(snapped))!!.anchors)
    }

    @Test fun undoBringsThemBackAfterAnEditDroppedThem() {
        val moved = "47.100001,11.100002;47.310000,11.310000;47.500005,11.500006"
        Engine.historyRecord(pts, moved)
        assertTrue(PlanCode.decode(encode(moved))!!.anchors.isEmpty())
        assertEquals(pts, Engine.historyStep("undo", moved))
        assertEquals(code, encode())
    }

    @Test fun aFreshWorkerGetsThemWithTheHistory() {
        val moved = "47.100001,11.100002;47.310000,11.310000;47.500005,11.500006"
        Engine.historyRecord(pts, moved)
        Engine.historyStep("undo", moved)
        val handedOver = Engine.historyExport()
        Engine.historyImport("#")
        assertEquals("", Engine.anchorsNow())
        Engine.historyImport(handedOver)
        assertEquals(code, encode())
        assertEquals(moved, Engine.historyStep("redo", pts))
        assertEquals(pts, Engine.historyStep("undo", moved))
        assertEquals(code, encode())
    }

    @Test fun anotherPlanWithOtherPointsGetsNone() {
        val other = "47.0,11.0;47.2,11.2"
        assertEquals("kept", Engine.anchorsLive(other, "kept"))
        assertNotEquals(code, encode(other))
        assertFalse(PlanCode.decode(encode(other))!!.anchors.isNotEmpty())
    }

    @Test fun aPlainPlanHasNoAnchorText() {
        Engine.historyImport("#")
        Engine.planArrive(pts, "")
        assertEquals("", Engine.anchorsNow())
        val plain = PlanCode.encode(SharedPlan(listOf(a, p, b), Activity.HIKE, 3))
        assertTrue(Engine.planParse("p=$plain").contains("\"anchors\":null"))
    }

    @Test fun aLinkGivesItsCodeByTheRuleOfPlanCode() {
        assertEquals("AbC-_1", Engine.linkCode(" https://plan.onpath.earth/#p=AbC-_1 "))
        assertEquals("AbC", Engine.linkCode("https://x/?a=1&p=AbC&b=2"))
        assertEquals("AbC", Engine.linkCode("p=AbC"))
        assertEquals("AbC", Engine.linkCode("https://x/#map=1&top=2&p=AbC")) // "p=" inside "top=" is no marker
        assertEquals("AbC", Engine.linkCode("AbC")) // a bare code stays
        assertEquals("#p=", Engine.linkCode("#p=")) // no code: PlanCode.parse says no
        val long = "x".repeat(16_001)
        assertEquals("#p=$long", Engine.linkCode("#p=$long")) // over the limit: PlanCode.parse refuses it
        assertEquals(null, PlanCode.parse(Engine.linkCode("#p=$long")))
    }

    @Test fun theStoreIsAskedForTheExpandedLegs() {
        val names = Regex("\"([^\"]+)\"").findAll(Engine.legsWanted(pts, "HIKE", 3)).map { it.groupValues[1] }.toList()
        assertEquals(names.toString(), 4, names.count { it.endsWith("_cut") }) // each of the four legs touches an anchor
        assertEquals(names.toString(), 4, names.count { it.endsWith("_settled") })
        assertEquals(names.toString(), 12, names.size)
    }
}
