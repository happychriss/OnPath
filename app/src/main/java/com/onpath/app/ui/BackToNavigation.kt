package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The way back to Navigate while a tour records and the plan is open (owner 2026-11-06, C): a green button in the form
 * of "▶ Los geht's", at the same place, in every step of the sheet, also over a shown tour or a place card – plain
 * "▲ Weiter geht's" in the middle (owner: no red dot, no figures – "ein einfaches, cooles Wort"), the same while paused
 * and on a free tour. Green = it goes to the navigation (planning blue, everything to the navigation green). Replaces the
 * round "←" of the top band; Android's Back does the same (MainActivity).
 */
@Composable
fun BackToNavigationButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val desc = Txt.s(Res.string.plan_back_nav_desc)
    Button(colors = ActionButtons.go(), onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = PlanFoot.HEIGHT).semantics { contentDescription = desc }) {
        Icon(Icons.Filled.Navigation, null, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(Txt.s(Res.string.plan_back_nav), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
            modifier = Modifier.clearAndSetSemantics { })
    }
}
