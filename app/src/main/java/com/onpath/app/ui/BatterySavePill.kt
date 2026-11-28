package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * The row under Navigate's status card (Chriss 2026-11-28 12:56, picture akku-pille.png A): on the left the quiet pill
 * „Akku sparen an“ while saving holds for this tour ([saving]) – a tap opens ⚙ with „Akku sparen“ on top; no warning
 * colour – and on the right the background download's chip as before. It never covers „Kein GPS“ (in the card) or
 * „Abseits“ (the card above it): it only sits below them.
 */
@Composable
internal fun NavBelowCardRow(saving: Boolean, onSaving: () -> Unit, download: BackgroundDownloadUi?, onDownload: () -> Unit) {
    if (!saving && download == null) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (saving) QuietPill(str(Res.string.nav_battery_saving), Icons.Outlined.BatterySaver,
            Modifier.weight(1f, fill = false).clickable(role = Role.Button, onClickLabel = str(Res.string.settings_battery_save), onClick = onSaving))
        Spacer(Modifier.weight(1f))
        download?.let { BackgroundDownloadChip(it, onDownload) }
    }
}
