package com.onpath.app.poi

import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.util.Locale

/** One OSM point of interest from pois.geojson (all data is OpenStreetMap, ODbL). */
data class Poi(
    val id: String,
    val kind: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val ele: Int? = null,
    val openingHours: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val wikipedia: String? = null,
    val cuisine: String? = null,
) {
    val kindInfo: PoiKind get() = PoiKinds.of(kind)

    /** Name, or the kind label when unnamed. */
    fun title(german: Boolean = isGerman()): String = name ?: kindInfo.label(german)

    /** "pizza;regional" -> "Pizza, Regional". */
    val cuisineLabel: String?
        get() = cuisine?.split(';', ',')?.map { it.trim().replace('_', ' ') }?.filter { it.isNotEmpty() }
            ?.joinToString(", ") { s -> s.replaceFirstChar { it.uppercase() } }?.ifEmpty { null }

    /** OSM opening_hours split into display lines ("Mo-Fr 08:00-18:00; Sa 08:00-12:00" -> 2 lines). */
    val openingHoursLines: List<String>
        get() = openingHours?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** First phone number when several are given ("+39 1; +39 2"). */
    val firstPhone: String? get() = phone?.split(';')?.firstOrNull()?.trim()?.ifEmpty { null }

    /** OSM element URL, e.g. https://www.openstreetmap.org/node/123. */
    val osmUrl: String?
        get() {
            val type = when (id.firstOrNull()) { 'n' -> "node"; 'w' -> "way"; 'r' -> "relation"; else -> return null }
            val num = id.drop(1).toLongOrNull() ?: return null
            return "https://www.openstreetmap.org/$type/$num"
        }

    companion object {
        fun isGerman(): Boolean = Locale.getDefault().language == "de"

        /** Pure mapping from GeoJSON properties (values may be String / Number / null). */
        fun fromProperties(props: Map<String, Any?>, lat: Double, lon: Double): Poi? {
            fun str(k: String): String? = props[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val kind = str("kind") ?: return null
            val ele = props["ele"].let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    null -> null
                    else -> v.toString().replace(",", ".").trim().toDoubleOrNull()
                }
            }?.let { Math.round(it).toInt() }
            return Poi(
                id = str("id") ?: "${kind}@$lat,$lon",
                kind = kind,
                name = str("name"),
                lat = lat, lon = lon,
                ele = ele,
                openingHours = str("opening_hours"),
                phone = str("phone"),
                website = str("website"),
                wikipedia = str("wikipedia"),
                cuisine = str("cuisine"),
            )
        }
    }
}

/** Maps a rendered/queried MapLibre feature (from source "pois") to a [Poi]; null if not a POI point. */
fun Poi.Companion.fromFeature(feature: Feature): Poi? {
    val point = feature.geometry() as? Point ?: return null
    val json = feature.properties() ?: return null
    val props = HashMap<String, Any?>()
    for ((k, v) in json.entrySet()) {
        props[k] = when {
            v == null || v.isJsonNull -> null
            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asNumber
            v.isJsonPrimitive -> v.asString
            else -> v.toString()
        }
    }
    return fromProperties(props, point.latitude(), point.longitude())
}
