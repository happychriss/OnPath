package com.onpath.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.setup.Brand
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.PhoneState
import com.onpath.app.setup.SetupGuide
import com.onpath.app.setup.Taken
import com.onpath.app.ui.OnPathColors
import com.onpath.strings.*

/** What the guide's buttons do – MainActivity opens the system dialogs and screens. */
data class SetupCallbacks(
    /** The step's main action: the system dialog or the phone's settings screen for [CheckId]. */
    val onContinue: (CheckId) -> Unit = {},
    val onLater: () -> Unit = {},
    val onTestVibration: () -> Unit = {},
    val onVibrationAnswer: (Felt) -> Unit = {},
    val onSoundSettings: () -> Unit = {},
    val onAppSettings: () -> Unit = {},
    val onMoreHelp: (Brand) -> Unit = {},
    val onBrandDone: () -> Unit = {},
    /** The end page's button, Back, or "Later" on the welcome. */
    val onClose: () -> Unit = {},
    val onWelcomeNow: () -> Unit = {},
)

/** Brand names as phones show them (not translated). */
fun brandName(b: Brand): String = when (b) {
    Brand.SAMSUNG -> "Samsung"
    Brand.XIAOMI -> "Xiaomi"
    Brand.HUAWEI -> "Huawei / Honor"
    Brand.ONEPLUS -> "OnePlus / Oppo / realme"
}

/** [row]: the neutral name in Settings → Phone setup ("Phone location"), not the step's "Location is off". */
fun checkTitle(id: CheckId, phone: PhoneState, row: Boolean = false): String = when (id) {
    CheckId.LOCATION_ON -> if (row) str0(Res.string.setup_locon_row) else str0(Res.string.setup_locon_title)
    CheckId.BATTERY_SAVER -> if (row) str0(Res.string.setup_saver_row) else str0(Res.string.setup_saver_title)
    CheckId.LOCATION -> str0(Res.string.setup_loc_title)
    CheckId.PRECISE -> str0(Res.string.setup_precise_title)
    CheckId.NOTIFICATIONS -> str0(Res.string.setup_notif_title)
    CheckId.VIBRATION -> str0(Res.string.setup_vib_title)
    CheckId.BATTERY -> str0(Res.string.setup_bat_title)
    CheckId.BRAND -> Txt.s(Res.string.setup_brand_title, phone.brand?.let(::brandName) ?: "")
    CheckId.UNUSED_RESET -> str0(Res.string.setup_unused_title)
}

fun checkIcon(id: CheckId): ImageVector = when (id) {
    CheckId.LOCATION -> Icons.Outlined.LocationOn
    CheckId.PRECISE -> Icons.Outlined.MyLocation
    CheckId.LOCATION_ON -> Icons.Outlined.LocationOff
    CheckId.NOTIFICATIONS -> Icons.Outlined.Notifications
    CheckId.VIBRATION -> Icons.Outlined.Vibration
    CheckId.BATTERY -> Icons.Outlined.BatteryAlert
    CheckId.BRAND -> Icons.Outlined.PhoneAndroid
    CheckId.BATTERY_SAVER -> Icons.Outlined.BatterySaver
    CheckId.UNUSED_RESET -> Icons.Outlined.History
}

private fun str0(r: org.jetbrains.compose.resources.StringResource) = Txt.s(r)

/** The "why" line of a check (also the explanation in Settings → Phone setup). */
fun checkWhy(id: CheckId, phone: PhoneState): String = when (id) {
    CheckId.LOCATION -> str0(Res.string.setup_loc_why)
    CheckId.PRECISE -> str0(Res.string.setup_precise_why)
    CheckId.LOCATION_ON -> str0(Res.string.setup_locon_why)
    CheckId.NOTIFICATIONS -> str0(Res.string.setup_notif_why)
    CheckId.VIBRATION -> if (phone.hasVibrator) str0(Res.string.setup_vib_why) else str0(Res.string.setup_vib_none)
    CheckId.BATTERY -> str0(Res.string.setup_bat_why)
    CheckId.BRAND -> Txt.s(Res.string.setup_brand_why, phone.brand?.let(::brandName) ?: "")
    CheckId.BATTERY_SAVER -> str0(Res.string.setup_saver_why)
    CheckId.UNUSED_RESET -> str0(Res.string.setup_unused_why)
}

