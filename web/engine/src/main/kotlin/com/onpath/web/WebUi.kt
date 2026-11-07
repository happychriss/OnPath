package com.onpath.web

import com.onpath.app.debug.Problems
import com.onpath.app.poi.PoiFilter
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.strings.*
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.RouteNamer

/**
 * Small UI rules of the web page (docs/design/web-planner.md §13) – the app's own code: the route name proposed when a
 * plan is saved (search/RouteNamer, as the app's AppViewModel.suggestName) and the POI kind filters of Map content
 * (poi/PoiKinds + poi/PoiFilter, as the app's map/LayerSettings).
 */
object WebUi {
    /** [line] "lat,lon;…" (the route, thinned), [names] "start\tend\tvia1\tvia2…" (place names of stops, "" = none). */
    fun routeName(line: String, names: String): String {
        val pts = line.split(';').filter { it.isNotBlank() }.map { p -> p.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }
        val n = names.split('\t')
        return try {
            RouteNamer.name(pts, WebSearch.places(), n.getOrNull(0)?.ifBlank { null }, n.getOrNull(1)?.ifBlank { null },
                n.drop(2).filter { it.isNotBlank() }) ?: ""
        } catch (e: Exception) {
            Problems.warn("search", "No route name proposed (naming failed)", e); ""
        }
    }

    /**
     * The app's POI kinds: [{key, group, en, glyph, color}] in the app's order, the groups [{key, label}] (the names of
     * the app's Settings → POIs page, ui/SettingsScreen PoiGroup.label – same string resources) and the kinds hidden
     * by default.
     */
    fun poiKinds(): String = "{\"kinds\":" + PoiKinds.all.joinToString(",", "[", "]") {
        "{\"key\":" + q(it.key) + ",\"group\":" + q(it.group.name) + ",\"en\":" + q(it.label) + ",\"glyph\":" + q(it.glyph) +
            ",\"color\":" + q("#" + (it.group.argb and 0xFFFFFFL).toString(16).padStart(6, '0')) + "}"
    } + ",\"groups\":" + PoiGroup.entries.joinToString(",", "[", "]") { "{\"key\":" + q(it.name) + ",\"label\":" + q(groupLabel(it)) + "}" } +
        ",\"defaultHidden\":" + PoiKinds.defaultHidden.joinToString(",", "[", "]") { q(it) } + "}"

    private fun groupLabel(g: PoiGroup): String = Txt.s(when (g) {
        PoiGroup.FOOD -> Res.string.settings_poi_group_food
        PoiGroup.HUT -> Res.string.settings_poi_group_hut
        PoiGroup.WATER -> Res.string.settings_poi_group_water
        PoiGroup.SIGHT -> Res.string.settings_poi_group_sight
        PoiGroup.TRANSPORT -> Res.string.settings_poi_group_transport
        PoiGroup.FACILITY -> Res.string.settings_poi_group_facility
        PoiGroup.OTHER -> Res.string.settings_poi_group_other
    })

    /** Filter JSON for one poi- layer with [hidden] ("k1,k2,…") kinds off; "" = no filter (PoiFilter.layerFilter). */
    fun poiFilter(original: String, hidden: String): String =
        PoiFilter.layerFilter(original.ifBlank { null }, PoiKinds.all.map { it.key },
            hidden.split(',').filter { it.isNotBlank() }.toSet()) ?: ""

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

