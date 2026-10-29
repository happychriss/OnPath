package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * GPX import and the long-route rule on the JVM (the same Kotlin the Wasm engine runs). Also: the web's SAX stand-in
 * (onpathweb.javax.xml.parsers, used only in Wasm) reports the same events as the JDK's parser on the fixtures, and
 * the results are written to build/jvm-gpx.json – web/app/test/gpx-engine.mjs checks that Wasm gives the same JSON.
 */
class WebImportTest {
    private val fixtures = File("../app/test/fixtures")
    private fun read(name: String, activity: String = "BIKE_TOURING", sac: Int = 4) =
        WebImport.read(File(fixtures, name).readBytes(), name, activity, sac)

    @Test fun komootHikeSetsHikeT2AndSaysSo() {
        val j = read("komoot-hike.gpx")
        assertTrue(j, j.contains("\"name\":\"Meran 2000 & Ifinger Hütte\""))
        assertTrue(j, j.contains("\"activity\":\"HIKE\",\"sac\":2,\"switch\":true"))
        assertTrue(j, j.contains("\"note\":\"Activity set to Hike T2 from the file\""))
        assertTrue(j, j.contains("\"skippedNote\":null"))
        assertTrue(j, j.contains("\"durationS\":4860")) // the file's own time: 108 × 45 s
    }

    @Test fun ownTLevelInsideTheBandStays() {
        val j = read("komoot-hike.gpx", "HIKE", 1)
        assertTrue(j, j.contains("\"activity\":\"HIKE\",\"sac\":1,\"switch\":false,\"note\":null"))
    }

    @Test fun routeWithPrefixesCdataAndReferences() {
        val j = read("mtb-route.gpx")
        assertTrue(j, j.contains("\"name\":\"Lana – Völlan <MTB>\""))
        assertTrue(j, j.contains("\"activity\":\"MTB\""))
        assertTrue(j, j.contains("\"name\":\"Lana – Start\"") && j.contains("\"name\":\"Völlan\""))
        assertTrue(j, j.contains("\"skippedNote\":\"Some parts of the file were skipped.\""))
    }

    @Test fun brokenFileKeepsWhatWasReadable() {
        val j = read("broken.gpx")
        assertTrue(j, !j.contains("\"error\""))
        assertTrue(j, j.contains("\"skippedNote\":\"Some parts of the file were skipped.\""))
    }

    @Test fun nothingInsideIsThePlainError() {
        val j = WebImport.read("<gpx></gpx>".toByteArray(), "x.gpx", "HIKE", 2)
        assertEquals("{\"error\":\"Could not import this file: No track, route or waypoint found in this file.\"}", j)
    }

    @Test fun longRouteIsAskedWithTheAppsWords() {
        // Meran → about 600 km north (Hamburg direction): far beyond a day on foot
        val j = WebPlanRules.longRoute("46.67,11.16;52.05,11.16", "HIKE")
        assertTrue(j, j.startsWith("{\"ask\":true"))
        assertTrue(j, j.contains("\"title\":\"This route is very long\""))
        assertTrue(j, j.contains("days on foot. Calculating and loading its maps takes long.\""))
        assertTrue(j, j.contains("\"plan\":\"Plan anyway\",\"cancel\":\"Cancel\""))
        assertEquals("{\"ask\":false}", WebPlanRules.longRoute("46.67,11.16;46.69,11.15", "HIKE"))
        assertEquals("UNDO", WebPlanRules.onCancel("edit", true, false))
        assertEquals("RESTORE_ACTIVITY", WebPlanRules.onCancel("activity", false, false))
        assertEquals("KEEP_POINTS", WebPlanRules.onCancel("other", true, true))
    }

    /** SAX events as text: the JDK parser and the web stand-in must agree on every fixture (up to the first error). */
    @Test fun saxStandInMatchesTheJdk() {
        val out = StringBuilder("{")
        for (f in fixtures.listFiles { f -> f.extension == "gpx" }!!.sortedBy { it.name }) {
            val bytes = f.readBytes()
            val jdk = StringBuilder()
            val jdkError = try {
                javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser().parse(ByteArrayInputStream(bytes),
                    object : org.xml.sax.helpers.DefaultHandler() {
                        override fun startElement(u: String?, l: String?, q: String, a: org.xml.sax.Attributes) {
                            jdk.append("<").append(q); for (i in 0 until a.length) jdk.append(" ${a.getQName(i)}=${a.getValue(i)}"); jdk.append(">")
                        }
                        override fun endElement(u: String?, l: String?, q: String) { jdk.append("</").append(q).append(">") }
                        override fun characters(ch: CharArray, s: Int, n: Int) { jdk.appendRange(ch, s, s + n) }
                    }); false
            } catch (e: org.xml.sax.SAXException) { true }
            val web = StringBuilder()
            val webError = try {
                onpathweb.javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser().parse(ByteArrayInputStream(bytes),
                    object : onpathweb.org.xml.sax.helpers.DefaultHandler() {
                        override fun startElement(u: String?, l: String?, q: String, a: onpathweb.org.xml.sax.Attributes) {
                            web.append("<").append(q); for (i in 0 until a.length) web.append(" ${a.getQName(i)}=${a.getValue(i)}"); web.append(">")
                        }
                        override fun endElement(u: String?, l: String?, q: String) { web.append("</").append(q).append(">") }
                        override fun characters(ch: CharArray, s: Int, n: Int) { web.appendRange(ch, s, s + n) }
                    }); false
            } catch (e: onpathweb.org.xml.sax.SAXException) { true }
            assertEquals(f.name + ": error", jdkError, webError)
            // the JDK may hand over text in larger chunks before it notices an error: compare the elements read
            val a = jdk.toString(); val b = web.toString()
            if (jdkError) assertEquals(f.name, a.replace(Regex("[^<]*$"), "").take(b.length), b.replace(Regex("[^<]*$"), "").take(a.length))
            else assertEquals(f.name, a, b)
            if (out.length > 1) out.append(',')
            out.append(Engine.str(f.name)).append(':').append(WebImport.read(bytes, f.name, "BIKE_TOURING", 4))
        }
        File("build").mkdirs()
        File("build/jvm-gpx.json").writeText(out.append('}').toString())
    }
}
