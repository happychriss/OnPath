package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.AppViewModel.UpdateState
import com.onpath.strings.*

/** Small card above the plan sheet when a newer version is published. Never blocks the map. */
@Composable
fun UpdateBanner(state: UpdateState, onInstall: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val (version, percent) = when (state) {
        is UpdateState.Available -> state.release.version to null
        is UpdateState.Downloading -> state.release.version to state.percent
        else -> return
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, null, tint = OnPathColors.Green, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(str(Res.string.settings_upd_banner_title, version), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text(if (percent != null) str(Res.string.settings_upd_downloading, percent) else str(Res.string.settings_upd_kept),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
                Spacer(Modifier.height(8.dp))
            } else {
                NoticeButtons(listOf(str(Res.string.settings_upd_later) to onLater, str(Res.string.settings_upd_install) to onInstall),
                    NoticeTone.LIGHT, Modifier.padding(top = 4.dp, end = 4.dp, bottom = 4.dp))
            }
        }
    }
}

/** "Updates" section of the Settings page. */
@Composable
fun UpdateSectionContent(state: UpdateState, currentVersion: String, onCheck: () -> Unit, onInstall: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(Res.string.settings_upd_installed, currentVersion), style = MaterialTheme.typography.bodyMedium)
        when (state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> Text(str(Res.string.settings_upd_checking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdateState.UpToDate -> Text(str(Res.string.settings_upd_latest), style = MaterialTheme.typography.bodySmall, color = OnPathColors.Good)
            is UpdateState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = OnPathColors.Bad)
            is UpdateState.Available, is UpdateState.Downloading -> {
                val rel = (state as? UpdateState.Available)?.release ?: (state as UpdateState.Downloading).release
                Text(str(Res.string.settings_upd_version_available, rel.version), fontWeight = FontWeight.SemiBold)
                if (rel.notes.isNotBlank()) {
                    Text(rel.notes.lines().take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state is UpdateState.Downloading) {
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(str(Res.string.settings_upd_download_install, rel.version))
                    }
                }
            }
        }
        if (state !is UpdateState.Available && state !is UpdateState.Downloading) {
            OutlinedButton(onClick = onCheck, enabled = state != UpdateState.Checking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Text(str(Res.string.settings_upd_check))
            }
        }
        Text(str(Res.string.settings_upd_source),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
