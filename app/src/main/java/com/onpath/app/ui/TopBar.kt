package com.onpath.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * How a screen's top bar (← title, a trailing action, ⋮) shares its row. The title never breaks inside a word: its
 * longest word must fit beside the rest. At a large font the action first drops its label (icon only, 48 dp, with a
 * content description); only when even then the longest word does not fit is the title held to one line and
 * ellipsized. Normal font: the full action, the title wraps between words as before.
 */
object TopBarLayout {
    enum class Fit { FULL, ICON, ELLIPSIZE }

    /** Room for the title's longest word beside [fixed] (back, ⋮, padding) and an action [actionWidth] wide. */
    fun fits(rowWidth: Int, fixed: Int, titleWord: Int, actionWidth: Int): Boolean = fixed + titleWord + actionWidth <= rowWidth

    /** The action full while that fits, else icon only ([iconWidth]), else icon only and the title ellipsized. */
    fun fit(rowWidth: Int, fixed: Int, titleWord: Int, actionWidth: Int, iconWidth: Int): Fit = when {
        fits(rowWidth, fixed, titleWord, actionWidth) -> Fit.FULL
        fits(rowWidth, fixed, titleWord, iconWidth) -> Fit.ICON
        else -> Fit.ELLIPSIZE
    }
}

/**
 * The top bar row: [back], the [title] (takes the rest), the [action] (full or icon only, [TopBarLayout]) and [menu].
 * [title] gets `ellipsize` = hold to one line; [action] gets `iconOnly`.
 */
@Composable
fun TopBarRow(
    back: @Composable () -> Unit,
    title: @Composable (ellipsize: Boolean) -> Unit,
    action: (@Composable (iconOnly: Boolean) -> Unit)?,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val pad = 4.dp.roundToPx()
        val rowWidth = constraints.maxWidth
        val h = Constraints.Infinity
        // measured once without placing: the fixed parts, the title's longest word (min intrinsic), the full action
        val fixed = 2 * pad + subcompose("probe-back", back).sumOf { it.maxIntrinsicWidth(h) } +
            subcompose("probe-menu", menu).sumOf { it.maxIntrinsicWidth(h) }
        val titleWord = subcompose("probe-title") { title(false) }.maxOfOrNull { it.minIntrinsicWidth(h) } ?: 0
        val fit = if (action == null) {
            if (TopBarLayout.fits(rowWidth, fixed, titleWord, 0)) TopBarLayout.Fit.FULL else TopBarLayout.Fit.ELLIPSIZE
        } else {
            val full = subcompose("probe-action") { action(false) }.sumOf { it.maxIntrinsicWidth(h) }
            TopBarLayout.fit(rowWidth, fixed, titleWord, full, 48.dp.roundToPx())
        }
        val row = subcompose("row") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                back()
                Row(Modifier.weight(1f)) { title(fit == TopBarLayout.Fit.ELLIPSIZE) }
                action?.invoke(fit != TopBarLayout.Fit.FULL)
                menu()
            }
        }.map { it.measure(constraints.copy(minWidth = 0)) }
        val height = row.maxOfOrNull { it.height } ?: 0
        layout(rowWidth, height) { row.forEach { it.placeRelative(0, 0) } }
    }
}
