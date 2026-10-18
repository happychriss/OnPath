package com.onpath.app.ui

import androidx.compose.foundation.layout.Row
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
 * Dialogs of saving / loading routes, drawn above every screen (map, Manage routes): Save route name,
 * "Replace current plan?", and a save problem. None of them closes by itself.
 */
@Composable
fun RouteDialogs(vm: AppViewModel) {
    vm.saveChoice?.let { _ ->
        SaveChoiceDialog(vm.savedName ?: "", unsaved = vm.planUnsaved,
            onSave = vm::saveChoiceOverwrite, onSaveAsNew = vm::saveChoiceAsNew, onCancel = { vm.saveChoice = null })
    }
    if (vm.saveDialog) NameDialog(if (vm.savedName != null) "Save as new route" else "Save route",
        vm.savedName ?: vm.defaultName("Route"),
        onDone = { vm.saveDialog = false; vm.saveRoute(it) }, onDismiss = { vm.saveDialog = false })
    vm.saveFirst?.let { _ ->
        NameDialog("Save current route", vm.savedName ?: vm.defaultName("Route"),
            onDone = { vm.saveFirstDone(it) }, onDismiss = { vm.saveFirst = null })
    }
    vm.replaceAsk?.let { _ ->
        ReplacePlanDialog(onSaveFirst = vm::replaceSaveFirst, onReplace = vm::replaceConfirmed, onCancel = { vm.replaceAsk = null })
    }
    vm.routesProblem?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.routesProblem = null },
            title = { Text("Not saved") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.routesProblem = null }) { Text("OK") } },
        )
    }
}

/**
 * ⋮ → Save route for a plan that belongs to a saved route [name]: **Save** overwrites it (name stays),
 * **Save as new…** asks for a name and keeps [name] as it was. Stays until answered (tap outside / Back = Cancel).
 */
@Composable
fun SaveChoiceDialog(name: String, unsaved: Boolean, onSave: () -> Unit, onSaveAsNew: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Save, null) },
        title = { Text("Save route") },
        text = {
            Text(if (unsaved) "Save your changes into “$name”, or keep “$name” as it was and save a new route."
                else "“$name” is saved. Save it again, or save a copy as a new route.")
        },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                TextButton(onClick = onSaveAsNew, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save as new…") }
            }
        },
    )
}