/** What to do – depends on what Android still allows (dialog or settings path) and on an earlier "no". */
fun checkDo(id: CheckId, phone: PhoneState, denied: Boolean): String = when (id) {
    CheckId.LOCATION -> when {
        phone.locationAskBlocked -> str0(Res.string.setup_loc_blocked)
        denied -> str0(Res.string.setup_loc_denied)
        else -> str0(Res.string.setup_loc_do)
    }
    CheckId.PRECISE -> if (phone.locationAskBlocked) str0(Res.string.setup_loc_blocked) else str0(Res.string.setup_precise_do)
    CheckId.LOCATION_ON -> str0(Res.string.setup_locon_do)
    CheckId.NOTIFICATIONS -> if (phone.notificationAskBlocked || phone.sdk < 33 || phone.channelOff) str0(Res.string.setup_notif_blocked)
        else str0(Res.string.setup_notif_do)
    CheckId.VIBRATION -> str0(Res.string.setup_vib_do)
    CheckId.BATTERY -> str0(Res.string.setup_bat_do)
    CheckId.BRAND -> when (phone.brand) {
        Brand.SAMSUNG -> str0(Res.string.setup_brand_samsung)
        Brand.XIAOMI -> str0(Res.string.setup_brand_xiaomi)
        Brand.HUAWEI -> str0(Res.string.setup_brand_huawei)
        Brand.ONEPLUS -> str0(Res.string.setup_brand_oneplus)
        null -> ""
    }
    CheckId.BATTERY_SAVER -> str0(Res.string.setup_saver_do)
    CheckId.UNUSED_RESET -> str0(Res.string.setup_unused_do)
}

/**
 * The welcome page (first start) or the guide: one step per page – large icon, "Step 2 of 3", title, why, what to do,
 * one main button and "Later" (docs/design/onboarding-phone-check.md §4.2). Full screen, scrolls at large font sizes.
 */
@Composable
fun SetupGuideScreen(guide: SetupGuide?, welcome: Boolean, phone: PhoneState, taken: Map<CheckId, Taken>, cb: SetupCallbacks) {
    // at a large font size (older users) the buttons scroll with the text, so neither squeezes the other
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)
            .then(if (large) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Column(if (large) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    welcome -> Page(Icons.Outlined.Hiking, null, str(Res.string.setup_welcome_title),
                        str(Res.string.setup_welcome_text), null)
                    guide == null -> Unit
                    guide.finished -> {
                        val end = guide.end(phone)
                        Page(Icons.Filled.CheckCircle, null,
                            str(if (end == SetupGuide.End.ALL_SET) Res.string.setup_all_set_title else Res.string.setup_some_later_title),
                            str(when (end) {
                                SetupGuide.End.ALL_SET -> Res.string.setup_all_set_text
                                SetupGuide.End.SOME_LATER -> Res.string.setup_some_later_text
                                SetupGuide.End.TOUR_NOT_READY -> Res.string.setup_tour_needs
                            }), null, iconTint = if (end == SetupGuide.End.ALL_SET) OnPathColors.Good else MaterialTheme.colorScheme.primary)
                    }
                    else -> {
                        val id = guide.current!!
                        guide.justDone?.let { DoneLine(checkTitle(it, phone)) }
                        taken[id]?.let { NoteBox(str(if (it == Taken.BY_PHONE) Res.string.setup_taken_phone else Res.string.setup_taken_off)) }
                        val counter = if (guide.steps.size > 1) str(Res.string.setup_step_of, guide.stepNumber, guide.steps.size) else null
                        Page(checkIcon(id), counter, checkTitle(id, phone), checkWhy(id, phone),
                            checkDo(id, phone, id in guide.denied).takeIf { it.isNotEmpty() && (id != CheckId.VIBRATION || phone.hasVibrator) })
                        if (id == CheckId.VIBRATION && guide.vibrationPlayed && guide.vibrationAnswer == Felt.NO) {
                            NoteBox(str(Res.string.setup_vib_not_felt))
                        }
                    }
                }
            }
            Buttons(guide, welcome, phone, cb)
        }
    }
}

