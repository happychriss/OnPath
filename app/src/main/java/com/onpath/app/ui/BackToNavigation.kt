package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Format

/**
 * The way back to Navigate while a tour records and the plan is open (owner 2026-11-06, C – "genau": a real green button
 * in the form of "▶ Los geht's", at the same place, in every step of the sheet, also over a shown tour or a place card):
 * "● 1,7 km · 0:14   ▲ Navigation ›". Green = it goes to the navigation (owner's colour rule: planning blue, everything
 * that goes to the navigation green). Replaces the round "←" of the top band (owner: one does not find it when one works
 * with the thumb down below). Android's Back does the same (MainActivity). [tour] null = nothing known yet: the word only.
 */
@Composable
fun BackToNavigationButton(tour: TourState?, activity: com.onpath.app.routing.Activity, onClick: () -> Unit,
                           modifier: Modifier = Modifier) {
    val numbers = BackToNavigation.numbers(tour, LocalDensity.current.fontScale)
    val desc = Txt.s(Res.string.plan_back_nav_desc, activity.navName)
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = desc }) {
        if (numbers != null) {
            // the recording sign: a small light red dot (the red REC of Navigate, readable on green)
            Box(Modifier.size(8.dp).background(if (tour?.paused == true) OnPathColors.OnGreenMuted else RecOnGreen, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(numbers, style = MaterialTheme.typography.labelLarge, color = OnPathColors.OnGreenMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clearAndSetSemantics { })
        }
        Icon(Icons.Filled.Navigation, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(Txt.s(Res.string.plan_back_nav), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
            modifier = Modifier.clearAndSetSemantics { })
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp))
    }
}

/** Light red on the green button (≥ 3:1 as a sign, like the REC badge's red on white). */
private val RecOnGreen = Color(0xFFFF8A80)

/** Rules of the button's words (pure). */
object BackToNavigation {
    /** From this font scale on the numbers go: "Navigation ›" stays whole (owner 2026-11-06). */
    const val LARGE_FONT = 1.3f

    /**
     * The small numbers before "Navigation": "1,7 km · 0:14" (hours:minutes); "Nur Aufnahme" when no route is followed
     * (with the distance it did not fit beside "Navigation ›" in German); "Pause · 1,7 km" while paused. null = none (a
     * large font, or nothing known yet).
     */
    fun numbers(tour: TourState?, fontScale: Float): String? {
        if (tour == null || fontScale >= LARGE_FONT) return null
        val km = Format.distance(tour.distanceM)
        return when {
            tour.paused -> Txt.s(Res.string.plan_back_nav_paused, km)
            tour.recordOnly -> Txt.s(Res.string.plan_back_nav_record_only)
            else -> "$km · ${hoursMinutes(tour.elapsedS)}"
        }
    }

    /** 878 s → "0:14", 4 h 5 min → "4:05". */
    fun hoursMinutes(s: Long): String = "%d:%02d".format(s / 3600, (s % 3600) / 60)
}
