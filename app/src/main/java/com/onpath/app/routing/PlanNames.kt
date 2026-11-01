package com.onpath.app.routing

/**
 * Names of saved routes, shared with the web planner (it compiled its own copy before – `web/parity.json`): pure, no
 * app types. `ui/RouteRules` uses them.
 */
object PlanNames {
    /**
     * Name proposed in Save's dialog for a new route: the [proposed] one – a plan link's title while its points are
     * unchanged, else the place proposal "Vellau → Leiteralm" – or, when there is none, [dated] ("Route 31 Oct, 10:12").
     * A name a planned route already has ([taken]) gets " (2)", " (3)" … so two entries in My routes never look the same.
     */
    fun newRouteName(proposed: String?, dated: String, taken: Collection<String>): String {
        val base = proposed?.trim()?.takeIf { it.isNotEmpty() } ?: dated.trim()
        val names = taken.map { it.trim() }.toSet()
        if (base !in names) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in names }
    }

    /** Save as copy of [name]: "Name (2)" – the next free number; a copy of "Name (2)" is "Name (3)", not "Name (2) (2)". */
    fun copyName(name: String, taken: Collection<String>): String {
        val base = name.trim().replace(Regex(""" \(\d+\)$"""), "")
        return newRouteName(base, base, taken + base)
    }
}

/** When a computed route is fitted on the map, shared with the web planner (`ui/WholeRoute` uses it). Pure. */
object RouteFit {
    /**
     * An edit that gives the plan its first route (fewer than two points → two or more): the route is fitted once it is
     * computed (field A-019). Later map edits never move the map ([fitAfterPlace]: a place from the card does).
     */
    fun firstRoute(pointsBefore: Int, pointsAfter: Int): Boolean = pointsBefore < 2 && pointsAfter >= 2

    /**
     * A place added from the place card or the candidate marker's menu (often far off the shown part): the new route is
     * fitted once computed. Only when the plan changed and has a route. A hold on the map never moves it.
     */
    fun fitAfterPlace(pointsBefore: List<GeoPoint>, pointsAfter: List<GeoPoint>): Boolean =
        pointsAfter.size >= 2 && pointsAfter != pointsBefore
}
