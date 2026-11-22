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
 * expanded point: its visible index, or -1 for an anchor. Built only by [PlanAnchors.expand], [withoutAnchorsOfLeg] and the
 * Planner's identity expansion (internal constructor): an anchor before the first or after the last visible point cannot exist.
 */
class ExpandedPlan internal constructor(val points: List<GeoPoint>, val pins: Set<Int>, val visibleIndex: List<Int>) {
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

    /** Leg [i] → [i] + 1 has an anchor at an end: the one rule for which [LegCache] key it reads (§8.9). */
    fun touchesAnchor(i: Int): Boolean = visibleOf(i) < 0 || visibleOf(i + 1) < 0

    /** The visible points only (the plan as the user sees it). */
    val visible: List<GeoPoint> by lazy { points.filterIndexed { e, _ -> visibleIndex.getOrElse(e) { -1 } >= 0 } }

    /** [pins] as visible indices (a pinned anchor is no visible point and drops out). */
    val visiblePins: Set<Int> by lazy { pins.map { visibleOf(it) }.filter { it >= 0 }.toSet() }

    /** The same plan without the anchors between visible point [v] and [v] + 1 (the fallback of a leg with no route, §8.5). */
    fun withoutAnchorsOfLeg(v: Int): ExpandedPlan {
        val from = expandedIndex(v); val to = expandedIndex(v + 1)
        if (from < 0 || to < 0) return this
        val keep = points.indices.filter { it <= from || it >= to }
        val newIndex = HashMap<Int, Int>(); keep.forEachIndexed { n, e -> newIndex[e] = n }
        return ExpandedPlan(keep.map { points[it] }, pins.mapNotNull { newIndex[it] }.toSet(), keep.map { visibleIndex[it] })
    }
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
     * [p] as a plan code hands it back (1e-6°, a plain point without elevation or way data): the points a cold device
     * routes (§8.15). Bit for bit what [PlanCode] decodes (tested).
     */
    fun exact(p: GeoPoint) = GeoPoint(Math.round(p.lat * 1e6) / 1e6, Math.round(p.lon * 1e6) / 1e6)

