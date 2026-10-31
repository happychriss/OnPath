package com.onpath.app.routing

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

/**
 * Way types and surfaces of a route ([PlannedRoute.wayTypes] / [PlannedRoute.surfaces], contract keys in
 * RoutingContract.kt): label, colour, legend order, the "Other" fold and the one summary line
 * ("Cycleway 92 % · Asphalt 98 %"). Pure and shared with the web planner (`SharedCoreRuleTest.files` / `webShared`,
 * docs/handoff-web-notice-breakdown.md); `ui/RouteBreakdown.kt` only draws it.
 */
object BreakdownRules {
    /** One category: [key] from the contract, its label, its colour as plain ARGB (`0xAARRGGBB`; Compose: `Color(argb)`). */
    class Entry(val key: String, val label: StringResource, val argb: Long) {
        val text: String get() = Txt.s(label)
        /** "#RRGGBB" for CSS. */
        val hex: String get() = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
    }

    /** The two lists, in table order (the legend shows the route's order; the table order is for a fixed legend). */
    enum class Kind { WAY, SURFACE }

    val WAY: List<Entry> = listOf(
        Entry("cycleway", Res.string.plan_way_cycleway, 0xFF3B8E3B),
        Entry("path", Res.string.plan_way_path, 0xFF8D5A3B),
        Entry("footway", Res.string.plan_way_footway, 0xFFC08A5B),
        Entry("track", Res.string.plan_way_track, 0xFFC9A64E),
        Entry("quiet_road", Res.string.plan_way_quiet_road, 0xFF9AA5B1),
        Entry("road", Res.string.plan_way_road, 0xFF5F6B78),
        Entry("busy_road", Res.string.plan_way_busy_road, 0xFFC62828),
        Entry("steps", Res.string.plan_way_steps, 0xFF4E342E),
        Entry("ferry", Res.string.plan_way_ferry, 0xFF2F86C9),
    )
    val SURFACE: List<Entry> = listOf(
        Entry("asphalt", Res.string.plan_surface_asphalt, 0xFF546E7A),
        Entry("paved", Res.string.plan_surface_paved, 0xFF90A4AE),
        Entry("gravel", Res.string.plan_surface_gravel, 0xFFD1B074),
        Entry("unpaved", Res.string.plan_surface_unpaved, 0xFFA1887F),
        Entry("ground", Res.string.plan_surface_ground, 0xFF8D6E63),
        Entry("natural_trail", Res.string.plan_surface_natural_trail, 0xFF6D8B3A),
        Entry("alpine", Res.string.plan_surface_alpine, 0xFF7B7F86),
        Entry("unknown", Res.string.plan_surface_unknown, 0xFFE6E6E6),
    )
    /** Folded small shares ([foldSmall]) and keys not in the table. */
    val OTHER: Entry = Entry("other", Res.string.plan_other, 0xFFD5D5D5)

    private val wayByKey = WAY.associateBy { it.key }
    private val surfaceByKey = SURFACE.associateBy { it.key }

    fun table(kind: Kind): List<Entry> = if (kind == Kind.WAY) WAY else SURFACE

    /** The entry of [key], null = not in the table. */
    fun find(kind: Kind, key: String): Entry? = (if (kind == Kind.WAY) wayByKey else surfaceByKey)[key]

    /** The entry a bar / legend draws for [key]: unknown keys (and "other") as [OTHER]. */
    fun entry(kind: Kind, key: String): Entry = find(kind, key) ?: OTHER

    /** "Cycleway 92 % · Asphalt 98 %" – the largest way type and surface (an unknown key as its name with spaces). */
    fun summaryLine(wayTypes: List<Share>, surfaces: List<Share>): String {
        fun top(list: List<Share>, kind: Kind): String? {
            val total = list.sumOf { it.meters }
            val first = list.maxByOrNull { it.meters } ?: return null
            if (total <= 0) return null
            val pct = Math.round(first.meters / total * 100)
            return "${find(kind, first.key)?.text ?: first.key.replace('_', ' ')} $pct %"
        }
        return listOfNotNull(top(wayTypes, Kind.WAY), top(surfaces, Kind.SURFACE)).joinToString(" · ")
    }

    /** Categories below 3 % are merged into "Other" so the legend stays short. */
    fun foldSmall(shares: List<Share>, minFraction: Double = 0.03): List<Share> {
        val total = shares.sumOf { it.meters }
        if (total <= 0) return emptyList()
        val (big, small) = shares.partition { it.meters / total >= minFraction && it.key != "other" }
        val rest = small.sumOf { it.meters }
        return big + if (rest > 0) listOf(Share("other", rest)) else emptyList()
    }
}
