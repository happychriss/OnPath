package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-30: the blue strips sit a bit lower (not glued under the search bar) and never cover the plan sheet's
 * primary button or the turn card; their buttons are proper 48 dp buttons.
 */
class MessagePlacementTest {
    private val ui = "src/main/java/com/onpath/app/ui/"
    private fun src(name: String) = File(ui + name).readText()

    @Test fun stripsSitBelowTheSearchBarWithAGap() {
        // search pill + ☰ row ends at TOP_BAR_BOTTOM (76 dp): the strips start well below it, the chip stays right under ☰
        assertTrue(MessagePlacement.STRIP_TOP_DP >= TOP_BAR_BOTTOM.value.toInt() + 12)
        assertTrue(MessagePlacement.CHIP_TOP_DP < MessagePlacement.STRIP_TOP_DP)
        assertTrue(MessagePlacement.MESSAGE_TOP_DP == MessagePlacement.STRIP_TOP_DP)
    }

    /** The plan sheet is drawn after the strips in PlanMode: a strip can never lie over the sheet's primary button. */
    @Test fun planSheetIsDrawnOverTheStrips() {
        val plan = src("MapScreen.kt").substringAfter("private fun PlanMode(")
        val strip = plan.indexOf("PlanBackgroundStrip(vm")
        val sheet = plan.indexOf("SheetWithFooter(")
        assertTrue("strip $strip, sheet $sheet", strip in 0 until sheet)
    }

    /** Navigate: the error card goes below the turn card + status column, never over the turn card. */
    @Test fun navigateErrorCardBelowTheTurnCard() {
        for (h in listOf(0, 8, 120, 260, 600)) assertTrue(MessagePlacement.navigateTopDp(h) >= h + MessagePlacement.GAP_DP)
        val host = src("Notices.kt").substringAfter("fun androidx.compose.foundation.layout.BoxScope.ErrorCardHost(")
            .substringBefore("\n}\n")
        assertTrue(host.contains("MessagePlacement.navigateTopDp(vm.navigateTopDp)"))
        val nav = src("NavigateScreen.kt")
        assertTrue(nav.contains("onTopHeightDp = { vm.navigateTopDp = it }"))
        // the measured column holds the turn card
        val overlay = nav.substringAfter("fun NavigateOverlay(")
        assertTrue(overlay.indexOf(".onSizeChanged { onTopHeightDp(") in 0 until overlay.indexOf("TurnCard(it)"))
    }

    /** One button for the strips and cards: 48 dp, used by every map strip, the error card, the chip and the update card. */
    @Test fun stripsAndCardsUseTheSharedButton() {
        assertTrue(NOTICE_BUTTON_DP >= 48)
        val notice = src("BackgroundDownloads.kt").substringAfter("private fun Notice(")
        assertTrue(notice.contains("NoticeButtons(buttons"))
        assertTrue(src("Notices.kt").substringAfter("fun ErrorCard(").substringBefore("\n}\n").contains("NoticeButton("))
        assertTrue(src("NotificationsOffChip.kt").contains("NoticeButton(str(Res.string.common_ok), onOk, NoticeTone.AMBER"))
        assertTrue(src("UpdateUi.kt").substringAfter("fun UpdateBanner(").substringBefore("\n}\n").contains("NoticeButtons("))
        assertTrue(src("CellCard.kt").substringAfter("fun QuietPill(").substringBefore("\n}\n").contains("NoticeButton("))
    }
}
