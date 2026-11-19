package com.onpath.app.ui

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The quiet line on the map ([NoticeSurface.MAP_LINE]). */
object MapNote {
    /** How long it stays – a short sentence, read in passing (like Plan's longer GPS notes). */
    const val MS = 8_000L
}

/**
 * My routes and Settings: the one box over the screen (MessageCard, [MessagePlacement]) – an error without a line of its
 * own (HINWEIS_ERROR, [AppViewModel.errorCard]); a tap on a plan during a tour asks nothing (owner 2026-11-06). Plan and Navigate have their own hosts (PlanMessageHost, NavigateOverlay) with the same error box.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ScreenMessageHost(vm: AppViewModel) {
    val screen = when {
        vm.settingsSlice.showSettings -> MessageScreen.SETTINGS
        vm.routes.open -> MessageScreen.ROUTES
        else -> return
    }
    val error = vm.notices.errorCard?.let { errorMessage(it) { vm.notices.errorCard = null } }
    MessageSlot(Messages.pick(listOf(error)), MessagePlacement.topDp(screen))
}

/**
 * A message in the screen where the user acted (My routes, a Settings page): INFO = grey text with ⓘ, no box; ERROR =
 * red ⚠ + red text. Whole text, no OK – it goes when the screen is left.
 */
@Composable
fun NoticeLine(notice: Notice, modifier: Modifier = Modifier) {
    val error = notice.kind == NoticeKind.ERROR
    val colour = if (error) OnPathColors.Error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (error) Icons.Outlined.Warning else Icons.Outlined.Info, if (error) str(Res.string.plan_error) else null,
            tint = colour, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(notice.text, style = MaterialTheme.typography.bodyMedium, color = colour,
            fontWeight = if (error) FontWeight.Medium else null)
    }
}

/** A spinner + the busy text, for a button or row that started the work. */
@Composable
fun BusyContent(text: String) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(10.dp))
    Text(text)
}

/** The body of a dialog: Body size (16 sp) – Material uses 14 there. */
@Composable
fun DialogText(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)
