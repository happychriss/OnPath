package com.onpath.web

import com.onpath.strings.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * The app's texts for map areas on demand and waits (docs/design/web-planner.md §17): the page's map strip mirrors the
 * app's CellCards / MapLoad (ui/CellCard.kt, ui/MapLoad.kt – Compose files the engine cannot compile, web/parity.json),
 * so it asks for the raw strings here and fills the `%1$d` placeholders itself (XmlStrings.format's rule).
 * `{"key": "text", …}`; plurals as `{"one": …, "other": …}`.
 */
object WebMaps {
    private val strings: List<StringResource> get() = listOf(
        Res.string.maps_card_preparing, Res.string.maps_card_preparing_min, Res.string.maps_card_failed_many,
        Res.string.maps_card_offline, Res.string.maps_row_ready, Res.string.maps_row_your_position, Res.string.maps_row_your_point,
        Res.string.maps_row_area_n, Res.string.maps_row_area_east, Res.string.maps_row_area_north_east, Res.string.maps_row_area_north,
        Res.string.maps_row_area_north_west, Res.string.maps_row_area_west, Res.string.maps_row_area_south_west,
        Res.string.maps_row_area_south, Res.string.maps_row_area_south_east, Res.string.maps_overview_hint,
        Res.string.maps_state_waiting, Res.string.maps_state_waiting_min, Res.string.maps_state_building,
        Res.string.maps_state_building_min, Res.string.maps_err_unreachable, Res.string.maps_err_area_failed,
        Res.string.maps_err_no_connection, Res.string.maps_load_loading, Res.string.maps_load_preparing, Res.string.maps_about_mb,
        Res.string.maps_time_lt_minute, Res.string.maps_time_one_minute, Res.string.maps_time_minutes,
        Res.string.maps_hide_cd, Res.string.plan_calculating, Res.string.plan_searching_gps, Res.string.plan_no_location_permission,
        Res.string.msg_no_location_permission,
    )
    private val plurals: List<PluralStringResource> get() = listOf(
        Res.plurals.maps_card_more_ready, Res.plurals.maps_card_more_some_ready, Res.plurals.maps_card_more_waiting,
    )

    fun texts(): String = buildString {
        append('{')
        strings.forEachIndexed { i, s -> if (i > 0) append(','); append(Engine.str(s.key)).append(':').append(Engine.str(s.text)) }
        for (p in plurals) {
            append(',').append(Engine.str(p.key)).append(":{\"one\":").append(p.one?.let { Engine.str(it) } ?: "null")
                .append(",\"other\":").append(Engine.str(p.other)).append('}')
        }
        append('}')
    }
}
