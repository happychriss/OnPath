package com.onpath.app.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.app.setup.Check
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.Need
import com.onpath.app.setup.PhoneState
import com.onpath.app.setup.ResetStatus
import com.onpath.app.setup.Taken
import com.onpath.app.ui.OnPathColors
import com.onpath.strings.*

/**
 * Settings → Phone setup: every check that applies to this phone, grouped (needed for a tour · recommended ·
 * optional), each with its state, why, and "Set up" (the same step as the guide), plus the vibration test.
 */
@Composable
fun PhoneSetupSection(checks: List<Check>, phone: PhoneState, onFix: (CheckId) -> Unit, onTestVibration: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(str(Res.string.setup_phone_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))
        for ((need, label) in listOf(Need.REQUIRED to Res.string.setup_required, Need.RECOMMENDED to Res.string.setup_recommended,
            Need.OPTIONAL to Res.string.setup_optional)) {
            val group = checks.filter { it.need == need }
            if (group.isEmpty()) continue
            Text(str(label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
                Column {
                    group.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                        Row(c, phone, onFix, onTestVibration)
                    }
                }
            }
        }
    }
}

@Composable
private fun Row(c: Check, phone: PhoneState, onFix: (CheckId) -> Unit, onTestVibration: () -> Unit) {
    val (icon, tint) = when {
        c.ok -> Icons.Filled.CheckCircle to OnPathColors.Good
        c.need == Need.REQUIRED -> Icons.Outlined.ErrorOutline to OnPathColors.Error
        else -> Icons.Outlined.WarningAmber to OnPathColors.Warn
    }
    val state = when {
        c.id == CheckId.UNUSED_RESET -> str(if (phone.unusedReset == ResetStatus.ON) Res.string.setup_unused_on else Res.string.setup_unused_off)
        c.ok -> str(Res.string.setup_state_ok)
        c.id == CheckId.VIBRATION && phone.vibrationFelt == Felt.UNKNOWN -> str(Res.string.setup_state_untested)
        else -> str(Res.string.setup_state_missing)
    }
    Row(Modifier.fillMaxWidth().clickable { onFix(c.id) }.heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(checkTitle(c.id, phone, row = true), style = MaterialTheme.typography.bodyLarge)
            Text(state, style = MaterialTheme.typography.bodyMedium, color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant else tint)
            if (c.taken == Taken.BY_PHONE) Text(str(Res.string.setup_taken_phone), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(checkWhy(c.id, phone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            c.id == CheckId.VIBRATION && phone.hasVibrator ->
                TextButton(onClick = onTestVibration, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.setup_vib_test)) }
            !c.ok || c.id == CheckId.UNUSED_RESET ->
                TextButton(onClick = { onFix(c.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.setup_fix)) }
        }
    }
}
