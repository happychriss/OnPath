package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import com.onpath.strings.*

/*
 * The pairing window „Mit Browser verbinden“ (owner 2026-11-28 10:54, A-018: „eigenes Fenster“ – Sicherung stood on top
 * of „Sicherung & Browser“ when he paired). Only the browser part ([SyncSection]): not paired the steps + Kamera öffnen,
 * a scanned code its check digits + Verbinden / Abbrechen, paired the status + Trennen, problems as lines. Every pairing
 * path lands here ([SettingsPage.SYNC]): ☰ while paired or with a problem, no camera app, a `#pair=` link scanned /
 * shared / pasted, a broken pairing link. Opened from outside, back closes it ([SettingsSlice.settingsBack]).
 */

/** The window's title: the ☰ item's own words – „Mit Browser verbinden“, paired „Browser-Verbindung“ ([SyncMenuUi.labelPaired]). */
@Composable
fun pairWindowTitle(sync: SyncUi?): String =
    str(if (sync != null && sync.paired && !sync.unpairedInBrowser) Res.string.sync_menu_paired else Res.string.sync_menu_pair)

/** The window's content: the browser sync alone, the first thing on the page (Kamera öffnen without scrolling). */
fun LazyListScope.pairWindow(sync: SyncUi, cb: SyncCallbacks, now: Long) {
    item {
        val placed = LocalPairWindowPlaced.current
        // unclipped (position + size): a part below the screen shows as such
        Box(if (placed == null) Modifier else Modifier.onGloballyPositioned { placed(Rect(it.positionInRoot(), it.size.toSize())) }) {
            SyncSection(sync, cb, now)
        }
    }
}

/**
 * Where the window's content lies on the screen (px, in the root, not clipped to it) – for the test that every state's action is on the
 * first screen at every font size (PairWindowScreenshotTest; Paparazzi has no semantics tree). Null in the app.
 */
internal val LocalPairWindowPlaced = staticCompositionLocalOf<((Rect) -> Unit)?> { null }
