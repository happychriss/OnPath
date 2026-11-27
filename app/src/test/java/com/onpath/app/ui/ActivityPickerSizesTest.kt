package com.onpath.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Owner 2026-11-08 (father's feedback, Galaxy S10 – the owner's S10 shows it too): on a normal or small phone the
 * activity picker showed only "T1 … T5" with no word after them, and a tap on a T closed it without saying what the
 * level means. Now the chosen level's line "T2 · Bergwandern: …" stands under the chips, and a T tap keeps the picker
 * open. Pictures at the S10's size (1080 × 2280, ≈ 411 dp) and at 360 dp, font 1.0 and 1.3, German (and English at 1.0 only, owner 2026-11-20) –
 * `verifyPaparazziDebug` (gate) keeps them; the rules below keep the line in the code.
 */
abstract class ActivityPickerSizes(widthDp411: Boolean, font: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2280,
            xdpi = if (widthDp411) 420 else 480, ydpi = if (widthDp411) 420 else 480,
            density = if (widthDp411) Density.create(420) else Density.XXHIGH, fontScale = font),
        showSystemUi = false,
    )

    protected fun shot(locale: Locale, current: Activity, sac: Int = 2) {
        val before = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            // the sheet drawn in place at the bottom over a dimmed map (Paparazzi draws no dialog window)
            paparazzi.snapshot { OnPathTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF8A9187)),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    ActivityPickerContent(current, onSelect = {}, onDismiss = {}, sac = sac, onSac = {})
                }
            } }
        } finally { Locale.setDefault(before) }
    }

    @Test fun hikeT2German() = shot(Locale.GERMANY, Activity.HIKE)
    /** Variant C (owner 2026-11-27): the bike group's cards. */
    @Test fun bikeGerman() = shot(Locale.GERMANY, Activity.BIKE_TOURING)
    /**
     * Owner's Pixel 7 2026-11-27: the sheet jumped between Rad / Wandern and T5 lay under the navigation bar. T1–T5 all
     * shown with T5 chosen; [bikeGerman] is as tall (both groups laid out). The bar itself: [ActivityPickerWiringTest].
     */
    @Test fun hikeT5German() = shot(Locale.GERMANY, Activity.HIKE, sac = 5)
}

/** Galaxy S10: 1080 × 2280 px at 420 dpi ≈ 411 dp wide. */
class ActivityPickerS10Test : ActivityPickerSizes(widthDp411 = true, font = 1.0f) {
    @Test fun hikeT2English() = shot(Locale.UK, Activity.HIKE)
}
class ActivityPickerS10LargeFontTest : ActivityPickerSizes(widthDp411 = true, font = 1.3f)
/** A small phone: 360 dp wide. */
class ActivityPicker360Test : ActivityPickerSizes(widthDp411 = false, font = 1.0f) {
    @Test fun hikeT2English() = shot(Locale.UK, Activity.HIKE)
}
class ActivityPicker360LargeFontTest : ActivityPickerSizes(widthDp411 = false, font = 1.3f)

/** The rules behind the pictures: every level a row with its short line; a T tap does not close. */
class SacLineRuleTest {
    /**
     * Owner 2026-11-26 night: every level is a row with its name and one short line, readable before the tap (Navigate's
     * activity sheet keeps [SacChips]); 2026-11-27 variant C: the picker's level cards show name + line too, and one tap
     * chooses Hike with that level and closes – the words were read before it.
     */
    @Test fun theLevelRowsShowTheirWordsBeforeTheTap() {
        val src = File("src/main/java/com/onpath/app/ui/Screens.kt").readText()
        val chips = src.substringAfter("internal fun SacChips(").substringBefore("\n}\n")
        assertTrue(chips, chips.contains("Res.string.routes_sac_row") && chips.contains("Text(l.short"))
        val picker = File("src/main/java/com/onpath/app/ui/ActivityPicker.kt").readText()
        assertTrue(picker.contains("name = l.name, line = l.short"))
        assertTrue(picker.contains("onSac = { level -> onSelect(Activity.HIKE); onSac(level); onDismiss() }"))
    }
}
