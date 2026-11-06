package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Dialogs of saving / loading routes, drawn above every screen (map, My routes): Save's name dialog, "Discard
 * changes?", "Replace current plan?", "Navigate anyway?" and a save problem. None of them closes by itself.
 */
@Composable
fun RouteDialogs(vm: AppViewModel) {
    vm.saveAsk?.let { ask -> SaveNameDialog(ask.kind, ask.name, onSave = vm::confirmSave, onCancel = vm::cancelSave) }
    if (vm.discardAsk) DiscardPlanDialog(onDiscard = vm::discardConfirmed, onCancel = { vm.discardAsk = false },
        onSave = if (vm.planUnsaved && vm.canSaveBeforeClear) vm::discardSaveFirst else null, touring = vm.clearDuringTour,
        unsaved = vm.planUnsaved, name = vm.planNameForAsk)
    // flow map M1 / save paths P3: an unsaved plan before a track / route takes its place
    if (vm.replaceUnsavedAsk) DiscardPlanDialog(onDiscard = { vm.answerReplaceUnsaved(ReplaceAsk.Answer.DISCARD) },
        onCancel = { vm.answerReplaceUnsaved(ReplaceAsk.Answer.CANCEL) },
        onSave = if (vm.canSaveBeforeClear) ({ vm.answerReplaceUnsaved(ReplaceAsk.Answer.SAVE) }) else null,
        touring = vm.clearDuringTour, unsaved = true, name = vm.planNameForAsk)
    vm.replaceAsk?.let { t ->
        ReplacePlanDialog(onSaveFirst = vm::replaceSaveFirst, onReplace = vm::replaceConfirmed, onCancel = { vm.replaceAsk = null },
            fromLink = t is PlanToOpen.Shared)
    }
    if (vm.straightAsk) StraightLegsDialog(onNavigate = vm::straightConfirmed, onCancel = { vm.straightAsk = false })
    vm.routesProblem?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.routesProblem = null },
            title = { Text(str(Res.string.plan_something_wrong)) },
            text = { DialogText(msg) },
            confirmButton = { TextButton(onClick = { vm.routesProblem = null }) { Text(str(Res.string.common_ok)) } },
        )
    }
}

/**
 * Start navigation on a plan with legs the router could not find (dashed straight lines on the map). Those parts
 * are not a path: the user decides, nothing starts silently (R-12). No Cancel button: a tap outside or Back keeps the plan.
 */
@Composable
fun StraightLegsDialog(onNavigate: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(str(Res.string.plan_straight_title)) },
        text = {
            DialogText(str(Res.string.plan_straight_text))
        },
        confirmButton = { TextButton(colors = ActionButtons.goText(), onClick = onNavigate, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_navigate_anyway)) } },
        dismissButton = { TextButton(colors = ActionButtons.editText(), onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_fix_route)) } },
    )
}

/**
 * Save's name dialog (owner 2026-10-31: every Save asks): the name prefilled – the proposal, the saved route's name, or
 * "<name> (2)" for Save as copy – and fully selected, so typing replaces it; Save / Enter saves ([onSave] with the field,
 * a blank one keeps [name]), Cancel / Back / a tap outside saves nothing. A later proposal ([name] changes while the
 * dialog is open: the place name after the date name) replaces the field until the user types.
 */
