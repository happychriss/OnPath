package com.onpath.app.ui

import androidx.compose.runtime.Composable
import com.onpath.app.ui.routes.RestoreAsk
import com.onpath.app.ui.routes.RestoreBox
import com.onpath.strings.*

/** Plan's "Restore your old plan?" box while its question waits (only while the old plan is still in My routes, never during a tour). */
@Composable
internal fun restoreAskMessage(vm: AppViewModel, touring: Boolean): Message? =
    RestoreAsk.box(vm.save.restoreAsk, vm.routes.tours, touring)?.let { restoreAskMessage(it, onAnswer = vm.routesGo::answerRestore) }

/**
 * A new plan replaced the plan on the map (owner 2026-11-24, approved from a picture, [com.onpath.app.ui.routes.RestoreAsk]):
 * "Deinen alten Plan wiederherstellen?" + where the old plan is now – Behalten · **Wiederherstellen** (blue: planning). FRAGE,
 * stays until answered.
 */
@Composable
internal fun restoreAskMessage(box: RestoreBox, onAnswer: (Boolean) -> Unit): Message =
    Message(MessageKind.FRAGE, str(Res.string.routes_restore_title),
        str(if (box.underRecent) Res.string.routes_restore_recent else Res.string.routes_restore_saved, box.name),
        buttons = listOf(
            MessageButton(str(Res.string.routes_restore_keep)) { onAnswer(false) },
            MessageButton(str(Res.string.common_restore)) { onAnswer(true) },
        ), tone = ButtonTone.EDIT) // bringing a plan back = planning
