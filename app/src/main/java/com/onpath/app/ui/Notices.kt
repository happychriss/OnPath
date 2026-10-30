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

/**
 * Work the user started that takes a moment: it shows as a spinner where it was started (the
 * button / the My routes row), never as a message.
 */
enum class Busy(private val res: StringResource?, private val english: String = "") {
    IMPORT(Res.string.plan_busy_import),
    EXPORT(Res.string.plan_busy_export),
    /** Diagnostics stays English (owner). */
    DIAGNOSTICS(null, "Creating the report…"),
    BACKUP(Res.string.plan_busy_backup);

    val text: String get() = res?.let { Txt.s(it) } ?: english
}

/** What a message is. Questions (ACTION) and route warnings have their own places. */
enum class NoticeKind {
    /** The result of something the user did, good to know: a grey line, no box. */
    INFO,
    /** Something the user asked for (or the app needs to do for them) did not work: red, ⚠, what failed + one way out. */
    ERROR,
}

/** Where the user acted – the screen the result belongs to. */
enum class NoticePlace {
    /** My routes (import, export, delete, rides, restore result). */
    ROUTES,
    /** Settings (any page: backup, offline maps, updates, diagnostics, developer tools). */
    SETTINGS,
    /** The map itself (a share / link into the app, a map button, a dialog over the map). */
    MAP,
    /** Nobody acted: the recording service, a backup in the background, a crash last time. */
    BACKGROUND,
}

/** Where a message shows. */
enum class NoticeSurface {
    /** A line at the top of My routes (grey INFO / red ERROR); it goes when My routes is left. */
    ROUTES_LINE,
    /** A line at the top of the open Settings page; it goes when the page changes or Settings closes. */
    SETTINGS_LINE,
    /** The one light error card over the map (red edge, ⚠, OK) – stays until OK. */
    ERROR_CARD,
    /** Nowhere on screen: nobody waits for it (the problem log has it). */
    LOG_ONLY,
}

/** A message for a line in a screen. */
data class Notice(val text: String, val kind: NoticeKind)

/**
 * No dark message bar (owner): a message goes where the user acted; only an error that has no such place left gets the
 * light error card; an INFO without a place is not shown (its result is visible anyway – or nobody needs it).
 */
object NoticeRouting {
    fun surface(kind: NoticeKind, place: NoticePlace, routesOpen: Boolean, settingsOpen: Boolean): NoticeSurface = when {
        place == NoticePlace.ROUTES && routesOpen -> NoticeSurface.ROUTES_LINE
        place == NoticePlace.SETTINGS && settingsOpen -> NoticeSurface.SETTINGS_LINE
        kind == NoticeKind.ERROR -> NoticeSurface.ERROR_CARD
        else -> NoticeSurface.LOG_ONLY
    }
}

/**
 * The one error card: light red container, red edge, ⚠, what failed + one way out, OK.
 * Only for errors whose place is gone (background, a link or share into the app). Never disappears by itself.
 */
@Composable
fun ErrorCard(text: String, onOk: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), color = OnPathColors.ErrorContainer, contentColor = OnPathColors.OnErrorContainer,
        shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(OnPathColors.Error))
            Row(Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, str(Res.string.plan_error), tint = OnPathColors.Error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            NoticeButton(str(Res.string.common_ok), onOk, NoticeTone.RED, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
        }
    }
}

/**
 * Where the one error card goes ([MessagePlacement]): Plan – below the blue strip / loading chip while it shows;
 * Navigate – below the turn card, status card and chips, never over the turn card. Its own composable, so the measured
 * heights it reads recompose only the card.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ErrorCardHost(vm: AppViewModel) {
    val msg = vm.errorCard ?: return
    val navigate = vm.mode == AppMode.NAVIGATE && !vm.showSettings && !vm.routesOpen
    val top = if (navigate) MessagePlacement.navigateTopDp(vm.navigateTopDp)
        else MessagePlacement.topDp(vm.backgroundStripShown && vm.mode == AppMode.PLAN, vm.backgroundStripBottomDp)
    ErrorCard(msg, onOk = { vm.errorCard = null },
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = top.dp))
}

/** The background a [NoticeButton] sits on: one colour = one meaning (OnPathColors). */
enum class NoticeTone {
    /** The blue ACTION strip (a question with buttons, the "Paired. …" note). */
    BLUE,
    /** Amber WARNING strip / chip (a download failed, notifications off). */
    AMBER,
    /** The light red error card. */
    RED,
    /** A white card or the grey pill (update card, "no downloads in this build"). */
    LIGHT,
}

/**
 * The button of every message strip and card (owner 2026-10-30: "bigger OK" – a proper button, not small text): Material
 * button, 48 dp high (gloves), label in OnPathType's 16 sp (`labelLarge`). [main] = filled in the strip's colour (the
 * answer most people give, OK); the others = text buttons in the same colour. One look for the blue map strips, the
 * amber strip, the error card, the "Notifications off" chip and the update card.
 */
@Composable
fun NoticeButton(label: String, onClick: () -> Unit, tone: NoticeTone, main: Boolean = true, modifier: Modifier = Modifier) {
    val (fill, onFill) = when (tone) {
        NoticeTone.BLUE -> Color.White to OnPathColors.InfoBlue
        NoticeTone.AMBER -> OnPathColors.Warn to Color.White
        NoticeTone.RED -> OnPathColors.Error to Color.White
        NoticeTone.LIGHT -> OnPathColors.Green to Color.White
    }
    val textColour = when (tone) {
        NoticeTone.BLUE -> Color.White
        NoticeTone.AMBER -> OnPathColors.Warn
        NoticeTone.RED -> OnPathColors.OnErrorContainer
        NoticeTone.LIGHT -> OnPathColors.Green
    }
    val size = modifier.heightIn(min = NOTICE_BUTTON_DP.dp).widthIn(min = 64.dp)
    val content: @Composable () -> Unit = {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (main) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center)
    }
    if (main) Button(onClick = onClick, modifier = size, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = onFill)) { content() }
    else TextButton(onClick = onClick, modifier = size, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = textColour)) { content() }
}

/** Height of a [NoticeButton] (and its touch target): 48 dp. */
const val NOTICE_BUTTON_DP = 48

/** The buttons of a strip / card, right-aligned (last = main); they wrap to a second line when they do not fit. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NoticeButtons(buttons: List<Pair<String, () -> Unit>>, tone: NoticeTone, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        buttons.forEachIndexed { i, (label, act) -> NoticeButton(label, act, tone, main = i == buttons.lastIndex) }
    }
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
