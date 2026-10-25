package com.onpath.app.routing

/**
 * Komoot-style way type / surface breakdown from BRouter's per-segment way tags.
 * Pure Kotlin (no BRouter or Android types) so it can be unit tested on the JVM.
 *
 * Input is BRouter's `OsmTrack.aggregateMessages()`: one tab-separated line per run of
 * identical way tags: lon, lat, ele, distance (m), costPerKm, elevCost, turnCost, nodeCost,
 * initialCost, wayTags ("highway=path surface=gravel ..."), nodeTags, time, energy.
 * The engine sets `processUnusedTags=1` so that surface/tracktype/sac_scale are always present.
 *
 * Way type mapping (keys from RoutingContract.kt):
 * - ferry: route=ferry
 * - steps: highway=steps
 * - cycleway: highway=cycleway, or path/footway/bridleway with bicycle=designated
 * - path: highway=path/bridleway/via_ferrata
 * - footway: highway=footway/pedestrian
 * - track: highway=track
 * - quiet_road: residential/service/unclassified/living_street/road, and tertiary with
 *   estimated_traffic_class 1..3 (BRouter's traffic estimate, higher = busier)
 * - road: secondary/tertiary (+ _link)
 * - busy_road: primary/trunk/motorway (+ _link)
 * - other: anything else (platforms, construction, untagged)
 *
 * Surface mapping:
 * - alpine: sac_scale >= T4 (alpine_hiking and harder) or surface=rock/stone/scree
 * - asphalt: asphalt/chipseal
 * - paved: paved/paving_stones/concrete.../sett/cobblestone/bricks/wood/metal/grass_paver/...
 * - gravel: gravel/fine_gravel/compacted/pebblestone
 * - ground: ground/dirt/earth/soil/mud/clay
 * - unpaved: unpaved/grass/sand
 * - no surface tag:
 *   - track: tracktype grade1 -> paved, grade2 -> gravel, grade3 -> unpaved, grade4/5 -> ground
 *   - path/footway/bridleway with sac_scale -> natural_trail
 *   - tertiary and bigger roads -> asphalt (practically always true in Europe)
 *   - footway=sidewalk -> paved
 *   - otherwise unknown
 */
object BRouterWayStats {

    data class Breakdown(val wayTypes: List<Share>, val surfaces: List<Share>)

    /** Builds the breakdown from BRouter message lines, scaled so the totals equal [totalM]. */
    fun fromMessages(messages: List<String?>?, totalM: Double): Breakdown {
        if (messages.isNullOrEmpty()) return Breakdown(emptyList(), emptyList())
        val ways = LinkedHashMap<String, Double>()
        val surf = LinkedHashMap<String, Double>()
        var sum = 0.0
        for (line in messages) {
            if (line.isNullOrEmpty()) continue
            val cols = line.split('\t')
            if (cols.size < 10) continue
            val d = cols[3].trim().toDoubleOrNull() ?: continue
            if (d <= 0.0 || d.isNaN()) continue
            val tags = parseTags(cols[9])
            ways.merge(wayType(tags), d, Double::plus)
            surf.merge(surface(tags), d, Double::plus)
            sum += d
        }
        if (sum <= 0.0) return Breakdown(emptyList(), emptyList())
        val scale = if (totalM > 0.0 && !totalM.isNaN()) totalM / sum else 1.0
        return Breakdown(toShares(ways, scale), toShares(surf, scale))
    }

    /** Way attributes for the time model from one message's way tags. */
    fun attrs(tags: Map<String, String>): WayAttrs = WayAttrs(
        wayType = wayType(tags),
        surface = surface(tags),
        sac = SAC[tags["sac_scale"]] ?: 0,
        mtb = tags["mtb:scale"]?.trim()?.firstOrNull()?.digitToIntOrNull()?.coerceIn(0, 6) ?: -1,
        smoothness = tags["smoothness"],
        visibility = tags["trail_visibility"],
        rough = tags["surface"]?.takeIf { it in ROUGH },
        bicycle = tags["bicycle"]?.trim()?.ifEmpty { null },
    )

