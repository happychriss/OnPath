package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.onpath.app.data.AppSettings
import com.onpath.app.routing.Activity

/**
 * ⚙ on the way, variant V1 (Chriss 2026-11-28 10:59: „V1 ist prima, und man kann ja immer noch in die anderen
 * Einstellungen“; item 2026-11-28-settings-on-the-way): without scrolling – Akku sparen · Hinweise an Abzweigungen · the
 * screen row of the tour's activity only · Warnung abseits der Route; „Handy einrichten“ on top only while something is
 * missing. The rows are the Navigation page's own (no row merged, no text changed); everything else of that page –
 * Warnen nach, Vibrieren abseits der Route, Stimme testen, the other activity's screen row, Position auf der Route, Knöpfe
 * während der Tour halten – stays on it, reached through „Alle Einstellungen“ ([NavigationSection]).
 */
@Composable
internal fun OnTheWaySection(settings: AppSettings, onChange: (AppSettings) -> Unit, activity: Activity, onPhoneSetup: () -> Unit,
                             phoneChecks: List<com.onpath.app.setup.Check>? = null,
                             phone: com.onpath.app.setup.PhoneState = com.onpath.app.setup.PhoneState(sdk = 35)) {
    val place = SettingRows.phoneRow(phoneChecks)
    val missing = SettingRows.missing(phoneChecks).map { com.onpath.app.ui.setup.checkTitle(it, phone, row = true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (place != SettingRows.PhoneRow.LAST) SettingsCard { PhoneSetupRow(place, missing, onPhoneSetup) }
        SettingsGroupHeader(str(Res.string.settings_group_battery))
        SettingsCard {
            SwitchRow(str(Res.string.settings_battery_save), str(Res.string.settings_battery_save_sub), settings.batterySave) {
                onChange(settings.copy(batterySave = it))
            }
        }
        SettingsGroupHeader(str(Res.string.settings_group_junctions))
        SettingsCard { JunctionGuideRow(settings, onChange) }
        SettingsGroupHeader(str(Res.string.settings_group_screen))
        SettingsCard {
            if (activity == Activity.HIKE) SwitchRow(str(Res.string.settings_nav_screen_on), str(Res.string.settings_nav_screen_on_sub), settings.keepScreenOn) {
                onChange(settings.copy(keepScreenOn = it))
            } else BikeScreenRow(settings, onChange)
        }
        SettingsGroupHeader(str(Res.string.settings_group_offroute))
        SettingsCard {
            SwitchRow(str(Res.string.settings_nav_offroute), null, settings.offRouteWarning) { onChange(settings.copy(offRouteWarning = it)) }
        }
    }
}
