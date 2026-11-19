package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The message boxes and map notices that Plan draws in its top slot (under the search bar, over the map; [MessagePlacement]),
 * as component pictures – the real [MessageCard] / [mapNoticeMessage] alone, as wide as on the phone. Each cell is
 * wrapped in the top slot's side margin ([MessagePlacement.GAP_DP] on both sides of the screen), so the texts break
 * exactly as in the whole-screen pictures. English and German at the font 1.0, German at the largest font (2.0).
 *
 * What is NOT checked here (stays with whole-screen pictures): where the box sits (12 dp under the search bar + ☰ row), what
 * it covers or leaves free (the activity button, the map controls), the plan sheet / footer around it, and how that
 * behaves at the largest font – [ScreenshotTest.hereAsk] (tall FRAGE with the empty plan, English),
 * [ScreenshotTest.cellCardFirstStart] (HINWEIS with a title on two lines, English), [GermanScreenshotTest.planPairHint] (tall
 * FRAGE, German), [GermanScreenshotTest.planUnpairedOfflineNote] (HINWEIS, three lines, German),
 * [GermanScreenshotTest.planSlowAsk] (FRAGE, its own sheet), [PairedNoteLargeFontGermanTest.planUnpairedOfflineNote] (HINWEIS
 * at font 2.0: title + four lines + OK on its own row, over the map and the scale bar) and
 * [MessageCardLargeFontTest.planPairHint] (the tallest box at 2.0). Unit tests of the rules: [MessagePlacementTest] (the
 * 12 dp under the top element, one box composable), [MessagesTest] (one box at a time, the edge colours),
 * [MapNoticeTest] (which map state is a box, which the quiet pill) and [CellCardsTest].
 *
 * Replaces whole-screen pictures of: updateBanner, cellCardMobileQuestion, cellCardWifiOnlyWaiting, cellCardFailed,
 * cellCardNotAvailable (English), planPairedNote, planUnpairedNote (German) and, at the font 2.0, planPairedNote,
 * planUnpairedNote (German).
 */
abstract class PlanMessagesGalleryTest(fontScale: Float = 1f, private val boxWidth: Dp = BOX_WIDTH_PHONE) {
    @get:Rule
    val paparazzi = ShotGallery.paparazzi(fontScale)

    /** A box as the top slot lays it out: [MessagePlacement.GAP_DP] from the screen's left edge, [boxWidth] wide. */
    @Composable
    protected fun TopSlot(box: @Composable () -> Unit) = OnPathTheme {
        Box(Modifier.padding(start = MessagePlacement.GAP_DP.dp).width(boxWidth)) { box() }
    }

    /** What the map shows for a cell card, exactly as CardFrame of [ScreenshotTest] did: the box, or the quiet pill. */
    @Composable
    protected fun MapNotice(card: CellCardUi?) = TopSlot {
        mapNotice(card)?.let { n -> mapNoticeMessage(n, CellCardCallbacks())?.let { MessageCard(it) } ?: MapNoticePill(n) }
    }

    protected companion object {
        /** ScreenshotTest's screen: 1024 px at 420 dpi = 390.1 dp, less the top slot's two margins of 12 dp. */
        val BOX_WIDTH_PHONE: Dp = 366.dp
        /** A Pixel 5 (1080 px at 440 dpi = 392.7 dp) with a font of its own, less the margins: the large-font pictures. */
        val BOX_WIDTH_PIXEL_5: Dp = (1080 / 2.75f - 2 * MessagePlacement.GAP_DP).dp
    }
}

/** English at the font 1.0: the boxes of the new version and of the map for here. */
class PlanMessagesGalleryEnglishTest : PlanMessagesGalleryTest() {
    private val release = com.onpath.app.update.AppUpdater.Release(
        "0.4.1", "- Trail names along the paths\n- Update button in Settings", "https://x/onpath.apk", 32_000_000, "https://x")

    /**
     * FRAGE (blue edge): the new version (title + "kept" line, Later · Install); the app's mobile-data question (three areas, 25 MB: the
     * areas wait, Wi-Fi only · Load now – stays until answered); with "Maps only on Wi-Fi" on, the strip says the areas wait and
     * offers to load them once over mobile data (owner Q6: Not now · the long primary label must fit); a map that
     * failed: ⚠ HINWEIS amber with Try again.
     */
    @Test fun updateBannerAndMobileQuestionAndWifiAndFailed() = paparazzi.snapshot {
        Gallery {
            cell("updateBanner") { TopSlot { MessageCard(updateMessage(release.version, {}, {})) } }
            cell("cellCardMobileQuestion") {
                MapNotice(CellCardShots.card(CellCardShots.mobileQuestion, job = CellCardShots.hereJob4,
                    sizes = mapOf(CellCardShots.own to 10 * CellCardShots.mb)))
            }
            cell("cellCardWifiOnlyWaiting") {
                MapNotice(CellCardShots.card(CellCardShots.wifiOnlyWaiting, job = CellCardShots.hereJob3,
                    sizes = mapOf(CellCardShots.own to 10 * CellCardShots.mb), wifiOnly = true))
            }
            cell("cellCardFailed") {
                MapNotice(CellCardShots.card(CellCardShots.failed, sizes = CellCardShots.job.cells.associateWith { 9 * CellCardShots.mb }))
            }
        }
    }

