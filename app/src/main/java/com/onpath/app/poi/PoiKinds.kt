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
     * highlights by default. On by default: huts, shelters, camping, water, waterfalls, all sights (viewpoints,
     * castles, ruins, churches, chapels, caves, museums – owner 2026-10-23), all food (restaurants, cafés, bars,
     * snack bars), bus stops (where a hike starts or ends – owner), train and lift stations. Parking stays off (owner).
     */
    val defaultHidden: Set<String> = setOf(
        "shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging",
    )

    /** Bumped whenever [defaultHidden] changes; stored next to the user's hidden kinds ([upgradeHidden]). */
    const val DEFAULTS_VERSION = 2

    /** Earlier [defaultHidden] sets (0.1–0.6.1, 0.6.2–0.10.1): a stored set equal to one was never changed by the user. */
    private val oldDefaults: List<Set<String>> = listOf(
        setOf("bar", "fast_food", "shop", "bus", "parking", "toilets", "picnic", "info", "bike_shop", "charging",
            "church", "chapel"),
        setOf("shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging", "church", "chapel"),
    )

    /**
     * Hidden kinds stored by an app before [DEFAULTS_VERSION]: the app saves the whole set with every settings change,
     * so a set equal to an old default means the user never chose – they get today's default (e.g. sights on). A set
     * the user changed is kept as it is.
     */
    fun upgradeHidden(stored: Set<String>): Set<String> = if (stored in oldDefaults) defaultHidden else stored

    fun inGroup(g: PoiGroup): List<PoiKind> = list.filter { it.group == g }

    fun of(key: String?): PoiKind =
        byKey[key] ?: PoiKind(key ?: "poi", PoiGroup.OTHER, "•", (key ?: "Place").replace('_', ' ')
            .replaceFirstChar { it.uppercase() }, (key ?: "Ort").replace('_', ' ').replaceFirstChar { it.uppercase() })
}

/** Layer ids in the style that carry POIs all start with this prefix. */
const val POI_LAYER_PREFIX = "poi-"
