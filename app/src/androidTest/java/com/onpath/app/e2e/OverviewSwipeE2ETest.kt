package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.routes_overview_position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * Swiping through My routes' large overviews (owner 2026-11-15): a row tap opens the overview with „1 of 3“, a swipe
 * shows the next tour („2 of 3“), and the overview's map picture is kept on the phone (files/overviews/<uuid>.webp) – a
 * second opening shows the stored one, nothing is drawn again.
 */
@RunWith(AndroidJUnit4::class)
class OverviewSwipeE2ETest : E2EBase() {
    @Before fun setUp() = launch()

    @Test fun swipesThroughTheListAndKeepsThePictures() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TourDb.get(ctx)
        val uuids = (1..3).map { n ->
            val line = (0..120).map { i ->
                val a = i / 120.0 * 1.8 * Math.PI
                TrackPoint(MERAN_LAT + 0.01 * n * sin(a), MERAN_LON + 0.014 * cos(a + n), 400.0 + i)
            }
            val id = db.insertTour(TourKind.PLANNED, "Swipe $n", "HIKE", 2, 5_000.0 * n, 200.0, 200.0, 3600.0, listOf(line),
                listOf(GpxWaypoint(line.first().lat, line.first().lon, null), GpxWaypoint(line.last().lat, line.last().lon, null)))
            db.getTour(id)!!.uuid
        }
        scenario.onActivity { vm(it).openRoutes() }
        // newest first: „Swipe 3“ is the first row
        val row = findFresh(By.text("Swipe 3"), 15_000)
        assertNotNull("the newest row", row)
        row!!.click()
        assertNotNull("the pill on the first page", findFresh(By.text(Txt.s(Res.string.routes_overview_position, 1, 3)), 10_000))
        var file: java.io.File? = null
        scenario.onActivity { file = vm(it).overviewPictures.file(uuids[2]) }
        waitUntil("the first overview's picture stored", 60_000) { file!!.isFile && file!!.length() > 0 }
        // the key file is written only when a picture is made (a stored one read marks just the picture as used)
        val keyFile = java.io.File(file!!.parentFile, "${uuids[2]}.key")
        val made = keyFile.lastModified()
        shot("overview-1")
        // swipe left: the next tour
        val (w, h) = device.displayWidth to device.displayHeight
        device.swipe((w * 0.85).toInt(), (h * 0.45).toInt(), (w * 0.15).toInt(), (h * 0.45).toInt(), 20)
        assertNotNull("the pill on the second page", findFresh(By.text(Txt.s(Res.string.routes_overview_position, 2, 3)), 10_000))
        assertNotNull("the second tour's name", findFresh(By.text("Swipe 2"), 5_000))
        shot("overview-2")
        // back to the list, open the first again: the stored picture, not a new one
        device.pressBack()
        val again = findFresh(By.text("Swipe 3"), 10_000)
        assertNotNull("the list again", again)
        pause(1_100, "a new picture would have a later file time")
        again!!.click()
        assertNotNull(findFresh(By.text(Txt.s(Res.string.routes_overview_position, 1, 3)), 10_000))
        pause(3_000, "the map picture")
        shot("overview-1-again")
        assertEquals("the stored picture is shown again, not drawn anew", made, keyFile.lastModified())
    }
}
