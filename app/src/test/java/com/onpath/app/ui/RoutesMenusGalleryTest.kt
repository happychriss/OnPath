package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import com.onpath.app.ui.routes.ActivityFilterItems
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The ⋮ menus of My routes and the activity chip's menu as component pictures: the real menu items in [MenuCard] (the
 * imitation of Material's drop-down; where the app opens a menu is never checked here). The list behind the menu in the
 * old pictures is the real RoutesScreen – it is still drawn by other scenes in the same state: routesActivityMenu's list
 * = [ScreenshotTest.routesActivityHike] (English), [GermanScreenshotTest.routesActivityHike] and
 * [RouteSortLargeFontGermanTest.routesActivityHike] (German 2.0); routesItemMenu*'s lists = tours / toursDone of
 * [GermanScreenshotTest] (German 1.0).
 *
 * Replaces: ScreenshotTest.routesActivityMenu, GermanScreenshotTest.routesActivityMenu,
 * RouteSortLargeFontGermanTest.routesActivityMenu, GermanScreenshotTest.routesItemMenuPlanned and
 * routesItemMenuDoneWithPlan. The English routesItemMenu* ([ScreenshotTest]) and both languages at font 2.0
 * ([SlideLabelLargeFontTest]) stay whole-screen pictures – no other picture shows their lists – the cells here repeat
 * their menus.
 */
abstract class RoutesMenusGalleryTest(fontScale: Float = 1f) {
    @get:Rule
    val paparazzi = ShotGallery.paparazzi(fontScale)

    /** The chip's menu: the six (single choice), a line, "Alle" – the chip's choice "Hike" ticked. */
    @Composable
    private fun ActivityMenu() = OnPathTheme {
        Box(Modifier.padding(8.dp)) { MenuCard(Modifier) { ActivityFilterItems(Activity.HIKE) {} } }
    }

    /**
     * My routes → ⋮ of a planned route (owner 2026-10-31): "Planen" and "Los geht's" on top, both in the plan blue (owner
     * 2026-11-07 asked for them on top; 2026-11-13 made every go in Plan blue), then Rename, Save as copy, Export GPX, Share plan, Delete.
     */
    @Composable
    private fun PlannedMenu() = OnPathTheme {
        Box(Modifier.padding(8.dp)) {
            MenuCard(Modifier) {
                TourMenuItems(routesFixtureTours.first { it.kind == TourKind.PLANNED }, {}, {}, {}, null, {}, {},
                    onPlan = {}, onGo = {}) {}
            }
        }
    }

    /**
     * Gemacht → ⋮ of a recorded tour that followed a plan (owner 2026-11-07): "Planen" and "Los geht's" (both plan blue) on
     * top as for a saved planned route, then Rename, Export GPX, Edit rides, Delete – no "Als Plan übernehmen".
     */
    @Composable
    private fun DoneWithPlanMenu() = OnPathTheme {
        Box(Modifier.padding(8.dp)) {
            MenuCard(Modifier) {
                TourMenuItems(routesFixtureTours[1].copy(hasPlan = true), {}, {}, {}, onEditRides = {}, onCopy = null, onShare = null,
                    onPlan = {}, onGo = {}) {}
            }
        }
    }

    @Test fun routesActivityMenuAndItemMenuPlanned() = paparazzi.snapshot {
        Gallery {
            cell("routesActivityMenu") { ActivityMenu() }
            cell("routesItemMenuPlanned") { PlannedMenu() }
        }
    }

    @Test fun routesItemMenuDoneWithPlan() = paparazzi.snapshot {
        Gallery { cell("routesItemMenuDoneWithPlan") { DoneWithPlanMenu() } }
    }
}

class RoutesMenusGalleryGermanTest : RoutesMenusGalleryTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** German at the phone's largest font (2.0): the menu's words wrap, nothing is cut. */
class RoutesMenusGalleryGermanLargeFontTest : RoutesMenusGalleryTest(2.0f) {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
