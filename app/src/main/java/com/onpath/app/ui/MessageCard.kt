package com.onpath.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.onpath.strings.*

/**
 * The message system (owner 2026-10-31, docs/design/ui-style.md): exactly three kinds – FRAGE and HINWEIS are boxes
 * ([MessageCard]); ANZEIGE (a state, nothing to do) is never a box but a line / card in place.
 */
enum class MessageKind {
    /** A decision: blue edge, title (+ detail), two verb buttons (three only for "re-plan via the stop?"); until answered. */
    FRAGE,
    /** Must know: grey edge, ONE button (OK or the one way out); never goes by itself. */
    HINWEIS,
    /** Must know, attention: amber edge + amber ⚠. */
    HINWEIS_WARN,
    /** Must know, something failed: red edge + red ⚠ – what failed + one way out. */
    HINWEIS_ERROR,
}

/** A button of a box: a verb ("Load", "Take new route") or OK. */
class MessageButton(val label: String, val onClick: () -> Unit)

/**
 * One box message. [buttons]: last = the primary (filled green). [icon]: an optional meaning icon for a question
 * (U-turn, activity); attention / error always show ⚠. [onTap]: the card's text is tappable too (e.g. "Notifications
 * off" → the setup step). [navigate]: a Navigate question – 56 dp buttons (on the move, gloves).
 */
data class Message(
    val kind: MessageKind,
    val title: String,
    val detail: String? = null,
    val buttons: List<MessageButton>,
    val icon: ImageVector? = null,
    val onTap: (() -> Unit)? = null,
    val tapLabel: String? = null,
    val navigate: Boolean = false,
)

/** Pure rules of the message boxes, unit-tested (MessagesTest). */
object Messages {
    /**
     * One box at a time: the first FRAGE wins, else the first HINWEIS (callers list errors before plain notes). The
     * others wait in their own state and come next – nothing is dropped.
     */
    fun pick(candidates: List<Message?>): Message? {
        val all = candidates.filterNotNull()
        return all.firstOrNull { it.kind == MessageKind.FRAGE } ?: all.firstOrNull()
    }

    /**
     * A message written as one text ("Couldn't find this place. Try again when you have internet.") → title + detail,
     * split after the first sentence. A period after a short word ("z. B.", "ca.") is not a sentence end.
     */
    fun split(text: String): Pair<String, String?> {
        var from = 0
        while (true) {
            val i = text.indexOf(". ", from)
            if (i < 0) return text.trim() to null
            val word = text.substring(0, i).substringAfterLast(' ')
            val rest = text.substring(i + 2).trim()
            if (word.length >= 3 && rest.isNotEmpty()) return text.substring(0, i + 1) to rest
            from = i + 2
        }
    }
}

/**
 * Where a box sits (one rule for every screen): 12 dp under the top element, below the status bar. Plan: under the
 * search pill + ☰ row (the plan sheet is drawn over the box, so it never covers the primary button); Navigate: under the
 * turn card / status column (measured) – the re-route question too; My routes: under the title + tabs, over the list;
 * Settings: under the title row. Pure, unit-tested.
 */
enum class MessageScreen { PLAN, NAVIGATE, ROUTES, SETTINGS }

object MessagePlacement {
    /** The gap between the top element and the box (and the box's side margin). */
    const val GAP_DP = 12
    /** Top of the small download chip's touch area below the status bar (right under ☰). */
    const val CHIP_TOP_DP = 68
    /** My routes: title row (4 + 56 + 4 dp) + Planned / Done tabs (48 dp). */
    const val ROUTES_HEADER_DP = 112
    /** Settings: the title row (4 + 56 + 4 dp). */
    const val SETTINGS_HEADER_DP = 64

    /** Plan: the bottom of the search pill + ☰ row. */
    val planTopRowDp: Int get() = TOP_BAR_BOTTOM.value.toInt()

    /** The box's top below the status bar. [navigateColumnDp]: Navigate's measured top column (turn + status cards). */
    fun topDp(screen: MessageScreen, navigateColumnDp: Int = 0): Int = GAP_DP + when (screen) {
        MessageScreen.PLAN -> planTopRowDp
        MessageScreen.NAVIGATE -> navigateColumnDp.coerceAtLeast(0)
        MessageScreen.ROUTES -> ROUTES_HEADER_DP
        MessageScreen.SETTINGS -> SETTINGS_HEADER_DP
    }
}

/** The edge (and ⚠) colour of a kind: one colour = one meaning. */
fun MessageKind.edge(): Color = when (this) {
    MessageKind.FRAGE -> OnPathColors.InfoBlue
    MessageKind.HINWEIS -> OnPathColors.Grey
    MessageKind.HINWEIS_WARN -> OnPathColors.Warn
    MessageKind.HINWEIS_ERROR -> OnPathColors.Error
}

/** Height of a box button (and its touch target); [NAVIGATE_BUTTON_DP] for a Navigate question. */
const val MESSAGE_BUTTON_DP = 48
const val NAVIGATE_BUTTON_DP = 56

