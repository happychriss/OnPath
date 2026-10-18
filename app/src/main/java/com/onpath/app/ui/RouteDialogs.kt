package com.onpath.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Dialogs of saving / loading routes, drawn above every screen (map, Manage routes): Save route name,
 * "Replace current plan?", and a save problem. None of them closes by itself.
 */
@Composable
fun RouteDialogs(vm: AppViewModel) {
    if (vm.saveDialog) NameDialog("Save route", vm.savedName ?: vm.defaultName("Route"),
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
