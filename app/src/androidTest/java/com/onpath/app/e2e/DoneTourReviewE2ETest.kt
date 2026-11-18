package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.domain.RoutesTab
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.common_export_gpx
import com.onpath.strings.routes_review_details
import com.onpath.strings.routes_review_highest
import com.onpath.strings.routes_review_longest_pause
import com.onpath.strings.routes_review_on_the_way
import com.onpath.strings.routes_review_share
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * A done tour's look back in its large overview (owner 2026-11-15, round 3 of the tour analysis): a recorded walk with a
 * long stop opens with its highlights (longest pause, highest point), „Details ›“ opens the further numbers, the share
 * icon offers "GPX exportieren".
 */
@RunWith(AndroidJUnit4::class)
class DoneTourReviewE2ETest : E2EBase() {
    @Before fun setUp() = launch()

    @Test fun aDoneTourShowsItsLookBack() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TourDb.get(ctx)
        val t0 = System.currentTimeMillis() - 3 * 3600_000L
        // a 4 km loop at ~1.2 m/s climbing 200 m to its far side, with a 12 min stop half way
        val walk = (0..330).map { i ->
            val a = i / 330.0 * 2 * Math.PI
            TrackPoint(MERAN_LAT + 0.0057 * sin(a), MERAN_LON + 0.0083 * (1 - cos(a)), 400.0 + 100 * (1 - cos(a)),
                t0 + i * 10_000L + if (i > 165) 12 * 60_000L else 0L)
        }
        val stop = (1..71).map { k -> walk[165].copy(timeMs = walk[165].timeMs!! + k * 10_000L) }
        val pts = walk.take(166) + stop + walk.drop(166)
        var activity = "HIKE"; var sac = 2
        scenario.onActivity { activity = vm(it).activity.name; sac = vm(it).sac }
        val name = "E2E look back"
        val id = db.insertTour(TourKind.RECORDED, name, activity, sac, 4_000.0, 200.0, 200.0, 3600.0, listOf(pts))
        try {
            scenario.onActivity { vm(it).routesTab = RoutesTab.DONE; vm(it).openRoutes() }
            val row = findFresh(By.text(name), 20_000)
            assertNotNull("the done tour's row – ${state()}", row)
            row!!.click()
            assertNotNull("its longest pause", findFresh(By.text(Txt.s(Res.string.routes_review_longest_pause)), 15_000))
            assertNotNull("its highest point", findFresh(By.text(Txt.s(Res.string.routes_review_highest)), 5_000))
            pause(3_000, "the map picture") // the map picture
            shot("1-look-back")
            clickText(Txt.s(Res.string.routes_review_details))
            assertNotNull("the Details sheet", findFresh(By.text(Txt.s(Res.string.routes_review_on_the_way)), 5_000))
            shot("2-details")
            device.pressBack()
            device.wait(androidx.test.uiautomator.Until.gone(By.text(Txt.s(Res.string.routes_review_on_the_way))), 5_000)
            findFresh(By.desc(Txt.s(Res.string.routes_review_share)), 5_000)!!.click()
            assertNotNull("share → GPX exportieren", findFresh(By.text(Txt.s(Res.string.common_export_gpx)), 5_000))
            shot("3-share")
            device.pressBack()
        } finally {
            db.deleteTour(id)
        }
    }
}
