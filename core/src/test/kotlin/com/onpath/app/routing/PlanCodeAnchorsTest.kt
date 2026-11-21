package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/** Tag 7 of the plan code: exact points + detour anchors (docs/design/detour-stitch.md §3, §8.10). */
class PlanCodeAnchorsTest {
    private val pts = listOf(
        GeoPoint(46.671234, 11.150567), GeoPoint(46.680129, 11.126405), GeoPoint(46.687906, 11.111103),
        GeoPoint(46.700001, 11.099999), GeoPoint(46.71, 11.08),
    )

    private fun p(lat: Double, lon: Double) = GeoPoint(lat, lon)
    private val anchors = mapOf(
        2 to Anchors(listOf(p(46.690123, 11.120001), p(46.689001, 11.115002)), listOf(p(46.694444, 11.105555))),
        3 to Anchors(after = listOf(p(46.703333, 11.095001))),
    )
    private fun plan(anchors: Map<Int, Anchors> = this.anchors) = SharedPlan(pts, Activity.HIKE, 3, pinned = setOf(2), names = mapOf(2 to "Gasthaus"), title = "T", anchors = anchors)

    // a tiny Base64url + byte builder to craft damaged tag-7 payloads
    private val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private fun b64(code: String): ByteArray {
        val out = ArrayList<Byte>(); var v = 0; var bits = 0
        for (c in code) { v = (v shl 6) or abc.indexOf(c); bits += 6; if (bits >= 8) { bits -= 8; out += (v shr bits).toByte(); v = v and ((1 shl bits) - 1) } }
        return out.toByteArray()
    }
    private fun b64(b: ByteArray) = buildString {
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i); var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xff else 0)
            for (k in 0..n) append(abc[(v shr (18 - 6 * k)) and 0x3f])
            i += 3
        }
    }
    private class W { val l = ArrayList<Byte>()
        fun varint(v: Long): W { var x = v; while (x >= 0x80) { l += ((x and 0x7f) or 0x80).toByte(); x = x ushr 7 }; l += x.toByte(); return this }
        fun zz(v: Long) = varint((v shl 1) xor (v shr 63))
    }

    /** The code of [plan] without tag 7 plus a hand-made tag 7 with [payload]. */
    private fun withTag7(payload: ByteArray): String {
        val base = b64(PlanCode.encode(plan(emptyMap())))
        val tag = W().varint(7).varint(payload.size.toLong()).l.toByteArray()
        return b64(base + tag + payload)
    }

    private fun rem(w: W, points: List<GeoPoint>, bad: Boolean = false, count: Int = pts.size, format: Long = 1) {
        w.varint(format); w.varint(count.toLong())
        for ((i, q) in points.withIndex()) {
            w.zz(((q.lat * 1e6).roundToLong() - (q.lat * 1e5).roundToLong() * 10) + if (bad && i == 1) 11 else 0)
            w.zz((q.lon * 1e6).roundToLong() - (q.lon * 1e5).roundToLong() * 10)
        }
    }

    private fun entry(w: W, i: Long, before: List<GeoPoint>, after: List<GeoPoint>, extra: Int = 0) {
        val e = W()
        e.varint(i).varint(before.size.toLong()).varint(after.size.toLong())
        var lat = if (i in pts.indices) (pts[i.toInt()].lat * 1e6).roundToLong() else 0L
        var lon = if (i in pts.indices) (pts[i.toInt()].lon * 1e6).roundToLong() else 0L
        for (q in before + after) {
            val la = (q.lat * 1e6).roundToLong(); val lo = (q.lon * 1e6).roundToLong()
            e.zz(la - lat); e.zz(lo - lon); lat = la; lon = lo
        }
        repeat(extra) { e.varint(77) } // bytes of a later format
        w.varint(e.l.size.toLong()); w.l += e.l
    }

    private fun assertPlainPoints(code: String) {
        val back = PlanCode.decode(code)!!
        assertEquals(pts.size, back.points.size)
        pts.zip(back.points).forEach { (a, b) -> assertTrue(abs(a.lat - b.lat) < 6e-6 && abs(a.lon - b.lon) < 6e-6) }
        // the 1e-5° values, not the exact ones
        assertEquals((pts[0].lat * 1e5).roundToLong() / 1e5, back.points[0].lat, 0.0)
        assertTrue(back.anchors.isEmpty())
        assertEquals(setOf(2), back.pinned); assertEquals("T", back.title)
    }

    @Test fun roundTripKeepsExactPointsAndAnchors() {
        val back = PlanCode.decode(PlanCode.encode(plan()))!!
        assertEquals(pts, back.points)
        assertEquals(anchors, back.anchors)
        assertEquals(setOf(2), back.pinned); assertEquals(mapOf(2 to "Gasthaus"), back.names); assertEquals(Activity.HIKE, back.activity)
        assertEquals(PlanCode.encode(plan()), PlanCode.encode(back)) // stable
    }

    @Test fun withoutAnchorsTheCodeIsTheOldOne() {
        assertEquals("AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU",
            PlanCode.encode(SharedPlan(listOf(GeoPoint(46.6717, 11.15), GeoPoint(46.6879, 11.1111)), Activity.HIKE, 3,
                pinned = setOf(1), names = mapOf(1 to "Vellau"), title = "Waalweg", anchors = emptyMap())))
        assertEquals(PlanCode.encode(plan(emptyMap())), PlanCode.encode(plan(mapOf(1 to Anchors()))))
    }

    @Test fun anOlderReaderKeepsThePlanAtOneMetre() {
        val code = PlanCode.encode(plan())
        val old = PlanCode.decode(code, knownTags = 6)!!
        assertEquals(pts.size, old.points.size)
        assertEquals((pts[0].lat * 1e5).roundToLong() / 1e5, old.points[0].lat, 0.0)
        assertEquals(setOf(2), old.pinned)
        assertEquals(old, plan(emptyMap()).copy(points = old.points))
        assertTrue(old.anchors.isEmpty())
        assertEquals(PlanCode.decode(PlanCode.encode(plan(emptyMap())))!!.points, old.points)
    }

    @Test fun hand_made_payload_reads() {
        val w = W(); rem(w, pts); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after)
        val back = PlanCode.decode(withTag7(w.l.toByteArray()))!!
        assertEquals(pts, back.points)
        assertEquals(mapOf(2 to anchors[2]!!), back.anchors)
    }

    @Test fun remainderOutOfRangeDropsTheWholeTag() {
        val w = W(); rem(w, pts, bad = true); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertPlainPoints(withTag7(w.l.toByteArray()))
    }

    @Test fun missingRemaindersDropTheWholeTag() {
        val w = W(); rem(w, pts.take(3)) // fewer remainders than points (count says 5): the rest reads as garbage, never throws
        assertPlainPoints(withTag7(w.l.toByteArray()))
        val w2 = W(); rem(w2, pts.take(3), count = 3) // count 3 is not the plan's 5
        entry(w2, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertPlainPoints(withTag7(w2.l.toByteArray()))
        assertPlainPoints(withTag7(ByteArray(0)))
        assertPlainPoints(withTag7(byteArrayOf(1))) // format only
        val w3 = W(); rem(w3, pts, format = 2) // another format
        entry(w3, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertPlainPoints(withTag7(w3.l.toByteArray()))
    }

    @Test fun anEntryMayCarryExtraBytesAtItsEnd() {
        val w = W(); rem(w, pts); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after, extra = 5); entry(w, 3, emptyList(), anchors[3]!!.after)
        val back = PlanCode.decode(withTag7(w.l.toByteArray()))!!
        assertEquals(pts, back.points); assertEquals(anchors, back.anchors)
    }

    @Test fun anEntryRunningPastThePayloadDropsTheWholeTag() {
        val w = W(); rem(w, pts); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after)
        w.varint(50).varint(3) // claims 50 bytes, has 1
        assertPlainPoints(withTag7(w.l.toByteArray()))
    }

    @Test fun anEmptyEntryIsSkipped() {
        val w = W(); rem(w, pts); entry(w, 1, emptyList(), emptyList()); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertEquals(mapOf(2 to anchors[2]!!), PlanCode.decode(withTag7(w.l.toByteArray()))!!.anchors)
    }

    @Test fun codeIsStableAfterADecode() {
        val r = java.util.Random(42)
        repeat(200) {
            val ps = List(1 + r.nextInt(6)) { GeoPoint(46.0 + r.nextDouble(), 11.0 + r.nextDouble()) }
            val an = if (ps.size > 2) mapOf(1 to Anchors(listOf(GeoPoint(46.5 + r.nextDouble() / 10, 11.5)), listOf(GeoPoint(46.2 + r.nextDouble() / 10, 11.2)))) else emptyMap()
            val x = PlanCode.encode(SharedPlan(ps, Activity.HIKE, anchors = an))
            assertEquals(x, PlanCode.encode(PlanCode.decode(x)!!))
            assertEquals(PlanCode.decode(x, 6)!!.points, PlanCode.decode(PlanCode.encode(PlanCode.decode(x)!!), 6)!!.points)
        }
        val tricky = listOf(GeoPoint(46.6712349, 11.1500001), GeoPoint(46.6712351, 11.1499999), GeoPoint(46.6712350, 11.15), GeoPoint(-33.8688195, -70.0000049))
        val x = PlanCode.encode(SharedPlan(tricky, Activity.HIKE, anchors = mapOf(1 to Anchors(listOf(GeoPoint(46.7, 11.2))))))
        assertEquals(x, PlanCode.encode(PlanCode.decode(x)!!))
    }

    @Test fun anAnchorMayBeFarFromItsPointButNotOver30Degrees() {
        val base = pts[2]
        val far = List(8) { GeoPoint(Math.round((base.lat - 0.7 * (it + 1)) * 1e6) / 1e6, base.lon) } // 8 anchors 78 km apart, 620 km away
        val back = PlanCode.decode(PlanCode.encode(plan(mapOf(2 to Anchors(far)))))!!
        assertEquals(far, back.anchors[2]!!.before)
        val edge = GeoPoint(base.lat, base.lon + 29.999999)
        assertEquals(setOf(2), PlanCode.decode(PlanCode.encode(plan(mapOf(2 to Anchors(listOf(edge))))))!!.anchors.keys)
        val over = GeoPoint(base.lat, base.lon + 30.000001)
        val w = PlanCode.decode(PlanCode.encode(plan(mapOf(2 to Anchors(listOf(over)), 3 to Anchors(after = listOf(GeoPoint(46.703, 11.095)))))))!!
        assertEquals(setOf(3), w.anchors.keys) // the writer leaves the entry out
        val hand = W(); rem(hand, pts); entry(hand, 2, listOf(over), emptyList()); entry(hand, 3, emptyList(), listOf(GeoPoint(46.703, 11.095)))
        assertEquals(setOf(3), PlanCode.decode(withTag7(hand.l.toByteArray()))!!.anchors.keys) // and the reader drops it
    }

    @Test fun aNonFiniteAnchorLeavesItsEntryOutInsteadOfThrowing() {
        val bad = Anchors(listOf(GeoPoint(Double.NaN, 11.0)))
        val inf = Anchors(after = listOf(GeoPoint(46.7, Double.POSITIVE_INFINITY)))
        val back = PlanCode.decode(PlanCode.encode(plan(mapOf(1 to bad, 2 to inf, 3 to Anchors(after = listOf(GeoPoint(46.703, 11.095)))))))!!
        assertEquals(setOf(3), back.anchors.keys)
    }

    @Test fun truncatedPayloadDropsTheWholeTag() {
        val w = W(); rem(w, pts); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertPlainPoints(withTag7(w.l.toByteArray().dropLast(3).toByteArray()))
        val w2 = W(); rem(w2, pts); w2.varint(4).varint(2).varint(1_000_000_000L).varint(1) // a count the bytes cannot hold
        assertPlainPoints(withTag7(w2.l.toByteArray()))
        val w3 = W(); rem(w3, pts); w3.varint(4).varint(2).varint(1).varint(0).zz(5) // one coordinate missing
        assertPlainPoints(withTag7(w3.l.toByteArray()))
    }

    @Test fun overFiveHundredAnchorsDropTheWholeTag() {
        val w = W(); rem(w, pts)
        val near = p(46.690123, 11.120001)
        repeat(63) { entry(w, 2, List(8) { near }, emptyList()) } // 504 anchors
        assertPlainPoints(withTag7(w.l.toByteArray()))
    }

    @Test fun badEntriesAreDroppedOneByOne() {
        val good = anchors[2]!!
        val w = W(); rem(w, pts)
        entry(w, 9, good.before, good.after) // index out of range
        entry(w, 1, List(9) { p(46.68 + it * 1e-4, 11.12) }, emptyList()) // nine on a side
        entry(w, 3, listOf(p(46.703333, 60.0)), emptyList()) // far over 30°
        entry(w, 2, good.before, good.after) // fine
        val back = PlanCode.decode(withTag7(w.l.toByteArray()))!!
        assertEquals(pts, back.points)
        assertEquals(mapOf(2 to good), back.anchors)
    }

    @Test fun writerCapsEightPerSideAndFiveHundredInTotal() {
        val near = p(46.690123, 11.120001)
        val nine = Anchors(List(9) { near }, emptyList())
        val eight = Anchors(List(8) { near }, List(8) { near })
        val back = PlanCode.decode(PlanCode.encode(plan(mapOf(1 to nine, 2 to eight, 3 to Anchors(after = listOf(near))))))!!
        assertEquals(mapOf(2 to eight, 3 to Anchors(after = listOf(near))), back.anchors) // the entry with nine is left out whole
        assertEquals(pts, back.points)
        // 500 in total: with 5 points x 16 = 80 max, so build a long plan
        val long = List(40) { GeoPoint(46.0 + it * 0.001, 11.0 + it * 0.001) }
        val a = (1..38).associateWith { Anchors(List(8) { j -> GeoPoint(46.0 + it * 0.001, 11.0 + j * 1e-5) }, List(8) { j -> GeoPoint(46.0 + it * 0.001, 11.001 + j * 1e-5) }) }
        val b = PlanCode.decode(PlanCode.encode(SharedPlan(long, Activity.HIKE, anchors = a)))!!
        assertEquals(31, b.anchors.size) // 31 x 16 = 496; the 32nd would be 512
        assertEquals((1..31).toList(), b.anchors.keys.sorted())
        assertEquals(496, b.anchors.values.sumOf { it.size })
        assertEquals(long, b.points)
    }

    @Test fun aWrongPointCountDropsTheWholeTag() {
        val w = W(); rem(w, pts, count = pts.size + 1); entry(w, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertPlainPoints(withTag7(w.l.toByteArray()))
        val ok = W(); rem(ok, pts); entry(ok, 2, anchors[2]!!.before, anchors[2]!!.after)
        assertEquals(mapOf(2 to anchors[2]!!), PlanCode.decode(withTag7(ok.l.toByteArray()))!!.anchors)
    }

    @Test fun remaindersThatLeaveTheValidRangeDropTheWholeTag() {
        // 90.00000° + remainder 5 = 90.000005°
        val top = listOf(GeoPoint(90.0, 10.0), GeoPoint(89.9, 10.0))
        val base = b64(PlanCode.encode(SharedPlan(top, Activity.HIKE)))
        val w = W().varint(2).zz(5).zz(0).zz(0).zz(0)
        val code = b64(base + W().varint(7).varint(w.l.size.toLong()).l.toByteArray() + w.l.toByteArray())
        val back = PlanCode.decode(code)!!
        assertEquals(90.0, back.points[0].lat, 0.0)
        assertTrue(back.anchors.isEmpty())
        // the same with remainder 0 is read (exact points)
        val ok = W().varint(2).zz(0).zz(0).zz(0).zz(0)
        val okCode = b64(base + W().varint(7).varint(ok.l.size.toLong()).l.toByteArray() + ok.l.toByteArray())
        assertEquals(top, PlanCode.decode(okCode)!!.points)
    }

    @Test fun aPlanNearTheLimitLosesItsAnchorsNotItself() {
        val long = List(300) { GeoPoint(46.0 + it * 0.001, 11.0 + it * 0.001) }
        val names = (0 until 300).associateWith { "Name of point number ${it.toString().padStart(3, '0')}" } // 28 chars
        val an = (1..30).associateWith { i ->
            Anchors(List(8) { j -> GeoPoint(46.0 + i * 0.001 - (j + 1) * 0.01, 11.0 + i * 0.001 - (j + 1) * 0.01) },
                List(8) { j -> GeoPoint(46.0 + i * 0.001 + (j + 1) * 0.01, 11.0 + i * 0.001 + (j + 1) * 0.01) })
        }
        val code = PlanCode.encode(SharedPlan(long, Activity.HIKE, names = names, anchors = an))
        assertTrue("code ${code.length}", code.length in 12_000..15_744)
        val back = PlanCode.parse("https://planner.example/#p=$code")!!
        assertEquals(300, back.points.size); assertEquals(names, back.names)
        assertTrue(back.anchors.isEmpty())
        // a smaller plan keeps its anchors
        assertEquals(an.size, PlanCode.decode(PlanCode.encode(SharedPlan(long.take(40), Activity.HIKE, anchors = an)))!!.anchors.size)
    }

    @Test fun outOfRangeEntriesAreNotWritten() {
        val back = PlanCode.decode(PlanCode.encode(plan(mapOf(9 to anchors[2]!!, -1 to anchors[2]!!))))!!
        assertTrue(back.anchors.isEmpty())
        assertPlainPoints(PlanCode.encode(plan(mapOf(9 to anchors[2]!!))))
    }
}