@Composable
fun SaveNameDialog(kind: SaveAsk.Kind, name: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(name) { if (!typed) text = TextFieldValue(name, TextRange(0, name.length)) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(str(if (kind == SaveAsk.Kind.COPY) Res.string.plan_save_as_copy else Res.string.plan_save_route)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { if (it.text != text.text) typed = true; text = it }, singleLine = true,
                label = { Text(str(Res.string.routes_name_label)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text.text) }),
                modifier = Modifier.focusRequester(focus),
            )
            LaunchedEffect(Unit) {
                // expected: not attached in a screenshot test – the field is shown without the keyboard then
                try { focus.requestFocus() } catch (e: IllegalStateException) { com.onpath.app.debug.Problems.trace("ui", "Save dialog: no focus ($e)") }
            }
        },
        confirmButton = { TextButton(colors = ActionButtons.editText(), onClick = { onSave(text.text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_save)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}

/**
 * ✕ Neu planen (owner 2026-11-05: "Android standard, real care"): a Material 3 basic dialog ([DecisionDialog]).
 * - Unsaved changes: "Plan speichern?" – „Name“ / your plan has changes not saved yet (during a tour + "Deine Tour läuft
 *   weiter."); Verwerfen (red, apart on the left) | Abbrechen · Speichern (filled, plan blue). [onSave] null = nothing
 *   Save could store (one point): Verwerfen | Abbrechen.
 * - During a tour without unsaved changes ([unsaved] false): "Plan leeren?" – "Deine Tour läuft weiter und wird weiter
 *   aufgezeichnet." – Abbrechen · Plan leeren.
 * Without a tour and without unsaved changes there is no question (cleared at once, Undo brings it back).
 */
@Composable
fun DiscardPlanDialog(onDiscard: () -> Unit, onCancel: () -> Unit, onSave: (() -> Unit)? = null, touring: Boolean = false,
                      unsaved: Boolean = true, name: String? = null) {
    if (!unsaved) {
        DecisionDialog(str(Res.string.plan_clear_tour_title), str(Res.string.plan_clear_tour_text), onDismiss = onCancel,
            dismiss = str(Res.string.common_cancel) to onCancel, confirm = str(Res.string.plan_clear_tour_confirm) to onDiscard)
        return
    }
    val text = (if (name.isNullOrBlank()) str(Res.string.plan_save_ask_text) else str(Res.string.plan_save_ask_text_named, name)) +
        if (touring) "\n" + str(Res.string.plan_tour_goes_on) else ""
    DecisionDialog(if (onSave != null) str(Res.string.plan_save_ask_title) else str(Res.string.plan_discard_title), text, onDismiss = onCancel,
        destructive = str(Res.string.plan_discard) to onDiscard, dismiss = str(Res.string.common_cancel) to onCancel,
        confirm = onSave?.let { str(Res.string.plan_bar_save) to it })
}

/**
 * The Material 3 basic dialog (owner 2026-11-05): 24 dp inner padding, 280–560 dp wide (16 dp from the screen's edges), headline + supporting text, the
 * actions in ONE row – the destructive one apart on the left (error text), the way back (text) and the main action
 * (filled, plan blue) on the right. Only when they do not fit (a large font): M3's stacked layout, full-width buttons of
 * equal width – main, way back, destructive – never ragged right-aligned text.
 */
@Composable
fun DecisionDialog(
    title: String, text: String?, onDismiss: () -> Unit,
    dismiss: Pair<String, () -> Unit>, confirm: Pair<String, () -> Unit>?, destructive: Pair<String, () -> Unit>? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp, modifier = Modifier.padding(horizontal = 16.dp).widthIn(min = 280.dp, max = 560.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                text?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                DecisionActions(dismiss, confirm, destructive)
            }
        }
    }
}

@Composable
private fun DecisionActions(dismiss: Pair<String, () -> Unit>, confirm: Pair<String, () -> Unit>?, destructive: Pair<String, () -> Unit>?) {
    val blue = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = OnPathColors.ActionEdit, contentColor = OnPathColors.CardWhite)
    val red = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // each button: its word + 8 dp (text) / 16 dp (filled) padding each side; 8 dp between
        val words = listOfNotNull(destructive?.first?.let { it to 16 }, dismiss.first to 16, confirm?.first?.let { it to 32 })
        val textPad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        val need = with(density) { words.sumOf { (w, pad) -> tm.measure(w, style).size.width.toDp().value.toDouble() + pad } }.toFloat() +
            8f * (words.size - 1)
        if (DecisionLayout.oneRow(need, maxWidth.value)) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            destructive?.let { (w, f) -> TextButton(onClick = f, colors = red, contentPadding = textPad, modifier = Modifier.heightIn(min = 48.dp)) { Text(w, maxLines = 1, softWrap = false) } }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = dismiss.second, contentPadding = textPad, modifier = Modifier.heightIn(min = 48.dp)) { Text(dismiss.first, maxLines = 1, softWrap = false) }
            confirm?.let { (w, f) ->
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(onClick = f, colors = blue, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(w, maxLines = 1, softWrap = false) }
            }
        } else androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            confirm?.let { (w, f) -> androidx.compose.material3.Button(onClick = f, colors = blue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(w) } }
            androidx.compose.material3.OutlinedButton(onClick = dismiss.second, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(dismiss.first) }
            destructive?.let { (w, f) -> TextButton(onClick = f, colors = red, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(w) } }
        }
    }
}

/** When the dialog's actions fit in one row (dp). */
object DecisionLayout {
    fun oneRow(needDp: Float, widthDp: Float): Boolean = needDp <= widthDp
}

/**
 * "Planning a route from the track…" (owner 2026-11-01): a modal box while TrackToPlan runs – nothing else at the same
 * time, one way out: Cancel (nothing changes). Not closed by a tap outside or Back.
 */
@Composable
fun PlanFromTrackDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text(str(Res.string.plan_from_track_busy_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(12.dp))
                Text(str(Res.string.plan_from_track_busy_text))
            }
        },
        confirmButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_cancel)) } },
    )
}
