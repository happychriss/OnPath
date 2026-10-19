package com.onpath.app.search

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * An online place / address search. Results must be data we may show on our own (OSM) map –
 * so no Google APIs (their terms forbid showing results on a non-Google map); Google is a link-out only.
 */
interface SearchProvider {
    /** Display name for attribution, e.g. "Photon · © OpenStreetMap". */
    val attribution: String

    /** Blocking; call off the main thread. Throws on network errors (callers stay silent). */
    fun search(query: String, lat: Double, lon: Double, limit: Int = 10): List<Place>
}

/** Photon geocoder by Komoot (OpenStreetMap data): https://photon.komoot.io */
class PhotonProvider(
    private val userAgent: String,
    private val baseUrl: String = "https://photon.komoot.io/api/",
    private val timeoutMs: Int = 6000,
) : SearchProvider {
    override val attribution = "Photon · © OpenStreetMap"

    fun url(query: String, lat: Double, lon: Double, limit: Int): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val f = { v: Double -> String.format(Locale.ROOT, "%.5f", v) }
        return "$baseUrl?q=$q&lat=${f(lat)}&lon=${f(lon)}&limit=$limit&lang=de"
    }

    override fun search(query: String, lat: Double, lon: Double, limit: Int): List<Place> {
        val c = URL(url(query, lat, lon, limit)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return PhotonParser.parse(body)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun userAgent(version: String) = "OnPath-Android/$version (github.com/happychriss/OnPath)"
    }
}

/** Photon GeoJSON → [Place]s (pure, unit-tested). */
object PhotonParser {

    fun parse(json: String): List<Place> {
        val root = try { JsonParser.parseString(json) } catch (e: Exception) {
            Problems.warn("search", "Online search answer unreadable; no online results", e); return emptyList()
        }
        val feats = (root as? JsonObject)?.get("features") as? com.google.gson.JsonArray ?: return emptyList()
        val out = ArrayList<Place>()
        for (f in feats) {
            val o = f as? JsonObject ?: continue
            val coords = ((o.get("geometry") as? JsonObject)?.get("coordinates") as? com.google.gson.JsonArray) ?: continue
            if (coords.size() < 2) continue
            val lon = coords[0].asDoubleOrNull() ?: continue
            val lat = coords[1].asDoubleOrNull() ?: continue
            val p = o.get("properties") as? JsonObject ?: JsonObject()
            fun s(k: String) = p.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val key = s("osm_key") ?: ""; val value = s("osm_value") ?: ""
            // Not places you can go to: an administrative area's "centre" is just a point somewhere inside it (owner:
            // "Bozen" landed in the forest above Meran – the province's centroid), likewise traffic signs.
            if (key in SKIP_KEYS || (key == "highway" && value in SKIP_HIGHWAY)) continue
            val (kind, sub) = kindOf(key, value, s("type"))
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifEmpty { null }
            val name = s("name") ?: street ?: continue
            val address = addressLine(name, kind, street, s("postcode"), s("city") ?: s("town") ?: s("village"),
                s("district"), s("county"), s("state"), s("country"))
            val id = "o:" + (s("osm_type") ?: "?") + (s("osm_id") ?: "${lat},${lon}")
            out += Place(id, name, kind, lat, lon, ele = null, sub = sub, address = address, source = Place.Source.ONLINE)
        }
        return dedupe(out)
    }

    // expected: a feature with a non-numeric coordinate is skipped
    private fun JsonElement.asDoubleOrNull(): Double? = try { asDouble } catch (_: Exception) { null }

    private val SKIP_KEYS = setOf("boundary")
    private val SKIP_HIGHWAY = setOf("traffic_signals", "traffic_sign", "stop", "give_way", "crossing")

