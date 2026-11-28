package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-10-31 (docs/design/ui-style.md): every box (FRAGE, HINWEIS) sits 12 dp under the top element – Plan under
 * the search pill + ☰ row, Navigate under the turn / status column (the re-route question too), My routes under the
 * tabs, Settings under the title. One rule, [MessagePlacement].
 */
class MessagePlacementTest {
    private fun src(name: String) = sourceFile("ui/$name").readText()

    @Test fun twelveDpUnderTheTopElement() {
        assertEquals(12, MessagePlacement.GAP_DP)
        assertEquals(TOP_BAR_BOTTOM.value.toInt() + 12, MessagePlacement.topDp(MessageScreen.PLAN))
        assertEquals(MessagePlacement.ROUTES_HEADER_DP + 12, MessagePlacement.topDp(MessageScreen.ROUTES))
        assertEquals(MessagePlacement.SETTINGS_HEADER_DP + 12, MessagePlacement.topDp(MessageScreen.SETTINGS))
        for (h in listOf(0, 8, 120, 260, 600)) assertEquals(h + 12, MessagePlacement.topDp(MessageScreen.NAVIGATE, h))
        // not measured yet: still below the status bar, never above it
        assertEquals(12, MessagePlacement.topDp(MessageScreen.NAVIGATE, -5))
        // the small download chip sits top left where a box goes (Daumen-Karte): it shows only while no box does
        assertTrue(MessagePlacement.CHIP_TOP_DP <= MessagePlacement.topDp(MessageScreen.PLAN))
    }

    /** The headers the rule assumes: My routes title row (56 dp button + 2×4) + 48 dp tabs, Settings title row. */
    @Test fun headersMatchTheScreens() {
        val routes = src("Screens.kt").substringAfter("fun RoutesScreen(")
        // the title row is a TopBarRow (title never broken inside a word): its padding lives there
        assertTrue(routes.contains("TopBarRow("))
        assertTrue(src("TopBar.kt").contains("Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)"))
        assertTrue(routes.contains("IconButton(onClick = onBack, Modifier.size(56.dp))"))
        // the Geplant / Gemacht switch (owner 2026-11-27): 48 dp tall
        assertTrue(routes.contains("com.onpath.app.ui.routes.RoutesTabSwitch("))
        assertTrue(src("routes/RoutesTabSwitch.kt").contains("modifier.fillMaxWidth().heightIn(min = 48.dp).background(OnPathColors.PaperDeep, track)"))
        assertEquals(4 + 56 + 4 + 48, MessagePlacement.ROUTES_HEADER_DP)
        val settings = src("SettingsScreen.kt")
        assertTrue(settings.contains("Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {\n" +
            "                IconButton(onClick = { if (page != null) onUp?.invoke() ?: onPage(page.up) else onBack() }, Modifier.size(56.dp))"))
        assertEquals(4 + 56 + 4, MessagePlacement.SETTINGS_HEADER_DP)
    }

    /** Every box's buttons are big enough to hit with a glove. */
    @Test fun boxButtonsAreBigEnough() {
        assertTrue(MESSAGE_BUTTON_DP >= 48)
        assertTrue(NAVIGATE_BUTTON_DP >= 56)
    }
}
