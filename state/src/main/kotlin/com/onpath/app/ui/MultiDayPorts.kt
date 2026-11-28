package com.onpath.app.ui

/**
 * What multi-day tours keep in the preferences (the app: `PrefsMultiDay` over `Prefs`, one property each, same keys): the open
 * trip's days and night marks and the followed day's state (JSON, [MultiDayFlow]) and the kept multi-day map areas (cell ids).
 * The map question's `multiDayMapsAsked` and the Wi-Fi wait's `multiDayWifiCells` are gone (MD-11, 2026-11-28; their stored keys are ignored).
 */
interface MultiDayPrefs {
    var planMultiDay: String?
    var planDayFollow: String?
    var multiDayMapCells: Set<String>
}

/** What the corridor of a line needs in map areas ([all] along it, those [missing] on the phone, about how many [bytes]) – `MapDataFlow.TourMaps` for the slice. */
class TourMapAreas(val all: List<com.onpath.app.cells.CellId>, val missing: List<com.onpath.app.cells.CellId>, val bytes: Long)
