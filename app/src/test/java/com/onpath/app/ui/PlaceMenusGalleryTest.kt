package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.domain.MapGestures
import com.onpath.app.poi.Poi
import com.onpath.app.recording.GpsStatus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The menus of a held spot / place / route point on the plan map – their rows, texts, tint and fit – as component
 * pictures, two per picture. Each cell is the real menu composable alone, at the width the map's overlays give it
 * ([AnchoredOverlay] / [HoldAddOverlay] allow 320 dp); where the menu sits at the spot, near an edge or beside the ring is
 * NOT checked here: that stays with the whole-screen scenes [ScreenshotTest.pointMenuMiddlePoi],
 * [ScreenshotTest.pointMenuDestination] ([AnchoredOverlay]: right edge, top edge), [ScreenshotTest.holdAddMenu] and
 * [ScreenshotTest.holdAddMenuGoalFirst] ([HoldAddOverlay]: ring on the left / on the right of the middle), and, at the
 * largest font, [PlaceAddMenuLargeFontGermanTest.holdAddMenu]; the found place's menu above the pin or as a card:
 * [ScreenshotTest.sharedPlaceMenu] (German) and [FoundPlaceMenuLargeFontGermanTest].
 *
 * Replaces whole-screen pictures (spot, dot and map placeholder were the test's frame) of: firstPointMenu,
 * firstPointMenuNoGps, poiMenu, poiMenuClearGoal, poiMenuClearStop, poiMenuEmptyPlan, pointMenuStart (English),
 * poiMenu, poiMenuClearGoal, poiMenuClearStop, holdAddMenu, holdAddMenuGoalFirst (German) and poiMenu, poiMenuClearGoal,
 * poiMenuClearStop, holdAddMenu at the font 1.3 (German; [PlaceAddMenuMidFontGermanTest] is gone) and poiMenuClearGoal,
 * poiMenuClearStop at 2.0 (German). [PlaceAddMenuLargeFontGermanTest] keeps poiMenu and holdAddMenu at 2.0 as whole
 * screens (the placement: clamp to the top bound, beside the ring); their cells here check the rows' text fit. The font sizes 1.3 / 2.0 are device configs of their own
 * ([ShotGallery.paparazzi]), the cells of the other classes are drawn at 1.0.
 */
abstract class PlaceMenusGalleryTest(fontScale: Float = 1f) {
    @get:Rule
    val paparazzi = ShotGallery.paparazzi(fontScale)

    /** A menu as the map's overlay hosts size it: at most 320 dp wide; a little air round it for its shadow. */
    @Composable
    protected fun InOverlay(menu: @Composable () -> Unit) = OnPathTheme {
        Box(Modifier.padding(8.dp).widthIn(max = 320.dp)) { menu() }
    }

    private val inDoubt = listOf(PlaceAddMenu.Item.ADD_STOP, PlaceAddMenu.Item.NEW_DESTINATION)

    /** The held POI's menu (PoiMenu) with the add rows [items]. */
    @Composable
    protected fun PlaceAdd(vararg items: PlaceAddMenu.Item) = InOverlay {
        PoiMenu(Poi("n1", "cafe", "Waldcafé", 46.68, 11.25), onAdd = {}, onGoogle = {}, menu = PlaceAddMenu.Menu(items.toList()))
    }

    /**
     * Hold on a POI in Plan (owner 2026-11-15, variant A; 2026-11-17). In doubt (both about equal), inserting the cheaper:
     * „Insert“ first and tinted, „New destination“ plain under it. Past the goal, appending clearly shorter: only „New
     * destination“.
     */
    @Test fun poiMenuAndClearGoal() = paparazzi.snapshot {
        Gallery {
            cell("poiMenu") { PlaceAdd(*inDoubt.toTypedArray()) }
            cell("poiMenuClearGoal") { PlaceAdd(PlaceAddMenu.Item.NEW_DESTINATION) }
        }
    }

    /**
     * Beside the route or on it, inserting clearly shorter (owner 2026-11-17): only „Insert“. A hold on the empty map in
     * doubt (owner 2026-11-15): only the two rows, the likelier first and tinted, no title.
     */
    @Test fun poiMenuClearStopAndHoldAddMenu() = paparazzi.snapshot {
        Gallery {
            cell("poiMenuClearStop") { PlaceAdd(PlaceAddMenu.Item.ADD_STOP) }
            cell("holdAddMenu") { InOverlay { HoldAddMenu(PlaceAddMenu.Menu(inDoubt), onAddStop = {}, onNewDestination = {}) } }
        }
    }
}

/** The English and the German 1.0 galleries also hold the menus of the empty plan, the first point and the route point. */
abstract class PlaceMenusGalleryFullTest : PlaceMenusGalleryTest() {
    /**
     * Same two rows with the other one first (the second case of the hold menu: the spot right of the middle, goal
     * first). Hold on a POI with an empty plan: same choice as the first-point menu.
     */
    @Test fun holdAddMenuGoalFirstAndPoiMenuEmptyPlan() = paparazzi.snapshot {
        Gallery {
            cell("holdAddMenuGoalFirst") {
                InOverlay {
                    HoldAddMenu(PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.NEW_DESTINATION, PlaceAddMenu.Item.ADD_STOP)),
                        onAddStop = {}, onNewDestination = {})
                }
            }
            cell("poiMenuEmptyPlan") {
                InOverlay {
                    PoiMenu(Poi("n1", "hut", "Meraner Hütte", 46.68, 11.25, ele = 1960), onAdd = {}, onGoogle = {}, emptyPlan = true)
                }
            }
        }
    }

    /**
     * First hold on an empty plan: Start here · Go here (from my location) (position known). Same without a position:
     * "Go here" disabled with the reason.
     */
    @Test fun firstPointMenuAndNoGps() = paparazzi.snapshot {
        Gallery {
            cell("firstPointMenu") { InOverlay { FirstPointMenu(null, onStartHere = {}, onFromMe = {}) } }
            cell("firstPointMenuNoGps") { InOverlay { FirstPointMenu(MapGestures.fromMeBlocked(GpsStatus.SEARCHING, false), onStartHere = {}, onFromMe = {}) } }
        }
    }

    /** Hold on the start (unnamed): only Delete – it already is the start, it can't be the end of the route. */
    @Test fun pointMenuStart() = paparazzi.snapshot {
        Gallery { cell("pointMenuStart") { InOverlay { PointMenu(0, 4, null) {} } } }
    }
}

class PlaceMenusGalleryGermanTest : PlaceMenusGalleryFullTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** German at font size 1.3 (a common "larger" setting): one line each, a label wraps rather than being cut. */
class PlaceMenusGalleryGermanMidFontTest : PlaceMenusGalleryTest(1.3f) {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** German at the phone's largest font (2.0). */
class PlaceMenusGalleryGermanLargeFontTest : PlaceMenusGalleryTest(2.0f) {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
