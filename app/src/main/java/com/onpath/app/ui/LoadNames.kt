package com.onpath.app.ui

import com.onpath.strings.*
import com.onpath.app.cells.CellState
import com.onpath.app.routing.GeoPoint
import org.jetbrains.compose.resources.StringResource

/**
 * Human names for what loads in the background: map areas only have grid ids, so each one is named by why it loads –
 * the app knows that – and else by the nearest larger town of the world overview.
 */

/** Why an area loads; declaration order = importance (the chip names the most important). */
enum class LoadWhy {
    /** Along the plan's route (corridor). */
    ROUTE,
    /** Around a point the user set, searched or tapped (or the own position). */
    POINT,
    /** A newer version of an area the user has. */
    REFRESH,
    /** On a tour, the map ahead. */
    AHEAD,
}

/** Why one area was asked for: [at] = the point / route destination whose name is looked up when shown; [name] if known. */
data class LoadTag(val why: LoadWhy, val at: GeoPoint? = null, val name: String? = null, val sinceMs: Long = 0L)

/** Where one area is: asked → prepared on the server → loading → being joined into the map. */
enum class LoadPhase(private val res: StringResource) {
    LOADING(Res.string.maps_phase_loading), FINISHING(Res.string.maps_phase_finishing),
    PREPARING(Res.string.maps_phase_preparing), WAITING(Res.string.maps_phase_waiting);

    val text: String get() = Txt.s(res)
}

/** One loading thing: a map area, or ([paths]) a part the map needs for planning routes – never named on its own (owner). */
data class LoadEntry(
    val why: LoadWhy?,
    /** Name for [why]: the point's / search result's / destination's name; null = unknown. */
    val name: String? = null,
    /** Nearest larger town to the area's centre (overview), null = none near. */
    val town: String? = null,
    /** The user waits for it (their point / position): named first. */
    val waiting: Boolean = false,
    val bytes: Long = 0L,
    val phase: LoadPhase = LoadPhase.LOADING,
    val paths: Boolean = false,
)

/** Areas loading for the same reason and name: one line of the details. */
data class LoadGroup(
    /** "along your route to Gasthof Taser Alm" */
    val label: String,
    /** The short name for the chip ("Gasthof Taser Alm"), null = nothing to name. */
    val chipName: String?,
    val areas: Int,
    val bytes: Long,
    val phase: LoadPhase,
) {
    /** "Along your route to Gasthof Taser Alm (2 areas) · 24 MB · loading" */
    val line: String get() {
        val cap = label.replaceFirstChar { it.uppercase() }
        val head = if (areas > 1) Txt.s(Res.string.maps_group_areas, cap, areas) else cap
        return listOfNotNull(head, if (bytes > 0) "${mb(bytes)} MB" else null, phase.text).joinToString(" · ")
    }
}

object LoadNames {
    /** Longest name the chip shows before "…" (the chip also cuts at about half the screen). */
    const val CHIP_NAME_MAX = 24

    /** Label and chip name of [e]. */
    fun label(e: LoadEntry): Pair<String, String?> {
        val near = e.town?.let { Txt.s(Res.string.maps_name_near, it) } ?: Txt.s(Res.string.maps_name_area)
        return when (e.why) {
            LoadWhy.POINT -> (e.name?.let { Txt.s(Res.string.maps_name_around, it) } ?: near) to (e.name ?: e.town)
            LoadWhy.ROUTE -> (e.name?.let { Txt.s(Res.string.maps_name_route_to, it) } ?: Txt.s(Res.string.maps_name_route)) to
                (e.name ?: Txt.s(Res.string.maps_chip_your_route))
            LoadWhy.REFRESH -> (e.town ?: e.name)?.let { Txt.s(Res.string.maps_name_newer_near, it) to it }
                ?: (Txt.s(Res.string.maps_name_newer) to null)
            LoadWhy.AHEAD -> Txt.s(Res.string.maps_name_ahead).let { it to it }
            null -> near to e.town
        }
    }

    /**
     * [entries] grouped by label, most important first (waiting > route > point > newer > ahead > unknown). Route-planning
     * parts ([LoadEntry.paths]) are part of "the map" (owner): never a line of their own – their MB
     * join the most important line; without any map line they count only in the total.
     */
    fun groups(entries: List<LoadEntry>): List<LoadGroup> {
        data class Acc(val label: String, val chip: String?, val rank: Int, val order: Int, val list: MutableList<LoadEntry>)
        val acc = LinkedHashMap<String, Acc>()
        entries.forEachIndexed { i, e ->
            if (e.paths) return@forEachIndexed
            val (label, chip) = label(e)
            val rank = (if (e.waiting) 0 else 10) + (e.why?.ordinal?.plus(1) ?: 5)
            val a = acc.getOrPut(label) { Acc(label, chip, rank, i, ArrayList()) }
            a.list += e
            if (rank < a.rank) acc[label] = a.copy(rank = rank)
        }
        val extra = entries.filter { it.paths }.sumOf { it.bytes }
        return acc.values.sortedWith(compareBy<Acc> { it.rank }.thenBy { it.order }).mapIndexed { i, a ->
            LoadGroup(a.label, a.chip, a.list.size, a.list.sumOf { it.bytes } + if (i == 0) extra else 0L, a.list.minOf { it.phase })
        }
    }

    /** Chip name of [groups]: the most important name, shortened, and how many other names ("Bad Waldsee" to 1). */
    fun chip(groups: List<LoadGroup>): Pair<String, Int> {
        val names = groups.mapNotNull { it.chipName }.distinct()
        if (names.isEmpty()) return Txt.s(Res.string.maps_chip_map) to 0
        return short(names.first().replaceFirstChar { it.uppercase() }) to names.size - 1
    }

    /** [name] cut to [max] characters with "…" (at a word end when one is close). */
    fun short(name: String, max: Int = CHIP_NAME_MAX): String {
        val n = name.trim()
        if (n.length <= max) return n
        val cut = n.take(max - 1)
        val word = cut.lastIndexOf(' ')
        return (if (word >= max / 2) cut.take(word) else cut).trimEnd(' ', ',', '-', '–') + "…"
    }

    /** The phase of a loading map area's [s]. */
    fun phaseOf(s: CellState): LoadPhase = when (s) {
        is CellState.Downloading -> LoadPhase.LOADING
        CellState.Installing -> LoadPhase.FINISHING
        is CellState.Building, is CellState.Queued -> LoadPhase.PREPARING
        else -> LoadPhase.WAITING
    }
}
