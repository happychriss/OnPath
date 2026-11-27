package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner's Pixel 7 2026-11-27 23:52 (Plan during a bike tour): a tap on the activity opened „ein komplett anderes Menü“ –
 * the old list with T chips – not the new picker (variant C). Every way to the activity during a tour (Plan's sheet and
 * Navigate's icon both open [ActivitySwitchSheet]) now shows the picker's look: Rad | Wandern, the cards, T1–T5.
 */
class ActivitySwitchLookTest {
    private val sheet = File("src/main/java/com/onpath/app/ui/ActivitySwitchSheet.kt").readText()

    @Test fun theTourSheetLooksLikeThePicker() {
        val body = sheet.substringAfter("fun ActivitySwitchSheet(").substringBefore("\n/** A bike activity's card")
        assertTrue("Rad | Wandern", body.contains("GroupSwitch(hike) { hike = it }"))
        assertTrue("the bike cards", body.contains("BIKES.forEach { a -> SwitchCard(ui, a, !hike, onChoose) }"))
        assertTrue("T1–T5 as cards", body.contains("SacScale.levels.forEach { l -> LevelSwitchCard(ui, l, hike, onHikeLevel) }"))
        assertTrue("no jump: both groups laid out", body.contains("if (hike) { bikes(); levels() } else { levels(); bikes() }"))
        assertFalse("not the old T chips", body.contains("SacChips("))
        assertTrue("the cards are the picker's", sheet.contains("ChoiceCard(current, enabled = shown && row?.choosable == true"))
    }

    @Test fun everyWayDuringATourOpensThisSheet() {
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(map.contains("ActivitySwitchSheet(it, vm.activityCheckSlice::chooseActivity"))
        assertTrue(nav.contains("ActivitySwitchSheet(it, onChooseActivity, onKeepActivity"))
    }
}