    /**
     * Way attributes per track node: [i] = the way from node i to node i+1 (the last node repeats the last way).
     * BRouter's aggregated messages are runs of equal way tags, each reported at the run's last node (lon / lat in
     * micro-degrees, as in [nodeLon] / [nodeLat] = ilon − 180e6 / ilat − 90e6). Runs are matched to nodes in order by
     * position. Empty list when anything does not fit (the time model then assumes the activity's usual way).
     */
    fun perNode(messages: List<String?>?, nodeLon: IntArray, nodeLat: IntArray): List<WayAttrs?> {
        val n = nodeLon.size
        if (messages.isNullOrEmpty() || n < 2 || nodeLat.size != n) return emptyList()
        val out = arrayOfNulls<WayAttrs>(n)
        val cache = HashMap<String, WayAttrs>()
        var k = 0 // first node of the current run
        var last: WayAttrs? = null
        for (line in messages) {
            if (line.isNullOrEmpty()) continue
            val cols = line.split('\t')
            if (cols.size < 10) continue
            val lon = cols[0].trim().toIntOrNull() ?: continue
            val lat = cols[1].trim().toIntOrNull() ?: continue
            // a zero-length run reported at the current node (BRouter does this at the start of a track): nothing to assign
            if (nodeLon[k] == lon && nodeLat[k] == lat) continue
            var j = k + 1
            while (j < n && (nodeLon[j] != lon || nodeLat[j] != lat)) j++
            if (j >= n) return emptyList()
            val a = cache.getOrPut(cols[9]) { attrs(parseTags(cols[9])) }
            for (i in k until j) out[i] = a
            last = a; k = j
        }
        if (last == null) return emptyList()
        for (i in k until n) out[i] = last
        return out.toList()
    }

    fun parseTags(desc: String?): Map<String, String> {
        if (desc.isNullOrBlank()) return emptyMap()
        val m = HashMap<String, String>()
        for (kv in desc.trim().split(' ')) {
            val i = kv.indexOf('=')
            if (i <= 0 || i == kv.length - 1) continue
            m[kv.substring(0, i)] = kv.substring(i + 1)
        }
        return m
    }

    fun wayType(tags: Map<String, String>): String {
        if (tags["route"] == "ferry") return "ferry"
        val hw = tags["highway"] ?: return "other"
        return when (hw) {
            "steps" -> "steps"
            "cycleway" -> "cycleway"
            "path", "footway", "bridleway", "pedestrian" ->
                if (tags["bicycle"] == "designated" && hw != "pedestrian") "cycleway"
                else if (hw == "footway" || hw == "pedestrian") "footway"
                else "path"
            "via_ferrata" -> "path"
            "track" -> "track"
            "residential", "service", "unclassified", "living_street", "road" -> "quiet_road"
            "tertiary", "tertiary_link" -> {
                val traffic = tags["estimated_traffic_class"]?.toIntOrNull()
                if (traffic != null && traffic <= 3) "quiet_road" else "road"
            }
            "secondary", "secondary_link" -> "road"
            "primary", "primary_link", "trunk", "trunk_link", "motorway", "motorway_link" -> "busy_road"
            else -> "other"
        }
    }

    fun surface(tags: Map<String, String>): String {
        val sac = tags["sac_scale"]
        if (sac in ALPINE_SAC) return "alpine"
        val s = tags["surface"]
        if (s != null) {
            when (s) {
                "rock", "rocks", "rocky", "stone", "scree" -> return "alpine"
                "asphalt", "chipseal" -> return "asphalt"
                "paved", "paving_stones", "concrete", "sett", "cobblestone", "bricks", "brick",
                "wood", "metal", "grass_paver", "unhewn_cobblestone" -> return "paved"
                "gravel", "fine_gravel", "compacted", "pebblestone" -> return "gravel"
                "ground", "dirt", "earth", "soil", "mud", "clay" -> return "ground"
                "unpaved", "grass", "sand" -> return "unpaved"
            }
            if (s.startsWith("concrete") || s.startsWith("paving_stones") || s.startsWith("cobblestone")) return "paved"
            // other values (e.g. artificial_turf, ice) fall through to the defaults below
        }
        val hw = tags["highway"]
        if (hw == "track") {
            when (tags["tracktype"]) {
                "grade1" -> return "paved"
                "grade2" -> return "gravel"
                "grade3" -> return "unpaved"
                "grade4", "grade5" -> return "ground"
            }
        }
        if ((hw == "path" || hw == "footway" || hw == "bridleway") && sac != null) return "natural_trail"
        if (hw in MAJOR_ROADS) return "asphalt"
        if (hw == "footway" && tags["footway"] == "sidewalk") return "paved"
        return "unknown"
    }

    private fun toShares(m: Map<String, Double>, scale: Double): List<Share> =
        m.entries.map { Share(it.key, it.value * scale) }.sortedByDescending { it.meters }

    private val SAC = mapOf(
        "hiking" to 1, "T1-hiking" to 1, "mountain_hiking" to 2, "demanding_mountain_hiking" to 3,
        "alpine_hiking" to 4, "demanding_alpine_hiking" to 5, "difficult_alpine_hiking" to 6,
    )
    private val ROUGH = setOf("rock", "rocks", "rocky", "stone", "scree")
    private val ALPINE_SAC = setOf("alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking")
    private val MAJOR_ROADS = setOf(
        "tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link",
        "trunk", "trunk_link", "motorway", "motorway_link",
    )
}
