package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.domain.routes.RouteSort
import com.onpath.app.routing.Activity
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

// My routes' list rules (the activity filter, the favourites, the sort chips' texts) – plain, listed for the planner
// (state/web-ready.txt); the chips and the star are the app's Compose (ActivityFilter.kt, Favourites.kt, RouteSortChips.kt).

/**
 * My routes filtered by activity (owner 2026-11-13, after the sketches): a chip after "☆ Favoriten" always names one
 * choice – preset with the planning activity every time My routes opens ("🚶 Wandern ▾"), a pick holds until My routes
 * closes. Its menu: the six activities (single choice) and "Alle" under a line ("Alle ▾"). An activity shows exactly its
 * own tours (owner 2026-11-13 on the Pixel: „wenn ich E-Bike anklicke, will ich nur E-Bike sehen“). Combines with
 * Favoriten, the search and the sort, in both tabs; Zuletzt (the plan worked on) always shows. Tours without a known activity
 * show only under "Alle". Pure rules here.
 */
object ActivityFilter {
    /** The menu's order. */
    val MENU = listOf(Activity.HIKE, Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB)

    /** [t] shows under [chosen] (null = "Alle"): exactly that activity. */
    fun keep(t: Tour, chosen: Activity?): Boolean = chosen == null || t.activity == chosen.name

    /**
     * The saved routes / done tours My routes shows (no drafts – they show only as the Zuletzt row): only favourites when
     * [onlyFavourites], only [act] (null = all), in the order [sort]. One function, so the screen recomputes it with every
     * input as a key (field bug 2026-11-13: the activity was not one – a choice did not always filter the list).
     */
    fun saved(shown: List<Tour>, sort: RouteSort.Choice, onlyFavourites: Boolean, act: Activity?): List<Tour> =
        RouteSort.sort(Favourites.shown(shown.filter { !it.draft }, onlyFavourites).filter { keep(it, act) }, sort)
}

/**
 * Favourites in My routes (owner 2026-11-13, variant C after the sketches on `sketch/favourites-2026-11-13`): a star on
 * each saved row (tap toggles; filled gold = favourite) and a "☆ Favoriten" filter pill at the start of the sort chips –
 * on, both tabs show only the favourites, in the chosen order; Zuletzt (the plan worked on) always shows. Several favourites. Pure rules here.
 */
object Favourites {
    /** The saved routes / done tours shown: all, or with the pill on only the starred ones. */
    fun shown(list: List<Tour>, onlyFavourites: Boolean): List<Tour> = if (onlyFavourites) list.filter { it.favourite } else list
}

/** The sort chips' texts ([RouteSortChips]): a key's label and, for a key with a direction, "längste zuerst" …. */
object RouteSortTexts {
    fun key(k: RouteSort.Key): StringResource = when (k) {
        RouteSort.Key.NEWEST -> Res.string.routes_sort_newest
        RouteSort.Key.OLDEST -> Res.string.routes_sort_oldest
        RouteSort.Key.LENGTH -> Res.string.routes_sort_length
        RouteSort.Key.DURATION -> Res.string.routes_sort_duration
        RouteSort.Key.ASCENT -> Res.string.routes_sort_ascent
    }

    fun direction(k: RouteSort.Key, biggestFirst: Boolean): StringResource = when (k) {
        RouteSort.Key.ASCENT -> if (biggestFirst) Res.string.routes_sort_most else Res.string.routes_sort_least
        else -> if (biggestFirst) Res.string.routes_sort_longest else Res.string.routes_sort_shortest
    }
}
