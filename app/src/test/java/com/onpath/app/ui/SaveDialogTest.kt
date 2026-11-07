package com.onpath.app.ui

import com.onpath.app.domain.routes.SaveFlow
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31 (replaces "save at once"): every Save opens the name dialog – prefilled with the proposal ("Vellau →
 * Leiteralm", else "Route 31 Oct, 10:12") or the saved route's name, fully selected. A new plan becomes a new route; a
 * saved route is overwritten and renamed when the name changed – still ONE route. My routes → ⋮ → Save as copy
 * proposes "<name> (2)" and saves a copy of the stored route (the plan stays as it is). A blank name keeps the proposal / old name. Cancel saves nothing. The sheet's "Saved as
 * …" is a plain line (no rename in place).
 *
 * The steps are AppViewModel's (RouteRules.saveMode / newRouteName / cleanName + SaveAsk + the saved state), checked
 * here as a model, plus the app's code for the same paths.
 */
class SaveDialogTest {
    private val a = GeoPoint(46.680, 11.140)
    private val b = GeoPoint(46.700, 11.120)
    private val c = GeoPoint(46.710, 11.100)
    private val dated = "Route 31 Oct, 10:12"

    private data class Saved(val id: Long, var name: String, var points: List<GeoPoint>)

    /** The plan, My routes, the dialog and what a save leaves behind (DB, backup file, sync), as AppViewModel holds them. */
    private class Vm(var plan: List<GeoPoint>, val proposal: String?, val dated: String) {
        val routes = mutableListOf<Saved>()
        var linkId = -1L
        var linkName: String? = null
        var linkPoints: List<GeoPoint>? = null
        var saveAsk: SaveAsk? = null
        val syncChanges = mutableListOf<String>()
        val backups = mutableListOf<Long>()
        var loaded = false

        val savedName: String? get() = linkName.takeIf { RouteRules.linkedToSaved(plan, linkPoints) }
        private val savedId: Long get() = if (RouteRules.linkedToSaved(plan, linkPoints)) linkId else -1L
        private fun setSaved(name: String, id: Long, pts: List<GeoPoint>) { linkName = name; linkId = id; linkPoints = pts }

        /** [AppViewModel.requestSave] → askSave (the proposal as it arrives). */
        fun requestSave(then: (() -> Unit)? = null) {
            saveAsk = SaveFlow.ask(RouteRules.saveMode(savedId, savedName, routes.map { it.id }), savedName, savedId,
                proposal, dated, routes.map { it.name }, then)
        }

        /** [AppViewModel.requestCopyRoute] – My routes → ⋮ → Save as copy. */
        fun requestCopyRoute(t: Saved) {
            saveAsk = SaveFlow.askCopy(t.name, t.id, routes.map { it.name })
        }

        /** [AppViewModel.confirmSave] – the dialog's Save with what the field holds. */
        fun confirmSave(input: String) {
            val ask = saveAsk ?: return
            saveAsk = null
            when (val w = SaveFlow.confirm(ask, input, savedId)) { // the app's own decision, then the write as the VM does it
                is SaveFlow.Write.Copy -> { // [AppViewModel.copyRoute]: the stored route, the plan's link stays
                    val src = routes.first { it.id == w.routeId }
                    val id = (routes.maxOfOrNull { it.id } ?: 0) + 1
                    routes += Saved(id, w.name, src.points); backups += id; syncChanges += "route copied"
                }
                is SaveFlow.Write.Overwrite -> {
                    val r = routes.first { it.id == w.routeId }
                    r.points = plan; r.name = w.name; backups += r.id; syncChanges += "route overwritten"; setSaved(w.name, r.id, plan)
                }
                is SaveFlow.Write.New -> {
                    val id = (routes.maxOfOrNull { it.id } ?: 0) + 1
                    routes += Saved(id, w.name, plan); backups += id; syncChanges += "route saved"; setSaved(w.name, id, plan)
                }
            }
            ask.then?.invoke()
        }

        fun cancelSave() { saveAsk = null }
    }

    @Test fun saveOpensTheDialogWithTheProposal() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave()
        assertEquals(SaveAsk.Kind.NEW, vm.saveAsk?.kind)
        assertEquals("Vellau → Leiteralm", vm.saveAsk?.name)
        assertTrue("nothing saved before the dialog's Save", vm.routes.isEmpty())
        vm.confirmSave("Vellau → Leiteralm")
        assertEquals(listOf("Vellau → Leiteralm"), vm.routes.map { it.name })
        assertEquals("the sheet says \"Saved as …\"", "Vellau → Leiteralm", vm.savedName)
    }

    @Test fun withoutAProposalTheDateName() {
        val vm = Vm(listOf(a, b), proposal = null, dated = dated)
        vm.requestSave()
        assertEquals(dated, vm.saveAsk?.name)
    }

    @Test fun aTypedNameIsTheRoutesName() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("  Leiteralm loop ")
        assertEquals(listOf("Leiteralm loop"), vm.routes.map { it.name })
    }

    @Test fun anEmptyNameKeepsTheProposal() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("   ")
        assertEquals(listOf("Vellau → Leiteralm"), vm.routes.map { it.name })
    }

    @Test fun cancelSavesNothingAndDoesNotLoad() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave { vm.loaded = true } // "Replace current plan?" → Save first
        vm.cancelSave()
        assertNull(vm.saveAsk)
        assertTrue(vm.routes.isEmpty())
        assertFalse("Save first + Cancel loads nothing", vm.loaded)
    }

    @Test fun saveFirstLoadsAfterTheDialogsSave() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave { vm.loaded = true }
        vm.confirmSave("Vellau → Leiteralm")
        assertTrue(vm.loaded)
    }

    @Test fun aSavedRouteShowsItsNameAndIsOverwritten() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("Vellau → Leiteralm")
        vm.plan = listOf(a, b, c) // an edit: "… changed, not saved", Save again
        vm.requestSave()
        assertEquals(SaveAsk.Kind.OVERWRITE, vm.saveAsk?.kind)
        assertEquals("the current name, not a proposal", "Vellau → Leiteralm", vm.saveAsk?.name)
        vm.confirmSave("Vellau → Leiteralm")
        assertEquals("still one route", 1, vm.routes.size)
        assertEquals(listOf(a, b, c), vm.routes[0].points)
        assertFalse(RouteRules.isUnsaved(vm.plan, vm.linkPoints))
        assertEquals(listOf("route saved", "route overwritten"), vm.syncChanges)
    }

    @Test fun overwriteWithAChangedNameRenamesTheSameRoute() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("Vellau → Leiteralm")
        vm.plan = listOf(a, b, c)
        vm.requestSave(); vm.confirmSave("Leiteralm loop")
        assertEquals("one route, renamed", listOf("Leiteralm loop"), vm.routes.map { it.name })
        assertEquals(listOf(a, b, c), vm.routes[0].points)
        assertEquals("\"Saved as\" follows", "Leiteralm loop", vm.savedName)
    }

    @Test fun overwriteWithAnEmptyNameKeepsTheOldOne() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("Vellau → Leiteralm")
        vm.plan = listOf(a, b, c)
        vm.requestSave(); vm.confirmSave("")
        assertEquals(listOf("Vellau → Leiteralm"), vm.routes.map { it.name })
    }

    @Test fun saveAsCopyFromMyRoutesProposesNameTwoAndCopiesTheStoredRoute() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("Vellau → Leiteralm")
        vm.plan = listOf(a, b, c) // changed, not saved – the copy is of the stored route
        vm.requestCopyRoute(vm.routes[0])
        assertEquals(SaveAsk.Kind.COPY, vm.saveAsk?.kind)
        assertEquals("Vellau → Leiteralm (2)", vm.saveAsk?.name)
        vm.confirmSave("Vellau → Leiteralm (2)")
        assertEquals(listOf("Vellau → Leiteralm", "Vellau → Leiteralm (2)"), vm.routes.map { it.name })
        assertEquals("the copy is the stored route", listOf(a, b), vm.routes[1].points)
        assertEquals("the plan still belongs to the original", 1L, vm.linkId)
    }

    @Test fun aCopyWithAnEmptyNameKeepsTheProposal() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.requestSave(); vm.confirmSave("Vellau → Leiteralm")
        vm.requestCopyRoute(vm.routes[0]); vm.confirmSave(" ")
        assertEquals("Vellau → Leiteralm (2)", vm.routes[1].name)
    }

    // ---- wiring pins (no JVM harness for AppViewModel / TourDb): the entry points and the one DB write

    private val vmSrc = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vmSrc.substringAfter(start).substringBefore("\n    }\n")

    @Test fun everySaveEntryPointOpensTheDialog() {
        assertTrue(vmSrc.contains("fun requestSave() = askSave(null)"))
        assertFalse("no Replace current plan? any more (owner 2026-11-06, Zuletzt)", vmSrc.contains("fun replaceSaveFirst("))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue("☰ Save route – the one Save of the plan", map.contains("onSaveRoute = if (RouteRules.saveInMenu(vm.waypoints.size)) vm::requestSave else null"))
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue("My routes ⋮ Save as copy", main.contains("onCopy = { vm.requestCopyRoute(it) }"))
        assertTrue(body("fun confirmSave(").contains("is com.onpath.app.domain.routes.SaveFlow.Write.Copy -> copyRoute(w.routeId, w.name)"))
        val dialogs = File("src/main/java/com/onpath/app/ui/RouteDialogs.kt").readText()
        assertTrue(dialogs.contains("SaveNameDialog(ask.kind, ask.name, onSave = vm::confirmSave, onCancel = vm::cancelSave)"))
        assertTrue("the name selected", dialogs.contains("TextFieldValue(name, TextRange(0, name.length))"))
    }

    @Test fun overwriteRenamesInTheSameWrite() {
        val ow = body("private fun overwriteSaved(")
        assertTrue(ow, ow.contains("db.replacePlanned("))
        assertTrue(ow, ow.contains("if (it && name != old) db.rename(id, name)"))
        assertTrue(ow, ow.contains("setSaved(name, id, pts)"))
        assertTrue(ow, ow.contains("|| saving) return"))
    }
}
