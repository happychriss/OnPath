package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * „Markierten Wegen folgen“ reaches every engine with the same level (owner 2026-11-27): planning and the tour's
 * re-routing both take their engine from EngineProvider.create, which hands BRouterEngine the stored setting; the
 * suggestions read the same; BRouterEngine puts it into the profile parameters and its result cache's key. Fails when
 * an engine is built another way (it would route with another level).
 */
class MarkedTrailsWiringTest {
    private fun src(path: String) = File(path).readText()

    @Test fun planningAndReroutingTakeTheSameEngineWithTheSetting() {
        val provider = src("src/main/java/com/onpath/app/routing/EngineProvider.kt")
        assertTrue(provider.contains("BRouterEngine(context.applicationContext, segmentsDir, markedTrails(context), searchCost(context))"))
        assertTrue(provider.contains("return { prefs.markedTrails }"))
        val vm = src("src/main/java/com/onpath/app/ui/AppViewModel.kt")
        val service = src("src/main/java/com/onpath/app/recording/RecordingService.kt")
        assertTrue(vm.contains("override fun create() = EngineProvider.create(getApplication(), dataFiles.segmentsDir)"))
        assertTrue(service.contains("EngineProvider.create(this@RecordingService, DataFiles(this@RecordingService).segmentsDir)"))
        // no engine anywhere else in the app is built by hand
        val built = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("BRouterEngine(") && it.name != "BRouterEngine.kt" }.map { it.name }.toList()
        assertEquals(listOf("EngineProvider.kt"), built)
        // the suggestions' profile reads the same setting
        assertTrue(src("src/main/java/com/onpath/app/suggest/app/AppSuggest.kt")
            .contains("markedTrails = com.onpath.app.routing.EngineProvider.markedTrails(app),"))
        // the fingerprint follows the same stored level
        assertTrue(vm.contains("private val routingFingerprint: String get() = EngineProvider.fingerprint(getApplication())"))
        assertTrue(provider.contains("fun fingerprint(context: Context): String = markedTrails(context)().let { t ->"))
    }

    @Test fun theEngineRoutesAndCachesWithTheLevel() {
        val engine = src("src/main/java/com/onpath/app/routing/BRouterEngine.kt")
        assertTrue(engine.contains("val trails = MarkedTrails.of(markedTrails())"))
        assertTrue(engine.contains("val key = cacheKey(waypoints, activity, sacLimit, trails)"))
        assertTrue(engine.contains("rc.keyValues = keyValues(activity, sacLimit, trails)"))
        assertTrue(engine.contains("kv.putAll(MarkedTrails.keyValues(activity, trails))"))
    }

    /** The one stored key: what Settings writes is what the engines read. */
    @Test fun settingsAndEnginesShareTheKey() {
        val prefs = src("src/main/java/com/onpath/app/data/Prefs.kt")
        assertTrue(prefs.contains(".putInt(\"markedTrails\", v.markedTrails)"))
        assertEquals(3, Regex("\"markedTrails\"").findAll(prefs).count())
    }
}
