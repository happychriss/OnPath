package com.onpath.app.search

import com.onpath.app.poi.Poi
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKind
import com.onpath.app.poi.PoiKinds
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

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
    /**
     * Offline addresses: the village / fraction shown after the street ("Hauptstraße 5 · Völlan"); [town] is then
     * the municipality ("Lana"), shown small on line 2 (owner 2026-10-22). Null for everything else.
     */
    val village: String? = null,
    /** A small note that stays on the place card, e.g. "Position from the address" for a shared place. */
    val note: String? = null,
) {
    enum class Source { POI, MAP, TRAIL, ONLINE }

    val kindInfo: PoiKind get() = SearchKinds.of(kind, sub)

    /** Name, or the kind label (app language) when unnamed; [german] is not used any more. */
    @Suppress("UNUSED_PARAMETER")
    fun title(german: Boolean = Poi.isGerman()): String = name ?: kindInfo.label

    /** The municipality for line 2 of an address, when it differs from the village in the name; else null. */
    val municipality: String?
        get() = town?.takeIf { village != null && SearchText.normalize(it) != SearchText.normalize(village) }

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
        PoiKind("peak", PoiGroup.OTHER, "▲", Res.string.search_kind_peak),
        PoiKind("saddle", PoiGroup.OTHER, "⌒", Res.string.search_kind_saddle),
        PoiKind("place", PoiGroup.OTHER, "●", Res.string.search_kind_place),
        PoiKind("trail", PoiGroup.OTHER, "〰", Res.string.search_kind_trail),
        PoiKind("address", PoiGroup.OTHER, "⌂", Res.string.search_kind_address),
        PoiKind("street", PoiGroup.OTHER, "═", Res.string.search_kind_street),
        PoiKind("store", PoiGroup.FOOD, "■", Res.string.search_kind_store),
    ).associateBy { it.key }

    private val placeSub: Map<String, StringResource> = mapOf(
        "city" to Res.string.search_place_city, "town" to Res.string.search_place_town,
        "village" to Res.string.search_place_village, "hamlet" to Res.string.search_place_hamlet,
        "suburb" to Res.string.search_place_suburb, "neighbourhood" to Res.string.search_place_neighbourhood,
        "quarter" to Res.string.search_place_quarter, "isolated_dwelling" to Res.string.search_place_isolated_dwelling,
        "locality" to Res.string.search_place_locality,
    )

    /** Colour of a kind's icon (ARGB). */
    fun argb(kind: String): Long = when (kind) {
        "peak", "saddle" -> PEAK
        "place", "address", "street" -> PLACE
        "trail" -> TRAIL
        else -> of(kind).group.argb
    }

    fun of(kind: String, sub: String? = null): PoiKind {
        if (kind == "place" && sub != null) placeSub[sub]?.let { return PoiKind("place", PoiGroup.OTHER, "●", it) }
        if (kind == "other") {
            // an OSM value we have no name for ("clothes" -> "Clothes") shows as it is in both languages
            return if (sub == null) PoiKind("other", PoiGroup.OTHER, "•", Res.string.search_kind_place)
            else PoiKind("other", PoiGroup.OTHER, "•", null, sub.replace('_', ' ').replaceFirstChar { it.uppercase() })
        }
        return extra[kind] ?: PoiKinds.of(kind)
    }

    /** Category chips in the search view, in order. */
    enum class Category(private val res: StringResource, val kinds: Set<String>) {
        HUTS(Res.string.search_cat_huts, setOf("hut", "bivouac", "shelter")),
        PEAKS(Res.string.search_cat_peaks, setOf("peak", "saddle")),
        WATER(Res.string.search_cat_water, setOf("water", "spring", "waterfall")),
        PLACES(Res.string.search_cat_places, setOf("place")),
        STATIONS(Res.string.search_cat_stations, setOf("train", "lift", "bus")),
        ;
        /** Chip text in the app's language. */
        val label: String get() = Txt.s(res)
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
