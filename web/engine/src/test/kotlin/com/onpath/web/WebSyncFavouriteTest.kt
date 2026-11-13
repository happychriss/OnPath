package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * My routes in the browser (owner 2026-11-13: „Die Favoriten sind wirklich wichtig“): the page's star call is the shared
 * SyncLedger.setFavourite (a star never makes a copy), and syncList carries the star, the activity, the T level and the
 * app's round-trip rule for the row.
 */
class WebSyncFavouriteTest {
    private val dev = SyncFormat.newDevice(ByteArray(SyncFormat.DEVICE_BYTES) { 7 })
    private fun id(n: Int) = SyncFormat.newId(ByteArray(SyncFormat.ID_BYTES) { (it + n).toByte() })
    private fun code(points: List<GeoPoint>, a: Activity = Activity.HIKE, sac: Int = 3) = PlanCode.encode(SharedPlan(points, a, sac))
    private fun row(ledger: String, id: String): String =
        WebSync.list(ledger, dev).let { s -> Regex("\\{[^{}]*\"id\":\"" + Regex.escape(id) + "\"[^{}]*\\}").find(s)!!.value }

    private val meran = GeoPoint(46.6713, 11.1594)
    private val vellau = GeoPoint(46.6900, 11.1300)
    private val dorf = GeoPoint(46.6800, 11.1450)

    @Test fun theStarTravelsInTheListAndIsAnEdit() {
        var l = WebSync.save("", id(1), code(listOf(meran, vellau)), "Waalweg", 1000.0, dev)
        assertTrue(row(l, id(1)).contains("\"favourite\":false"))
        l = WebSync.setFavourite(l, id(1), true, 2000.0, dev)
        assertTrue(row(l, id(1)).contains("\"favourite\":true"))
        // an edit: to send with the next Save, a new version
        assertTrue(row(l, id(1)).contains("\"dirty\":true"))
        assertTrue(row(l, id(1)).contains("\"updatedAt\":2000"))
        l = WebSync.setFavourite(l, id(1), false, 3000.0, dev)
        assertTrue(row(l, id(1)).contains("\"favourite\":false"))
        // the page's Save without a star keeps it (null = keep)
        l = WebSync.setFavourite(l, id(1), true, 4000.0, dev)
        l = WebSync.saveRouted(l, id(1), code(listOf(meran, dorf, vellau)), "Waalweg 2", 5000.0, dev)
        assertTrue(row(l, id(1)).contains("\"favourite\":true"))
        // a rename keeps it too
        l = WebSync.rename(l, id(1), "Waalweg 3", 6000.0, dev)
        assertTrue(row(l, id(1)).contains("\"favourite\":true"))
        assertEquals(1, SyncLedger.read(l).plans().size)
    }

    @Test fun aStarOnAnUnknownIdChangesNothing() {
        val l = WebSync.save("", id(1), code(listOf(meran, vellau)), "Waalweg", 1000.0, dev)
        assertEquals(l, WebSync.setFavourite(l, id(9), true, 2000.0, dev))
    }

    @Test fun theRowSaysActivityLevelAndRoundTrip() {
        var l = WebSync.save("", id(1), code(listOf(meran, vellau), Activity.HIKE, 3), "Hin", 1000.0, dev)
        l = WebSync.save(l, id(2), code(listOf(meran, vellau, dorf, meran), Activity.MTB, 2), "Runde", 1000.0, dev)
        assertTrue(row(l, id(1)).contains("\"activity\":\"HIKE\",\"sac\":3,\"roundTrip\":false"))
        assertTrue(row(l, id(2)).contains("\"activity\":\"MTB\",\"sac\":2,\"roundTrip\":true"))
        assertEquals("{\"activity\":\"MTB\",\"sac\":2,\"roundTrip\":true}", WebSync.planFacts(code(listOf(meran, vellau, dorf, meran), Activity.MTB, 2)))
        assertEquals("{\"activity\":null,\"sac\":0,\"roundTrip\":false}", WebSync.planFacts("nonsense"))
    }

    @Test fun anImportedTrackIsAGuessFromItsLine() {
        val loop = (0..40).map { i -> val a = i / 40.0 * 2 * Math.PI; "${46.67 + 0.01 * Math.sin(a)},${11.16 + 0.01 * (1 - Math.cos(a))}" }.joinToString(";")
        val track = WebSync.trackFit(loop, "", "BIKE_TOURING", 2)
        var l = WebSync.saveTrack("", id(3), track, "Runde", 1000.0, dev)
        assertTrue(row(l, id(3)).contains("\"activity\":\"BIKE_TOURING\""))
        assertTrue(row(l, id(3)).contains("\"roundTrip\":true"))
        // back home after a short ride: none (never 500 m out)
        val short = (0..10).map { i -> "${46.67 + 0.0002 * (if (i <= 5) i else 10 - i)},11.16" }.joinToString(";")
        l = WebSync.saveTrack(l, id(4), WebSync.trackFit(short, "", "HIKE", 2), "Kurz", 1000.0, dev)
        assertTrue(row(l, id(4)).contains("\"roundTrip\":false"))
        assertFalse(WebSync.roundTripGuess(listOf(meran, vellau)))
        // tracks star like plans
        l = WebSync.setFavourite(l, id(3), true, 2000.0, dev)
        assertTrue(row(l, id(3)).contains("\"favourite\":true"))
    }
}
