package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/**
 * The browser's card at the top of the Settings list (owner 2026-11-28, P1; 11:14: always ONE line under the title): a
 * round sync sign (filled green while connected), the ☰ item's words as title, a dot + „Nicht verbunden“ (grey) or
 * „Verbunden“ (green, bold; amber with a sync problem worth a look). With whom shows only in the window. A tap opens it.
 */
@Composable
fun PairTopicCard(sync: SyncUi, top: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val state = PairCardState.of(sync)
    val on = state != PairCardState.OFF
    val colour = when (state) {
        PairCardState.TROUBLE -> OnPathColors.Warn
        PairCardState.ON -> OnPathColors.Green
        PairCardState.OFF -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.layout.Column(Modifier.padding(top = top)) {
        SettingsCard {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(if (on) OnPathColors.Green else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(Icons.Outlined.Sync, null, tint = if (on) OnPathColors.CardPaper else OnPathColors.Green, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(16.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(pairWindowTitle(sync), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (on) colour else androidx.compose.material3.MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(str(if (on) Res.string.sync_card_on else Res.string.sync_card_off),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 1,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = colour)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The card's one line: OFF „Nicht verbunden“ (also unpaired in the browser), ON „Verbunden“, TROUBLE „Verbunden“ in amber. */
enum class PairCardState {
    OFF, ON, TROUBLE;

    companion object {
        fun of(ui: SyncUi): PairCardState = when {
            !ui.paired || ui.unpairedInBrowser -> OFF
            ui.problem != null && ui.problem != com.onpath.app.plansync.SyncProblem.Offline -> TROUBLE
            else -> ON
        }
    }
}
