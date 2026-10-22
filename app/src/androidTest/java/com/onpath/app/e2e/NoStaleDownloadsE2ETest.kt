package com.onpath.app.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.MainActivity
import com.onpath.app.cells.CellId
import com.onpath.app.data.Prefs
import com.onpath.app.routing.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * CORE FEATURE C12 "no stale or unexpected downloads" end to end (field bug 2026-10-22: 16 corridor regions of a long
 * route Bozen → Germany stayed in the download queue and loaded again after every app start on Wi-Fi).
 *
 * The saved plan is that long route and the saved queue still holds its corridor (as on the owner's phone). The app
 * starts → the long-route question → Cancel → the app is started again → the queue holds no corridor region and nothing
 * of it was downloaded.
 */
@RunWith(AndroidJUnit4::class)
class NoStaleDownloadsE2ETest : E2EBase() {

    private val bozen = GeoPoint(46.4983, 11.3548)
    private val munich = GeoPoint(48.1374, 11.5755)

    private val queueFile: File get() = File(dataDir, "cells/requests.json")

    private fun corridorInQueue(): List<String> {
        val f = queueFile
        if (!f.isFile) return emptyList()
        val a = JSONObject(f.readText()).optJSONArray("cells") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("reason") == "CORRIDOR" }.map { it.getString("id") }
    }

    @Test fun longRouteCancelledThenRestartLeavesNoCorridorInTheQueue() {
        launch(beforeLaunch = {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Prefs(ctx).planWaypoints = listOf(bozen, munich)
            // the corridor of that route still queued from an earlier run
            val corridor = CellId.alongLine(listOf(bozen, munich), 10_000.0).take(16)
            File(dataDir, "cells").mkdirs()
            queueFile.writeText(JSONObject().put("cells", JSONArray(corridor.map {
                JSONObject().put("id", it.id).put("reason", "CORRIDOR").put("own", false).put("at", System.currentTimeMillis() - 3600_000).put("mobileOk", false)
            })).toString())
        })
        // the restored plan is far beyond a day: asked first, nothing routed or loaded
        clickText("Cancel")
        waitUntil("corridor dropped") { corridorInQueue().isEmpty() }
        var waiting = -1
        scenario.onActivity { waiting = vm(it).cells.waiting().regions }
        assertEquals(0, waiting)

        // restart the app: still nothing
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForMap()
        Thread.sleep(3_000)
        assertTrue("no corridor region queued after the restart", corridorInQueue().isEmpty())
        var installed = -1
        scenario.onActivity { installed = vm(it).cells.installed.value.size }
        assertEquals("nothing of the corridor was downloaded", 0, installed)
    }
}
