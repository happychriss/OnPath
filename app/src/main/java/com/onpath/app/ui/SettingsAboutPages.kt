package com.onpath.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.strings.*

// Settings → Privacy and Settings → About OnPath (logo + version, credits, licences); split out of SettingsScreen.kt.

/**
 * Privacy: what leaves the phone, to whom, when – checked against the code's network calls
 * (CellApi, Rd5PartStore / SegmentDownloader, PhotonProvider, AppUpdater, ShortLinkResolver, AndroidPlaceGeocoder,
 * link-outs) and the backup rules (res/xml/backup_rules.xml, data_extraction_rules.xml). Says the same as the
 * website's privacy notice (onpath.earth/datenschutz: hosting, device backup, pairing). Keep in step.
 */
@Composable
internal fun PrivacySection() {
    Section(SettingsPage.PRIVACY.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(str(Res.string.settings_privacy_lead), fontWeight = FontWeight.SemiBold)
            Text(str(Res.string.settings_privacy_tracks), style = MaterialTheme.typography.bodyMedium)
            Text(str(Res.string.settings_privacy_device_backup), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(str(Res.string.settings_privacy_what), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(str(Res.string.settings_privacy_ip), style = MaterialTheme.typography.bodySmall)
            PRIVACY_FLOWS.forEach { f ->
                Column {
                    Text(f.what, style = MaterialTheme.typography.bodyMedium)
                    Text("→ ${f.to} · ${f.whenText}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(str(Res.string.settings_privacy_google), style = MaterialTheme.typography.bodySmall)
            PrivacySyncAndLive()
        }
        PrivacyNoticeLink()
    }
}

/** One thing that leaves the phone: what, to whom, when. */
data class PrivacyFlow(val what: String, val to: String, val whenText: String)

/** In the app's language (strings_settings.xml); service names stay as they are. */
val PRIVACY_FLOWS: List<PrivacyFlow> get() = listOfNotNull(
    PrivacyFlow(Txt.s(Res.string.settings_privacy_maps_what), Txt.s(Res.string.settings_privacy_maps_to),
        Txt.s(Res.string.settings_privacy_when_maps)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_routing_what), "brouter.de", Txt.s(Res.string.settings_privacy_when_maps)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_search_what), Txt.s(Res.string.settings_privacy_search_to, "komoot"),
        Txt.s(Res.string.settings_privacy_search_when)),
    // update/AppUpdater – the Play build (A-003) has no own updater: no row there
    PrivacyFlow(Txt.s(Res.string.settings_privacy_update_what), "GitHub", Txt.s(Res.string.settings_privacy_update_when))
        .takeIf { com.onpath.app.BuildConfig.SELF_UPDATE },
    // search/ShortLinkResolver: redirects only, the Google page is never loaded (A-020)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_link_what), "Google", Txt.s(Res.string.settings_privacy_link_when)),
    // search/AndroidPlaceGeocoder: last step for a shared place (A-020)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_geocoder_what), Txt.s(Res.string.settings_privacy_geocoder_to),
        Txt.s(Res.string.settings_privacy_geocoder_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_tap_what), "Google Maps", Txt.s(Res.string.settings_privacy_tap_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_web_what), Txt.s(Res.string.settings_privacy_web_to),
        Txt.s(Res.string.settings_privacy_web_when)),
    PrivacyFlow(Txt.s(Res.string.settings_privacy_diag_what), Txt.s(Res.string.settings_privacy_diag_to),
        Txt.s(Res.string.settings_privacy_diag_when)),
    // plan sync with the web planner (plansync/HttpMailbox → plan.onpath.earth/sync/v1, only when paired)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_sync_what), Txt.s(Res.string.settings_privacy_sync_to),
        Txt.s(Res.string.settings_privacy_sync_when)),
    // live sharing (live/LiveUploader → the OnPath server, only while a tour is shared)
    PrivacyFlow(Txt.s(Res.string.settings_privacy_live_what), Txt.s(Res.string.settings_privacy_live_to),
        Txt.s(Res.string.settings_privacy_live_when)),
)

/**
 * About: logo, version, licence; then the links to the website (AboutLinks), the full credits (the map's
 * "© OpenStreetMap" opens this page) and the licences.
 */
@Composable
internal fun AboutSection(version: String, devTools: Boolean = false, onUnlockDevTools: () -> Unit = {},
                          update: @Composable () -> Unit = {}) {
    var taps by remember { mutableStateOf(0) }
    Section(SettingsPage.ABOUT.title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // big logo + name on top (owner 2026-11-07)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OnPathLogo(80.dp)
                Spacer(Modifier.height(12.dp))
                Text(Brand.NAME, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold,
                    letterSpacing = Brand.Tracking), color = OnPathColors.Walnut)
            }
            // tapped 7 times: Developer tools in Diagnostics (the Android convention); remembered
            Text("${Brand.NAME} $version", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                    .clickable(enabled = !devTools, indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        taps++
                        if (DevToolsUnlock.unlocked(taps)) onUnlockDevTools()
                    })
            if (devTools && taps > 0) Text(str(Res.string.settings_about_devtools_on), style = MaterialTheme.typography.bodySmall,
                color = OnPathColors.Green)
        }
        update() // the update state and its one button, in the same card (owner 2026-11-27)
    }
}

/** About → the full credits, under the links. */
@Composable
internal fun CreditsSection() {
    Section(str(Res.string.settings_about_credits)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(str(Res.string.settings_about_credits), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            MAP_CREDITS.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * About → Licences (A-020): every open-source part with version and licence, the texts from the APK's assets – all
 * offline (data/Licences). Closed: one row; open: the ODbL line, then one block per project.
 */
@Composable
internal fun LicencesSection(given: List<com.onpath.app.data.Licences.Entry>?, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    Section(str(Res.string.settings_licences)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = !open }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(str(Res.string.settings_licences), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(str(Res.string.settings_licences_sum), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (open) {
            val entries = remember(given) { given ?: com.onpath.app.data.Licences.load(assets) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(str(Res.string.settings_licences_lead), style = MaterialTheme.typography.bodySmall)
                Text(str(Res.string.settings_licences_odbl), style = MaterialTheme.typography.bodySmall)
                entries.forEach { e ->
                    HorizontalDivider()
                    LicenceEntry(e) { asset -> com.onpath.app.data.Licences.text(assets, asset) }
                }
            }
        }
    }
}

@Composable
private fun LicenceEntry(e: com.onpath.app.data.Licences.Entry, read: (String) -> String?) {
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(e.project.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(e.project.licence.title, style = MaterialTheme.typography.bodySmall, color = OnPathColors.Green)
        Text(e.libraries.joinToString(", "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.project.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (e.project.url.isNotEmpty()) Text(e.project.url, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.project.text?.let { asset ->
            TextButton(onClick = { shown = !shown }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(str(if (shown) Res.string.settings_licences_text_hide else Res.string.settings_licences_text))
            }
            if (shown) {
                val text = remember(asset) { read(asset) }
                text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
