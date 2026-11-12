package com.onpath.app.ui.routes

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.OnPathColors
import com.onpath.strings.*

/**
 * My routes' sort chips (owner 2026-11-12, variant C – Google Maps' filter chips) under the search box: one row that
 * scrolls sideways, the chosen chip green with ✓ and, for Länge / Dauer / Anstieg, an arrow for its direction (↓ biggest
 * first, ↑ smallest first); a tap on it turns it round ([RouteSort.tap]). When the screen opens the chosen chip is in view,
 * with a bit of the chip before it showing so the row reads as one that scrolls. Each chip says to TalkBack what it does
 * and the current direction.
 */
@Composable
fun RouteSortChips(choice: RouteSort.Choice, onChoice: (RouteSort.Choice) -> Unit, modifier: Modifier = Modifier) {
    val chosenIndex = RouteSort.Key.entries.indexOf(choice.key)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = chosenIndex)
    val peek = with(LocalDensity.current) { PEEK_DP.dp.toPx() }
    // the chip before the chosen one peeks in from the left (only at the start – never moves under the finger later)
    LaunchedEffect(Unit) { if (chosenIndex > 0) state.scrollBy(-peek) }
    LazyRow(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items(RouteSort.Key.entries, key = { it.name }) { k ->
            val chosen = k == choice.key
            val label = keyLabel(k)
            val direction = if (chosen && k.hasDirection) direction(k, choice.biggestFirst) else null
            val spoken = when {
                direction != null -> str(Res.string.routes_sort_chip_flip, label, direction)
                chosen -> str(Res.string.routes_sort_chip_on, label)
                else -> str(Res.string.routes_sort_chip, label)
            }
            FilterChip(selected = chosen, onClick = { onChoice(RouteSort.tap(choice, k)) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                leadingIcon = if (chosen) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                trailingIcon = if (direction != null) ({
                    Icon(if (choice.biggestFirst) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null,
                        Modifier.size(FilterChipDefaults.IconSize))
                }) else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OnPathColors.GreenLight),
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}

/** How much of the chip before the chosen one shows when the screen opens. */
private const val PEEK_DP = 40

@Composable
private fun keyLabel(k: RouteSort.Key): String = str(when (k) {
    RouteSort.Key.NEWEST -> Res.string.routes_sort_newest
    RouteSort.Key.OLDEST -> Res.string.routes_sort_oldest
    RouteSort.Key.LENGTH -> Res.string.routes_sort_length
    RouteSort.Key.DURATION -> Res.string.routes_sort_duration
    RouteSort.Key.ASCENT -> Res.string.routes_sort_ascent
})

/** "längste zuerst" … for a key with a direction. */
@Composable
private fun direction(k: RouteSort.Key, biggestFirst: Boolean): String = str(when (k) {
    RouteSort.Key.ASCENT -> if (biggestFirst) Res.string.routes_sort_most else Res.string.routes_sort_least
    else -> if (biggestFirst) Res.string.routes_sort_longest else Res.string.routes_sort_shortest
})
