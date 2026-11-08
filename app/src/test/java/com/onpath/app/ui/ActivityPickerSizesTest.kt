package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Owner 2026-11-08 (father's feedback, Galaxy S10 – the owner's S10 shows it too): on a normal or small phone the
 * activity picker showed only "T1 … T5" with no word after them, and a tap on a T closed it without saying what the
 * level means. Now the chosen level's line "T2 · Bergwandern: …" stands under the chips, and a T tap keeps the picker
 * open. Pictures at the S10's size (1080 × 2280, ≈ 411 dp) and at 360 dp, font 1.0 and 1.3, German and English –
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

    private fun shot(locale: Locale, current: Activity) {
        val before = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            paparazzi.snapshot { OnPathTheme { ActivityDialog(current, onSelect = {}, onDismiss = {}, sac = 2) } }
        } finally { Locale.setDefault(before) }
    }

    @Test fun hikeT2German() = shot(Locale.GERMANY, Activity.HIKE)
    @Test fun hikeT2English() = shot(Locale.UK, Activity.HIKE)
}

/** Galaxy S10: 1080 × 2280 px at 420 dpi ≈ 411 dp wide. */
class ActivityPickerS10Test : ActivityPickerSizes(widthDp411 = true, font = 1.0f)
class ActivityPickerS10LargeFontTest : ActivityPickerSizes(widthDp411 = true, font = 1.3f)
/** A small phone: 360 dp wide. */
class ActivityPicker360Test : ActivityPickerSizes(widthDp411 = false, font = 1.0f)
class ActivityPicker360LargeFontTest : ActivityPickerSizes(widthDp411 = false, font = 1.3f)

/** The rules behind the pictures: a chosen level always has its line under the chips; a T tap does not close. */
class SacLineRuleTest {
    @Test fun aChosenLevelAlwaysHasItsLine() {
        for (l in 1..5) {
            assertEquals(l, SacScale.lineLevel(l))
            val line = SacScale.line(l)
            assertTrue(line, line.startsWith("T$l · ${SacScale.of(l).name}: "))
            assertTrue(line, line.endsWith(SacScale.of(l).text))
        }
        assertNull("no level chosen (another activity): no line", SacScale.lineLevel(0))
    }

    @Test fun theChipsDrawTheLineAndATapKeepsThePickerOpen() {
        val src = File("src/main/java/com/onpath/app/ui/Screens.kt").readText()
        val chips = src.substringAfter("internal fun SacChips(").substringBefore("\n}\n")
        assertTrue(chips, chips.contains("SacScale.lineLevel(selected)") && chips.contains("Text(SacScale.line(l)"))
        val dialog = src.substringAfter("fun ActivityDialog(").substringBefore("\n}\n")
        val chipTap = dialog.substringAfter("SacChips(").substringBefore("\n")
        assertTrue(chipTap, !chipTap.contains("onDismiss()"))
    }
}
