package com.onpath.app.data

import com.onpath.app.cells.CellId
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.routing.BRouterEngine
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CORE FEATURE "routing data on a fresh install" (field test 2026-10-21: a fresh phone never downloaded routing data;
 * the plan showed a straight line and a notice with a file path). Which tiles a place needs, the plain notice, and the
 * loader: loads missing tiles by itself, respects "Maps only on Wi-Fi", waits offline, keeps partial files, and tells
 * the app when a tile arrived (the plan is then computed again).
 */
class RoutingDataAutoTest {

    // ---------------------------------------------------------------- which tiles

    @Test fun pointInBozenNeedsE10N45() {
        assertEquals(listOf("E10_N45.rd5"), RoutingTiles.forPoints(listOf(GeoPoint(46.5, 11.35))))
    }

    @Test fun routeAcrossATileEdgeNeedsBoth() {
        // Bozen → Lake Constance area across lon 10 (straight leg, no points in between)
        val t = RoutingTiles.forPoints(listOf(GeoPoint(46.5, 11.35), GeoPoint(47.6, 9.5)))
        assertEquals(listOf("E10_N45.rd5", "E5_N45.rd5"), t)
    }

    @Test fun westAndSouthTiles() {
        assertEquals(listOf("W5_S35.rd5"), RoutingTiles.forPoints(listOf(GeoPoint(-33.9, -1.2))))
    }

    @Test fun invalidPointsAreSkipped() {
        assertTrue(RoutingTiles.forPoints(listOf(GeoPoint(Double.NaN, 11.0))).isEmpty())
    }

    @Test fun cellsNeedTheTilesUnderThem() {
        val meran = CellId.at(46.67, 11.16)!!
        assertEquals(listOf("E10_N45.rd5"), RoutingTiles.forCells(listOf(meran)))
        // a region on the lon-10 edge needs both tiles
        val edge = CellId.at(46.5, 10.0)!!
        assertEquals(setOf("E5_N45.rd5", "E10_N45.rd5"), RoutingTiles.forCells(listOf(edge)).toSet())
    }

    @Test fun missingTiles() {
        assertEquals(listOf("E5_N45.rd5"), RoutingTiles.missing(listOf("E10_N45.rd5", "E5_N45.rd5")) { it == "E10_N45.rd5" })
    }

    // ---------------------------------------------------------------- the notice (never a path)

    @Test fun noticeTextsArePlain() {
        for (s in listOf(RoutingDataNotice.LOADING, RoutingDataNotice.NOT_YET)) {
            assertFalse(s, s.contains(".rd5") || s.contains("/") || s.contains("segments"))
        }
        assertTrue(RoutingDataNotice.text(true).startsWith("Routing data for this area is still loading"))
        assertTrue(RoutingDataNotice.text(false).startsWith("No routing data for this area yet"))
    }

    @Test fun routerMessageWithAPathBecomesThePlainNotice() {
        val old = "No routing data for this area (missing E10_N45.rd5 in /storage/emulated/0/Android/data/x/segments). " +
            "Shown as a straight dashed line."
        assertEquals(RoutingDataNotice.LOADING, RoutingDataNotice.forRoute(old, missing = false, loading = true))
        assertEquals(RoutingDataNotice.NOT_YET, RoutingDataNotice.forRoute("Leg 1→2: ${BRouterEngine.NO_DATA}", false, false))
        assertEquals(RoutingDataNotice.NOT_YET, RoutingDataNotice.forRoute(null, missing = true, loading = false))
        assertEquals("No route found.", RoutingDataNotice.forRoute("No route found.", missing = false, loading = false))
        assertNull(RoutingDataNotice.forRoute(null, missing = false, loading = false))
    }

    @Test fun routerMessageHasNoFileName() {
        assertFalse(BRouterEngine.NO_DATA.contains(".rd5"))
    }

    // ---------------------------------------------------------------- the loader

    private class Net(var online: Boolean = true, var wifi: Boolean = false) : NetworkStatus {
        override fun connected() = online
        override fun unmetered() = online && wifi
    }

    private val dir: File = Files.createTempDirectory("seg").toFile()

