package com.onpath.app.poi

/** Category groups; colours match tools/make_style.py (POI_GROUPS). */
enum class PoiGroup(val argb: Long) {
    FOOD(0xFFD9822B),
    HUT(0xFFB8392E),
    WATER(0xFF2F86C9),
    SIGHT(0xFF7D5BA6),
    TRANSPORT(0xFF4A6382),
    FACILITY(0xFF5E7F73),
    OTHER(0xFF777777),
}

data class PoiKind(val key: String, val group: PoiGroup, val glyph: String, val en: String, val de: String) {
    fun label(german: Boolean): String = if (german) de else en
}

/** Kinds produced by tools/extract_pois.py. Pure Kotlin (unit-testable). */
object PoiKinds {
    private val list = listOf(
        PoiKind("viewpoint", PoiGroup.SIGHT, "◉", "Viewpoint", "Aussichtspunkt"),
        PoiKind("hut", PoiGroup.HUT, "☗", "Mountain hut", "Schutzhütte"),
        PoiKind("bivouac", PoiGroup.HUT, "☗", "Bivouac / unstaffed hut", "Biwak / Selbstversorgerhütte"),
        PoiKind("shelter", PoiGroup.HUT, "☂", "Shelter", "Unterstand"),
        PoiKind("camping", PoiGroup.HUT, "⛺", "Campsite", "Campingplatz"),
        PoiKind("restaurant", PoiGroup.FOOD, "♨", "Restaurant", "Restaurant / Gasthaus"),
        PoiKind("cafe", PoiGroup.FOOD, "☕", "Café", "Café"),
        PoiKind("bar", PoiGroup.FOOD, "▼", "Bar / pub", "Bar / Kneipe"),
        PoiKind("fast_food", PoiGroup.FOOD, "♨", "Snack bar", "Imbiss"),
        PoiKind("shop", PoiGroup.FOOD, "■", "Food shop", "Lebensmittel"),
        PoiKind("water", PoiGroup.WATER, "⛲", "Drinking water", "Trinkwasser"),
        PoiKind("spring", PoiGroup.WATER, "♒", "Spring", "Quelle"),
        PoiKind("waterfall", PoiGroup.WATER, "♒", "Waterfall", "Wasserfall"),
        PoiKind("castle", PoiGroup.SIGHT, "⛫", "Castle", "Burg / Schloss"),
        PoiKind("ruins", PoiGroup.SIGHT, "⛫", "Ruins", "Ruine"),
        PoiKind("church", PoiGroup.SIGHT, "⛪", "Church", "Kirche"),
        PoiKind("chapel", PoiGroup.SIGHT, "⛪", "Chapel", "Kapelle"),
        PoiKind("cave", PoiGroup.SIGHT, "◖", "Cave", "Höhle"),
        PoiKind("museum", PoiGroup.SIGHT, "★", "Museum", "Museum"),
        PoiKind("bus", PoiGroup.TRANSPORT, "⛟", "Bus stop", "Bushaltestelle"),
        PoiKind("train", PoiGroup.TRANSPORT, "☰", "Train station", "Bahnhof"),
        PoiKind("lift", PoiGroup.TRANSPORT, "△", "Cable car / lift station", "Seilbahn / Liftstation"),
        PoiKind("parking", PoiGroup.TRANSPORT, "P", "Parking", "Parkplatz"),
        PoiKind("toilets", PoiGroup.FACILITY, "WC", "Toilets", "Toiletten"),
        PoiKind("picnic", PoiGroup.FACILITY, "⛱", "Picnic site", "Rastplatz"),
        PoiKind("info", PoiGroup.FACILITY, "i", "Tourist information", "Tourismusinformation"),
        PoiKind("bike_shop", PoiGroup.FACILITY, "⚙", "Bike shop / repair", "Fahrradwerkstatt"),
        PoiKind("charging", PoiGroup.FACILITY, "⚡", "E-bike charging", "E-Bike-Ladestation"),
    )
    private val byKey = list.associateBy { it.key }

    val all: List<PoiKind> get() = list

    /**
     * Kinds hidden until the user turns them on: keep the map calm like Komoot, which shows only
     * highlights by default. On by default: huts, shelters, camping, viewpoints, water, waterfalls,
     * castles/ruins/caves/museums, restaurants/cafés, train and lift stations.
     */
    val defaultHidden: Set<String> = setOf(
        "bar", "fast_food", "shop", "bus", "parking", "toilets", "picnic", "info", "bike_shop", "charging",
        "church", "chapel",
    )

    fun inGroup(g: PoiGroup): List<PoiKind> = list.filter { it.group == g }

    fun of(key: String?): PoiKind =
        byKey[key] ?: PoiKind(key ?: "poi", PoiGroup.OTHER, "•", (key ?: "Place").replace('_', ' ')
            .replaceFirstChar { it.uppercase() }, (key ?: "Ort").replace('_', ' ').replaceFirstChar { it.uppercase() })
}

/** Layer ids in the style that carry POIs all start with this prefix. */
const val POI_LAYER_PREFIX = "poi-"
const val POI_SOURCE_ID = "pois"
const val POI_DATA_FILE = "pois.geojson"
