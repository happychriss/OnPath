package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Navigate after the slide (owner 2026-11-05, Navigate C; 2026-11-06 one bottom bar): the slider only unlocks
 * ("» Entsperren"); then the tour's bottom bar "⏸ Pause | ✎ Planen" (paused "Weiter · Beenden · ✎") takes the slider's
 * place – the same as with the lock off. Without a recording only "Planen". Locks again after 30 s, on a tap on the map or
 * Android's back (also paused).
 */
class TourActionBarTest {
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
    private val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
    private val overlay = nav.substringAfter("fun NavigateOverlay(").substringBefore("\n}\n")

    private fun contrast(a: Color, b: Color): Double {
        val (l1, l2) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return l1 / l2
    }

    /**
     * Owner 2026-11-06 ("Stopp und Pause an der Stelle ist logischer"): unlocked or with the lock off, the tour has ONE
     * bottom bar ([TourBottomBar], TourBottomBarTest) – no buttons in the status card, no "Sperren" button; without a
     * recording the unlocked bottom is one blue "Planen".
     */
    @Test fun oneBottomBarNoCardButtonsNoLockButton() {
        assertTrue(overlay, overlay.contains("val tourBar = unlocked && ui.recordedM != null"))
        assertTrue(overlay, overlay.contains("if (tourBar) TourBottomBar(ui.paused, onPause, onEnd = { open = false; onEnd() }, onPlan = { open = false; onSlideToPlan() }, bottom)"))
        assertTrue(overlay, overlay.contains("else if (unlocked) ChangePlanButton({ open = false; onSlideToPlan() }, bottom)"))
        assertTrue("no buttons in the status card", overlay.contains("actions = null)"))
        assertFalse("the card actions are gone", bar.contains("fun TourCardActions("))
        val change = bar.substringAfter("fun ChangePlanButton(").substringBefore("\n}\n")
        assertTrue(change, change.contains("Res.string.nav_change_plan") && change.contains("OnPathColors.ActionEdit"))
        assertFalse("no Sperren button", change.contains("nav_lock") || change.contains("onLock"))
        assertFalse("no red", bar.contains("ActionStop") || bar.contains("OnPathColors.Error"))
        val slide = File("src/main/java/com/onpath/app/ui/SlideToUnlock.kt").readText()
        assertTrue(slide.contains("Icon(Icons.Filled.Lock, null"))
        assertTrue(overlay, overlay.contains("else SlideToPlan({ open = true }, bottom, recording = ui.recordedM != null"))
        assertFalse("the tile bar is gone", bar.contains("fun TourActionBar(") || bar.contains("fun ActionTiles("))
    }

    @Test fun readableColours() {
        assertTrue(contrast(TourActions.OnGoTonal, TourActions.GoTonal) >= 7.0)
        assertTrue(contrast(OnPathColors.CardWhite, OnPathColors.ActionEdit) >= 4.5)
        assertTrue(contrast(OnPathColors.ActionGo, OnPathColors.CardWhite) >= 4.5)
    }

    /**
     * Locks again by itself after 30 s; a tap on the map or Android's back locks it. The pause changes nothing about the
     * lock (owner 2026-11-06): "Sperren | Plan" stays after Pause, the 30 s count again from the pause, locked + paused
     * shows the slider ("Fortsetzen · Beenden · Plan"), and "Fortsetzen" is only in the card after the slide.
     */
    @Test fun locksAgainAsBeforeAlsoPaused() {
        assertEquals(30_000L, TourActions.OPEN_MS)
        assertTrue(overlay.contains("LaunchedEffect(open, lock, ui.paused) { if (lock && open && !actionsOpen) { kotlinx.coroutines.delay(TourActions.OPEN_MS); open = false } }"))
        assertTrue(overlay.contains("val unlocked = !lock || open\n"))
        assertTrue(overlay.contains("val closableBar = lock && open\n"))
        assertTrue("no pause exception any more", !overlay.contains("pausedOpen"))
        assertTrue(overlay.contains("BackHandler(enabled = closableBar) { open = false }"))
        assertTrue(overlay.contains("detectTapGestures { open = false }"))
        // Pause / Weiter keep the bar open (no onClose before onPause)
        assertTrue(bar.contains("onClick = { onPause(true) }") && bar.contains("onClick = { onPause(false) }"))
        // locked + paused: no "Weiter" past the lock; the slider says what is behind it
        assertTrue(!nav.contains("actions == null) TextButton(onClick = onResume"))
        assertTrue(overlay.contains("paused = ui.paused && ui.recordedM != null)"))
        val slider = File("src/main/java/com/onpath/app/ui/SlideToUnlock.kt").readText()
        assertTrue(slider.contains("paused -> Res.string.nav_slide_unlock_sub_paused"))
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
        assertTrue(overlay, overlay.contains("val unlocked = !lock || open\n"))
        assertTrue("no re-lock without the lock", overlay.contains("val closableBar = lock && open\n")
            && overlay.contains("if (lock && open && !actionsOpen)"))
        assertTrue("the app passes the setting", nav.contains("lock = vm.settings.navLock"))
        val settings = File("src/main/java/com/onpath/app/ui/SettingsScreen.kt").readText()
        assertTrue(settings.contains("Res.string.settings_nav_lock") && settings.contains("settings.copy(navLock = it)"))
    }

}
