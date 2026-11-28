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
    if (!BatteryEstimate.asks(now.level, now.usePerHour, toGoS, vm.settingsSlice.settings.batterySave, BatteryWatch.askedTour == tourId)) return null
    val lasts = BatteryEstimate.lastsS(now.level, now.usePerHour) ?: return null
    return BatteryAskUi(Format.durationShort(toGoS ?: return null), now.level ?: return null, Format.durationShort(lasts))
}

/** „Sparen“ turns „Akku sparen“ on (it stays set); either answer ends the question for this tour. */
fun answerBattery(vm: AppViewModel, tourId: Long?, save: Boolean) {
    BatteryWatch.askedTour = tourId
    if (save) vm.settingsSlice.put(vm.settingsSlice.settings.copy(batterySave = true))
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