    /** [p] already is its [exact] form (lat and lon equal). */
    fun isNormal(p: GeoPoint): Boolean { val e = exact(p); return p.lat == e.lat && p.lon == e.lon }

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
     * [follow] for ONE point N put in at index [at] ([after] = [before] with N there) on the visible leg `at − 1 → at`
     * whose routed line is [legLine] (§2 "Insert N between …", piece 2b): the anchors between the two old points keep their
     * place on the line instead of being dropped. S = old point `at − 1`'s `after` then old point `at`'s `before`, each
     * found on [legLine] ([LegSplit.positionsOn]: exactly once, strictly in order). N's place on the line splits S into S1
     * (before N) and S2 (after N).
     * - N on the line (within [LegSplit.ON_LINE_M] of a line point): S1 is `after` of point `at − 1` (its own) and `before`
     *   of N (the old `at`'s), S2 is `after` of N (the old `at − 1`'s) and `before` of the old `at` (its own); the route
     *   still passes every anchor in the same order, no new anchor.
     * - N off the line (up to [MAX_OFF_M]): N gets its own stitch ([forInsert]: anchors `an` before and `bn` after it) and the
     *   old anchors give way to it: of S1 only those before `an` stay (no `an`: those at least [ANCHOR_M] before N), of S2
     *   only those after `bn` (no `bn`: at least [ANCHOR_M] behind N), then N's `before` = its kept S1, `an`; its `after` =
     *   `bn`, its kept S2. An anchor kept near an off-line N would force the detour through the old line beside it.
     * The outer sides of both points stay; everything else as [follow]. Any doubt (shape, range, N farther than [MAX_OFF_M],
     * an anchor not found or out of order, the result not strictly in line order, two equal neighbours, one at a visible
     * point, more than [MAX_SIDE] on a side) = [follow]: a side dropped, never a wrong anchor.
     */
    fun followInsert(before: List<GeoPoint>, after: List<GeoPoint>, anchors: Map<Int, Anchors>, at: Int, legLine: List<GeoPoint>): Map<Int, Anchors> {
        val plain = follow(before, after, anchors)
        if (legLine.size < 2 || at !in 1 until before.size || after.size != before.size + 1) return plain
        if (after.subList(0, at) != before.subList(0, at) || after.subList(at + 1, after.size) != before.subList(at, before.size)) return plain
        val left = anchors[at - 1]?.after.orEmpty(); val right = anchors[at]?.before.orEmpty()
        val s = left + right
        val pos = LegSplit.positionsOn(legLine, s)
        if (pos.any { it < 0 } || (1 until pos.size).any { pos[it] <= pos[it - 1] }) return plain
        // N's place along the line, as forInsert projects a point
        val nPt = after[at]
        val cum = Geo.cumulative(legLine)
        val flat = Geo.Flat(nPt.lat)
        var seg = 0; var offM = Double.MAX_VALUE; var bestT = 0.0
        for (i in 0 until legLine.lastIndex) {
            val t = flat.t(nPt, legLine[i], legLine[i + 1])
            val d = flat.distanceAt(nPt, legLine[i], legLine[i + 1], t)
            if (d < offM) { offM = d; seg = i; bestT = t }
        }
        if (offM > MAX_OFF_M) return plain
        val posN = cum[seg] + bestT * (cum[seg + 1] - cum[seg])
        if (pos.any { cum[it] == posN }) return plain
        val onLine = legLine.any { Geo.distance(it, nPt) <= LegSplit.ON_LINE_M }
        if (onLine && s.isEmpty()) return plain // nothing to move, no stitch for a point on the line
        // the line index of each anchor kept, in expanded order, for the final check
        var an: GeoPoint? = null; var bn: GeoPoint? = null
        var keepS1: (Int) -> Boolean = { cum[pos[it]] < posN }
        var keepS2: (Int) -> Boolean = { cum[pos[it]] > posN }
        val ends = ArrayList<Int>() // line indices of an, bn
        if (!onLine) {
            val own = forInsert(legLine, before[at - 1], before[at], nPt)
            val cand = listOfNotNull(own?.before?.firstOrNull(), own?.after?.firstOrNull())
            val found = LegSplit.positionsOn(legLine, cand)
            val anIx = if (own?.before?.isNotEmpty() == true) found[0].takeIf { it >= 0 } else null
            val bnIx = if (own?.after?.isNotEmpty() == true) found[cand.size - 1].takeIf { it >= 0 } else null
            if (anIx != null) { an = GeoPoint(legLine[anIx].lat, legLine[anIx].lon); ends += anIx }
            if (bnIx != null) { bn = GeoPoint(legLine[bnIx].lat, legLine[bnIx].lon); ends += bnIx }
            val anAt = anIx?.let { cum[it] }; val bnAt = bnIx?.let { cum[it] }
            keepS1 = { if (anAt != null) cum[pos[it]] < anAt && cum[pos[it]] < posN else cum[pos[it]] <= posN - ANCHOR_M }
            keepS2 = { if (bnAt != null) cum[pos[it]] > bnAt && cum[pos[it]] > posN else cum[pos[it]] >= posN + ANCHOR_M }
        }
        val s1 = s.indices.filter { cum[pos[it]] < posN && keepS1(it) }
        val s2 = s.indices.filter { cum[pos[it]] > posN && keepS2(it) }
        val first = Anchors(anchors[at - 1]?.before.orEmpty(), s1.filter { it < left.size }.map { s[it] })
        val mid = Anchors(s1.filter { it >= left.size }.map { s[it] } + listOfNotNull(an),
            listOfNotNull(bn) + s2.filter { it < left.size }.map { s[it] })
        val last = Anchors(s2.filter { it >= left.size }.map { s[it] }, anchors[at]?.after.orEmpty())
        if (listOf(first, mid, last).any { it.before.size > MAX_SIDE || it.after.size > MAX_SIDE }) return plain
        // the check: this leg's anchors in expanded order are strictly increasing along the line, no two neighbours equal,
        // none at a visible point
        val order = ArrayList<Int>(); val coords = ArrayList<GeoPoint>()
        fun add(ix: Int, g: GeoPoint) { order += ix; coords += g }
        for (k in s1.filter { it < left.size }) add(pos[k], s[k])
        for (k in s1.filter { it >= left.size }) add(pos[k], s[k])
        if (an != null) add(ends[0], an)
        if (bn != null) add(ends[ends.size - 1], bn)
        for (k in s2.filter { it < left.size }) add(pos[k], s[k])
        for (k in s2.filter { it >= left.size }) add(pos[k], s[k])
        if ((1 until order.size).any { order[it] <= order[it - 1] }) return plain
        if ((1 until coords.size).any { coords[it].lat == coords[it - 1].lat && coords[it].lon == coords[it - 1].lon }) return plain
        val visible = listOf(before[at - 1], nPt, before[at])
        if (coords.any { c -> visible.any { it.lat == c.lat && it.lon == c.lon } }) return plain
        val out = HashMap(plain)
        for (k in at - 1..at + 1) out.remove(k)
        if (!first.isEmpty) out[at - 1] = first
        if (!mid.isEmpty) out[at] = mid
        if (!last.isEmpty) out[at + 1] = last
        return out
    }

    /** At most this many anchors per side of a point (the code's limit, §3). */
    private const val MAX_SIDE = 8

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
