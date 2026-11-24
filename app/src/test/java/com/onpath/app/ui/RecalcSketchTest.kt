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

/** SKETCH only (not for main): the plan menu today and with the row "Ganz neu berechnen". */
abstract class RecalcSketchTest(fontScale: Float, private val locale: Locale, private val planGroup: Boolean = false) {
    @get:Rule
    val paparazzi = ShotGallery.paparazzi(fontScale)

    private val before = Locale.getDefault()
    @Before fun setLocale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    @Composable
    private fun Cell(row: Boolean) {
        val menu = PlanMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            onUpdates = {}, onPairBrowser = {}, onHelp = {}, onSaveRoute = {}, onNewPlan = {}, onRecordWithoutPlan = {},
            onRecalcFromScratch = if (row) ({}) else null, recalcInPlanGroup = planGroup)
        OnPathTheme {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().background(PlanTopBand.Color)) { PlanTopBar({}, menu, Modifier.fillMaxWidth()) }
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                    MenuCard(Modifier) { PlanMenuItems(menu) {} }
                }
            }
        }
    }

    @Test fun menu() = paparazzi.snapshot {
        Gallery {
            cell("today") { Cell(false) }
            cell("with row") { Cell(true) }
        }
    }
}

class RecalcSketchGermanTest : RecalcSketchTest(1f, Locale.GERMANY)
class RecalcSketchGermanLargeTest : RecalcSketchTest(2f, Locale.GERMANY)
class RecalcSketchEnglishTest : RecalcSketchTest(1f, Locale.US)
class RecalcSketchEnglishLargeTest : RecalcSketchTest(2f, Locale.US)
class RecalcSketchBGermanTest : RecalcSketchTest(1f, Locale.GERMANY, true)
class RecalcSketchBGermanLargeTest : RecalcSketchTest(2f, Locale.GERMANY, true)
class RecalcSketchBEnglishTest : RecalcSketchTest(1f, Locale.US, true)
