package com.onpath.app.ui.suggest

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.onpath.app.ui.AppViewModel

/** After a card's tap: the panel's new height reaches the map's fit padding (MapScreen reports it after 120 ms). */
private const val REFIT_MS = 400L

/** The wish sheet while it is open ([SuggestSlice.wishOpen]); MapScreen composes it over the map. */
@Composable
fun SuggestWishHost(vm: AppViewModel) {
    val s = vm.suggest
    val w = s.wish
    if (!s.wishOpen || w == null) return
    SuggestWishDialog(w, onChange = { s.wish = it }, onCancel = s::cancelWish, onOk = s::ok)
}

/**
 * The suggestions in the plan sheet's place (MapScreen's mount point) while [SuggestSlice.phase] is set: Back closes
 * them; a point set on the map meanwhile (the plan is no longer empty) closes them too – the plan sheet comes back.
 */
@Composable
fun SuggestHostPanel(vm: AppViewModel) {
    val s = vm.suggest
    val phase = s.phase ?: return
    BackHandler { s.close() }
    val planned = vm.plan.points.isNotEmpty()
    LaunchedEffect(planned) { if (planned && !s.taking) s.close() }
    // the fit on a card's tap uses the sheet's height of that moment; once the panel has grown, fit again above it
    // (also when a card's whole text opens or closes: the loop stays in sight above the grown card)
    var open by remember { mutableIntStateOf(-1) }
    LaunchedEffect(s.chosen, s.cards, open) { if (s.chosen >= 0) { kotlinx.coroutines.delay(REFIT_MS); s.refit() } }
    SuggestPanel(
        SuggestPanelUi(phase, s.wish?.let(::wishSummary).orEmpty(), s.cards, s.notes, s.chosen, s.taking),
        SuggestPanelCallbacks(onChoose = s::choose, onTake = s::take, onChange = s::change, onClose = s::close, onOpen = { open = it }),
    )
}
