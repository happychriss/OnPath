package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.routes_act_chip_desc
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Field bug 2026-11-13 (owner's Pixel): „der Filter, wenn man Fahrrad oder Wandern klickt, filtert nicht immer
 * automatisch alles“ – on the real app: My routes opens with the planning activity, every choice in the chip's menu
 * filters the list at once, exactly that activity (owner: „E-Bike nur E-Bike“).
 */
@RunWith(AndroidJUnit4::class)
class RoutesActivityFilterE2ETest : E2EBase() {
    @Before fun setUp() = launch()

    private fun gone(text: String) = waitUntil("\"$text\" gone", 5_000) { device.findObject(By.text(text)) == null }
    private fun shown(text: String) = waitUntil("\"$text\" shown", 5_000) { device.findObject(By.text(text)) != null }

    @Test fun everyChoiceFiltersTheListAtOnce() {
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val line = listOf(TrackPoint(MERAN_LAT, MERAN_LON), TrackPoint(MERAN_LAT + 0.01, MERAN_LON + 0.01))
        val stops = listOf(GpxWaypoint(MERAN_LAT, MERAN_LON, null), GpxWaypoint(MERAN_LAT + 0.01, MERAN_LON + 0.01, null))
        for ((name, a) in listOf("Filter hike" to Activity.HIKE, "Filter bike" to Activity.BIKE_TOURING, "Filter ebike" to Activity.EBIKE))
            db.insertTour(TourKind.PLANNED, name, a.name, 2, 1500.0, 10.0, 10.0, 600.0, listOf(line), stops)
        scenario.onActivity { vm(it).selectActivity(Activity.HIKE); vm(it).openRoutes() }
        shown("Filter hike"); gone("Filter bike"); gone("Filter ebike")
        fun pick(now: Activity, next: Activity) {
            device.wait(androidx.test.uiautomator.Until.findObject(By.desc(Txt.s(Res.string.routes_act_chip_desc, now.chip))), 5_000)!!.click()
            Thread.sleep(400)
            clickText(next.label)
        }
        pick(Activity.HIKE, Activity.BIKE_TOURING)
        shown("Filter bike"); gone("Filter hike"); gone("Filter ebike")
        pick(Activity.BIKE_TOURING, Activity.EBIKE)
        shown("Filter ebike"); gone("Filter bike"); gone("Filter hike")
        pick(Activity.EBIKE, Activity.HIKE)
        shown("Filter hike"); gone("Filter ebike")
        shot("hike-again")
    }
}
