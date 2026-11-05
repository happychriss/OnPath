package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Navigate after the slide (owner 2026-11-05, Navigate C): the slider only unlocks ("» Entsperren"); then "Pause"
 * (green tonal, "Fortsetzen" while paused) and "Tour beenden" (green outlined, asks first) sit directly under the route
 * info in the status card, and ONE big blue "Plan ändern" takes the slider's place. No red, words not signs.
 * Without a recording only "Plan ändern". Locks again after 30 s, on a tap on the map or Android's back (not while paused).
 */
class TourActionBarTest {
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
    private val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
    private val overlay = nav.substringAfter("fun NavigateOverlay(").substringBefore("\n}\n")

    private fun contrast(a: Color, b: Color): Double {
        val (l1, l2) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return l1 / l2
    }

    @Test fun pauseAndEndInTheCardChangeRouteInTheSlidersPlace() {
        val actions = bar.substringAfter("fun TourCardActions(").substringBefore("\n}\n")
        assertTrue(actions, actions.contains("str(if (paused) Res.string.common_resume else Res.string.nav_action_pause)"))
        // never cut: side by side while both words fit, else stacked (large font)
        assertTrue(TourActions.sideBySide(300f, 334f))
        assertFalse(TourActions.sideBySide(380f, 334f))
        assertTrue(actions, actions.contains("onClick = { onClose(); onPause(!paused) }"))
        assertTrue(actions, actions.contains("onClick = { onClose(); onEnd() }") && actions.contains("Res.string.nav_end_tour"))
        assertFalse("no red", bar.contains("ActionStop") || bar.contains("OnPathColors.Error"))
        val change = bar.substringAfter("fun ChangePlanButton(").substringBefore("\n}\n")
        assertTrue(change, change.contains("Res.string.nav_change_plan") && change.contains("OnPathColors.ActionEdit"))
        // in the card, directly under the route info; Plan ändern where the slider was
        assertTrue(overlay, overlay.contains("actions = if (unlocked && ui.recordedM != null) ({ TourCardActions(ui.paused, onPause, onEnd, onClose = { open = false }) })"))
        assertTrue(overlay, overlay.contains("ChangePlanButton({ open = false; onSlideToPlan() }, bottom)"))
        assertTrue(overlay, overlay.contains("else SlideToPlan({ open = true }, bottom, recording = ui.recordedM != null"))
        assertFalse("the tile bar is gone", bar.contains("fun TourActionBar(") || bar.contains("fun ActionTiles("))
    }

    @Test fun readableColours() {
        assertTrue(contrast(TourActions.OnGoTonal, TourActions.GoTonal) >= 7.0)
        assertTrue(contrast(OnPathColors.CardWhite, OnPathColors.ActionEdit) >= 4.5)
        assertTrue(contrast(OnPathColors.ActionGo, OnPathColors.CardWhite) >= 4.5)
    }

    /** Locks again by itself after 30 s; a tap on the map or Android's back locks it; paused it stays open. */
    @Test fun locksAgainAsBefore() {
        assertEquals(30_000L, TourActions.OPEN_MS)
        assertTrue(overlay.contains("kotlinx.coroutines.delay(TourActions.OPEN_MS); open = false"))
        assertTrue(overlay.contains("val pausedOpen = ui.paused && ui.recordedM != null"))
        assertTrue(overlay.contains("val closableBar = lock && open && !pausedOpen"))
        assertTrue(overlay.contains("BackHandler(enabled = closableBar) { open = false }"))
        assertTrue(overlay.contains("detectTapGestures { open = false }"))
        // paused: "Fortsetzen" in the card's action row, not twice
        assertTrue(nav.contains("if (ui.paused && ui.recordedM != null && actions == null) TextButton(onClick = onResume"))
    }

    @Test fun theSliderOnlyUnlocks() {
        val slider = File("src/main/java/com/onpath/app/ui/SlideToUnlock.kt").readText()
        assertTrue(slider.contains("val label = str(Res.string.nav_slide_unlock)"))
        assertTrue(slider.contains("val mainText = \"»  \$label\""))
        assertTrue(slider.contains("str(if (recording) Res.string.nav_slide_unlock_desc else Res.string.nav_slide_unlock_desc_plan)"))
        for (dir in listOf("values", "values-de")) {
            val s = File("../strings/src/commonMain/composeResources/$dir/strings_navigate.xml").readText()
            assertTrue(dir, s.contains("name=\"nav_slide_unlock\"") && s.contains("name=\"nav_change_plan\""))
        }
    }

    /** The plan sheet during a tour has no tile / pill bar (owner 2026-11-05, R5): one green "Weiter geht's". */
    @Test fun thePlanHasNoTourBar() {
        assertTrue(!bar.contains("fun TourEditBar(") && !bar.contains("fun ActionPills("))
    }

    /**
     * Settings → Navigation "Sperre beim Navigieren" (owner 2026-11-05): ON by default ("damit nichts Dummes passiert");
     * off = no slider, Pause / Tour beenden and the big blue "Plan ändern" at once, never locking again.
     */
    @Test fun theLockIsASettingOnByDefault() {
        assertTrue(com.onpath.app.data.AppSettings().navLock)
        val prefs = File("src/main/java/com/onpath/app/data/Prefs.kt").readText()
        assertTrue(prefs, prefs.contains("navLock = sp.getBoolean(\"navLock\", true)") && prefs.contains(".putBoolean(\"navLock\", v.navLock)"))
        assertTrue(overlay, overlay.contains("val unlocked = !lock || open || pausedOpen"))
        assertTrue("no re-lock without the lock", overlay.contains("val closableBar = lock && open && !pausedOpen")
            && overlay.contains("if (lock && open && !actionsOpen)"))
        assertTrue("the app passes the setting", nav.contains("lock = vm.settings.navLock"))
        val settings = File("src/main/java/com/onpath/app/ui/SettingsScreen.kt").readText()
        assertTrue(settings.contains("Res.string.settings_nav_lock") && settings.contains("settings.copy(navLock = it)"))
    }

}
