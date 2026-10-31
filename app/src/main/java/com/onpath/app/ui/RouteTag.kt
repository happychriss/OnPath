package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.onpath.app.data.Tour
import com.onpath.app.plansync.SyncMark
import com.onpath.strings.Res
import com.onpath.strings.routes_tag_changed
import com.onpath.strings.routes_tag_new
import com.onpath.strings.str
import org.jetbrains.compose.resources.StringResource

/**
 * The tag after a route's name in My routes (owner 2026-10-31, docs/design/ui-style.md "Tags"): **New** = came new from
 * the paired browser and not opened yet, **Changed** = changed in the browser since it was last opened. Read from the
 * route's [SyncMark] (plansync/SyncMarks: the sync marks, opening the route clears it) – planned routes and imported
 * tracks alike; no mark (or no uuid) = no tag.
 */
enum class RouteTag(val text: StringResource) {
    NEW(Res.string.routes_tag_new),
    CHANGED(Res.string.routes_tag_changed);

    companion object {
        fun of(mark: SyncMark?): RouteTag? = when (mark) {
            SyncMark.NEW -> NEW
            SyncMark.CHANGED -> CHANGED
            null -> null
        }

        fun of(t: Tour, marks: Map<String, SyncMark>): RouteTag? = if (t.uuid.isEmpty()) null else of(marks[t.uuid])
    }
}

/** A tag: one short word in a light blue pill (`TagContainer` / `TagInk`), 12 sp bold. Never cut, never wraps. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = OnPathColors.TagInk,
        maxLines = 1, softWrap = false,
        modifier = modifier.background(OnPathColors.TagContainer, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/**
 * A one-line [title] (ellipsized) with an optional [tag] after it. The tag is never cut: the title gets what is left of
 * the row; when that is less than [TagRow.MIN_TITLE_SHARE] of the row (largest font, a narrow card) and the title does
 * not fit whole, the tag goes under the title instead.
 */
@Composable
fun TitleWithTag(title: @Composable () -> Unit, tag: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    Layout(content = { title(); tag?.invoke() }, modifier = modifier) { ms, c ->
        val maxW = if (c.hasBoundedWidth) c.maxWidth else Constraints.Infinity
        if (ms.size < 2) {
            val t = ms[0].measure(c.copy(minWidth = 0))
            return@Layout layout(t.width, t.height) { t.place(0, 0) }
        }
        val gap = 8.dp.roundToPx()
        val tagP = ms[1].measure(Constraints(maxWidth = maxW))
        val fullTitle = ms[0].maxIntrinsicWidth(Constraints.Infinity)
        val sameRow = TagRow.sameRow(maxW, fullTitle, tagP.width, gap)
        if (sameRow) {
            val room = if (maxW == Constraints.Infinity) fullTitle else (maxW - tagP.width - gap).coerceAtLeast(0)
            val t = ms[0].measure(Constraints(maxWidth = minOf(room, fullTitle)))
            val h = maxOf(t.height, tagP.height)
            layout(t.width + gap + tagP.width, h) {
                t.place(0, (h - t.height) / 2)
                tagP.place(t.width + gap, (h - tagP.height) / 2)
            }
        } else {
            val t = ms[0].measure(Constraints(maxWidth = maxW))
            val below = 2.dp.roundToPx()
            layout(maxOf(t.width, tagP.width), t.height + below + tagP.height) {
                t.place(0, 0)
                tagP.place(0, t.height + below)
            }
        }
    }
}

/** Where the tag goes (pure, unit-tested): beside the title or under it. */
object TagRow {
    /** The title keeps more than half the row beside a tag (else the tag goes under it). */
    const val MIN_TITLE_SHARE = 0.55f

    fun sameRow(rowWidth: Int, titleWidth: Int, tagWidth: Int, gap: Int): Boolean {
        if (rowWidth == Constraints.Infinity) return true
        val room = rowWidth - tagWidth - gap
        return room >= titleWidth || room >= rowWidth * MIN_TITLE_SHARE
    }
}

/** A route's name with its [RouteTag] (My routes): the name ellipsizes before the tag is cut. */
@Composable
fun RouteTitle(name: String, tag: RouteTag?, modifier: Modifier = Modifier) {
    TitleWithTag(
        title = { Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        tag = tag?.let { { Tag(str(it.text)) } },
        modifier = modifier,
    )
}
