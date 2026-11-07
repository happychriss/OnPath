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
