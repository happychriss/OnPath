package com.onpath.app.routing

import com.onpath.app.data.Geo
import kotlin.math.abs

/** Hidden via points of a plan point, all ON the line it was put on, in route order (1e-6°, BRouter's own grid). */
data class Anchors(val before: List<GeoPoint> = emptyList(), val after: List<GeoPoint> = emptyList()) {
    val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty()
    val size: Int get() = before.size + after.size
}

/**
 * The list the Planner routes (docs/design/detour-stitch.md §1, §8.1): the visible points with each point's anchors in
 * front of and behind it. [pins] are the pinned visible indices mapped into [points]. [visibleIndex] has one entry per
 * expanded point: its visible index, or -1 for an anchor.
 */
class ExpandedPlan(val points: List<GeoPoint>, val pins: Set<Int>, val visibleIndex: List<Int>) {
    /** The expanded index of every visible point. */
    private val positions: IntArray by lazy {
        val n = (visibleIndex.maxOrNull() ?: -1) + 1
        IntArray(n).also { out -> visibleIndex.forEachIndexed { e, v -> if (v >= 0) out[v] = e } }
    }

    val visibleCount: Int get() = positions.size

    /** The visible index of expanded point [e], or -1 for an anchor (or an index out of range). */
    fun visibleOf(e: Int): Int = visibleIndex.getOrElse(e) { -1 }

    /** The expanded index of visible point [v], or -1 when out of range. */
    fun expandedIndex(v: Int): Int = if (v in positions.indices) positions[v] else -1

    val hasAnchors: Boolean get() = points.size != positions.size
}

/** Anchors of a plan point: what to put in the expanded list, where to take them, how they follow edits. */
object PlanAnchors {
    /** A stitch only on a leg longer than this (metres of line); shorter legs are routed in full, cheap. */
    const val MIN_LEG_M = 60_000.0
    /** ... and only for a point at most this far off the line. */
    const val MAX_OFF_M = 2_000.0
    /** An anchor lies about this far along the line from the point. */
    const val ANCHOR_M = 10_000.0
    /** A side is kept only when its leg end is at least [ANCHOR_M] + this far along the line. */
    const val SIDE_MARGIN_M = 2_000.0

    /**
     * The expanded list of [points]: each point's `before` anchors in front, `after` behind it. Entries out of range
     * are ignored; the first point's `before` and the last point's `after` are never expanded (no leg to cut there).
     * Without anchors the list equals [points] and the mapping is the identity.
     */
    fun expand(points: List<GeoPoint>, anchors: Map<Int, Anchors>, pinned: Set<Int>): ExpandedPlan {
        val out = ArrayList<GeoPoint>(points.size)
        val vis = ArrayList<Int>(points.size)
        val pos = IntArray(points.size)
        for (i in points.indices) {
            val a = anchors[i]
            if (a != null && i > 0) for (p in a.before) { out += p; vis += -1 }
            pos[i] = out.size; out += points[i]; vis += i
            if (a != null && i < points.lastIndex) for (p in a.after) { out += p; vis += -1 }
        }
        return ExpandedPlan(out, pinned.filter { it in points.indices }.map { pos[it] }.toSet(), vis)
    }

    /**
     * The stitch rule (§1) for a point [p] put on a leg whose routed line is [legLine] (between the visible points
     * [prev] and [next]). Null when the line is not
     * longer than [MIN_LEG_M], [p] is more than [MAX_OFF_M] off it, or both sides are short. Else `before` = the line
     * point about [ANCHOR_M] before p's nearest position, `after` = about [ANCHOR_M] behind it; a side stays empty when
     * its leg end is closer than [ANCHOR_M] + [SIDE_MARGIN_M] along the line. An anchor is always an exact point of
     * [legLine] (same lat / lon, but a plain [GeoPoint] without elevation or way data), never interpolated and never the
     * line's first or last point, never at [p]'s, [prev]'s or [next]'s place (that would be a zero-length leg), and strictly
     * before p's position on the `before` side and strictly after it on the `after` side.
     */
    fun forInsert(legLine: List<GeoPoint>, prev: GeoPoint, next: GeoPoint, p: GeoPoint): Anchors? {
        if (legLine.size < 2) return null
        val cum = Geo.cumulative(legLine)
        val total = cum.last()
        if (total <= MIN_LEG_M) return null
        val flat = Geo.Flat(p.lat)
        var seg = 0
        var best = Double.MAX_VALUE
        var bestT = 0.0
        for (i in 0 until legLine.lastIndex) {
            val t = flat.t(p, legLine[i], legLine[i + 1])
            val d = flat.distanceAt(p, legLine[i], legLine[i + 1], t)
            if (d < best) { best = d; seg = i; bestT = t }
        }
        if (best > MAX_OFF_M) return null
        val at = cum[seg] + bestT * (cum[seg + 1] - cum[seg])
        val before = if (at >= ANCHOR_M + SIDE_MARGIN_M) pick(cum, legLine, 1..seg, at - ANCHOR_M) { cum[it] < at && usable(legLine[it], p, prev, next) } else null
        val after = if (total - at >= ANCHOR_M + SIDE_MARGIN_M) pick(cum, legLine, seg + 1..legLine.lastIndex - 1, at + ANCHOR_M) { cum[it] > at && usable(legLine[it], p, prev, next) } else null
        if (before == null && after == null) return null
        return Anchors(listOfNotNull(before), listOfNotNull(after))
    }

    /** A line point is no anchor where a visible point of the leg sits (lat / lon): that would be a zero-length leg. */
    private fun usable(g: GeoPoint, vararg visible: GeoPoint) = visible.none { it.lat == g.lat && it.lon == g.lon }

