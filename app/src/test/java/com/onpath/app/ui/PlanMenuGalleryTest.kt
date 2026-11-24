package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onpath.app.domain.RouteMenuState
import com.onpath.app.domain.RouteRules
import com.onpath.app.plansync.PlanSync
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import com.onpath.app.plansync.SyncState
import com.onpath.app.plansync.SyncProblem

/**
 * The ☰ menu of Plan in all its states, three small pictures per language (two states side by side): per cell the top bar (search pill, mode pill and ☰
 * with its dot) and under it the open menu's rows.
 *
 * Replaces eight whole-screen pictures of [ScreenshotTest] / [GermanScreenshotTest] (planMenuEmpty, planMenuNavigating
 * in English; planMenuOpen, planMenuUnsaved, planMenuPhoneSetup, planMenuSyncPaired, planMenuSyncProblem,
 * planMenuSyncUnpairedInBrowser in German) whose point was the menu's rows and the sign on ☰, not the screen: the
 * plan sheet under them has its own pictures (plan, planUnsaved, planEmpty, planTourRunning), and the card's place was
 * the test's imitation of a popup. Every state is now drawn in both languages (before: each in one).
 * planMenuUnsaved and planMenuSyncUnpairedInBrowser have no cell: their old pictures were byte for byte planMenuOpen's.
 * The menu has no sign for unsaved changes since the owner's word of 2026-11-06 ([PlanMenu.dotWarn]; the unsaved sheet
 * is in planUnsaved), and "unpaired in the browser" does not show in the menu (the state: SettingsPagesTest
 * .unpairedInTheBrowser, PairEntryTest; the dot rule: SettingsPagesTest.menuDotRule).
 */
abstract class PlanMenuGalleryTest {
    @get:Rule
    val paparazzi = ShotGallery.paparazzi()

    private val syncNow = 1_761_150_000_000L
    private val paired = SyncState(paired = true, peer = "Chrome on Linux", lastOkMs = syncNow - 60_000,
        sinceMs = syncNow - 86_400_000, checkedMs = syncNow - 60_000)
    private val withRoute get() = RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false)

    /** As MapScreen builds the menu: Save route on top with a plan, "Neu planen" with a plan, "Nur aufzeichnen" while no tour runs. */
    @Composable
    private fun MenuCell(state: RouteMenuState, phoneSetup: Boolean = false, sync: (() -> SyncMenuUi)? = null,
                          record: Boolean = true) {
        // [sync] is built inside the composition: its texts follow the test's language
        val menu = PlanMenu(state, onUpdates = {}, onPairBrowser = {}, onPhoneSetup = if (phoneSetup) ({}) else null, onHelp = {},
            sync = sync?.invoke() ?: SyncMenuUi(), onSaveRoute = if (state.save) ({}) else null,
            onNewPlan = if (state.save) ({}) else null, onRecordWithoutPlan = if (record) ({}) else null)
        OnPathTheme {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().background(PlanTopBand.Color)) { PlanTopBar({}, menu, Modifier.fillMaxWidth()) }
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                    MenuCard(Modifier) { PlanMenuItems(menu) {} }
                }
            }
        }
    }

    /** ☰ on an empty plan (My routes, Record only) and with a route (Save route on top, New plan, Record only). */
    @Test fun planMenuStates() = paparazzi.snapshot {
        Gallery {
            cell("planMenuEmpty") {
                MenuCell(RouteRules.menu(hasRoute = false, pointCount = 0, savedCount = 0, navigating = false, recording = false))
            }
            cell("planMenuOpen") { MenuCell(withRoute) }
        }
    }

    /**
     * While a tour runs (looked at from Plan): no "Record only". Something is needed from the phone (location off …):
     * red "Phone setup" first (owner 2026-10-30).
     */
    @Test fun planMenuNavigatingAndPhoneSetup() = paparazzi.snapshot {
        Gallery {
            cell("planMenuNavigating") {
                MenuCell(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = true, recording = true), record = false)
            }
            cell("planMenuPhoneSetup") { MenuCell(withRoute, phoneSetup = true) }
        }
    }

    /**
     * Paired with a browser: the item "Browser pairing" plain, no dot. A sync problem worth a look (mailbox full):
     * amber dot on ☰ and the item's sign amber.
     */
    @Test fun planMenuSyncStates() = paparazzi.snapshot {
        Gallery {
            cell("planMenuSyncPaired") { MenuCell(withRoute, sync = { SyncMenuUi.of(paired, "Pixel 8", syncNow) }) }
            cell("planMenuSyncProblem") {
                MenuCell(withRoute, sync = { SyncMenuUi.of(paired.copy(problem = SyncProblem.Refused(507, "full")), "Pixel 8", syncNow) })
            }
        }
    }
}

class PlanMenuGalleryGermanTest : PlanMenuGalleryTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
