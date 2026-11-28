package com.onpath.app.ui.routes

import com.onpath.strings.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.OnPathColors

/**
 * A filter that is set (Chriss 2026-11-28 10:00, picture filter-gesetzt.png): dark green, white words, × at the end – one
 * look the eye cannot miss beside the light sort chips. A tap on the chip itself = [onBody] (the activity's list to
 * change it; Favoriten: off), a tap on × = [onClear] (the filter goes, „Alle“). The × has its own 48 dp touch target.
 */
@Composable
fun SetFilterChip(icon: ImageVector, label: String, onBody: () -> Unit, onClear: () -> Unit, spokenBody: String,
                  iconTint: Color = Color.White) {
    val spokenClear = str(Res.string.routes_filter_clear, label)
    Surface(shape = RoundedCornerShape(8.dp), color = OnPathColors.Green, modifier = Modifier.heightIn(min = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable(role = Role.Button, onClick = onBody).semantics { contentDescription = spokenBody }
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
            }
            Box(Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp).clickable(role = Role.Button, onClick = onClear)
                .semantics { contentDescription = spokenClear; role = Role.Button }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}
