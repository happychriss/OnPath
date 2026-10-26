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
        assertEquals(Pick(Activity.HIKE, 3), SportType.forImport("mountaineering", Activity.MTB, 2))
    }

    /**
     * Captain 2026-10-26 ("no surprises"): a Komoot hiking type covers a band of T levels – `hike` (and `jogging`) T1–T2,
     * `mountaineering` T3–T5. The user's own T level is kept when it is inside the band, else the band's default.
     */
    @Test fun theUsersTLevelIsKeptInsideTheTypesBand() {
        for (own in 1..5) {
            assertEquals("hike, own T$own", Pick(Activity.HIKE, if (own <= 2) own else 2),
                SportType.forImport("hike", Activity.HIKE, own))
            assertEquals("jogging, own T$own", Pick(Activity.HIKE, if (own <= 2) own else 2),
                SportType.forImport("jogging", Activity.HIKE, own))
            assertEquals("mountaineering, own T$own", Pick(Activity.HIKE, if (own >= 3) own else 3),
                SportType.forImport("mountaineering", Activity.HIKE, own))
        }
        // the remembered T level counts also when a bike is chosen at the moment
        assertEquals(Pick(Activity.HIKE, 4), SportType.forImport("mountaineering", Activity.MTB, 4))
        assertEquals(Pick(Activity.HIKE, 1), SportType.forImport("hike", Activity.EBIKE, 1))
        // bikes carry no level: the pick is the plain activity
        assertEquals(Pick(Activity.MTB), SportType.forImport("mtb", Activity.HIKE, 4))
    }

    @Test fun anImportThatChangesTheActivitySaysSo() {
        val mtb = SportType.importChoice("mtb", Activity.HIKE, 2, touring = false)
        assertTrue(mtb.switch)
        assertEquals("Activity set to MTB from the file", mtb.note)
        assertEquals("Activity set to Bike from the file",
            SportType.importChoice("touring_bicycle", Activity.MTB, 2, touring = false).note)
        // Hike names its T level
        val berg = SportType.importChoice("mountaineering", Activity.MTB, 2, touring = false)
        assertEquals(Pick(Activity.HIKE, 3), berg.pick)
        assertEquals("Activity set to Hike T3 from the file", berg.note)
        // only the T level changes (hike at T4 → T2): said too
        assertEquals("Activity set to Hike T2 from the file",
            SportType.importChoice("hike", Activity.HIKE, 4, touring = false).note)
    }

    @Test fun noChangeNoLine() {
        // same activity, T level inside the band, a bike with another remembered level, unknown / missing type
        for ((type, act, sac) in listOf(Triple("mtb", Activity.MTB, 4), Triple("hike", Activity.HIKE, 1),
                Triple("mountaineering", Activity.HIKE, 5), Triple("nordic_walking", Activity.EMTB, 3),
                Triple(null, Activity.HIKE, 4))) {
            val c = SportType.importChoice(type, act, sac, touring = false)
            assertTrue("$type", !c.switch)
            assertNull("$type", c.note)
        }
        // during a tour the followed route keeps its activity: no switch, no line
        val t = SportType.importChoice("mtb", Activity.HIKE, 2, touring = true)
        assertEquals(Pick(Activity.MTB), t.pick)
        assertTrue(!t.switch)
        assertNull(t.note)
    }

    /**
     * Owner 2026-10-26: opening a tour from My routes (imported or recorded) switches to the tour's stored activity and
     * says so in one grey line – the same rules as the import (T level kept inside the band, nothing during a tour).
     */
    @Test fun openingATourSwitchesToItsActivity() {
        val bike = SportType.openChoice("BIKE_TOURING", 2, Activity.HIKE, 2, touring = false)
        assertTrue(bike.switch)
        assertEquals(Pick(Activity.BIKE_TOURING), bike.pick)
        assertEquals("Activity set to Bike from the tour", bike.note)
        assertEquals("Activity set to Gravelbike from the tour",
            SportType.openChoice("BIKE_TREKKING", 3, Activity.MTB, 2, touring = false).note)
        // a mountain hike (T4) while a bike is chosen and the remembered level is T2: the tour's level
        val berg = SportType.openChoice("HIKE", 4, Activity.EMTB, 2, touring = false)
        assertEquals(Pick(Activity.HIKE, 4), berg.pick)
        assertEquals("Activity set to Hike T4 from the tour", berg.note)
        // Hike T2 tour while hiking at T4: outside the band T1–T2 → the tour's level, said
        assertEquals("Activity set to Hike T2 from the tour",
            SportType.openChoice("HIKE", 2, Activity.HIKE, 4, touring = false).note)
    }

    @Test fun openingATourKeepsTheTLevelInsideItsBand() {
        // T level inside the tour's band (T1–T2 / T3–T5): the user's own stays – no switch, no line
        for ((tourSac, own) in listOf(2 to 1, 1 to 2, 3 to 5, 5 to 3, 4 to 4)) {
            val c = SportType.openChoice("HIKE", tourSac, Activity.HIKE, own, touring = false)
            assertEquals("tour T$tourSac, own T$own", Pick(Activity.HIKE, own), c.pick)
            assertTrue(!c.switch)
            assertNull(c.note)
        }
        // from a bike: the remembered level counts when it is inside the band
        assertEquals(Pick(Activity.HIKE, 5), SportType.openChoice("HIKE", 3, Activity.MTB, 5, touring = false).pick)
    }

    @Test fun openingATourWithoutAnActivityChangesNothing() {
        for (name in listOf(null, "", "SKI", "hike")) { // missing, empty, unknown, not the stored (enum) form
            val c = SportType.openChoice(name, 3, Activity.MTB, 4, touring = false)
            assertEquals("$name", Pick(Activity.MTB, 4), c.pick)
            assertTrue("$name", !c.switch)
            assertNull("$name", c.note)
        }
        // same activity: nothing to say
        assertNull(SportType.openChoice("EBIKE", 2, Activity.EBIKE, 5, touring = false).note)
        // during a tour the followed route keeps its activity
        val t = SportType.openChoice("MTB", 2, Activity.HIKE, 2, touring = true)
        assertTrue(!t.switch)
        assertNull(t.note)
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

    @Test fun aLinkTypeInsideTheTrackIsNotTheSport() {
        // GPX 1.1 allows <link><type>text/html</type></link> inside <trk>: that is a MIME type, not the sport
        val g = GpxParser.parse(gpx(null).replace("<trk><name>Test</name>",
            "<trk><name>Test</name><link href=\"https://example.org\"><type>text/html</type></link>"))
        assertNull(g.type)
    }

    @Test fun severalLinesTheFirstTypeCounts() {
        // a file with two tracks of different sports (and one without): the first line's type is the file's sport
        val two = gpx("mtb").replace("</gpx>",
            """<trk><name>B</name><type>hike</type><trkseg><trkpt lat="46.69" lon="11.17"/></trkseg></trk></gpx>""")
        assertEquals("mtb", GpxParser.parse(two).type)
        val firstUntyped = gpx(null).replace("</gpx>",
            """<trk><name>B</name><type>HIKE</type><trkseg><trkpt lat="46.69" lon="11.17"/></trkseg></trk></gpx>""")
        val g = GpxParser.parse(firstUntyped)
        assertEquals(listOf(null, "HIKE"), g.lines.map { it.type })
        assertEquals(Pick(Activity.HIKE, 2), SportType.activityOf(g.type))
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
            val type = GpxParser.parse(text).type
            assertEquals("$a T$sac", a, SportType.forImport(type, Activity.BIKE_TOURING, 1).activity)
            // the T level comes back by band: T1–T2 → Komoot hike, T3–T5 → mountaineering; the same user gets it back
            if (a == Activity.HIKE) assertEquals("T$sac", sac, SportType.forImport(type, Activity.HIKE, sac).sac)
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
