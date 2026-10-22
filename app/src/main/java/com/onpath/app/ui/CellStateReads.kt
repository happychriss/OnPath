package com.onpath.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutines.flow.StateFlow

/**
 * UI derived from fast-changing map-cell state (download bytes tick many times a second) without recomposing the
 * caller on every tick: the flows are collected into States that only [compute] reads, inside a derivedStateOf – so
 * whoever reads the result recomposes only when the derived value really changes (==).
 *
 * Phone trace 0.10.0 (Pixel 8): "Sheet move" 23 of 61 frames janky while map areas downloaded – the plan sheet's
 * composable read the cell states directly and recomposed (with the points rows) on every progress tick. Unit-tested
 * (CellStateReadsTest).
 */
@Composable
internal fun <A, B, T> rememberDerived(a: StateFlow<A>, b: StateFlow<B>, compute: (A, B) -> T): State<T> {
    val sa = a.collectAsState()
    val sb = b.collectAsState()
    val c = rememberUpdatedState(compute)
    return remember(sa, sb) { derivedStateOf(structuralEqualityPolicy()) { c.value(sa.value, sb.value) } }
}
