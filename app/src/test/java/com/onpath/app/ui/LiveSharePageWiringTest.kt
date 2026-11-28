package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live sharing's own page (Chriss 2026-11-28 14:11): every way in lands on it, „Teilen beenden“ only while sharing,
 * a new name reaches the shared page, and nothing else of the sharing stays as loose rows in Settings.
 */
class LiveSharePageWiringTest {
    private fun src(p: String) = File("src/main/java/com/onpath/app/$p").readText()

    @Test fun everyWayInLandsOnThePage() {
        val w = src("ui/LiveShareWiring.kt")
        assertTrue("the tour bubble's share button", w.contains("LiveShareButton(slice.sign, size) { vm.settingsSlice.openSettings(SettingsPage.LIVE) }"))
        assertTrue("the waiting pill", w.contains("LiveWaitingPill(slice.sign) { vm.settingsSlice.openSettings(SettingsPage.LIVE) }"))
        assertTrue("the ⚙ row on the way", w.contains("val open = { vm.settingsSlice.settingsPage = SettingsPage.LIVE }") && w.contains("LiveShareRow(slice.sign, open)"))
        assertTrue("the whole list", src("ui/SettingsScreen.kt").contains("l.row { onPage(SettingsPage.LIVE) }"))
        assertTrue("the map column's menu uses the same button", src("ui/MapFooter.kt").contains("tourShare(size)"))
        // no own menu, no loose rows any more
        val ui = src("ui/LiveShareUi.kt")
        assertFalse(ui.contains("fun LiveSignMenu(") || ui.contains("fun LiveSettingsCard("))
    }

    @Test fun stopOnlyWhileSharingAndTheThreePoints() {
        val p = src("ui/LiveSharePage.kt").substringAfter("fun LiveSharePage(")
        assertTrue(p.contains("if (LiveAction.STOP in actions)"))
        assertTrue(p.contains("Res.string.live_name_row") && p.contains("Res.string.live_share_again") && p.contains("Res.string.live_stop"))
    }

    @Test fun aNewNameReachesTheSharedPage() {
        assertTrue(File("../state/src/main/kotlin/com/onpath/app/ui/live/LiveShareSlice.kt").readText().contains("if (edit && sharingNow) client.liveRename(n)"))
        assertTrue(src("live/LiveEdge.kt").contains("override fun liveRename(name: String) { LiveEdge.attach(app)?.rename(name) }"))
        assertTrue(src("live/LiveLane.kt").contains("uploader.rename(name)"))
    }
}
