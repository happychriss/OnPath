package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint

/** Two via points of a planned route in the test lists (shared by [ScreenshotTest] and the gallery tests). */
internal val routesFixtureVia = listOf(GpxWaypoint(46.67, 11.16, null), GpxWaypoint(46.70, 11.20, null))

/** The My routes list of the screenshot tests: a planned route, a recorded MTB tour, an imported GPX, a demo tour. */
internal val routesFixtureTours = listOf(
    Tour(1, TourKind.PLANNED, "Route Meran – Hirzer", 1_760_000_000_000, "HIKE", 3, 14_200.0, 1210.0, 300.0, 6.1 * 3600, "done", routesFixtureVia),
    Tour(2, TourKind.RECORDED, "Tour 12 Oct 2026, 09:14", 1_760_200_000_000, "MTB", 3, 32_800.0, 940.0, 940.0, 3.2 * 3600, "done", emptyList(),
        avgKmh = 10.3), // the overview's Ø Tempo (owner 2026-11-16): the row reads km · ↑ m · km/h
    Tour(3, TourKind.IMPORTED, "Komoot – Waalweg Algund", 1_760_400_000_000, "HIKE", 3, 8_100.0, 210.0, 190.0, 2.1 * 3600, "done", emptyList()),
    Tour(4, TourKind.RECORDED, "Demo · Algund", 1_760_500_000_000, "HIKE", 2, 5_300.0, 120.0, 120.0, 1.4 * 3600, "done", emptyList(), avgKmh = 3.8),
)
