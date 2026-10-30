package com.onpath.app.cells

import com.onpath.app.debug.Problems
import com.onpath.app.routing.RouterClassHash

/**
 * Router classes vs this app's routing profiles (docs/map-style-notes.md "Router classes"): a cell's green / forbidden
 * ways come from the BRouter profiles it was built with (manifest `wayclass`). After a profile change those can differ
 * from what the router does now – the map then shows a way as allowed that the route avoids, or the other way round.
 *
 * [check] compares each installed cell's hash with [appHash] ([com.onpath.app.routing.RouterClassHash]): equal → nothing;
 * unknown (older cells / store without the field, format-1 hashes of cells built before comments stopped counting
 * ([RouterClassHash.isComparable]), region packages, app hash unreadable) → nothing, quietly; different →
 * one WARNING per check for the cells newly found, and those cells are returned for a quiet refresh (the normal
 * [CellReason.REFRESH] path: the doorman serves a newer build when it has one, e.g. after the CELL_SCHEMA bump that
 * comes with a profile change). Each cell version is reported and returned once per app run; cells built on a developer
 * PC ([CellApi.isDevVersion]) are only reported – a refresh would replace them with the server's build.
 */
class RouterClassCheck(appHash: () -> String?) {
    private val app: String? by lazy(appHash)
    /** "cell@version" already reported this run. */
    private val seen = HashSet<String>()

    /**
     * The cells of [installed] to ask for again; [canRequest] false (no map server key) → reported only. Never throws for
     * missing data; never blocks (no disk or network).
     */
    @Synchronized fun check(installed: List<CellStore.InstalledCell>, canRequest: Boolean): List<CellId> {
        val app = app?.takeIf { RouterClassHash.isComparable(it) } ?: return emptyList()
        val fresh = installed.filter { c ->
            !c.id.isPackage && RouterClassHash.isComparable(c.wayclass) && c.wayclass != app && seen.add("${c.id.id}@${c.version}")
        }
        if (fresh.isEmpty()) return emptyList()
        val ask = if (canRequest) fresh.filter { !CellApi.isDevVersion(it.version) }.map { it.id } else emptyList()
        val what = when {
            ask.size == fresh.size -> "a newer build is asked for"
            ask.isNotEmpty() -> "a newer build is asked for (developer cells kept)"
            canRequest -> "developer cells kept"
            else -> "no map server to ask"
        }
        Problems.warn("cells", "${fresh.size} map region(s) show router classes of other routing profiles " +
            "(map ${fresh.mapNotNull { it.wayclass }.distinct().joinToString("/")}, app $app): " +
            "${fresh.joinToString { it.id.id }} – allowed / forbidden ways may differ from the routes; $what")
        return ask
    }
}
