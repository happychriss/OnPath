package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * There are three ways to end a tour – End tour, "You have arrived" → Save
 * and the "Interrupted tour" dialog after a restart. All three show the bus / car / lift summary when a ride was found
 * (core feature C12), and the unfinished dialog has Continue · Delete · Save like End tour.
 */
class TourEndPathsTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
    private val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
    private val screens = File("src/main/java/com/onpath/app/ui/Screens.kt").readText()

    private fun body(src: String, start: String) = src.substringAfter(start).substringBefore("\n    }\n")

    @Test fun arrivalSaveRunsTheRideCheck() {
        val arrival = map.substringAfter("ArrivalDialog(").substringBefore("\n")
        assertTrue(arrival, arrival.contains("vm.askEndTour(saveIfNoRides = true)"))
        assertFalse(arrival, arrival.contains("onSave = vm::endTour"))
        val ask = body(vm, "fun askEndTour(")
        assertTrue(ask, ask.contains("EndAsk.Rides(id, s)") && ask.contains("saveIfNoRides -> endTour()"))
    }

    /** Field bug 2026-10-31: after a reboot the map behind "Interrupted tour" was empty – no service, no track. */
    @Test fun unfinishedTourIsDrawnBehindItsQuestion() {
        val start = vm.substringAfter("val active = db.findActiveRecording()").substringBefore("\n        }\n")
        assertTrue(start, start.contains("db.loadSegments(active.id)") && start.contains("unfinishedTrack = line; unfinished = active"))
        val draw = map.substringAfter("val recTrack =").substringBefore("\n")
        assertTrue(draw, draw.contains("rec?.track ?: vm.unfinished?.let { vm.unfinishedTrack }"))
        assertTrue(map.contains("c.setTrack(MapController.SRC_REC, recTrack)"))
    }

    @Test fun unfinishedSaveRunsTheRideCheckAndAppliesTheChoice() {
        val ask = body(vm, "fun askSaveUnfinished(")
        assertTrue(ask, ask.contains("RideDetector.detect") && ask.contains("unfinishedRides = t to s"))
        val save = body(vm, "fun saveUnfinished(")
        assertTrue(save, save.contains("RideApply.choose") && save.contains("RideApply.finish"))
        assertTrue(main.contains("onSave = { vm.askSaveUnfinished(t) }"))
        assertTrue(main.contains("RideSummaryDialog(s, onSave = { vm.saveUnfinished(t, it) }, onDelete = { vm.deleteUnfinished(t) }"))
    }

    /** Delete asks no second question (owner 2026-11-01: "ist ja sowieso alles im Papierkorb") – the grey bin note under it. */
    @Test fun unfinishedDialogHasContinueDeleteSaveAndTheBinNote() {
        val dlg = screens.substringAfter("fun UnfinishedDialog(").substringBefore("\n}\n")
        // texts as string resources (EN + DE): Continue, Save; Delete + the note come with DeleteApartButtons
        listOf("Res.string.routes_unfinished_continue", "Res.string.common_save", "DeleteApartButtons(onDelete,",
            "onDismissRequest = {}").forEach {
            assertTrue(it, dlg.contains(it))
        }
        assertFalse(dlg, dlg.contains("askDelete"))
        val bar = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()
        val buttons = bar.substringAfter("internal fun DeleteApartButtons(").substringBefore("\n}\n")
        // V2 order (owner 2026-11-05): Save filled, the way back outlined, the quiet Delete with the bin note
        assertTrue(buttons, buttons.indexOf("EndSaveButton(") in 0 until buttons.indexOf("EndKeepButton(") &&
            buttons.indexOf("EndKeepButton(") < buttons.indexOf("EndDeleteArea("))
        val delete = bar.substringAfter("private fun EndDeleteArea(").substringBefore("\n}\n")
        assertTrue(delete, delete.contains("Res.string.nav_delete_tour") && delete.contains("Text(RECYCLE_BIN_NOTE"))
        val del = body(vm, "fun deleteUnfinished(")
        // done first: from the recycle bin it comes back as a normal tour, never as "unfinished" again
        assertTrue(del, del.indexOf("\"done\"") in 0 until del.indexOf("setDeleted"))
    }
}
