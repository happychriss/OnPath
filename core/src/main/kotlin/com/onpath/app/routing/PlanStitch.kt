package com.onpath.app.routing

/**
 * The routed line of each visible leg of a plan ([points], the plan as the user sees it) under [profile]; null = that
 * leg is straight or unknown. Taken from a route the plan really has, so a stitch decided from it lies on the line the
 * user sees (docs/design/detour-stitch.md §1).
 */
class VisibleLines(val points: List<GeoPoint>, val profile: AnchorProfile, val lines: List<List<GeoPoint>?>)

/** The writer side of the anchors: when an edit of the points makes new ones (§2). Pure; the lines come from the caller. */
object PlanStitch {
    /** Visible legs of [route] (one routed leg per EXPANDED leg of [plan]); null when the route does not fit [plan]. */
    fun linesOf(route: PlannedRoute, plan: ExpandedPlan, profile: AnchorProfile): VisibleLines? {
        if (route.legs.size != plan.points.size - 1) return null
        val lines = ArrayList<List<GeoPoint>?>()
        for (v in 0 until plan.visibleCount - 1) {
            val from = plan.expandedIndex(v); val to = plan.expandedIndex(v + 1)
            if (from < 0 || to <= from) return null
            val legs = route.legs.subList(from, to)
            if (legs.any { it.straight || it.points.size < 2 }) { lines += null; continue }
            val line = ArrayList<GeoPoint>()
            for ((k, l) in legs.withIndex()) line.addAll(if (k == 0) l.points else l.points.drop(1)) // the join point once
            lines += line
        }
        return VisibleLines(plan.visible, profile, lines)
    }

    /** At most this many anchors per side (the code's limit, as in [PlanAnchors]). */
    private const val MAX_SIDE = 8

    /**
     * The anchor set after the edit [before] → [after]. Without usable [lines] this is [PlanAnchors.edited] and nothing
     * else. The lines are usable when they are the lines of exactly [before] and of the set's own profile (an empty set
     * has none, so any profile will do): else the line the decision would use is not the line the anchors must lie on.
     * - ONE point put in: [PlanAnchors.followInsert] (its own stitch, and the transfer between its neighbours' anchors).
     * - ONE inner point moved: a new point stitched again (owner: a move is an insert at another spot) on the line of
     *   the two legs it was on.
     * - Anything else (append, delete, reverse, several points): as [PlanAnchors.edited].
     */
    fun after(before: List<GeoPoint>, after: List<GeoPoint>, set: AnchorSet, lines: VisibleLines?): AnchorSet {
        val base = PlanAnchors.edited(before, after, set.anchors)
        val plain = set.copy(anchors = base)
        if (lines == null || lines.points != before) return plain
        if (set.anchors.isNotEmpty() && set.profile != lines.profile) return plain
        if (lines.lines.size != before.size - 1) return plain
        val out: Map<Int, Anchors> = when {
            after.size == before.size + 1 -> {
                var at = 0
                while (at < before.size && before[at] == after[at]) at++
                if (at !in 1 until before.size) return plain
                if (after.subList(at + 1, after.size) != before.subList(at, before.size)) return plain
                val line = lines.lines[at - 1] ?: return plain
                PlanAnchors.followInsert(before, after, set.anchors, at, line)
            }
            after.size == before.size -> {
                val diff = before.indices.filter { before[it] != after[it] }
                val i = diff.singleOrNull() ?: return plain
                if (i == 0 || i == before.size - 1) return plain
                val a = lines.lines[i - 1] ?: return plain
                val b = lines.lines[i] ?: return plain
                val own = PlanAnchors.forInsert(a + b.drop(1), before[i - 1], before[i + 1], after[i])
                if (own == null || own.isEmpty || own.before.size > MAX_SIDE || own.after.size > MAX_SIDE) return plain
                base + (i to own)
            }
            else -> return plain
        }
        return AnchorSet(out, lines.profile)
    }
}
