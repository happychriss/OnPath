package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

/**
 * The empty start sheet's short guide (owner 2026-11-07, variant A of https://claude.ai/artifact/BPZJMPiAY1Da3Krzrrf4MY,
 * without "Nur aufzeichnen"): under "Wohin soll's gehen?" three calm numbered steps – 1 "Ziel finden ›" (the only
 * tappable step: it opens the search; it replaces the old hand line "Halte dein Ziel …", which said the same), 2 "Route
 * anpassen", 3 "Los geht's" (plain text, never a button – a start button without a route makes no sense) – and one
 * small link "Meine Touren öffnen". Step 1 sits in the summary step (the sheet opens at it, the rolled-up sheet stays
 * as small as before), steps 2–3 and the link below it. Only while the plan is empty and no tour runs; an empty plan
 * during a tour keeps its own line ("Tour läuft weiter …") and "▲ Weiter geht's".
 */
object EmptyGuide {
    data class Step(val n: Int, val title: StringResource, val line: StringResource, val tappable: Boolean)

    val steps = listOf(
        Step(1, Res.string.plan_guide_find_title, Res.string.plan_guide_find_line, tappable = true),
        Step(2, Res.string.plan_guide_adjust_title, Res.string.plan_guide_adjust_line, tappable = false),
        Step(3, Res.string.plan_guide_go_title, Res.string.plan_guide_go_line, tappable = false),
    )

    /** The guide shows for an empty plan without a tour (a tour has its own empty-plan line). */
    fun shows(pointCount: Int, touring: Boolean): Boolean = pointCount == 0 && !touring
}

@Composable
private fun StepNumber(n: Int, color: Color) =
    Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Text("$n", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
    }

@Composable
private fun GuideStep(step: EmptyGuide.Step, onTap: (() -> Unit)?) {
    val blue = OnPathColors.PlanAccent
    val neutral = OnPathColors.Walnut
    val tap = if (step.tappable && onTap != null) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier
    Row(tap.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        StepNumber(step.n, if (step.tappable) blue else neutral)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (step.tappable) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str(step.title), style = MaterialTheme.typography.titleMedium, color = blue, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = blue, modifier = Modifier.size(22.dp))
            } else Text(str(step.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(str(step.line), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Step 1 – in the summary step, where the sheet opens (tap: the search). */
@Composable
internal fun EmptyGuideFirst(onSearch: () -> Unit) = GuideStep(EmptyGuide.steps.first(), onSearch)

/** Steps 2–3 and "Meine Touren öffnen" – below the summary step (half and full sheet). */
@Composable
internal fun EmptyGuideRest(onMyRoutes: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptyGuide.steps.drop(1).forEach { GuideStep(it, null) }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.clickable(role = Role.Button, onClick = onMyRoutes).heightIn(min = 48.dp).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(str(Res.string.plan_guide_my_routes), style = MaterialTheme.typography.bodyLarge,
                color = OnPathColors.Walnut, fontWeight = FontWeight.Medium)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnPathColors.Walnut, modifier = Modifier.size(20.dp))
        }
    }
}
