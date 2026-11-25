package com.onpath.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.onpath.strings.*

/** The two titled privacy paragraphs for what only runs when the user turns it on: plan sync and live sharing. */
@Composable
fun ColumnScope.PrivacySyncAndLive() {
    // plan sync (docs/design/web-sync.md §12 – the website's privacy notice says the same)
    HorizontalDivider()
    Text(str(Res.string.settings_privacy_sync_title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    Text(str(Res.string.settings_privacy_sync_text), style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    // live sharing (docs/design/live-tracking.md §13)
    Text(str(Res.string.settings_privacy_live_title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    Text(str(Res.string.settings_privacy_live_text), style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
}
