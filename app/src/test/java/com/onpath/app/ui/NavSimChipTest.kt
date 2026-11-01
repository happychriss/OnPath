package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner 2026-11-01: the "Tour-Sim" chip on the Navigate page only after the developer tools unlock – never for testers. */
class NavSimChipTest {
    @Test fun onlyWithDeveloperToolsUnlocked() {
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.contains("sim = if (!vm.devToolsShown) null else {"))
        // default: no chip (every other caller, the screenshots)
        assertTrue(nav.contains("sim: (@Composable () -> Unit)? = null,"))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val start = vm.substringAfter("fun startNavSim(").substringBefore("\n    }\n")
        assertTrue("rides the followed line first", start.indexOf("line?.takeIf { it.size >= 2 }") in 0 until start.indexOf("demoLine()"))
        assertTrue(start.contains("DemoLocation.startRide(path, label)"))
    }
}
