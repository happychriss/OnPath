package com.onpath.app.ui

import com.onpath.app.cells.CellState
import com.onpath.app.routing.GeoPoint

/**
 * Human names for what loads in the background (owner 2026-10-23): map areas only have grid ids, so each one is named
 * by why it loads – the app knows that – and else by the nearest larger town of the world overview. Pure, unit-tested.
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
enum class LoadPhase(val text: String) { LOADING("loading"), FINISHING("almost done"), PREPARING("being prepared"), WAITING("waiting") }

/** One loading thing: a map area ([paths] false) or the paths for route planning. */
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
    val paths: Boolean = false,
) {
    /** "Along your route to Gasthof Taser Alm (2 areas) · 24 MB · loading" */
    val line: String get() {
        val head = label.replaceFirstChar { it.uppercase() } + if (areas > 1) " ($areas areas)" else ""
        return listOfNotNull(head, if (bytes > 0) "${mb(bytes)} MB" else null, phase.text).joinToString(" · ")
    }
}

object LoadNames {
    /** Longest name the chip shows before "…" (the chip also cuts at about half the screen). */
    const val CHIP_NAME_MAX = 24
    const val PATHS_LABEL = "paths for route planning"

    /** Label and chip name of [e]. */
    fun label(e: LoadEntry): Pair<String, String?> {
        if (e.paths) return PATHS_LABEL to null
        val near = e.town?.let { "near $it" } ?: "map area"
        return when (e.why) {
            LoadWhy.POINT -> (e.name?.let { "around $it" } ?: near) to (e.name ?: e.town)
            LoadWhy.ROUTE -> (e.name?.let { "along your route to $it" } ?: "along your route") to (e.name ?: "your route")
            LoadWhy.REFRESH -> (e.town ?: e.name)?.let { "newer map near $it" to it } ?: ("newer map" to null)
            LoadWhy.AHEAD -> "ahead on your tour" to "ahead on your tour"
            null -> near to e.town
        }
    }

    /** [entries] grouped by label, most important first (waiting > route > point > newer > ahead > unknown > paths). */
    fun groups(entries: List<LoadEntry>): List<LoadGroup> {
        data class Acc(val label: String, val chip: String?, val rank: Int, val order: Int, val list: MutableList<LoadEntry>)
        val acc = LinkedHashMap<String, Acc>()
        entries.forEachIndexed { i, e ->
            val (label, chip) = label(e)
            val rank = (if (e.waiting) 0 else 10) + when { e.paths -> 6; e.why == null -> 5; else -> e.why.ordinal + 1 }
            val a = acc.getOrPut(label) { Acc(label, chip, rank, i, ArrayList()) }
            a.list += e
            if (rank < a.rank) acc[label] = a.copy(rank = rank)
        }
        return acc.values.sortedWith(compareBy<Acc> { it.rank }.thenBy { it.order }).map { a ->
            LoadGroup(a.label, a.chip, a.list.count { !it.paths }, a.list.sumOf { it.bytes }, a.list.minOf { it.phase }, a.list.all { it.paths })
        }
    }

    /** Chip name of [groups]: the most important name, shortened, and how many other names ("Bad Waldsee" to 1). */
    fun chip(groups: List<LoadGroup>): Pair<String, Int> {
        val names = groups.mapNotNull { it.chipName }.distinct()
        if (names.isEmpty()) return "Map" to 0
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
