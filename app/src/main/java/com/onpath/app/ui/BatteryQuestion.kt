package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.onpath.app.data.Format
import com.onpath.app.domain.tour.BatteryEstimate
import com.onpath.app.recording.BatteryWatch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp

/** The numbers of the battery question: the time still to go, the level now, how long it lasts at the use so far. */
data class BatteryAskUi(val toGo: String, val level: Int, val lasts: String)

/**
 * The battery question's state for Navigate (Chriss 2026-11-28 10:38, picture akku-varianten.png, R-10): shown when the
 * estimate ([BatteryWatch], [BatteryEstimate.asks]) reaches the goal with less than 10 % – once per tour, not while „Akku
 * sparen“ is on; null otherwise. [toGoS] = Navigate's time still to go.
 */
@Composable
fun batteryAsk(vm: AppViewModel, tourId: Long?, toGoS: Double?): BatteryAskUi? {
    val now by BatteryWatch.now.collectAsState()
    if (tourId == null || now.tourId != tourId) return null
    val mark by BatteryWatch.saveMark.collectAsState()
    val saving = com.onpath.app.domain.tour.BatterySave.effective(mark, tourId)
    if (!BatteryEstimate.asks(now.level, now.usePerHour, toGoS, saving, BatteryWatch.askedTour == tourId)) return null
    val lasts = BatteryEstimate.lastsS(now.level, now.usePerHour) ?: return null
    return BatteryAskUi(Format.durationShort(toGoS ?: return null), now.level ?: return null, Format.durationShort(lasts))
}

/**
 * Either answer ends the question for this tour; „Sparen“ opens ⚙ on the way with „Akku sparen“ on top – the user switches
 * there and reads that it holds for this tour only (Chriss 2026-11-28 12:55).
 */
fun answerBattery(vm: AppViewModel, tourId: Long?, save: Boolean) {
    BatteryWatch.askedTour = tourId
    if (save) vm.settingsSlice.openSettingsOnTheWay()
    com.onpath.app.debug.Problems.trace("battery", "Battery question answered: ${if (save) "Sparen" else "Nein danke"}")
}

/**
 * „Akku reicht wohl nicht bis zum Ziel“ – a Navigate question (green edge), [Nein danke] [Sparen]; never goes by itself
 * while the estimate still says so.
 */
@Composable
internal fun batteryMessage(b: BatteryAskUi, onAnswer: (Boolean) -> Unit): Message =
    Message(MessageKind.FRAGE, str(Res.string.nav_battery_title), str(Res.string.nav_battery_line, b.toGo, b.level, b.lasts),
        listOf(MessageButton(str(Res.string.nav_battery_no)) { onAnswer(false) }, MessageButton(str(Res.string.nav_battery_save)) { onAnswer(true) }),
        icon = Icons.Outlined.BatteryAlert, navigate = true)

/**
 * The „Akku sparen“ switch in ⚙ (Navigation page and on the way): „an“ while it holds for the running tour or waits for the
 * next one; under its name in bold „Gilt nur für die aktuelle Tour“ (Chriss 2026-11-28 12:55), then the line what it does.
 */
@Composable
internal fun BatterySaveSwitch() {
    val running by com.onpath.app.recording.RecordingService.state.collectAsState()
    val mark by BatteryWatch.saveMark.collectAsState()
    val on = com.onpath.app.domain.tour.BatterySave.effective(mark, running?.tourId)
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { BatteryWatch.setSaving(it) })
        .padding(start = 16.dp, end = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f).padding(vertical = 6.dp)) {
            androidx.compose.material3.Text(str(Res.string.settings_battery_save), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Text(str(Res.string.settings_battery_save_tour), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            androidx.compose.material3.Text(str(Res.string.settings_battery_save_sub), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        SettingsSwitch(on)
    }
}

/** „Akku sparen an“ in Navigate: saving holds for the running tour (Chriss 2026-11-28 12:56). */
@Composable
fun batterySaving(tourId: Long?): Boolean {
    val mark by BatteryWatch.saveMark.collectAsState()
    return tourId != null && com.onpath.app.domain.tour.BatterySave.effective(mark, tourId)
}
