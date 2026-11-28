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
import com.onpath.app.domain.RouteRules
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The ☰ menu without and with the row "Ganz neu berechnen" (owner 2026-11-25, picture B: in the plan group after "Neu planen",
 * route icon): German, English and German at a double font (no clipped or wrapped row). The row's rules: PlanRecalcFromScratchTest
 * (state), its place and tap: PlanActionsTest.
 */
abstract class PlanMenuRecalcGalleryTest(fontScale: Float, private val locale: Locale) {
    @get:Rule
    val paparazzi = ShotGallery.paparazzi(fontScale)

    private val before = Locale.getDefault()
    @Before fun setLocale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    @Composable
    private fun Cell(row: Boolean) {
        val menu = PlanMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            onPairBrowser = {}, onHelp = {}, onSaveRoute = {}, onNewPlan = {}, onRecordWithoutPlan = {},
            onRecalcFromScratch = if (row) ({}) else null)
        OnPathTheme {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                    MenuCard(Modifier) { PlanMenuItems(menu) {} }
                }
            }
        }
    }

    @Test fun menu() = paparazzi.snapshot {
        Gallery {
            cell("planMenuNoRecalc") { Cell(false) }
            cell("planMenuRecalc") { Cell(true) }
        }
    }
}

class PlanMenuRecalcGalleryGermanTest : PlanMenuRecalcGalleryTest(1f, Locale.GERMANY)
class PlanMenuRecalcGalleryGermanLargeTest : PlanMenuRecalcGalleryTest(2f, Locale.GERMANY)
class PlanMenuRecalcGalleryEnglishTest : PlanMenuRecalcGalleryTest(1f, Locale.US)
