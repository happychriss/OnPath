package com.onpath.app.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * My routes' map pictures (owner 2026-11-13, L1) on the real app with real MapLibre: routes saved without the phone
 * making a picture (as from the browser, a GPX import or a restore) get one in the background when My routes opens –
 * one file per tour – and none is made while a tour would run. Logs how long one picture takes (tag OnPathThumb).
 */
@RunWith(AndroidJUnit4::class)
class RouteThumbE2ETest : E2EBase() {
    @Before fun setUp() = launch()

    @Test fun routesWithoutAPictureGetOneInTheBackground() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TourDb.get(ctx)
        val uuids = (1..3).map { n ->
            val line = (0..120).map { i ->
                val a = i / 120.0 * 1.8 * Math.PI
                TrackPoint(MERAN_LAT + 0.01 * n * sin(a), MERAN_LON + 0.014 * cos(a + n), 400.0 + i)
            }
            val id = db.insertTour(TourKind.PLANNED, "Thumb $n", "HIKE", 2, 5_000.0 * n, 200.0, 200.0, 3600.0, listOf(line),
                listOf(GpxWaypoint(line.first().lat, line.first().lon, null), GpxWaypoint(line.last().lat, line.last().lon, null)))
            db.getTour(id)!!.uuid
        }
        scenario.onActivity { vm(it).openRoutes() }
        var files = emptyList<java.io.File>()
        scenario.onActivity { a -> files = uuids.map { u -> vm(a).thumbs.file(u) } }
        waitUntil("three map pictures", 90_000) { files.all { it.isFile && it.length() > 0 } }
        var timings = emptyList<Long>()
        waitUntil("their timings", 10_000) { scenario.onActivity { timings = vm(it).thumbTimings }; timings.size >= 3 }
        // the list with the pictures, and one picture as stored (diagnostics: /sdcard/e2e)
        Thread.sleep(1_500)
        shot("list")
        ctx.getExternalFilesDir(null)?.let { ext -> files.first().copyTo(java.io.File(ext, "thumb-sample.webp"), overwrite = true) }
        device.executeShellCommand("sh -c 'cp /sdcard/Android/data/com.onpath.app/files/thumb-sample.webp /sdcard/e2e/ 2>/dev/null'")
        Log.i("OnPathThumb", "map pictures: ${timings.joinToString()} ms (${files.map { it.length() / 1024 }} KB)")
        assertTrue("each well under the snapshot's 15 s limit: $timings", timings.size >= 3 && timings.all { it < 15_000 })
        // nothing again for the same lines
        scenario.onActivity { vm(it).refreshTours() }
        Thread.sleep(3_000)
        var after = emptyList<Long>()
        scenario.onActivity { after = vm(it).thumbTimings }
        assertTrue("no picture made twice: $after", after.size == timings.size)
    }
}
