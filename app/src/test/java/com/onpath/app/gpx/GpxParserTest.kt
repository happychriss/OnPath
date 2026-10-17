package com.onpath.app.gpx

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GpxParserTest {

    private val komoot = """<?xml version='1.0' encoding='UTF-8'?>
<gpx version="1.1" creator="https://www.komoot.de" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata>
    <name>Waalweg Algund – Meran</name>
    <author><link href="https://www.komoot.de"><text>komoot</text><type>text/html</type></link></author>
  </metadata>
  <wpt lat="46.680000" lon="11.130000"><name>Hut</name></wpt>
  <trk>
    <name>Waalweg Algund – Meran</name>
    <trkseg>
      <trkpt lat="46.678081" lon="11.127548"><ele>420.5</ele><time>2024-06-01T08:00:00.000Z</time></trkpt>
      <trkpt lat="46.678504" lon="11.128203"><ele>425.0</ele><time>2024-06-01T08:00:30.000Z</time></trkpt>
      <trkpt lat="46.679021" lon="11.129100"><ele>431.0</ele><time>2024-06-01T08:01:05.000Z</time></trkpt>
    </trkseg>
  </trk>
</gpx>"""

    @Test fun komootExport() {
        val g = GpxParser.parse(komoot)
        assertEquals("Waalweg Algund – Meran", g.name)
        assertEquals(1, g.lines.size)
        assertEquals(3, g.pointCount)
        assertEquals(1, g.waypoints.size)
        assertEquals("Hut", g.waypoints[0].name)
        val p = g.allSegments[0][0]
        assertEquals(46.678081, p.lat, 1e-9)
        assertEquals(420.5, p.ele!!, 1e-9)
        assertEquals(1717228800000L, p.timeMs)
        assertTrue(g.warnings.isEmpty())
    }

    @Test fun routeInsteadOfTrack_noEleNoTime() {
        val g = GpxParser.parse("""<gpx version="1.0"><rte><name>R</name>
            <rtept lat="46.6" lon="11.1"/><rtept lat="46.61" lon="11.11"></rtept><rtept lat="46.62" lon="11.12"><name>x</name></rtept>
            </rte></gpx>""")
        assertEquals(1, g.lines.size)
        assertTrue(g.lines[0].isRoute)
        assertEquals("R", g.name)
        assertEquals(3, g.pointCount)
        assertNull(g.allSegments[0][0].ele)
        assertNull(g.allSegments[0][0].timeMs)
    }

    @Test fun multipleSegmentsAndPrefixedNamespace() {
        val g = GpxParser.parse("""<g:gpx xmlns:g="http://www.topografix.com/GPX/1/0"><g:trk>
            <g:trkseg><g:trkpt lat="1" lon="2"/><g:trkpt lat="1.001" lon="2"/></g:trkseg>
            <g:trkseg><g:trkpt lat="1.01" lon="2"/></g:trkseg></g:trk></g:gpx>""")
        assertEquals(2, g.allSegments.size)
        assertEquals(3, g.pointCount)
    }

    @Test fun truncatedFileKeepsValidPart() {
        val cut = komoot.substring(0, komoot.indexOf("<trkpt lat=\"46.679021\"") + 30)
        val g = GpxParser.parse(cut)
        assertEquals(2, g.pointCount)
        assertTrue(g.warnings.isNotEmpty())
    }

    @Test fun brokenXmlRecoveredByScan() {
        // unescaped '&' early in the file breaks XML parsing; regex recovery still finds the points
        val g = GpxParser.parse("""<gpx><metadata><name>Tom & Jerry</name></metadata><trk><trkseg>
            <trkpt lat="46.1" lon="11.1"><ele>500</ele></trkpt>
            <trkpt lat='46.2' lon='11.2'><ele>510</ele></trkpt>
            </trkseg></trk></gpx>""")
        assertEquals(2, g.pointCount)
        assertEquals(510.0, g.allSegments[0][1].ele!!, 1e-9)
        assertTrue(g.warnings.any { "recovered" in it })
    }

    @Test fun bomAndJunkBeforeHeader() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "\n  <gpx><wpt lat=\"46\" lon=\"11\"/></gpx>".toByteArray()
        val g = GpxParser.parse(bytes)
        assertEquals(1, g.waypoints.size)
    }

    @Test fun invalidCoordinatesSkipped() {
        val g = GpxParser.parse("""<gpx><trk><trkseg><trkpt lat="abc" lon="11"/><trkpt lat="95" lon="11"/>
            <trkpt lat="46" lon="11"/></trkseg></trk></gpx>""")
        assertEquals(1, g.pointCount)
        assertTrue(g.warnings.any { "Skipped 2" in it })
    }

    @Test fun emptyOrNotGpxThrows() {
        for (bad in listOf("", "hello world", "<html><body>nope</body></html>")) {
            try { GpxParser.parse(bad); fail("expected exception for '$bad'") } catch (_: GpxException) { }
        }
    }

    @Test fun timeFormats() {
        assertNotNull(GpxParser.parseTime("2024-06-01T08:00:00Z"))
        assertNotNull(GpxParser.parseTime("2024-06-01T10:00:00+02:00"))
        assertNotNull(GpxParser.parseTime("2024-06-01T08:00:00"))
        assertEquals(GpxParser.parseTime("2024-06-01T08:00:00Z"), GpxParser.parseTime("2024-06-01T10:00:00+02:00"))
        assertNull(GpxParser.parseTime("yesterday"))
    }

    @Test fun writeThenReadRoundTrip() {
        val segs = listOf(
            listOf(TrackPoint(46.1, 11.1, 500.0, 1717228800000L), TrackPoint(46.2, 11.2, null, null)),
            listOf(TrackPoint(46.3, 11.3, 520.0, null)),
        )
        val xml = GpxWriter.write("A & B <test>", segs, listOf(GpxWaypoint(46.0, 11.0, "Start", null)))
        val g = GpxParser.parse(xml)
        assertEquals("A & B <test>", g.name)
        assertEquals(2, g.allSegments.size)
        assertEquals(3, g.pointCount)
        assertEquals(1717228800000L, g.allSegments[0][0].timeMs)
        assertEquals("Start", g.waypoints[0].name)
        assertTrue(g.warnings.isEmpty())
    }

    @Test fun geoHelpers() {
        val d = Geo.distance(46.67, 11.16, 46.68, 11.16)
        assertEquals(1112.0, d, 5.0)
        val (up, down) = Geo.climb(listOf(100.0, 101.0, 99.0, 110.0, 120.0, 105.0, null, 100.0))
        assertEquals(20.0, up, 0.01)   // 100 -> 120 (noise below 4 m ignored)
        assertEquals(20.0, down, 0.01)
        val line = listOf(com.onpath.app.routing.GeoPoint(46.0, 11.0), com.onpath.app.routing.GeoPoint(46.0, 11.01))
        assertEquals(111.0, Geo.distanceToLine(46.001, 11.005, line), 2.0)
    }
}
