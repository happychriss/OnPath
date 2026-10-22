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

    // ---------------------------------------------------------------- which 1° parts (owner decision "D")

    @Test fun pointInMeranNeedsOnePart() {
        assertEquals(listOf("E10_N45.rd5:E11_N46"), RoutingTiles.forPoints(listOf(GeoPoint(46.67, 11.16))))
    }

    @Test fun routeAcrossATileEdgeNeedsPartsOfBoth() {
        // Bozen → Lake Constance area across lon 10 (straight leg, no points in between)
        val t = RoutingTiles.forLine(listOf(GeoPoint(46.5, 11.35), GeoPoint(47.6, 9.5)), 1_000.0)
        assertTrue(t.containsAll(listOf("E10_N45.rd5:E11_N46", "E10_N45.rd5:E10_N47", "E5_N45.rd5:E9_N47")))
        assertTrue(t.none { it.endsWith("E12_N46") || it.endsWith("E9_N45") }) // only along the line
    }

    @Test fun westAndSouthParts() {
        assertEquals(listOf("W5_S35.rd5:W2_S34"), RoutingTiles.forPoints(listOf(GeoPoint(-33.9, -1.2))))
    }

    @Test fun invalidPointsAreSkipped() {
        assertTrue(RoutingTiles.forPoints(listOf(GeoPoint(Double.NaN, 11.0))).isEmpty())
        assertTrue(RoutingTiles.forLine(listOf(GeoPoint(Double.NaN, 11.0))).isEmpty())
    }

    @Test fun cellsNeedThePartsUnderThem() {
        val bozen = CellId.at(46.50, 11.35)!! // 11.25–11.60 E
        assertEquals(listOf("E10_N45.rd5:E11_N46"), RoutingTiles.forCells(listOf(bozen)))
        val meran = CellId.at(46.67, 11.16)!! // 10.90–11.25 E: on the 11°E line
        assertEquals(setOf("E10_N45.rd5:E10_N46", "E10_N45.rd5:E11_N46"), RoutingTiles.forCells(listOf(meran)).toSet())
        // a region on the lon-10 edge needs a part of each file
        val edge = CellId.at(46.5, 10.0)!!
        assertEquals(setOf("E5_N45.rd5:E9_N46", "E10_N45.rd5:E10_N46"), RoutingTiles.forCells(listOf(edge)).toSet())
    }

    @Test fun missingParts() {
        assertEquals(listOf("b"), RoutingTiles.missing(listOf("a", "b")) { it == "a" })
    }

    @Test fun deletingARegionFreesOnlyPartsNoOtherRegionNeeds() {
        val meran = CellId.at(46.67, 11.16)!! // parts 10°E + 11°E 46°N
        val bozen = CellId.at(46.50, 11.35)!! // part 11°E 46°N
        val ulten = CellId.at(46.55, 10.95)!! // parts 10°E + 11°E 46°N
        assertEquals(listOf("E10_N45.rd5:E10_N46"), RoutingTiles.unneeded(listOf(meran), listOf(bozen)))
        assertTrue(RoutingTiles.unneeded(listOf(ulten), listOf(meran)).isEmpty())
        assertEquals(setOf("E10_N45.rd5:E10_N46", "E10_N45.rd5:E11_N46"), RoutingTiles.unneeded(listOf(meran, bozen), emptyList()).toSet())
    }

    @Test fun sharedPartsCountOnceInOfflineMaps() {
        val meran = CellId.at(46.67, 11.16)!!
        val bozen = CellId.at(46.50, 11.35)!!
        val sizes = RoutingTiles.bytesPerCell(listOf(meran, bozen)) { if (it.endsWith("E11_N46")) 9L else 5L }
        assertEquals(mapOf(meran to 14L, bozen to 0L), sizes)
    }

    // ---------------------------------------------------------------- the notice (never a path)

    @Test fun noticeTextsArePlain() {
        for (s in listOf(RoutingDataNotice.LOADING, RoutingDataNotice.NOT_YET)) {
            assertFalse(s, s.contains(".rd5") || s.contains("/") || s.contains("segments"))
        }
        // owner 2026-10-22: never "routing data" – it is all "the map for this area"
        assertEquals("The map for this area is still loading – the route is shown as a straight line until then.", RoutingDataNotice.text(true))
        assertEquals("The map for this area loads when you're online – the route is shown as a straight line until then.", RoutingDataNotice.text(false))
        for (s in listOf(RoutingDataNotice.LOADING, RoutingDataNotice.NOT_YET)) assertFalse(s, s.contains("routing", ignoreCase = true))
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
    private val server = Rd5PartStoreTest.FakeRemote(
        com.onpath.app.routing.Rd5PartsTest.fakeRemoteFile(Files.createTempDirectory("srv").toFile(),
            com.onpath.app.routing.Rd5PartsTest.sizes()).first, "\"v1\"")
    private val store = Rd5PartStore(dir, server)
    private val meran = "E10_N45.rd5:E11_N46"

    private fun loader(net: Net, wifiOnly: Boolean = false, installed: MutableList<String> = mutableListOf(),
                       fail: Boolean = false, calls: MutableList<String> = mutableListOf()) =
        RoutingDataLoader(dir, net, { wifiOnly }, launch = { it() }, onInstalled = { installed += it }, store = store,
            download = { name, d, progress, cancelled ->
                calls += name
                if (fail) { server.breakAfter = 10; store.bytesToLoad(listOf(com.onpath.app.routing.Rd5Parts.Part.parse(name)!!)) }
                RoutingDataLoader.fetch(store, name, d, progress, cancelled)
            })

    @Test fun missingTileLoadsByItselfAndTheAppIsTold() {
        val got = mutableListOf<String>()
        val l = loader(Net(), installed = got)
        val miss = l.ensure(RoutingTiles.forPoints(listOf(GeoPoint(46.5, 11.35))))
        assertEquals(listOf(meran), miss)
        assertTrue(File(dir, "E10_N45.rd5").isFile)
        assertEquals(listOf(meran), got) // → the plan is computed again
        assertNull(l.status)
        assertTrue(l.missing(listOf(meran)).isEmpty())
        assertFalse(l.exists("E10_N45.rd5:E10_N46")) // only the part that was needed
        assertTrue(l.exists("E10_N45.rd5")) // a whole-file name: the file is there
    }

    @Test fun presentTileIsNotLoadedAgain() {
        server.file.copyTo(File(dir, "E10_N45.rd5")) // a whole file (old download): every part is there
        val calls = mutableListOf<String>()
        assertTrue(loader(Net(), calls = calls).ensure(listOf(meran, "E10_N45.rd5:E10_N46")).isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun mobileDataLoadsUnlessWifiOnly() {
        val calls = mutableListOf<String>()
        loader(Net(wifi = false), calls = calls).ensure(listOf(meran))
        assertEquals(1, calls.size) // owner: routing data on mobile data too

        store.remove(listOf(com.onpath.app.routing.Rd5Parts.Part.parse(meran)!!))
        val c2 = mutableListOf<String>()
        val l = loader(Net(wifi = false), wifiOnly = true, calls = c2)
        l.ensure(listOf(meran))
        assertTrue(c2.isEmpty())
        assertEquals(RoutingDataLoader.Phase.WIFI, l.status?.phase)
        // the explicit Diagnostics tap loads anyway
        l.ensure(listOf(meran), ignoreWifiOnly = true)
        assertEquals(1, c2.size)
    }

    @Test fun offlineWaitsAndLoadsWhenOnline() {
        val net = Net(online = false)
        val got = mutableListOf<String>()
        val l = loader(net, installed = got)
        l.ensure(listOf(meran))
        assertEquals(RoutingDataLoader.Phase.OFFLINE, l.status?.phase)
        assertFalse(l.loading)
        net.online = true
        l.retry()
        assertEquals(listOf(meran), got)
    }

    @Test fun failureKeepsThePartialFileAndIsNotHammered() {
        val calls = mutableListOf<String>()
        val l = loader(Net(), fail = true, calls = calls)
        l.ensure(listOf(meran))
        assertEquals(RoutingDataLoader.Phase.FAILED, l.status?.phase)
        assertFalse(File(dir, "E10_N45.rd5").exists()) // never a half file in place
        assertEquals(10L, l.status?.doneBytes) // the partial part counts (resume)
        l.retry() // a network change right after: not again
        assertEquals(1, calls.size)
        l.retry(force = true) // "Try again"
        assertEquals(2, calls.size)
        l.retry(now = System.currentTimeMillis() + RoutingDataLoader.RETRY_AFTER_FAIL_MS + 1)
        assertEquals(3, calls.size)
    }

    @Test fun cancelStopsAndForgets() {
        val l = loader(Net(online = false))
        l.ensure(listOf(meran))
        l.cancel()
        assertNull(l.status)
    }

    @Test fun sizeEstimatedUntilKnown() {
        val l = loader(Net(online = false))
        l.ensure(listOf(meran))
        assertEquals(RoutingTiles.ESTIMATED_BYTES, l.status?.totalBytes)
    }

    @Test fun realSizesBeforeLoading() {
        // the modal's "about X MB": read from the server's index before the first byte of a part loads
        val sizes = mutableListOf<Long>()
        lateinit var l: RoutingDataLoader
        l = RoutingDataLoader(dir, Net(), { false }, launch = { it() }, store = store,
            download = { _, _, _, _ -> sizes += l.status!!.totalBytes; throw IllegalStateException("stop here") })
        l.ensure(listOf(meran, "E10_N45.rd5:E10_N46"))
        val idx = com.onpath.app.routing.Rd5Parts.readIndex(server.file)!!
        assertEquals(listOf(idx.size(6) + idx.size(1)), sizes)
    }

    /** Field bug 2026-10-22: routing parts loading for an older route's corridor are dropped; a point's part stays. */
    @Test fun corridorPartsFollowThePlan() {
        val l = loader(Net(online = false))
        val other = "E10_N45.rd5:E10_N46"
        l.ensure(listOf(meran, other), corridor = true)
        l.ensure(listOf(meran)) // also the user's point
        assertEquals(listOf(other), l.keepCorridor(emptyList()))
        assertEquals(listOf(meran), l.status?.names)
        assertTrue(l.keepCorridor(emptyList()).isEmpty())
    }
}