@Composable
private fun Page(icon: ImageVector, counter: String?, title: String, why: String, todo: String?,
                 iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Spacer(Modifier.heightIn(min = 24.dp).padding(top = 24.dp))
    Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(48.dp))
    }
    Spacer(Modifier.padding(top = 20.dp))
    counter?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() })
    Text(why, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
    todo?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun DoneLine(what: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, null, tint = OnPathColors.Good, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(what, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.Good)
    }
}

@Composable
private fun NoteBox(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = OnPathColors.WarnContainer, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.Ink, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Buttons(guide: SetupGuide?, welcome: Boolean, phone: PhoneState, cb: SetupCallbacks) {
    val big = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            welcome -> {
                Button(onClick = cb.onWelcomeNow, modifier = big) { Text(str(Res.string.setup_welcome_now)) }
                TextButton(onClick = cb.onClose, modifier = big) { Text(str(Res.string.setup_later)) }
            }
            guide == null -> Unit
            guide.finished -> Button(onClick = cb.onClose, modifier = big) { Text(str(Res.string.setup_close)) }
            else -> when (val id = guide.current!!) {
                CheckId.VIBRATION -> when {
                    !phone.hasVibrator -> Button(onClick = cb.onLater, modifier = big) { Text(str(Res.string.setup_continue)) }
                    guide.vibrationPlayed && guide.vibrationAnswer == Felt.UNKNOWN -> {
                        Text(str(Res.string.setup_vib_question), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { cb.onVibrationAnswer(Felt.YES) }, modifier = big) { Text(str(Res.string.setup_vib_yes)) }
                        OutlinedButton(onClick = { cb.onVibrationAnswer(Felt.NO) }, modifier = big) { Text(str(Res.string.setup_vib_no)) }
                    }
                    guide.vibrationAnswer == Felt.NO -> {
                        Button(onClick = cb.onTestVibration, modifier = big) { Text(str(Res.string.setup_vib_test)) }
                        OutlinedButton(onClick = cb.onSoundSettings, modifier = big) { Text(str(Res.string.setup_vib_sound_settings)) }
                        TextButton(onClick = cb.onLater, modifier = big) { Text(str(Res.string.setup_later)) }
                    }
                    else -> {
                        Button(onClick = cb.onTestVibration, modifier = big) { Text(str(Res.string.setup_vib_test)) }
                        TextButton(onClick = cb.onLater, modifier = big) { Text(str(Res.string.setup_later)) }
                    }
                }
                CheckId.BRAND -> {
                    Button(onClick = cb.onAppSettings, modifier = big) { Text(str(Res.string.setup_open_app_settings)) }
                    OutlinedButton(onClick = cb.onBrandDone, modifier = big) { Text(str(Res.string.setup_brand_done)) }
                    phone.brand?.let { b -> TextButton(onClick = { cb.onMoreHelp(b) }, modifier = big) { Text(str(Res.string.setup_more_help)) } }
                    TextButton(onClick = cb.onLater, modifier = big) { Text(str(Res.string.setup_later)) }
                }
                else -> {
                    val again = id in guide.denied && !phone.locationAskBlocked && (id == CheckId.LOCATION || id == CheckId.PRECISE)
                    Button(onClick = { cb.onContinue(id) }, modifier = big) {
                        Text(str(if (again) Res.string.setup_try_again else Res.string.setup_continue))
                    }
                    TextButton(onClick = cb.onLater, modifier = big) { Text(str(Res.string.setup_later)) }
                }
            }
        }
    }
}
