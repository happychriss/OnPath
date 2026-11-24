package com.onpath.app.ui

import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint

// The multi-day overview's screen models, moved unchanged from MultiDaySheet.kt (a Composable file) – rebuild phase 2 piece 20.

/**
 * One row of a multi-day tour's overview: day [n], km, hm, its end ([end] null = the finish); [places] = "von – nach" under
 * it, null unless the place is known at both ends (no second line then).
 */
data class DayRowUi(val n: Int, val places: String?, val distanceM: Double, val ascentM: Double,
                    val end: DayEndKind?, val over: Boolean)

/**
 * The plan sheet's overview of a multi-day tour (sketch round 2, phone step 11): [name] · N days, the height profile with
 * the day borders ([borders]: share of the route + kind), one row per day; [selected] = the picked day(s).
 */
data class DaysUi(val name: String?, val count: Int, val profile: List<GeoPoint>, val borders: List<Pair<Float, DayEndKind>>,
                  val rows: List<DayRowUi>, val selected: IntRange?)

/**
 * A followed day's head line in the plan sheet: "Tag 4 von 6", "‹ Alle Tage" when the whole tour can come back (no tour
 * running), and the day's missing maps ([mapsBytes], §7: said before the start; tap = load).
 */
data class DayHeadUi(val label: String, val canLeave: Boolean, val mapsBytes: Long?)