    /** The line point of [range] that passes [ok] whose distance along the line is nearest to [target]; the first on a tie. */
    private fun pick(cum: DoubleArray, line: List<GeoPoint>, range: IntRange, target: Double, ok: (Int) -> Boolean): GeoPoint? {
        var bi = -1
        for (i in range) if (ok(i) && (bi < 0 || abs(cum[i] - target) < abs(cum[bi] - target))) bi = i
        // a plain coordinate (no ele / way): equal to itself after a code round trip
        return if (bi < 0) null else GeoPoint(line[bi].lat, line[bi].lon)
    }

    /**
     * The anchors of [anchors] (by index into [before]) after an edit [before] → [after], the rows of §2 that need no
     * line. They follow their point by value (the common start and end by position first, so a point that is there twice keeps its
     * place; a rotated closed loop keeps the anchors of its edges); a deleted point's anchors go, a moved point (a new
     * value) has none. When the point next to P on one side changed (deleted, moved, replaced, or a new point inserted
     * there), P loses the anchors of THAT side and keeps the other. Inserts that keep or transfer anchors by line
     * position (an insert between P and one of its anchors) need the line and are piece 2, not here: until then such an
     * insert drops that side.
     */
    fun follow(before: List<GeoPoint>, after: List<GeoPoint>, anchors: Map<Int, Anchors>): Map<Int, Anchors> {
        if (anchors.isEmpty()) return anchors
        loopShift(before, after)?.let { return rotated(anchors, before.size - 1, it) }
        val src = sources(before, after)
        val out = HashMap<Int, Anchors>()
        for (j in after.indices) {
            val i = src[j]
            val a = anchors[i] ?: continue
            val keepBefore = sameNeighbour(src, j - 1, i - 1, before.size)
            val keepAfter = sameNeighbour(src, j + 1, i + 1, before.size)
            val kept = Anchors(if (keepBefore) a.before else emptyList(), if (keepAfter) a.after else emptyList())
            if (!kept.isEmpty) out[j] = kept
        }
        return out
    }

    /**
     * For each point of [after] the index it had in [before], or -1: the common start and end by position first (so a
     * point that is there twice, like a closing point, keeps its own place), then the middle by value where a point is there exactly once on each side; the rest counts as new.
     */
    private fun sources(before: List<GeoPoint>, after: List<GeoPoint>): IntArray {
        val src = IntArray(after.size) { -1 }
        val m = minOf(before.size, after.size)
        var pre = 0
        while (pre < m && before[pre] == after[pre]) { src[pre] = pre; pre++ }
        var suf = 0
        while (suf < m - pre && before[before.size - 1 - suf] == after[after.size - 1 - suf]) {
            src[after.size - 1 - suf] = before.size - 1 - suf; suf++
        }
        // the middle: a value is matched only when it is there exactly once on each side – never a guess between two
        // copies of a point (a wrong anchor could send the route a wrong way; a lost one only costs full routing)
        val oldAt = HashMap<GeoPoint, Int>(); val oldTwice = HashSet<GeoPoint>()
        for (i in pre until before.size - suf) if (oldAt.put(before[i], i) != null) oldTwice += before[i]
        val newCount = HashMap<GeoPoint, Int>()
        for (j in pre until after.size - suf) newCount[after[j]] = (newCount[after[j]] ?: 0) + 1
        for (j in pre until after.size - suf) {
            val v = after[j]
            if (newCount[v] == 1 && v !in oldTwice) src[j] = oldAt[v] ?: -1
        }
        return src
    }

    /**
     * The shift k of a rotated closed loop ([PlanEdits.rotateLoop]): both lists start and end at the same point, have the
     * same size and `after[j] == before[(j + k) % n]` for exactly one k in 0 until n (n = size - 1); null otherwise
     * (also for k = 0 and for an ambiguous shift – the normal rule then applies).
     */
    private fun loopShift(before: List<GeoPoint>, after: List<GeoPoint>): Int? {
        if (before.size != after.size || before.size < 3 || before.first() != before.last() || after.first() != after.last()) return null
        val n = before.size - 1
        val ks = (0 until n).filter { k -> (0 until n).all { j -> after[j] == before[(j + k) % n] } }
        return ks.singleOrNull()?.takeIf { it != 0 }
    }

    /** Every point keeps the anchors of its edges: the closing point's `before` and the new start's `after` included. */
    private fun rotated(anchors: Map<Int, Anchors>, n: Int, k: Int): Map<Int, Anchors> {
        val out = HashMap<Int, Anchors>()
        for (j in 0..n) {
            val i = (j + k) % n
            val b = if (j == 0) emptyList() else anchors[if (i == 0) n else i]?.before.orEmpty()
            val a = if (j == n) emptyList() else anchors[i]?.after.orEmpty()
            if (b.isNotEmpty() || a.isNotEmpty()) out[j] = Anchors(b, a)
        }
        return out
    }

    /** The neighbour at new index [nj] is the one that was at old index [oi]; no neighbour then and now counts as the same. */
    private fun sameNeighbour(src: IntArray, nj: Int, oi: Int, oldSize: Int): Boolean {
        val newHas = nj in src.indices
        val oldHas = oi in 0 until oldSize
        return if (newHas && oldHas) src[nj] == oi else !newHas && !oldHas
    }

    /** The anchors of a plan of [size] points that was reversed: indices mirrored, `before` and `after` swapped and reversed. */
    fun reversed(anchors: Map<Int, Anchors>, size: Int): Map<Int, Anchors> =
        anchors.entries.filter { it.key in 0 until size }.associate { (i, a) ->
            size - 1 - i to Anchors(a.after.reversed(), a.before.reversed())
        }
}
