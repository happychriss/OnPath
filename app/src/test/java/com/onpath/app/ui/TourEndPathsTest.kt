package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * There are three ways to end a tour – End tour, "You have arrived" → Save
 * and the "Unfinished tour" dialog after a restart. All three show the bus / car / lift summary when a ride was found
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

    @Test fun unfinishedSaveRunsTheRideCheckAndAppliesTheChoice() {
        val ask = body(vm, "fun askSaveUnfinished(")
        assertTrue(ask, ask.contains("RideDetector.detect") && ask.contains("unfinishedRides = t to s"))
        val save = body(vm, "fun saveUnfinished(")
        assertTrue(save, save.contains("RideApply.choose") && save.contains("RideApply.finish"))
        assertTrue(main.contains("onSave = { vm.askSaveUnfinished(t) }"))
        assertTrue(main.contains("RideSummaryDialog(s, onSave = { vm.saveUnfinished(t, it) }, onDelete = { vm.deleteUnfinished(t) }"))
    }

    @Test fun unfinishedDialogHasContinueDeleteSaveAndDeleteAsksAgain() {
        val dlg = screens.substringAfter("fun UnfinishedDialog(").substringBefore("\n}\n")
        // texts as string resources (EN + DE): Continue, Delete, Save, "Delete this tour?"
        listOf("Res.string.routes_unfinished_continue", "Res.string.common_delete", "Res.string.common_save",
            "Res.string.routes_unfinished_delete_title", "onDismissRequest = {}").forEach {
            assertTrue(it, dlg.contains(it))
        }
        val del = body(vm, "fun deleteUnfinished(")
        // done first: from the recycle bin it comes back as a normal tour, never as "unfinished" again
        assertTrue(del, del.indexOf("\"done\"") in 0 until del.indexOf("setDeleted"))
    }
}
