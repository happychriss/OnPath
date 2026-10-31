package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Dialogs of saving / loading routes, drawn above every screen (map, My routes): "Replace current plan?", "Navigate
 * anyway?" and a save problem. None of them closes by itself. Save itself asks nothing (owner 2026-10-31, "wie Word"):
 * it saves at once, the sheet's "Saved as …" renames in place.
 */
@Composable
fun RouteDialogs(vm: AppViewModel) {
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
        confirmButton = { TextButton(onClick = onNavigate, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_navigate_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(str(Res.string.plan_fix_route)) } },
    )
}
