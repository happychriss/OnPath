package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31, "wie Word" (like the browser planner): Save saves at once – no name dialog – under a proposed
 * name ("Vellau → Leiteralm", else "Route 31 Oct, 10:12"); saving again after edits overwrites the same route. The
 * sheet's "Saved as “Name”" is tapped to rename in place, through the same code as My routes → Rename (backup file,
 * sync change). A blank name keeps the old one.
 *
 * The steps are AppViewModel's (RouteRules.saveMode / newRouteName / cleanName + the saved state), checked here as a
 * model like [TourRouteBlockedTest], plus the app's code for the same paths.
 */
class SaveAtOnceTest {
    private val a = GeoPoint(46.680, 11.140)
    private val b = GeoPoint(46.700, 11.120)
    private val c = GeoPoint(46.710, 11.100)
    private val dated = "Route 31 Oct, 10:12"

    private data class Saved(val id: Long, var name: String, var points: List<GeoPoint>)

    /** The plan, My routes and what a save / rename leaves behind (DB, backup file, sync), as AppViewModel holds them. */
    private class Vm(var plan: List<GeoPoint>, val proposal: String?, val dated: String) {
        val routes = mutableListOf<Saved>()
        var linkId = -1L
        var linkName: String? = null
        var linkPoints: List<GeoPoint>? = null
        val syncChanges = mutableListOf<String>()
        val backups = mutableListOf<Long>()

        val savedName: String? get() = linkName.takeIf { RouteRules.linkedToSaved(plan, linkPoints) }
        private val savedId: Long get() = if (RouteRules.linkedToSaved(plan, linkPoints)) linkId else -1L
        private fun setSaved(name: String, id: Long, pts: List<GeoPoint>) { linkName = name; linkId = id; linkPoints = pts }

        /** [AppViewModel.requestSave]. */
        fun save() = when (RouteRules.saveMode(savedId, savedName, routes.map { it.id })) {
            SaveMode.OVERWRITE -> routes.first { it.id == savedId }.let { r ->
                r.points = plan; backups += r.id; syncChanges += "route overwritten"; setSaved(r.name, r.id, plan)
            }
            SaveMode.NEW_ROUTE -> saveAsNew()
        }

        /** [AppViewModel.saveAsNew]. */
        fun saveAsNew() {
            val name = RouteRules.newRouteName(proposal, dated, routes.map { it.name })
            val id = (routes.maxOfOrNull { it.id } ?: 0) + 1
            routes += Saved(id, name, plan); backups += id; syncChanges += "route saved"; setSaved(name, id, plan)
        }

        /** [AppViewModel.renameTour] – My routes → Rename. */
        fun renameTour(t: Saved, input: String) {
            val name = RouteRules.cleanName(input, t.name)
            if (name == t.name) return
            t.name = name; backups += t.id; syncChanges += "renamed"
            if (linkId == t.id) setSaved(name, linkId, linkPoints!!)
        }

        /** [AppViewModel.renamePlan] – the sheet's "Saved as …" field. */
        fun renamePlan(input: String) {
            if (savedName == null) return
            routes.firstOrNull { it.id == linkId }?.let { renameTour(it, input) }
        }
    }

