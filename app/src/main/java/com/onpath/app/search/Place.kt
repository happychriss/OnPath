package com.onpath.app.search

import com.onpath.app.poi.Poi
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKind
import com.onpath.app.poi.PoiKinds

/**
 * A place found by the search, or a POI tapped on the map – the shared model behind the place card.
 * Offline places are OpenStreetMap data from the region package; online ones come from Photon (also OSM).
 * Nothing from Google is ever stored here.
 */
data class Place(
    val id: String,
    val name: String?,
    /** A [PoiKinds] key, or one of the search-only kinds in [SearchKinds] (peak, place, trail …). */
    val kind: String,
    val lat: Double,
    val lon: Double,
    val ele: Int? = null,
    /** Finer class, e.g. "village" for kind "place". */
    val sub: String? = null,
    /** Readable address or region line (online results), e.g. "Hauptstraße 3, 39012 Meran". */
    val address: String? = null,
    val source: Source = Source.POI,
    /** Other names (Italian name, full bilingual name) – searched, not shown. */
    val alt: List<String> = emptyList(),
    /** Full OSM details when the place is a POI from pois.geojson. */
    val poi: Poi? = null,
    /** ISO country code (online results, offline addresses) – for "same country first". */
    val cc: String? = null,
    /** Town / municipality (online results, offline addresses). */
    val town: String? = null,
) {
    enum class Source { POI, MAP, TRAIL, ONLINE }

    val kindInfo: PoiKind get() = SearchKinds.of(kind, sub)

    fun title(german: Boolean = Poi.isGerman()): String = name ?: kindInfo.label(german)

    val openingHoursLines: List<String> get() = poi?.openingHoursLines ?: emptyList()

    /** As a [Poi] (for the existing card / pinned-POI logic). */
    fun toPoi(): Poi = poi ?: Poi(id = id, kind = kind, name = name, lat = lat, lon = lon, ele = ele)

    companion object {
        fun fromPoi(p: Poi): Place = Place(p.id, p.name, p.kind, p.lat, p.lon, p.ele, source = Source.POI, poi = p)
    }
}

/** Labels, colours and category chips for search results (POI kinds plus peaks, places, trails, addresses). */
object SearchKinds {
    private const val PEAK = 0xFF8A5A2B
    private const val PLACE = 0xFF3D4F5C
    private const val TRAIL = 0xFFB3541E

    private val extra = listOf(
        PoiKind("peak", PoiGroup.OTHER, "▲", "Peak", "Gipfel"),
        PoiKind("saddle", PoiGroup.OTHER, "⌒", "Pass / saddle", "Pass / Joch"),
        PoiKind("place", PoiGroup.OTHER, "●", "Place", "Ort"),
        PoiKind("trail", PoiGroup.OTHER, "〰", "Trail", "Weg"),
        PoiKind("address", PoiGroup.OTHER, "⌂", "Address", "Adresse"),
        PoiKind("street", PoiGroup.OTHER, "═", "Street", "Straße"),
        PoiKind("store", PoiGroup.FOOD, "■", "Shop", "Geschäft"),
    ).associateBy { it.key }

    private val placeSub = mapOf(
        "city" to ("City" to "Stadt"), "town" to ("Town" to "Stadt"), "village" to ("Village" to "Dorf"),
        "hamlet" to ("Hamlet" to "Weiler"), "suburb" to ("District" to "Stadtteil"),
        "neighbourhood" to ("District" to "Viertel"), "quarter" to ("District" to "Viertel"),
        "isolated_dwelling" to ("Farm / house" to "Hof / Einzelhaus"), "locality" to ("Locality" to "Flur / Örtlichkeit"),
    )

    /** Colour of a kind's icon (ARGB). */
    fun argb(kind: String): Long = when (kind) {
        "peak", "saddle" -> PEAK
        "place", "address", "street" -> PLACE
        "trail" -> TRAIL
        else -> of(kind).group.argb
    }

    fun of(kind: String, sub: String? = null): PoiKind {
        if (kind == "place" && sub != null) placeSub[sub]?.let { (en, de) -> return PoiKind("place", PoiGroup.OTHER, "●", en, de) }
        if (kind == "other") {
            val l = sub?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Place"
            return PoiKind("other", PoiGroup.OTHER, "•", l, if (sub == null) "Ort" else l)
        }
        return extra[kind] ?: PoiKinds.of(kind)
    }

    /** Category chips in the search view, in order. */
    enum class Category(val en: String, val de: String, val kinds: Set<String>) {
        HUTS("Huts", "Hütten", setOf("hut", "bivouac", "shelter")),
        PEAKS("Peaks", "Gipfel", setOf("peak", "saddle")),
        WATER("Water", "Wasser", setOf("water", "spring", "waterfall")),
        PLACES("Places", "Orte", setOf("place")),
        STATIONS("Stations", "Stationen", setOf("train", "lift", "bus")),
        ;
        fun label(german: Boolean) = if (german) de else en
    }

    /** Ranking boost by kind: things hikers and bikers aim for come first. */
    fun boost(kind: String, sub: String?): Double = when (kind) {
        "hut", "peak" -> 12.0
        "bivouac", "viewpoint", "train", "lift" -> 8.0
        "place" -> when (sub) { "city", "town" -> 14.0; "village" -> 10.0; "suburb" -> 6.0; "hamlet" -> 4.0; else -> 2.0 }
        "saddle", "waterfall", "castle" -> 6.0
        "trail", "shelter", "camping", "spring", "ruins", "museum" -> 4.0
        "restaurant", "cafe", "water" -> 3.0
        "bus", "church", "chapel" -> 2.0
        "parking", "toilets" -> 0.0
        else -> 1.0
    }
}