/** The error box for [text] (a whole sentence or two, split into title + detail) with OK. */
@Composable
fun errorMessage(text: String, onOk: () -> Unit): Message {
    val (title, detail) = Messages.split(text)
    return Message(MessageKind.HINWEIS_ERROR, title, detail, listOf(MessageButton(str(Res.string.common_ok), onOk)))
}

/**
 * [message] at its place on a screen (see [MessagePlacement]); [onBottomDp] = its bottom below the status bar (hints
 * under the search bar go below it).
 */
@Composable
fun BoxScope.MessageSlot(message: Message?, topDp: Int, modifier: Modifier = Modifier, onBottomDp: ((Int) -> Unit)? = null) {
    if (message == null) return
    val density = LocalDensity.current
    MessageCard(message, modifier.align(Alignment.TopCenter).statusBarsPadding()
        .padding(start = MessagePlacement.GAP_DP.dp, end = MessagePlacement.GAP_DP.dp, top = topDp.dp)
        .then(if (onBottomDp == null) Modifier else Modifier.onSizeChanged {
            onBottomDp(topDp + with(density) { it.height.toDp().value.toInt() })
        }))
}

/**
 * The one message box (style B, owner 2026-10-31): white card, 16 dp corners, elevation 6, a 4 dp left edge in the
 * kind's colour, ⚠ for attention / error, title 16 sp bold + at most one 14 sp detail. One button sits beside the text
 * (own row at a large font); two or more get their own row, right-aligned, last = primary (filled green).
 */
@Composable
fun MessageCard(message: Message, modifier: Modifier = Modifier) {
    val edge = message.kind.edge()
    val large = LocalDensity.current.fontScale > LARGE_FONT
    val beside = message.buttons.size == 1 && !large && !message.navigate
    Surface(shape = RoundedCornerShape(16.dp), color = OnPathColors.CardWhite, contentColor = OnPathColors.Ink,
        shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Box(Modifier.drawBehind { drawRect(edge, size = Size(EDGE_DP.dp.toPx(), size.height)) }) {
            Column(Modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = if (beside) 12.dp else 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tap = message.onTap
                    Row(Modifier.weight(1f)
                        .then(if (tap == null) Modifier
                            else Modifier.clickable(role = Role.Button, onClickLabel = message.tapLabel, onClick = tap))
                        .heightIn(min = MESSAGE_BUTTON_DP.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val warn = message.kind == MessageKind.HINWEIS_WARN || message.kind == MessageKind.HINWEIS_ERROR
                        val icon = if (warn) Icons.Outlined.Warning else message.icon
                        if (icon != null) {
                            Icon(icon, if (message.kind == MessageKind.HINWEIS_ERROR) str(Res.string.plan_error) else null,
                                tint = edge, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(message.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = OnPathColors.Ink)
                            message.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = OnPathColors.InkMuted)
                            }
                        }
                    }
                    if (beside) MessageButtonView(message.buttons[0], main = true, heightDp = MESSAGE_BUTTON_DP)
                }
                if (!beside && message.buttons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MessageButtonRow(message.buttons, navigate = message.navigate, large = large)
                }
            }
        }
    }
}

/** Above this font scale (like the setup guide) a single button gets its own row and Navigate buttons stack. */
private const val LARGE_FONT = 1.3f
private const val EDGE_DP = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageButtonRow(buttons: List<MessageButton>, navigate: Boolean, large: Boolean) {
    val main = buttons.lastIndex
    when {
        // Navigate, two answers: each half the width, 56 dp – in thumb reach with gloves
        navigate && buttons.size == 2 && !large -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            buttons.forEachIndexed { i, b ->
                MessageButtonView(b, main = i == main, heightDp = NAVIGATE_BUTTON_DP, modifier = Modifier.weight(1f))
            }
        }
        // Navigate, three answers or a large font: full width, the primary first, then the others from the nearest
        navigate -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf(main) + buttons.indices.filter { it != main }.reversed()).forEach { i ->
                MessageButtonView(buttons[i], main = i == main, heightDp = NAVIGATE_BUTTON_DP, modifier = Modifier.fillMaxWidth())
            }
        }
        else -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            buttons.forEachIndexed { i, b -> MessageButtonView(b, main = i == main, heightDp = MESSAGE_BUTTON_DP) }
        }
    }
}

/** Primary = filled green, white text; the others = green text buttons. Label 16 sp (`labelLarge`). */
@Composable
private fun MessageButtonView(b: MessageButton, main: Boolean, heightDp: Int, modifier: Modifier = Modifier) {
    val size = modifier.heightIn(min = heightDp.dp).widthIn(min = 64.dp)
    val shape = RoundedCornerShape(16.dp)
    val content: @Composable () -> Unit = {
        Text(b.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (main) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2)
    }
    if (main) Button(onClick = b.onClick, modifier = size, shape = shape, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OnPathColors.Green, contentColor = OnPathColors.CardWhite)) { content() }
    else TextButton(onClick = b.onClick, modifier = size, shape = shape, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = OnPathColors.Green)) { content() }
}
