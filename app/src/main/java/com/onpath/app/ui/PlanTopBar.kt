package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onpath.app.routing.Activity

/** Plan mode top bar: My tours and Settings on the left, the activity on the right. */
@Composable
fun PlanTopBar(
    activity: Activity, sac: Int,
    onActivity: () -> Unit, onTours: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onTours, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
            color = Color.White, shadowElevation = 4.dp) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FormatListBulleted, null, tint = Color(0xFF333333), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("My tours", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onSettings, modifier = Modifier.size(48.dp), shape = CircleShape, color = Color.White, shadowElevation = 4.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFF333333)) }
        }
        Spacer(Modifier.weight(1f))
        ActivityChip(activity, sac, onClick = onActivity)
    }
}
