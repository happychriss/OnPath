package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31 (replaces "save at once"): every Save opens the name dialog. The flows of that dialog and of the writes
 * behind it – proposal, typed / empty name, a name that exists, overwrite and rename, copy, Cancel, a failed write – are
 * behaviour tests on the real `SaveSlice` now (`:state` `SaveFlowTest`); what stays here is the owner's decision that
 * pins the entry points of the screens (the view model's forwarders and the dialog's wiring).
 */
class SaveDialogTest {
    private val vmSrc = File("../app/src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()

    @Test fun everySaveEntryPointOpensTheDialog() {
        assertTrue(vmSrc.contains("fun requestSave() = save.request(null)"))
        assertFalse("no Replace current plan? any more (owner 2026-11-06, Zuletzt)", vmSrc.contains("fun replaceSaveFirst("))
        val map = File("../app/src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue("☰ Save route – the one Save of the plan", map.contains("onSaveRoute = if (RouteRules.saveInMenu(vm.waypoints.size)) vm::requestSave else null"))
        val main = File("../app/src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue("My routes ⋮ Save as copy", main.contains("onCopy = { vm.requestCopyRoute(it) }"))
        val dialogs = File("../app/src/main/java/com/onpath/app/ui/RouteDialogs.kt").readText()
        assertTrue(dialogs.contains("SaveNameDialog(ask.kind, ask.name, onSave = vm::confirmSave, onCancel = vm::cancelSave)"))
        assertTrue("the name selected", dialogs.contains("TextFieldValue(name, TextRange(0, name.length))"))
    }
}