    /** OSM key/value → our kind (+ place class). */
    fun kindOf(key: String, value: String, type: String? = null): Pair<String, String?> = when (key) {
        "place" -> when (value) {
            "city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter", "isolated_dwelling", "locality" -> "place" to value
            "house" -> "address" to null
            else -> "place" to null
        }
        "tourism" -> when (value) {
            "alpine_hut" -> "hut" to null
            "wilderness_hut" -> "bivouac" to null
            "camp_site", "caravan_site" -> "camping" to null
            "viewpoint" -> "viewpoint" to null
            "museum" -> "museum" to null
            "information" -> "info" to null
            "picnic_site" -> "picnic" to null
            else -> "other" to value
        }
        "natural" -> when (value) {
            "peak", "volcano" -> "peak" to null
            "saddle" -> "saddle" to null
            "spring" -> "spring" to null
            "waterfall" -> "waterfall" to null
            "cave_entrance" -> "cave" to null
            else -> "other" to value
        }
        "mountain_pass" -> "saddle" to null
        "waterway" -> if (value == "waterfall") "waterfall" to null else "other" to value
        "historic" -> when (value) { "castle" -> "castle" to null; "ruins" -> "ruins" to null; else -> "other" to value }
        "highway" -> when (value) {
            "bus_stop" -> "bus" to null
            else -> "street" to null
        }
        "railway" -> when (value) { "station", "halt", "stop" -> "train" to null; else -> "other" to value }
        "public_transport" -> "bus" to null
        "aerialway" -> "lift" to null
        "amenity" -> when (value) {
            "restaurant" -> "restaurant" to null
            "cafe" -> "cafe" to null
            "bar", "pub", "biergarten" -> "bar" to null
            "fast_food" -> "fast_food" to null
            "drinking_water", "fountain", "water_point" -> "water" to null
            "shelter" -> "shelter" to null
            "parking" -> "parking" to null
            "toilets" -> "toilets" to null
            "bus_station" -> "bus" to null
            "place_of_worship" -> "church" to null
            "charging_station" -> "charging" to null
            "bicycle_repair_station" -> "bike_shop" to null
            else -> "other" to value
        }
        "shop" -> when (value) {
            "supermarket", "convenience", "bakery", "butcher", "greengrocer", "deli", "farm" -> "shop" to null
            "bicycle" -> "bike_shop" to null
            else -> "store" to value
        }
        "building" -> "address" to null
        else -> if (type == "house") "address" to null else if (type == "street") "street" to null else "other" to value.ifEmpty { null }
    }

    /**
     * Readable second line. Addresses/POIs: "Hauptstraße 3, 39012 Meran"; places: "Burggrafenamt, Südtirol".
     * Never repeats the name.
     */
    fun addressLine(name: String, kind: String, street: String?, postcode: String?, city: String?,
                    district: String?, county: String?, state: String?, country: String?): String? {
        val parts = if (kind == "place") listOfNotNull(city, county, state)
        else listOfNotNull(street, listOfNotNull(postcode, city ?: district).joinToString(" ").ifEmpty { null }, if (city == null) county else null)
        return parts.filter { it != name }.distinct().joinToString(", ").ifEmpty { country?.takeIf { it != name } }
    }

    /** Same normalised name within 100 m: keep the first. */
    fun dedupe(list: List<Place>): List<Place> {
        val out = ArrayList<Place>()
        for (p in list) if (out.none { SearchMerge.samePlace(it, p) }) out += p
        return out
    }
}

/** Merging offline and online results. */
object SearchMerge {
    const val SAME_PLACE_M = 100.0

    /** Same place: one of the names matches (normalised) and they are less than 100 m apart. */
    fun samePlace(a: Place, b: Place): Boolean {
        if (Geo.distance(a.lat, a.lon, b.lat, b.lon) >= SAME_PLACE_M) return false
        val an = (listOfNotNull(a.name) + a.alt).map { SearchText.normalize(it) }.toSet()
        return (listOfNotNull(b.name) + b.alt).any { SearchText.normalize(it) in an }
    }

    /** Online results not already among the offline ones (the offline copy wins: it has more details). */
    fun onlineExtras(offline: List<Place>, online: List<Place>): List<Place> =
        online.filter { o -> offline.none { samePlace(it, o) } }
}

/** Google Maps text search as a link-out (nothing from Google is fetched or stored). */
object GoogleSearchLink {
    /** Google ranks by the device's location itself; the documented search URL has no bias parameter. */
    fun url(query: String): String =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
}
