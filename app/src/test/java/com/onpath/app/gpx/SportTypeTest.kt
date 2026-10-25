package com.onpath.app.gpx

import com.onpath.app.gpx.SportType.Pick
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Komoot's sport in `<trk><type>` preselects the activity on import and is written on export (owner 2026-10-25). */
class SportTypeTest {

    /** A small synthetic GPX like Komoot's export (name, type, two points); [type] null = no `<type>`. */
    private fun gpx(type: String?, line: String = "trk", wptType: String? = null): String {
        val pts = if (line == "trk") """<trkseg><trkpt lat="46.67" lon="11.15"/><trkpt lat="46.68" lon="11.16"/></trkseg>"""
            else """<rtept lat="46.67" lon="11.15"/><rtept lat="46.68" lon="11.16"/>"""
        return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>Test</name><author><link href="https://example.org"><type>text/html</type></link></author></metadata>
  <wpt lat="46.675" lon="11.155"><name>Hut</name>${wptType?.let { "<type>$it</type>" } ?: ""}</wpt>
  <$line><name>Test</name>${type?.let { "<type>$it</type>" } ?: ""}$pts</$line>
</gpx>"""
    }

    @Test fun komootTypesMapToActivities() {
        val expected = mapOf(
            "touring_bicycle" to Pick(Activity.BIKE_TOURING), "touringbicycle" to Pick(Activity.BIKE_TOURING),
            "racebike" to Pick(Activity.BIKE_TOURING), "e_racebike" to Pick(Activity.BIKE_TOURING),
            "e_touring_bicycle" to Pick(Activity.EBIKE), "e_touringbicycle" to Pick(Activity.EBIKE),
            "mtb_easy" to Pick(Activity.BIKE_TREKKING),
            "mtb" to Pick(Activity.MTB), "mtb_advanced" to Pick(Activity.MTB),
            "e_mtb" to Pick(Activity.EMTB), "e_mtb_easy" to Pick(Activity.EMTB), "e_mtb_advanced" to Pick(Activity.EMTB),
            "hike" to Pick(Activity.HIKE, 2), "jogging" to Pick(Activity.HIKE, 2), "mountaineering" to Pick(Activity.HIKE, 3),
        )
        expected.forEach { (type, pick) -> assertEquals(type, pick, SportType.activityOf(type)) }
    }

    @Test fun spellingIsTolerated() {
        assertEquals(Pick(Activity.BIKE_TOURING), SportType.activityOf("  Touring_Bicycle\n"))
        assertEquals(Pick(Activity.EMTB), SportType.activityOf("E-MTB"))
    }

    @Test fun unknownOrMissingKeepsTheCurrentActivity() {
        for (t in listOf(null, "", "   ", "climbing", "text/html", "9", "ski_touring"))
            assertNull(t.toString(), SportType.activityOf(t))
        assertEquals(Pick(Activity.MTB, 4), SportType.forImport("nordic_walking", Activity.MTB, 4))
        assertEquals(Pick(Activity.HIKE, 4), SportType.forImport(null, Activity.HIKE, 4))
        assertEquals(Pick(Activity.HIKE, 3), SportType.forImport("mountaineering", Activity.MTB, 5))
    }

    @Test fun parserReadsTheTrackType() {
        assertEquals("touring_bicycle", GpxParser.parse(gpx("touring_bicycle")).type)
        assertEquals("mtb", GpxParser.parse(gpx("mtb", line = "rte")).type)
        assertEquals(" hike ".trim(), GpxParser.parse(gpx(" hike ")).type)
    }

    @Test fun parserIgnoresOtherTypes() {
        // no line type: the author link's and the waypoint's <type> are not the sport
        val g = GpxParser.parse(gpx(null, wptType = "hike"))
        assertNull(g.type)
        assertEquals(1, g.waypoints.size)
        assertEquals(2, g.pointCount)
    }

    @Test fun damagedFileStillGivesTheType() {
        // cut off in the middle of the track: the readable part keeps its type
        val cut = gpx("e_mtb").substringBefore("<trkpt lat=\"46.68\"")
        val g = GpxParser.parse(cut)
        assertEquals("e_mtb", g.type)
        assertEquals(Pick(Activity.EMTB), SportType.activityOf(g.type))
    }

    @Test fun regexRecoveryFindsTheType() {
        val broken = gpx("mtb_easy").replace("<name>Test</name><type>", "<name>Te<st</name><type>")
        val g = GpxParser.parse(broken)
        assertEquals(2, g.pointCount)
        assertEquals("mtb_easy", g.type)
    }

    @Test fun writerWritesTheTypeAfterTheName() {
        val text = GpxWriter.write("Tour", listOf(listOf(TrackPoint(46.67, 11.15), TrackPoint(46.68, 11.16))), type = "e_mtb")
        assertTrue(text, text.contains("<trk>\n    <name>Tour</name>\n    <type>e_mtb</type>\n    <trkseg>"))
        val none = GpxWriter.write("Tour", listOf(listOf(TrackPoint(46.67, 11.15))))
        assertTrue(!none.contains("<type>"))
    }

    @Test fun exportImportRoundTripKeepsTheActivity() {
        val seg = listOf(listOf(TrackPoint(46.67, 11.15, 400.0), TrackPoint(46.68, 11.16, 420.0)))
        for (a in Activity.entries) for (sac in if (a == Activity.HIKE) 1..5 else 2..2) {
            val text = GpxWriter.write("Tour", seg, type = SportType.typeOf(a, sac))
            val back = SportType.forImport(GpxParser.parse(text).type, Activity.BIKE_TOURING, 1)
            assertEquals("$a T$sac", a, back.activity)
            // the T level comes back by band: T1–T2 → T2 (Komoot hike), T3–T5 → T3 (Komoot mountaineering)
            if (a == Activity.HIKE) assertEquals("T$sac", if (sac >= 3) 3 else 2, back.sac)
        }
    }

    @Test fun exportUsesKomootSpelling() {
        assertEquals("touring_bicycle", SportType.typeOf(Activity.BIKE_TOURING, 2))
        assertEquals("e_touring_bicycle", SportType.typeOf(Activity.EBIKE, 2))
        assertEquals("mountaineering", SportType.typeOf(Activity.HIKE, 4))
        assertEquals("hike", SportType.typeOf("HIKE", 2))
        assertNull(SportType.typeOf("SKI", 2))
        assertNull(SportType.typeOf(null as String?, 2))
    }
}
