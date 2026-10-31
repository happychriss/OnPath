package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import com.onpath.app.routing.BreakdownRules
import com.onpath.app.routing.Share
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The plan sheet's way-type / surface rules after their move into the shared routing/BreakdownRules.kt: labels,
 * colours and the summary line identical to the tables that lived in this Compose file (frozen copy below).
 */
class BreakdownTest {
    private val oldWay = mapOf(
        "cycleway" to (Res.string.plan_way_cycleway to Color(0xFF3B8E3B)),
        "path" to (Res.string.plan_way_path to Color(0xFF8D5A3B)),
        "footway" to (Res.string.plan_way_footway to Color(0xFFC08A5B)),
        "track" to (Res.string.plan_way_track to Color(0xFFC9A64E)),
        "quiet_road" to (Res.string.plan_way_quiet_road to Color(0xFF9AA5B1)),
        "road" to (Res.string.plan_way_road to Color(0xFF5F6B78)),
        "busy_road" to (Res.string.plan_way_busy_road to Color(0xFFC62828)),
        "steps" to (Res.string.plan_way_steps to Color(0xFF4E342E)),
        "ferry" to (Res.string.plan_way_ferry to Color(0xFF2F86C9)),
    )
    private val oldSurface = mapOf(
        "asphalt" to (Res.string.plan_surface_asphalt to Color(0xFF546E7A)),
        "paved" to (Res.string.plan_surface_paved to Color(0xFF90A4AE)),
        "gravel" to (Res.string.plan_surface_gravel to Color(0xFFD1B074)),
        "unpaved" to (Res.string.plan_surface_unpaved to Color(0xFFA1887F)),
        "ground" to (Res.string.plan_surface_ground to Color(0xFF8D6E63)),
        "natural_trail" to (Res.string.plan_surface_natural_trail to Color(0xFF6D8B3A)),
        "alpine" to (Res.string.plan_surface_alpine to Color(0xFF7B7F86)),
        "unknown" to (Res.string.plan_surface_unknown to Color(0xFFE6E6E6)),
    )
    private val oldOther = Res.string.plan_other to Color(0xFFD5D5D5)

    private fun oldSummaryLine(wayTypes: List<Share>, surfaces: List<Share>): String {
        fun top(list: List<Share>, names: Map<String, Pair<StringResource, Color>>): String? {
            val total = list.sumOf { it.meters }
            val first = list.maxByOrNull { it.meters } ?: return null
            if (total <= 0) return null
            val pct = Math.round(first.meters / total * 100)
            return "${names[first.key]?.first?.let { Txt.s(it) } ?: first.key.replace('_', ' ')} $pct %"
        }
        return listOfNotNull(top(wayTypes, oldWay), top(surfaces, oldSurface)).joinToString(" · ")
    }

    @Test fun tablesAsBefore() {
        val keys = oldWay.keys + oldSurface.keys + listOf("other", "mud_bath", "")
        for ((kind, old) in listOf(BreakdownRules.Kind.WAY to oldWay, BreakdownRules.Kind.SURFACE to oldSurface)) {
            assertEquals(old.keys.toList(), BreakdownRules.table(kind).map { it.key })
            for (k in keys) {
                val (label, color) = old[k] ?: oldOther
                val e = BreakdownRules.entry(kind, k)
                assertEquals("$kind $k", label.key, e.label.key)
                assertEquals("$kind $k", color, Color(e.argb))
            }
        }
    }

    @Test fun summaryLineAsBefore() {
        val ways = listOf(emptyList(), listOf(Share("cycleway", 9200.0), Share("road", 800.0)),
            listOf(Share("track", 3000.0), Share("path", 6100.0), Share("road", 900.0)), listOf(Share("mud_bath", 5.0)),
            listOf(Share("road", 0.0)), listOf(Share("steps", 1.0), Share("busy_road", 1.0)), listOf(Share("other", 7.0)))
        val surfaces = listOf(emptyList(), listOf(Share("asphalt", 9800.0), Share("gravel", 200.0)),
            listOf(Share("natural_trail", 5200.0), Share("gravel", 4800.0)), listOf(Share("unknown", 1.0)),
            listOf(Share("alpine", 333.0), Share("ground", 667.0)))
        val before = Locale.getDefault()
        try {
            for (l in listOf(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(l)
                for (w in ways) for (s in surfaces) assertEquals("$w $s", oldSummaryLine(w, s), BreakdownRules.summaryLine(w, s))
            }
        } finally { Locale.setDefault(before) }
    }
}
