package com.onpath.app.ui

import com.onpath.strings.*

/** Full credits (Settings → About); the map shows only "© OpenStreetMap" and opens these on a tap. */
val MAP_CREDITS: List<String> get() = listOf(
    Res.string.settings_credit_osm, Res.string.settings_credit_omt, Res.string.settings_credit_terrain,
    Res.string.settings_credit_ne, Res.string.settings_credit_brouter, Res.string.settings_credit_icons,
    Res.string.settings_credit_maplibre,
).map { Txt.s(it) }