    private fun loader(net: Net, wifiOnly: Boolean = false, installed: MutableList<String> = mutableListOf(),
                       fail: Boolean = false, calls: MutableList<String> = mutableListOf()) =
        RoutingDataLoader(dir, net, { wifiOnly }, launch = { it() }, onInstalled = { installed += it },
            download = { name, d, progress, _ ->
                calls += name
                if (fail) { File(d, ".$name.part").writeBytes(ByteArray(10)); throw IllegalStateException("Server answered 503") }
                progress(5, 10); File(d, name).writeBytes(ByteArray(10)); progress(10, 10)
            })

    @Test fun missingTileLoadsByItselfAndTheAppIsTold() {
        val got = mutableListOf<String>()
        val l = loader(Net(), installed = got)
        val miss = l.ensure(RoutingTiles.forPoints(listOf(GeoPoint(46.5, 11.35))))
        assertEquals(listOf("E10_N45.rd5"), miss)
        assertTrue(File(dir, "E10_N45.rd5").isFile)
        assertEquals(listOf("E10_N45.rd5"), got) // → the plan is computed again
        assertNull(l.status)
        assertTrue(l.missing(listOf("E10_N45.rd5")).isEmpty())
    }

    @Test fun presentTileIsNotLoadedAgain() {
        File(dir, "E10_N45.rd5").writeBytes(ByteArray(3))
        val calls = mutableListOf<String>()
        assertTrue(loader(Net(), calls = calls).ensure(listOf("E10_N45.rd5")).isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun mobileDataLoadsUnlessWifiOnly() {
        val calls = mutableListOf<String>()
        loader(Net(wifi = false), calls = calls).ensure(listOf("E10_N45.rd5"))
        assertEquals(1, calls.size) // owner: routing data on mobile data too

        File(dir, "E10_N45.rd5").delete()
        val c2 = mutableListOf<String>()
        val l = loader(Net(wifi = false), wifiOnly = true, calls = c2)
        l.ensure(listOf("E10_N45.rd5"))
        assertTrue(c2.isEmpty())
        assertEquals(RoutingDataLoader.Phase.WIFI, l.status?.phase)
        // the explicit Settings tap loads anyway
        l.ensure(listOf("E10_N45.rd5"), ignoreWifiOnly = true)
        assertEquals(1, c2.size)
    }

    @Test fun offlineWaitsAndLoadsWhenOnline() {
        val net = Net(online = false)
        val got = mutableListOf<String>()
        val l = loader(net, installed = got)
        l.ensure(listOf("E10_N45.rd5"))
        assertEquals(RoutingDataLoader.Phase.OFFLINE, l.status?.phase)
        assertFalse(l.loading)
        net.online = true
        l.retry()
        assertEquals(listOf("E10_N45.rd5"), got)
    }

    @Test fun failureKeepsThePartialFileAndIsNotHammered() {
        val calls = mutableListOf<String>()
        val l = loader(Net(), fail = true, calls = calls)
        l.ensure(listOf("E10_N45.rd5"))
        assertEquals(RoutingDataLoader.Phase.FAILED, l.status?.phase)
        assertTrue(File(dir, ".E10_N45.rd5.part").isFile)
        assertEquals(10L, l.status?.doneBytes) // the partial file counts (resume)
        l.retry() // a network change right after: not again
        assertEquals(1, calls.size)
        l.retry(force = true) // "Try again"
        assertEquals(2, calls.size)
        l.retry(now = System.currentTimeMillis() + RoutingDataLoader.RETRY_AFTER_FAIL_MS + 1)
        assertEquals(3, calls.size)
    }

    @Test fun cancelStopsAndForgets() {
        val l = loader(Net(online = false))
        l.ensure(listOf("E10_N45.rd5"))
        l.cancel()
        assertNull(l.status)
    }

    @Test fun sizeEstimatedUntilKnown() {
        val l = loader(Net(online = false))
        l.ensure(listOf("E10_N45.rd5"))
        assertEquals(RoutingTiles.ESTIMATED_BYTES, l.status?.totalBytes)
    }
}