    /** No downloads in this build: the grey HINWEIS with OK (title on two lines, OK beside it). */
    @Test fun cellCardNotAvailable() = paparazzi.snapshot {
        Gallery { cell("cellCardNotAvailable") { MapNotice(CellCardShots.card(emptyMap(), available = false)) } }
    }
}

/** German at the font 1.0: the two notes after pairing / unpairing (grey HINWEIS, OK beside the text). */
class PlanMessagesGalleryGermanTest : PlanMessagesGalleryTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /**
     * Right after Pair (owner 2026-10-30): back on the Plan map, "Verbunden. …" with OK at the top until OK; right after
     * Unpair (owner 2026-10-30, as simple as pairing): "Getrennt. …" with OK.
     */
    @Test fun planPairedNoteAndUnpairedNote() = paparazzi.snapshot {
        Gallery {
            cell("planPairedNote") { TopSlot { MessageCard(syncNoteMessage(SyncNote.PAIRED, onOk = {})) } }
            cell("planUnpairedNote") { TopSlot { MessageCard(syncNoteMessage(SyncNote.UNPAIRED, onOk = {})) } }
        }
    }
}

/**
 * German at the phone's largest font (2.0): the whole sentence wraps, OK stays a 48 dp target (was the point of
 * PairedNoteLargeFontGermanTest). The box is as wide as on a Pixel 5 ([BOX_WIDTH_PIXEL_5]) – that was the old pictures' device.
 */
class PlanMessagesGalleryGermanLargeFontTest : PlanMessagesGalleryTest(2.0f, BOX_WIDTH_PIXEL_5) {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    @Test fun planPairedNoteAndUnpairedNote() = paparazzi.snapshot {
        Gallery {
            cell("planPairedNote") { TopSlot { MessageCard(syncNoteMessage(SyncNote.PAIRED, onOk = {})) } }
            cell("planUnpairedNote") { TopSlot { MessageCard(syncNoteMessage(SyncNote.UNPAIRED, onOk = {})) } }
        }
    }
}

/**
 * The map-cell states the cell-card scenes draw ([ScreenshotTest] and [PlanMessagesGalleryEnglishTest] share them): cells of
 * the job around Meran as the server reports them.
 */
internal object CellCardShots {
    val own = CellId(543, 361)
    val east = CellId(544, 361)
    val north = CellId(543, 360)
    val south = CellId(543, 362)
    val job = CellJob(listOf(own, east, north, south, CellId(544, 360)), own, CellWhy.PLANNED)
    const val mb = 1_048_576L

    /** A job from the position: own area + the neighbours that wait (the mobile question / Wi-Fi-only scenes). */
    val hereJob4 = CellJob(listOf(own, east, north, south), own, CellWhy.HERE)
    val hereJob3 = CellJob(listOf(own, east, north), own, CellWhy.HERE)

    /** Mobile data: own area loaded, the others ask once with their size. */
    val mobileQuestion = mapOf(own to CellState.Ready("v"),
        east to CellState.WaitingForWifi(9 * mb), north to CellState.WaitingForWifi(8 * mb), south to CellState.WaitingForWifi(8 * mb))

    /** "Maps only on Wi-Fi" on: two areas wait. */
    val wifiOnlyWaiting = mapOf(own to CellState.Ready("v"),
        east to CellState.WaitingForWifi(9 * mb), north to CellState.WaitingForWifi(8 * mb))

    /** All areas loaded but one that failed. */
    val failed: Map<CellId, CellState> = job.cells.associateWith<CellId, CellState> { CellState.Ready("v") } +
        (east to CellState.Failed("Map for this area failed"))

    fun card(states: Map<CellId, CellState>, sizes: Map<CellId, Long> = emptyMap(), job: CellJob = this.job,
             declined: Boolean = false, available: Boolean = true, wifiOnly: Boolean = false) =
        CellCards.build(job, states, sizes, available, wifiOnly = wifiOnly, mobileDeclined = declined)
}
