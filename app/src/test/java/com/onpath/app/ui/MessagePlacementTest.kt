package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31 (docs/design/ui-style.md): every box (FRAGE, HINWEIS) sits 12 dp under the top element – Plan under
 * the search pill + ☰ row, Navigate under the turn / status column (the re-route question too), My routes under the
 * tabs, Settings under the title. One rule, [MessagePlacement].
 */
class MessagePlacementTest {
    private val ui = "src/main/java/com/onpath/app/ui/"
    private fun src(name: String) = File(ui + name).readText()

    @Test fun twelveDpUnderTheTopElement() {
        assertEquals(12, MessagePlacement.GAP_DP)
        assertEquals(TOP_BAR_BOTTOM.value.toInt() + 12, MessagePlacement.topDp(MessageScreen.PLAN))
        assertEquals(MessagePlacement.ROUTES_HEADER_DP + 12, MessagePlacement.topDp(MessageScreen.ROUTES))
        assertEquals(MessagePlacement.SETTINGS_HEADER_DP + 12, MessagePlacement.topDp(MessageScreen.SETTINGS))
        for (h in listOf(0, 8, 120, 260, 600)) assertEquals(h + 12, MessagePlacement.topDp(MessageScreen.NAVIGATE, h))
        // not measured yet: still below the status bar, never above it
        assertEquals(12, MessagePlacement.topDp(MessageScreen.NAVIGATE, -5))
        // the small download chip stays right under ☰, above where a box goes
        assertTrue(MessagePlacement.CHIP_TOP_DP < MessagePlacement.topDp(MessageScreen.PLAN))
    }

    /** The headers the rule assumes: My routes title row (56 dp button + 2×4) + 48 dp tabs, Settings title row. */
    @Test fun headersMatchTheScreens() {
        val routes = src("Screens.kt").substringAfter("fun RoutesScreen(")
        assertTrue(routes.contains("Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)"))
        assertTrue(routes.contains("IconButton(onClick = onBack, Modifier.size(56.dp))"))
        assertTrue(routes.contains("modifier = Modifier.heightIn(min = 48.dp),"))
        assertEquals(4 + 56 + 4 + 48, MessagePlacement.ROUTES_HEADER_DP)
        val settings = src("SettingsScreen.kt")
        assertTrue(settings.contains("Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {\n" +
            "                IconButton(onClick = { if (page != null) onPage(null) else onBack() }, Modifier.size(56.dp))"))
        assertEquals(4 + 56 + 4, MessagePlacement.SETTINGS_HEADER_DP)
    }

    /** The plan sheet is drawn after the box in PlanMode: a box can never lie over the sheet's primary button. */
    @Test fun planSheetIsDrawnOverTheBox() {
        val plan = src("MapScreen.kt").substringAfter("private fun PlanMode(")
        val host = plan.indexOf("PlanMessageHost(vm")
        val sheet = plan.indexOf("SheetWithFooter(")
        assertTrue("host $host, sheet $sheet", host in 0 until sheet)
    }

    /** Every screen uses the one rule; the Navigate question sits at the top now, under the measured column. */
    @Test fun everyHostUsesTheRule() {
        assertTrue(src("MapScreen.kt").contains("MessagePlacement.topDp(MessageScreen.PLAN)"))
        val nav = src("NavigateScreen.kt").substringAfter("fun NavigateOverlay(").substringBefore("\n}\n")
        assertTrue(nav.contains("MessagePlacement.topDp(MessageScreen.NAVIGATE, topColumnDp)"))
        assertTrue(nav.indexOf(".onSizeChanged { topColumnDp =") in 0 until nav.indexOf("TurnCard(it)"))
        // the question is no longer at the bottom, above the slide switch; it is drawn over the map controls
        val bottom = nav.substringAfter("Modifier.align(Alignment.BottomCenter)").substringBefore("SlideToPlan(")
        assertFalse(bottom.contains("rerouteMessage("))
        val host = src("Notices.kt").substringAfter("fun androidx.compose.foundation.layout.BoxScope.ScreenMessageHost(")
            .substringBefore("\n}\n")
        assertTrue(host.contains("MessageScreen.SETTINGS") && host.contains("MessageScreen.ROUTES"))
        assertTrue(host.contains("MessagePlacement.topDp(screen)"))
        assertTrue(File("src/main/java/com/onpath/app/MainActivity.kt").readText().contains("ScreenMessageHost(vm)"))
    }

    /** The old strips, chips and cards are gone: every box is a MessageCard. */
    @Test fun oneBoxComposable() {
        val all = File(ui).walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        val gone = listOf("fun ErrorCard(", "fun NoticeButton(", "fun MapNoticeStrip(", "fun HereAskStrip(", "fun OkNoteStrip(",
            "fun ActionStrip(", "fun SyncNoteStrip(", "fun PairHintStrip(", "fun NotificationsOffChip(", "fun RerouteQuestionCard(",
            "fun UpdateBanner(")
        all.forEach { (name, text) -> gone.forEach { assertFalse("$name still has $it", text.contains(it)) } }
        assertTrue(MESSAGE_BUTTON_DP >= 48)
        assertTrue(NAVIGATE_BUTTON_DP >= 56)
    }
}
