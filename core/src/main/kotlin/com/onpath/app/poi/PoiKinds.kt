package com.onpath.app.poi

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

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

/**
 * [res] = the kind's name in strings_poi.xml / strings_search.xml (English + German); null for a kind we do not know,
 * which then shows [raw] (the OSM value made readable, "Ski rental") in both languages.
 */
data class PoiKind(val key: String, val group: PoiGroup, val glyph: String, private val res: StringResource?, val raw: String = "") {
    /** Name in the app's language. */
    val label: String get() = res?.let { Txt.s(it) } ?: raw
}

/** Kinds produced by tools/extract_pois.py. Pure Kotlin (unit-testable). */
object PoiKinds {
    private val list = listOf(
        PoiKind("viewpoint", PoiGroup.SIGHT, "◉", Res.string.poi_kind_viewpoint),
        PoiKind("hut", PoiGroup.HUT, "☗", Res.string.poi_kind_hut),
        PoiKind("bivouac", PoiGroup.HUT, "☗", Res.string.poi_kind_bivouac),
        PoiKind("shelter", PoiGroup.HUT, "☂", Res.string.poi_kind_shelter),
        PoiKind("camping", PoiGroup.HUT, "⛺", Res.string.poi_kind_camping),
        PoiKind("hotel", PoiGroup.HUT, "⌂", Res.string.poi_kind_hotel),
        PoiKind("guest_house", PoiGroup.HUT, "⌂", Res.string.poi_kind_guest_house),
        PoiKind("hostel", PoiGroup.HUT, "⌂", Res.string.poi_kind_hostel),
        PoiKind("restaurant", PoiGroup.FOOD, "♨", Res.string.poi_kind_restaurant),
        PoiKind("cafe", PoiGroup.FOOD, "☕", Res.string.poi_kind_cafe),
        PoiKind("bar", PoiGroup.FOOD, "▼", Res.string.poi_kind_bar),
        PoiKind("fast_food", PoiGroup.FOOD, "♨", Res.string.poi_kind_fast_food),
        PoiKind("shop", PoiGroup.FOOD, "■", Res.string.poi_kind_shop),
        PoiKind("water", PoiGroup.WATER, "⛲", Res.string.poi_kind_water),
        PoiKind("spring", PoiGroup.WATER, "♒", Res.string.poi_kind_spring),
        PoiKind("waterfall", PoiGroup.WATER, "♒", Res.string.poi_kind_waterfall),
        PoiKind("castle", PoiGroup.SIGHT, "⛫", Res.string.poi_kind_castle),
        PoiKind("ruins", PoiGroup.SIGHT, "⛫", Res.string.poi_kind_ruins),
        PoiKind("church", PoiGroup.SIGHT, "⛪", Res.string.poi_kind_church),
        PoiKind("chapel", PoiGroup.SIGHT, "⛪", Res.string.poi_kind_chapel),
        PoiKind("cave", PoiGroup.SIGHT, "◖", Res.string.poi_kind_cave),
        PoiKind("museum", PoiGroup.SIGHT, "★", Res.string.poi_kind_museum),
        PoiKind("bus", PoiGroup.TRANSPORT, "⛟", Res.string.poi_kind_bus),
        PoiKind("train", PoiGroup.TRANSPORT, "☰", Res.string.poi_kind_train),
        PoiKind("lift", PoiGroup.TRANSPORT, "△", Res.string.poi_kind_lift),
        PoiKind("parking", PoiGroup.TRANSPORT, "P", Res.string.poi_kind_parking),
        PoiKind("toilets", PoiGroup.FACILITY, "WC", Res.string.poi_kind_toilets),
        PoiKind("picnic", PoiGroup.FACILITY, "⛱", Res.string.poi_kind_picnic),
        PoiKind("info", PoiGroup.FACILITY, "i", Res.string.poi_kind_info),
        PoiKind("bike_shop", PoiGroup.FACILITY, "⚙", Res.string.poi_kind_bike_shop),
        PoiKind("charging", PoiGroup.FACILITY, "⚡", Res.string.poi_kind_charging),
    )
    private val byKey = list.associateBy { it.key }

    val all: List<PoiKind> get() = list

    /**
     * Kinds hidden until the user turns them on: keep the map calm like Komoot, which shows only
     * highlights by default. On by default: huts, shelters, camping, water, waterfalls, all sights (viewpoints,
     * castles, ruins, churches, chapels, caves, museums – owner), all food (restaurants, cafés, bars,
     * snack bars), bus stops (where a hike starts or ends – owner), train and lift stations. Parking stays off (owner).
     */
    val defaultHidden: Set<String> = setOf(
        "shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging",
        // hotels, guest houses, hostels (2026-11-26): a town full of beds is no hiking map – found with the
        // „Übernachten“ chip ([LODGING]), shown on the map once the user turns them on
        "hotel", "guest_house", "hostel",
    )

    /** Bumped whenever [defaultHidden] changes; stored next to the user's hidden kinds ([upgradeHidden]). */
    const val DEFAULTS_VERSION = 3

    /**
     * Places to stay – THE one definition (owner 2026-11-26, the „Übernachten“ chip in app and planner,
     * `SearchKinds.Category.SLEEP`; the multi-day tour's „Als Übernachtung“ sets a lodging place as the night's hotel at
     * once): staffed huts, bivouacs, campsites, hotels / motels, guest houses, hostels. Not shelters (a roof against the
     * rain, not planned to sleep in).
     */
    val LODGING: Set<String> = setOf("hut", "bivouac", "camping", "hotel", "guest_house", "hostel")

    /** True for a place to stay ([LODGING]); null / unknown kinds are not. */
    fun isLodging(kind: String?): Boolean = kind != null && kind in LODGING

    /** Earlier [defaultHidden] sets (oldest first): a stored set equal to one was never changed by the user. */
    private val oldDefaults: List<Set<String>> = listOf(
        setOf("bar", "fast_food", "shop", "bus", "parking", "toilets", "picnic", "info", "bike_shop", "charging",
            "church", "chapel"),
        setOf("shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging", "church", "chapel"),
        setOf("shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging"),
    )

    /**
     * Hidden kinds stored by an app before [DEFAULTS_VERSION]: the app saves the whole set with every settings change,
     * so a set equal to an old default means the user never chose – they get today's default (e.g. sights on). A set
     * the user changed is kept as it is.
     */
    fun upgradeHidden(stored: Set<String>): Set<String> = if (stored in oldDefaults) defaultHidden else stored

    fun inGroup(g: PoiGroup): List<PoiKind> = list.filter { it.group == g }

    fun of(key: String?): PoiKind =
        byKey[key] ?: if (key == null) PoiKind("poi", PoiGroup.OTHER, "•", Res.string.search_kind_place)
        else PoiKind(key, PoiGroup.OTHER, "•", null, key.replace('_', ' ').replaceFirstChar { it.uppercase() })
}

/** Layer ids in the style that carry POIs all start with this prefix. */
const val POI_LAYER_PREFIX = "poi-"