    @Test fun aNewPlanIsSavedAtOnceUnderTheProposedName() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.save()
        assertEquals(listOf("Vellau → Leiteralm"), vm.routes.map { it.name })
        assertEquals("the sheet says \"Saved as …\"", "Vellau → Leiteralm", vm.savedName)
        assertEquals(listOf("route saved"), vm.syncChanges)
    }

    @Test fun withoutAProposalTheDateName() {
        val vm = Vm(listOf(a, b), proposal = null, dated = dated)
        vm.save()
        assertEquals(dated, vm.savedName)
    }

    @Test fun savingAgainAfterEditsOverwritesTheSameRoute() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.save()
        vm.plan = listOf(a, b, c) // an edit: "… changed, not saved", Save beside ⋯ again
        assertTrue(RouteRules.isUnsaved(vm.plan, vm.linkPoints))
        vm.save()
        assertEquals("still one route", 1, vm.routes.size)
        assertEquals(listOf(a, b, c), vm.routes[0].points)
        assertEquals("Vellau → Leiteralm", vm.savedName)
        assertFalse(RouteRules.isUnsaved(vm.plan, vm.linkPoints))
    }

    @Test fun saveAsNewKeepsTheSavedRouteAndNamesTheCopyApart() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.save()
        vm.plan = listOf(a, b, c)
        vm.saveAsNew() // sheet ⋯ → Save as new route
        assertEquals(listOf("Vellau → Leiteralm", "Vellau → Leiteralm (2)"), vm.routes.map { it.name })
        assertEquals(listOf(a, b), vm.routes[0].points)
        assertEquals("Vellau → Leiteralm (2)", vm.savedName)
    }

    @Test fun renameFromThePlanSheet() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.save()
        vm.renamePlan("  Leiteralm loop ")
        assertEquals("Leiteralm loop", vm.routes[0].name)
        assertEquals("\"Saved as\" follows", "Leiteralm loop", vm.savedName)
    }

    @Test fun anEmptyNameKeepsTheOldOne() {
        val vm = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated)
        vm.save()
        vm.renamePlan("   ")
        assertEquals("Vellau → Leiteralm", vm.routes[0].name)
        assertEquals("nothing to sync", listOf("route saved"), vm.syncChanges)
    }

    @Test fun aRenameFromTheSheetSyncsLikeOneFromMyRoutes() {
        val sheet = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated).apply { save(); renamePlan("Leiteralm loop") }
        val routes = Vm(listOf(a, b), proposal = "Vellau → Leiteralm", dated = dated).apply { save(); renameTour(this.routes[0], "Leiteralm loop") }
        assertEquals(listOf("route saved", "renamed"), sheet.syncChanges)
        assertEquals(routes.syncChanges, sheet.syncChanges)
        assertEquals(routes.backups, sheet.backups)
        assertEquals(routes.savedName, sheet.savedName)
    }

    // ---- the app's code

    private val vmSrc = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vmSrc.substringAfter(start).substringBefore("\n    }\n")

    @Test fun noSaveDialogIsLeft() {
        for (gone in listOf("saveDialog", "saveChoice", "SaveChoice", "saveFirst", "suggestedName"))
            assertFalse("$gone removed", vmSrc.contains(gone))
        val dialogs = File("src/main/java/com/onpath/app/ui/RouteDialogs.kt").readText()
        assertFalse(dialogs.contains("NameDialog("))
        assertFalse(dialogs.contains("SaveChoiceDialog"))
        // every Save goes through save(): sheet button, ☰ "Save route", sheet ⋯, "Replace current plan?" → Save first
        assertTrue(vmSrc.contains("fun requestSave() = save(null)"))
        assertTrue(body("fun replaceSaveFirst(").contains("save { open(t) }"))
        val save = body("private fun save(")
        assertTrue(save, save.contains("SaveMode.OVERWRITE -> overwriteSaved(then)"))
        assertTrue(save, save.contains("SaveMode.NEW_ROUTE -> saveAsNew(then)"))
    }

    @Test fun aNewRouteIsNamedByTheRuleAndASecondTapSavesNothing() {
        val asNew = body("fun saveAsNew(")
        assertTrue(asNew, asNew.contains("RouteRules.newRouteName(proposed, defaultName("))
        assertTrue(asNew, asNew.contains("|| saving) return"))
        assertTrue(asNew, asNew.contains("finally { saving = false }"))
        assertTrue(body("private fun overwriteSaved(").contains("|| saving) return"))
    }

    @Test fun theSheetRenameIsMyRoutesRename() {
        assertTrue(body("fun renamePlan(").contains("renameTour(t, input)"))
        val rename = body("fun renameTour(")
        for (step in listOf("RouteRules.cleanName(input, t.name)", "db.rename(t.id, name)", "Backups.tourChanged(", "sync.changed(\"renamed\")",
            "setSaved(name, linkId, savedPoints)"))
            assertTrue("renameTour: $step", rename.contains(step))
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue("My routes → Rename", main.contains("onRename = { t, name -> vm.renameTour(t, name) }"))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("onRename = vm::renamePlan"))
        assertTrue("Back cancels", map.contains("BackHandler { vm.cancelRenamePlan() }"))
    }
}
