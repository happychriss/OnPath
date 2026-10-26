package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Dialogs of saving / loading routes, drawn above every screen (map, My routes): Save route name,
 * "Replace current plan?", and a save problem. None of them closes by itself.
 */
@Composable
fun RouteDialogs(vm: AppViewModel) {
    vm.saveChoice?.let { _ ->
        SaveChoiceDialog(vm.savedName ?: "", unsaved = vm.planUnsaved,
            onSave = vm::saveChoiceOverwrite, onSaveAsNew = vm::saveChoiceAsNew, onCancel = { vm.saveChoice = null })
    }
    if (vm.saveDialog) NameDialog(str(if (vm.savedName != null) Res.string.plan_save_as_new_route else Res.string.plan_save_route),
        vm.savedName ?: vm.defaultName(str(Res.string.plan_route_default_name)),
        onDone = { vm.saveDialog = false; vm.saveRoute(it) }, onDismiss = { vm.saveDialog = false },
        suggestion = vm.suggestedName.takeIf { vm.savedName == null })
    vm.saveFirst?.let { _ ->
        NameDialog(str(Res.string.plan_save_current_route), vm.savedName ?: vm.defaultName(str(Res.string.plan_route_default_name)),
            onDone = { vm.saveFirstDone(it) }, onDismiss = { vm.saveFirst = null },
            suggestion = vm.suggestedName.takeIf { vm.savedName == null })
    }
    vm.replaceAsk?.let { _ ->
        ReplacePlanDialog(onSaveFirst = vm::replaceSaveFirst, onReplace = vm::replaceConfirmed, onCancel = { vm.replaceAsk = null })
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
 * Sheet ⋯ → Save route for a plan that belongs to a saved route [name]: **Save** overwrites it (name stays),
 * **Save as new…** asks for a name and keeps [name] as it was. No Cancel button: a tap outside or Back cancels
 * (like "Replace current plan?").
 */
@Composable
fun SaveChoiceDialog(name: String, unsaved: Boolean, onSave: () -> Unit, onSaveAsNew: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Save, null) },
        title = { Text(str(Res.string.plan_save_route)) },
        text = {
            DialogText(if (unsaved) str(Res.string.plan_save_changes_into, name)
                else str(Res.string.plan_saved_again, name))
        },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.common_save)) } },
        dismissButton = {
            TextButton(onClick = onSaveAsNew, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_save_as_new)) }
        },
    )
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
        confirmButton = { TextButton(onClick = onNavigate, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_navigate_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_fix_route)) } },
    )
}
