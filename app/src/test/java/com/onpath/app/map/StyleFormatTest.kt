package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Format-1 and format-2 bundled styles: metadata, sources, and every layer id the app switches. */
class StyleFormatTest {
    private val f1 = JSONObject(File("src/main/assets/style/onpath.json").readText())
    private val f2 = JSONObject(File("src/main/assets/style/f2/onpath.json").readText())

    private fun ids(s: JSONObject): List<String> {
        val a = s.getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }

    @Test fun formatFromMetadata() {
        assertEquals(1, StyleLoader.styleFormat(f1.toString()))
        assertEquals(2, StyleLoader.styleFormat(f2.toString()))
        assertEquals(1, StyleLoader.styleFormat("""{"version":8,"sources":{},"layers":[]}"""))
        assertEquals(1, StyleLoader.styleFormat("not json"))
    }

    @Test fun assetFolders() {
        assertEquals("style", StyleLoader.assetDir(1))
        assertEquals("style/f2", StyleLoader.assetDir(2))
    }

    @Test fun format2ReadsOneOverlaySource() {
        val src = f2.getJSONObject("sources")
        assertTrue(src.has("overlays"))
        for (gone in listOf("routes", "trails", "ways", "paths", "lowways", "trailnames")) assertTrue(gone, !src.has(gone))
        val layers = f2.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("source") == "overlays") assertTrue(l.getString("id"), l.has("source-layer"))
            assertTrue("no fallback layers in format 2", l.optJSONObject("metadata")?.has(OptionalSources.FALLBACK_KEY) != true)
        }
    }

    @Test fun layerIdsTheAppSwitchesExistInBoth() {
        val used = listOf("routes-hike", "routes-bike", "routes-hike-major", "routes-bike-major-label",
            "access-no-dash-foot", "access-no-sign-foot", "access-no-dash-bike", "access-no-sign-bike",
            "trails-sac-label", "trails-mtb-label", "surface-loose-track", "surface-loose-path",
            "greenway-foot", "greenway-bike", "greenway-touring", "greenway-mtb", "poi-station", "poi-tier1",
            "poi-station-name", "poi-tier1-name")
        for (s in listOf(f1, f2)) { val have = ids(s).toSet(); used.forEach { assertTrue(it, it in have) } }
        // no MTB route band: mtb.brf ignores route=mtb, MTB shows the cycle band (map vs router audit #7)
        for (s in listOf(f1, f2)) assertTrue(ids(s).none { it.startsWith("routes-mtb") })
        // every format-1 layer has a format-2 twin, except the base-map fallbacks and the base map's low-zoom path
        // lines (format 2 draws those from the overlay, with the town flag)
        assertEquals(setOf("track-base", "path-base", "track-low", "path-low"), ids(f1).toSet() - ids(f2).toSet())
        // format 2 only (MapController.setOverlay)
        val f2only = listOf("access-no-dash-foot-service", "access-no-sign-bike-service", "bike-lane-left",
            "bike-lane-right-shared", "bike-oneway", "bike-oneway-streets", "bike-oneway-paths", "bike-both-ways")
        f2only.forEach { assertTrue(it, it in ids(f2).toSet()) }
    }

    /**
     * Router classes (owner decisions 2026-10-22 (3) / (4), docs/map-style-notes.md "Router classes"): format 2 colours
     * green / forbidden per BRouter profile family from the class keys and keeps the tag rules for cells without them;
     * one forbidden-way layer set and one barrier layer per family (MapController.setOverlay); the hiking band above
     * the T level is its own layer (MapController.setSacLimit).
     */
    @Test fun format2RouterClassLayers() {
        val layers = f2.getJSONArray("layers")
        val byId = (0 until layers.length()).associate { layers.getJSONObject(it).let { l -> l.getString("id") to l } }
        for ((mode, key) in listOf("foot" to "ch", "bike" to "ct", "touring" to "cf", "mtb" to "cm")) {
            for (id in listOf("greenway-$mode", "access-no-dash-$mode", "access-no-sign-$mode",
                    "access-no-dash-$mode-service", "access-no-sign-$mode-service")) {
                val f = byId[id]?.optJSONArray("filter")?.toString()
                assertTrue("$id exists", f != null)
                assertTrue("$id reads $key", f!!.startsWith("[\"case\",[\"has\",\"$key\"]"))
            }
            assertTrue("barrier-blocked-$mode", byId["barrier-blocked-$mode"]?.optString("source-layer") == "barriers")
            // excluded only for difficulty (class 3): a grey line, never the red forbidden look (owner 2026-10-22)
            assertTrue("too-hard-$mode", byId["too-hard-$mode"]?.optJSONArray("filter")?.toString()?.contains("3") == true)
            assertTrue("forbidden is class 2 only", byId.getValue("access-no-dash-$mode").optJSONArray("filter")!!
                .toString().contains("[\"==\",[\"get\",\"$key\"],2]"))
        }
        // only the hiking layers are visible before the app switches the activity
        assertEquals("visible", byId.getValue("access-no-dash-foot").getJSONObject("layout").getString("visibility"))
        assertEquals("none", byId.getValue("access-no-dash-mtb").getJSONObject("layout").getString("visibility"))
        for (s in listOf(f1, f2)) {
            val ids = ids(s)
            assertTrue("routes-hike-over", "routes-hike-over" in ids)
            assertTrue("grey pieces above the band", ids.indexOf("routes-hike-over") > ids.indexOf("routes-hike"))
        }
    }

    /** Field test 2026-10-22: an inn vanished at some zooms – a route number ("40") above it won the collision. */
    @Test fun poisArePlacedBeforeTrailNumbers() {
        for (s in listOf(f1, f2)) {
            val ids = ids(s)
            val lastTrailLabel = ids.indexOfLast { it.startsWith("trail-") && (it.contains("name") || it.contains("ref")) }
            val firstPoi = ids.indexOfFirst { it.startsWith("poi-") }
            assertTrue("trail labels found", lastTrailLabel >= 0)
            assertTrue("POIs above trail names/numbers: $firstPoi > $lastTrailLabel", firstPoi > lastTrailLabel)
        }
    }

    /**
     * Field report 0.10.1 (Bad Waldsee): restaurants swapped while zooming – all POIs of one kind had the same sort key,
     * so each tile's feature order decided. The key now ends in the POI's fixed priority "pr" (extract_pois.priority,
     * < 50000), below the named/unnamed band, and old data without "pr" still works (coalesce).
     */
    @Test fun poiSortKeyUsesFixedPriority() {
        for (s in listOf(f1, f2)) {
            val a = s.getJSONArray("layers")
            val pois = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.getString("id").startsWith("poi-") && !it.getString("id").startsWith("poi-station") }
            assertTrue("POI layers found", pois.size >= 4)
            for (l in pois) {
                val key = l.getJSONObject("layout").get("symbol-sort-key").toString().replace(" ", "")
                assertTrue("${l.getString("id")}: pr in $key", key.contains("""["coalesce",["to-number",["get","pr"]],0]"""))
                assertTrue("${l.getString("id")}: unnamed band above pr", key.contains("""["case",["has","name"],0,50000]"""))
                assertTrue("${l.getString("id")}: one kind rank spans both bands", key.contains("100000"))
            }
        }
    }

    /**
     * Owner 2026-10-23: names can never push out icons. Each POI layer is split into an icon layer "poi-<x>" (icon, no
     * text) and a name layer "poi-<x>-name" (text, no icon) with the same filter, sort key and zooms. MapLibre Native
     * places symbols from the TOP layer down, so every icon layer lies above every name layer – all icons are placed
     * before any name; a name without room simply does not show. Icons still collide (no pile-ups), names collide
     * with icons and each other, nothing ignores placement.
     */
    @Test fun poiIconsArePlacedBeforeNames() {
        for (s in listOf(f1, f2)) {
            val a = s.getJSONArray("layers")
            val all = (0 until a.length()).map { a.getJSONObject(it) }
            val idx = all.withIndex().associate { (i, l) -> l.getString("id") to i }
            val pois = all.filter { it.getString("id").startsWith("poi-") }
            val (names, icons) = pois.partition { it.getString("id").endsWith("-name") }
            assertTrue("POI icon layers found", icons.size >= 6)
            assertEquals("one name layer per icon layer", icons.map { it.getString("id") + "-name" }.toSet(),
                names.map { it.getString("id") }.toSet())
            assertTrue("every icon layer above (= placed before) every name layer",
                icons.minOf { idx.getValue(it.getString("id")) } > names.maxOf { idx.getValue(it.getString("id")) })
            for (l in pois) {
                val id = l.getString("id")
                val lo = l.getJSONObject("layout")
                assertEquals("$id: symbol", "symbol", l.getString("type"))
                if (id.endsWith("-name")) {
                    assertTrue("$id: name only", lo.has("text-field") && !lo.has("icon-image"))
                    assertTrue("$id: variable anchors", lo.has("text-variable-anchor"))
                    assertTrue("$id: offset beside the icon", lo.optDouble("text-radial-offset", 0.0) > 0.0)
                    assertEquals("$id: names collide", false, lo.optBoolean("text-allow-overlap", false))
                    assertEquals("$id: names block others", false, lo.optBoolean("text-ignore-placement", false))
                } else {
                    assertTrue("$id: icon only", lo.has("icon-image") && !lo.has("text-field"))
                    assertEquals("$id: icon not optional", false, lo.optBoolean("icon-optional", false))
                    assertEquals("$id: icons collide", false, lo.optBoolean("icon-allow-overlap", false))
                    assertEquals("$id: icons block others", false, lo.optBoolean("icon-ignore-placement", false))
                    val twin = all[idx.getValue("$id-name")]
                    for (k in listOf("filter", "minzoom", "maxzoom", "source"))
                        assertEquals("$id: same $k as its name", l.opt(k)?.toString(), twin.opt(k)?.toString())
                    assertEquals("$id: same sort key as its name", lo.opt("symbol-sort-key")?.toString(),
                        twin.getJSONObject("layout").opt("symbol-sort-key")?.toString())
                }
            }
        }
    }

    /** Format 2: small streets come from overlays "streets" from z13 – the trimmed base map has none there. */
    @Test fun format2StreetsFromOverlayFromZ13() {
        val layers = f2.getJSONArray("layers")
        val own = (0 until layers.length()).map { layers.getJSONObject(it) }
            .filter { it.optString("source-layer") == "streets" }.map { it.getString("id") }.toSet()
        for (id in listOf("road-minor-casing", "road-minor-lane", "road-service-casing", "road-service-lane",
                "road-minor-tunnel", "bridge-casing-streets", "bike-oneway-streets", "access-no-dash-foot-service"))
            assertTrue(id, id in own)
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val f = l.optJSONArray("filter")?.toString() ?: continue
            if (l.optString("source-layer") == "transportation" && ("\"minor\"" in f || "\"service\"" in f))
                assertTrue(l.getString("id"), l.optDouble("maxzoom", 24.0) <= 13.0)
        }
    }

    /**
     * Phone log 2026-10-22 (0.10.0): "[ParseStyle]: [4]: Expected array<color> but found string instead." MapLibre Native
     * 13.x types hillshade-shadow-color / -highlight-color as array<color> and cannot interpolate or step it – an
     * expression there is dropped and the layer draws opaque black / white. Only plain colour strings (or arrays of
     * them) are allowed; zoom-dependent shading uses zoom-band layers (make_style.py hillshade()). The bands of one
     * light must tile the zoom range without gaps or overlaps.
     */
    @Test fun hillshadeColoursAreConstantsNativeAccepts() {
        for (s in listOf(f1, f2)) {
            val layers = s.getJSONArray("layers")
            val hs = (0 until layers.length()).map { layers.getJSONObject(it) }.filter { it.optString("type") == "hillshade" }
            assertTrue(hs.isNotEmpty())
            for (l in hs) {
                val p = l.getJSONObject("paint")
                for (prop in listOf("hillshade-shadow-color", "hillshade-highlight-color")) {
                    val v = p.opt(prop) ?: continue
                    val ok = v is String || (v is org.json.JSONArray && v.length() > 0 &&
                        (0 until v.length()).all { v.get(it) is String } && v.getString(0) !in EXPRESSION_OPS)
                    assertTrue("${l.getString("id")} $prop must be a constant colour, got $v", ok)
                }
            }
            for (light in listOf("hillshade-nw", "hillshade-w")) {
                val bands = hs.filter { it.getString("id") == light || it.getString("id").startsWith("$light-z") }
                    .map { it.optDouble("minzoom", 0.0) to it.optDouble("maxzoom", 24.0) }.sortedBy { it.first }
                assertEquals(light, 0.0, bands.first().first, 0.0)
                bands.zipWithNext().forEach { (a, b) -> assertEquals("$light bands touch", a.second, b.first, 0.0) }
            }
        }
    }

    /**
     * Quiet towns (owner 2026-10-26, docs/design/map-colour-analysis.md "Quiet towns"): format 2 reads the overlay town
     * flag "u" – no town paths zoomed out, grey town footways, white town streets, link pieces for the path gap, pale
     * pedestrian streets (not squares), no hiking green on town footways. Older cells without "u" look as before.
     */
    @Test fun format2QuietTowns() {
        val byId = byId(f2)
        assertTrue("trails-low leaves town paths out", byId.getValue("trails-low").getJSONArray("filter").toString().contains("\"u\""))
        for (id in listOf("paths-own", "paths-link", "cycleway", "path-steps")) {
            val color = byId.getValue(id).getJSONObject("paint").get("line-color").toString()
            assertTrue("$id: grey in town", color.startsWith("[\"case\"") && "\"u\"" in color)
        }
        // link pieces: k = "l" (the town part is "paths-link-town", format2TownWaysEndAtTheStreet)
        for (id in listOf("paths-link", "paths-link-town"))
            assertTrue(id, byId.getValue(id).getJSONArray("filter").toString().endsWith("[\"==\",[\"get\",\"k\"],\"l\"]]"))
        assertTrue("pedestrian squares: no lane", byId.getValue("pedestrian-town").getJSONArray("filter").toString().contains("\"ar\""))
        assertTrue("no hiking green in town", byId.getValue("greenway-foot").getJSONArray("filter").toString().contains("\"u\""))
        assertTrue("white town streets", byId.getValue("road-minor-lane").getJSONObject("paint").get("line-color").toString()
            .contains("\"u\""))
        for (s in listOf(f1, f2)) assertTrue("no tint band", ids(s).none { "access-no-tint" in it })
        // the soft bike glow of 0.11.2 is gone (owner 2026-10-26: green line = good way, band = signed route)
        for (s in listOf(f1, f2)) assertTrue("no glow", ids(s).none { it.endsWith("-glow") })
    }

    /**
     * Bike activities, the same two layers as hiking (owner 2026-10-26): green line = a way good for the bike, band = a
     * signed route. Local / regional cycle routes: a mint band above the road casings and below every lane (a mint border
     * beside the street, never over it – also on a wide main road), light zoomed out; long distance (net >= 3): exactly the
     * hiking major-route look (green band + pink dashes).
     */
    @Test fun bikeRoutesAsBands() {
        for (s in listOf(f1, f2)) {
            val byId = byId(s)
            val order = ids(s)
            val band = order.indexOf("routes-bike")
            assertTrue("routes-bike above the road casings", band > order.indexOf("road-motorway-casing"))
            assertTrue("routes-bike below the lanes", band < order.indexOf("track-own-lane-hikeview"))
            // main roads keep a thin edge on top of the band (owner 2026-10-26, Benrather Straße)
            for (gid in listOf("tertiary", "secondary", "primary", "motorway")) {
                val e = order.indexOf("road-$gid-edge")
                assertTrue("road-$gid-edge above the band, below the lanes", e > band && e < order.indexOf("track-own-lane-hikeview"))
            }
            val w = byId.getValue("routes-bike").getJSONObject("paint").get("line-width")
            assertTrue("wider than a street at z16", range(w, 16.0).first >= 12.0)
            val op = byId.getValue("routes-bike").getJSONObject("paint").get("line-opacity")
            assertTrue("light zoomed out", range(op, 12.0).second <= 0.6)
            // long distance = the hiking major look
            for ((bike, hike) in listOf("routes-bike-major" to "routes-hike-major", "routes-bike-major-low" to "routes-hike-major-low",
                                        "routes-bike-over" to "routes-hike")) {
                assertEquals(bike, byId.getValue(hike).getJSONObject("paint").toString(), byId.getValue(bike).getJSONObject("paint").toString())
                assertTrue("$bike: net >= 3", byId.getValue(bike).get("filter").toString().contains("\"net\""))
                assertEquals(bike, "none", byId.getValue(bike).getJSONObject("layout").getString("visibility"))
            }
            // route names: every named piece (schema 7 label lines "lb"), placed first (last layer) so streets don't hide them
            assertEquals("routes-bike-major-label", order.last())
            assertTrue(!byId.getValue("routes-bike-major-label").get("filter").toString().contains("\"net\""))
            // the bike green line: a little wider than the hiking one
            val g = byId.getValue("greenway-bike").getJSONObject("paint").get("line-width")
            val h = byId.getValue("greenway-foot").getJSONObject("paint").get("line-width")
            assertTrue(range(g, 16.0).first > range(h, 16.0).second)
        }
    }

    /**
     * Bike view of tracks and paths (owner 2026-10-26, "the brown ways look very dominant"): for bike activities a track is
     * a light lane with thin edges ("-bikeview" layers), hiking keeps the dotted brown edges ("-hikeview"); a closed way is a
     * quieter dash for bikes. MapController.setOverlay switches the suffixes; the style ships the hiking look.
     */
    @Test fun bikeAndHikeViews() {
        for (s in listOf(f1, f2)) {
            val byId = byId(s)
            val bike = byId.keys.filter { it.endsWith("-bikeview") }
            val hike = byId.keys.filter { it.endsWith("-hikeview") }
            assertTrue(bike.containsAll(listOf("track-bike-edges-bikeview", "track-bike-lane-bikeview")))
            // bicycle streets (schema 7 "bs"): the green bike line, format 2
            if (s === f2) assertTrue("bike-street-bikeview" in bike)
            assertTrue(hike.containsAll(listOf("track-own-single-hikeview", "track-own-loose-hikeview", "track-own-lane-hikeview")))
            bike.forEach { assertEquals(it, "none", byId.getValue(it).getJSONObject("layout").getString("visibility")) }
            hike.forEach { assertTrue(it, byId.getValue(it).optJSONObject("layout")?.optString("visibility") != "none") }
            // closed ways: hiking keeps the clear red dash, bikes a thinner one
            val foot = byId.getValue("access-no-dash-foot").getJSONObject("paint").get("line-width")
            val bikeDash = byId.getValue("access-no-dash-bike").getJSONObject("paint").get("line-width")
            assertTrue(range(bikeDash, 16.0).second < range(foot, 16.0).first)
        }
    }

    /**
     * Overview (owner 2026-10-26, every activity): zoomed out (≤ z13.5) a signed route is a thin solid line and paths are
     * hidden (bikes) or faint (hiking); from z14.5 the close-up look (route band, paths). Bikes get "-bikeview" twins of the
     * shared path layers (MapController shows the twin instead of the base layer) and a thin "routes-bike-low" line.
     */
    @Test fun overviewZoomedOut() {
        val byId = byId(f2)
        fun op(id: String, z: Double) = range(byId.getValue(id).getJSONObject("paint").get("line-opacity"), z)
        fun w(id: String, z: Double) = range(byId.getValue(id).getJSONObject("paint").get("line-width"), z)
        // routes: thin and solid zoomed out, the soft band close in
        assertTrue(w("routes-hike", 13.0).second <= 2.5 && op("routes-hike", 13.0).first >= 0.9)
        assertTrue(w("routes-hike", 15.0).first >= 8.0 && op("routes-hike", 15.0).second <= 0.5)
        assertTrue("bike band hidden zoomed out", op("routes-bike", 13.0).second == 0.0)
        assertTrue("bike overview line", op("routes-bike-low", 13.0).first >= 0.9 && op("routes-bike-low", 14.5).second == 0.0)
        // paths: no black lines for bikes zoomed out, faint for hikers, full close in
        for (id in listOf("paths-own", "trails-low", "trails-mountain", "cycleway")) {
            assertTrue("$id-bikeview", op("$id-bikeview", 13.0).second == 0.0)
            assertEquals("$id-bikeview", "none", byId.getValue("$id-bikeview").getJSONObject("layout").getString("visibility"))
            assertTrue("$id faint for hikers", op(id, 13.0).second in 0.1..0.5)
        }
        assertTrue(op("paths-own", 15.0).second == 1.0)
        assertTrue(op("track-bike-lane-bikeview", 13.0).second == 0.0)
    }

    /**
     * Owner 2026-10-26: arrow colours (dark = one-way for all, green ⇄ = bikes both ways, green → = one-way only for
     * bikes), quieter forbidden markings (signs from z16, see-through), street space (schema 7 "areas" / "fw") between
     * the buildings and the road casings, underpasses (tn "t") as a clear path above the railway.
     */
    /** Region view (owner 2026-10-26): long-distance routes always, regional from z11, every route from z12.5 – bike and hike. */
    @Test fun routeTiersByZoom() {
        val byId = byId(f2)
        for (id in listOf("routes-hike", "routes-bike", "routes-bike-low")) assertTrue(id, byId.getValue(id).getDouble("minzoom") >= 12.5)
        for (id in listOf("routes-hike-regional", "routes-bike-regional")) {
            assertEquals(id, 11.0, byId.getValue(id).getDouble("minzoom"), 0.0)
            assertTrue(id, byId.getValue(id).get("filter").toString().contains("\"net\""))
        }
        assertTrue(byId.getValue("routes-bike-major-low").getDouble("minzoom") < 11)
        assertTrue(byId.getValue("routes-hike-major-low").get("filter").toString().contains("[\">=\",[\"coalesce\",[\"get\",\"net\"],0],3]"))
    }

    @Test fun arrowsForbiddenStreetSpaceUnderpass() {
        val byId = byId(f2)
        val order = ids(f2)
        assertEquals("oneway-arrow", byId.getValue("bike-oneway-streets").getJSONObject("layout").getString("icon-image"))
        assertEquals("bike-oneway-arrow", byId.getValue("bike-oneway-streets-green-bikeview").getJSONObject("layout").getString("icon-image"))
        assertEquals("bike-both-ways", byId.getValue("bike-both-ways-streets-bikeview").getJSONObject("layout").getString("icon-image"))
        for (m in listOf("foot", "bike")) {
            val sign = byId.getValue("access-no-sign-$m")
            assertTrue(sign.getInt("minzoom") >= 16)
            assertTrue(sign.getJSONObject("paint").getDouble("icon-opacity") < 1.0)
        }
        val firstRoad = order.indexOfFirst { it.startsWith("road-") }
        for (id in listOf("street-area", "street-area-outline", "street-space-lines"))
            assertTrue(id, order.indexOf(id) in (order.indexOf("building") + 1) until firstRoad)
        assertTrue(order.indexOf("paths-underpass") > order.indexOf("rail-hatch"))
    }

    /**
     * Town street endings + contrast (owner 2026-10-26, Hilden old town on 0.11.2: "the green line and black lines go into
     * the other street", "the roads are very low contrast"): in towns the path-like layers and the bike green lines have a
     * "-town" copy below every lane, above the casings and the bike route band (paths end at the street edge), switched
     * with their layer by MapController.setOverlay; country paths keep their place above the lanes; passages are dashed;
     * pedestrian streets have an edge below the lanes.
     */
    @Test fun format2TownWaysEndAtTheStreet() {
        val byId = byId(f2)
        val order = ids(f2)
        val firstRoad = order.indexOfFirst { it.startsWith("road-") }
        val firstLane = order.indexOfFirst { it.startsWith("road-") && it.endsWith("-lane") }
        val allLanes = order.indexOf("track-own-lane-hikeview")
        for (id in listOf("paths-own", "paths-link", "cycleway", "cycleway-unpaved", "path-steps",
                          "greenway-bike", "greenway-touring", "greenway-mtb")) {
            val town = byId.getValue("$id-town")
            assertTrue("$id-town below the lanes", order.indexOf("$id-town") < allLanes)
            assertTrue("$id-town above the bike route band", order.indexOf("$id-town") > order.indexOf("routes-bike"))
            assertTrue("$id stays above the lanes outside towns", order.indexOf(id) > firstLane)
            assertTrue("$id-town: only u = 1", "\"u\"" in town.get("filter").toString())
            assertEquals("$id-town: same paint", byId.getValue(id).getJSONObject("paint").toString(), town.getJSONObject("paint").toString())
            assertEquals("$id-town: same visibility", byId.getValue(id).optJSONObject("layout")?.optString("visibility"),
                town.optJSONObject("layout")?.optString("visibility"))
        }
        assertTrue("green line on paths: above the grey path", order.indexOf("greenway-bike-town") > order.indexOf("paths-own-town"))
        // passages: never a solid line through the houses
        assertTrue("paths-own skips town passages", "\"tn\"" in byId.getValue("paths-own").get("filter").toString())
        val tunnel = byId.getValue("paths-tunnel-town")
        assertTrue(tunnel.getJSONObject("paint").has("line-dasharray"))
        assertTrue(tunnel.getInt("minzoom") >= 16)
        assertTrue(order.indexOf("paths-tunnel-town") < allLanes)
        // pedestrian streets: an edge with the casings, the lane on top
        val ped = order.indexOf("pedestrian-town-casing")
        assertTrue(ped in (firstRoad + 1) until order.indexOf("pedestrian-town"))
        assertEquals(byId.getValue("pedestrian-town").get("filter").toString(), byId.getValue("pedestrian-town-casing").get("filter").toString())
    }

    /**
     * Owner 2026-10-26 (industrial area, field test): a forbidden marking is never wider than a normal street at the same
     * zoom – a private service road's dash stays inside the service street's lane, a forbidden path's dash inside a
     * minor street's lane. Private driveways / parking aisles are not drawn below z17.
     */
    @Test fun forbiddenNeverLouderThanAStreet() {
        val byId = byId(f2)
        fun width(id: String, z: Double) = range(byId.getValue(id).getJSONObject("paint").get("line-width"), z)
        for (mode in listOf("foot", "bike", "touring", "mtb")) {
            var z = 14.0
            while (z <= 18.0) {
                assertTrue("service z$z", width("access-no-dash-$mode-service", z).second < width("road-service-lane", z).first)
                assertTrue("path z$z", width("access-no-dash-$mode", z).second < width("road-minor-lane", z).first)
                z += 0.5
            }
            assertTrue(byId.getValue("access-no-sign-$mode-service").optDouble("minzoom") >= 17.0)
        }
        assertTrue(byId.getValue("road-service-lane").getJSONObject("paint").get("line-opacity").toString().contains("parking_aisle"))
    }

    private fun byId(s: JSONObject): Map<String, JSONObject> {
        val layers = s.getJSONArray("layers")
        return (0 until layers.length()).associate { layers.getJSONObject(it).let { l -> l.getString("id") to l } }
    }

    /** (min, max) of a numeric paint value at zoom z: numbers, "interpolate" on zoom, "case" (both branches). */
    private fun range(v: Any, z: Double): Pair<Double, Double> {
        if (v is Number) return v.toDouble() to v.toDouble()
        val a = v as org.json.JSONArray
        return when (a.getString(0)) {
            "case" -> (1 until a.length() step 2).map { if (it + 1 < a.length()) range(a.get(it + 1), z) else range(a.get(it), z) }
                .let { r -> r.minOf { it.first } to r.maxOf { it.second } }
            "interpolate" -> {
                val type = a.getJSONArray(1)
                val base = if (type.getString(0) == "exponential") type.getDouble(1) else 1.0
                val stops = (3 until a.length() step 2).map { a.getDouble(it) to a.get(it + 1) }
                if (z <= stops.first().first) return range(stops.first().second, z)
                if (z >= stops.last().first) return range(stops.last().second, z)
                val i = stops.indexOfLast { it.first <= z }
                val (z0, v0) = stops[i]; val (z1, v1) = stops[i + 1]
                val t = if (base == 1.0) (z - z0) / (z1 - z0) else (Math.pow(base, z - z0) - 1) / (Math.pow(base, z1 - z0) - 1)
                val r0 = range(v0, z); val r1 = range(v1, z)
                (r0.first + (r1.first - r0.first) * t) to (r0.second + (r1.second - r0.second) * t)
            }
            else -> error("unsupported $a")
        }
    }

    private companion object {
        val EXPRESSION_OPS = setOf("interpolate", "step", "match", "case", "literal", "coalesce", "get", "to-color", "rgba", "rgb")
    }
}
