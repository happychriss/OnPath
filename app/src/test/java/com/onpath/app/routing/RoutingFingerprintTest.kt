package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The routing fingerprint (Settings → Diagnostics; docs/design/routing-fingerprint.md). */
class RoutingFingerprintTest {
    private val assets = File("src/main/assets/brouter")
    private fun read(name: String) = File(assets, name).readBytes()

    @Test fun brouterVersionIsTheJarsInAppLibs() {
        val jars = File("libs").listFiles { f -> f.name.startsWith("brouter-") && f.name.endsWith(".jar") }.orEmpty()
        assertTrue("BRouter jars in app/libs", jars.isNotEmpty())
        jars.forEach { assertTrue("${it.name} is BRouter ${RoutingFingerprint.BROUTER_VERSION}",
            it.name.endsWith("-${RoutingFingerprint.BROUTER_VERSION}.jar")) }
    }

    /** A new profile in assets/brouter must count too (else two different profile sets look the same). */
    @Test fun everyProfileIsHashed() {
        val files = assets.listFiles { f -> f.name.endsWith(".brf") || f.name == "lookups.dat" }.orEmpty().map { it.name }.sorted()
        assertEquals(files, RoutingFingerprint.FILES.sorted())
        RoutingFingerprint.FILES.forEach { assertTrue("$it is a profile BRouterEngine can use or lookups.dat", File(assets, it).isFile) }
    }

    @Test fun fingerprintOfTheAppsProfiles() {
        val fp = RoutingFingerprint.of(::read)
        assertTrue(fp, RoutingFingerprint.isComparable(fp))
        assertEquals("stable", fp, RoutingFingerprint.of(::read))
        // anything that changes routes changes it: a profile byte, the BRouter version, our planner version
        val edited = RoutingFingerprint.of({ n -> if (n == "trekking.brf") read(n) + " ".toByteArray() else read(n) })
        assertNotEquals(fp, edited)
        assertNotEquals(fp, RoutingFingerprint.of(::read, brouter = "1.7.12"))
        assertNotEquals(fp, RoutingFingerprint.of(::read, planner = RoutingFingerprint.PLANNER_VERSION + 1))
        // a checkout with CRLF line ends is the same profile
        val crlf = RoutingFingerprint.of({ n -> String(read(n), Charsets.ISO_8859_1).replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1) })
        assertEquals(fp, crlf)
    }

    @Test fun comparableFormat() {
        assertTrue(RoutingFingerprint.isComparable("r1-0123456789ab"))
        assertFalse(RoutingFingerprint.isComparable(null))
        assertFalse(RoutingFingerprint.isComparable("r2-0123456789ab"))
        assertFalse(RoutingFingerprint.isComparable("2-0123456789abcdef"))
    }

    @Test fun diagnosticsLine() {
        assertEquals("r1-0123456789ab · BRouter ${RoutingFingerprint.BROUTER_VERSION} · planner ${RoutingFingerprint.PLANNER_VERSION}" +
            " · data E10_N45 2026-10-26, E5_N45 –",
            RoutingFingerprint.describe("r1-0123456789ab", listOf("E5_N45.rd5" to null, "E10_N45.rd5" to 1_792_982_642_161L)))
        assertTrue(RoutingFingerprint.describe("r1-0123456789ab", emptyList()).endsWith("· no routing data"))
    }

    @Test fun days() {
        assertEquals("1970-01-01", RoutingFingerprint.day(0))
        assertEquals("2000-02-29", RoutingFingerprint.day(951_782_400_000L))
        assertEquals("1969-12-31", RoutingFingerprint.day(-1))
        assertEquals("2026-10-26", RoutingFingerprint.day(1_792_982_642_161L))
    }
}
