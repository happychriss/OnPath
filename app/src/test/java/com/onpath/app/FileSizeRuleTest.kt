package com.onpath.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one size rule (architecture plan 2026-11, rebuild phase 2 piece 21): no production Kotlin file in `:app`, `:core` or `:state`
 * has more than [LIMIT] lines, except the files named in [TODAYS_LARGER_FILES] – each with the line count it had when the rule came
 * (2026-11-19) as its cap: a listed file only ever shrinks (lower its number when it does, never raise it), a new file above the limit
 * fails. It replaces `AppViewModelSizeTest`, which held `AppViewModel.kt` alone. A file that gets down to [LIMIT] leaves the list.
 * New state and logic go into a state holder of their own (a slice in `:state`), not into a file that is already large.
 */
class FileSizeRuleTest {
    private val roots = listOf("src/main", "../core/src/main", "../state/src/main")

    private fun production(): List<File> = roots.flatMap { r -> File(r).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun key(f: File): String = "com/onpath/app/" + f.path.replace('\\', '/').substringAfter("/com/onpath/app/")

    @Test fun noFileIsAboveTheLimitExceptTheNamedOnesAndThoseOnlyShrink() {
        val files = production()
        assertTrue("the three source roots were found (run from the app module): ${files.size} files", files.size > 400)
        val tooBig = mutableListOf<String>()
        for (f in files) {
            val lines = f.readLines().size
            val cap = TODAYS_LARGER_FILES[key(f)]
            if (cap == null && lines > LIMIT) tooBig += "${key(f)}: $lines lines – above $LIMIT and not on the list: split it (a slice of its own), do not add it here"
            if (cap != null && lines > cap) tooBig += "${key(f)}: $lines lines – above its cap $cap: it only ever shrinks (put the new code into a slice of its own)"
        }
        assertTrue(tooBig.joinToString("\n"), tooBig.isEmpty())
    }

    @Test fun aListedFileThatIsDownToTheLimitLeavesTheList() {
        val byKey = production().associateBy { key(it) }
        val stale = mutableListOf<String>()
        for ((name, cap) in TODAYS_LARGER_FILES) {
            val f = byKey[name]
            if (f == null) { stale += "$name is on the list but does not exist (moved? rename it here)"; continue }
            val lines = f.readLines().size
            if (lines <= LIMIT) stale += "$name has $lines lines now (limit $LIMIT): take it off the list"
            else if (lines < cap) stale += "$name has $lines lines, its cap is $cap: lower the cap to $lines (the list only ever shrinks)"
        }
        assertTrue(stale.joinToString("\n"), stale.isEmpty())
    }

    private companion object {
        const val LIMIT = 800

        /** Today's larger files (2026-11-19, after rebuild phase 2 piece 21) with their line counts as caps. */
        val TODAYS_LARGER_FILES = mapOf(
            "com/onpath/app/ui/AppViewModel.kt" to 1_935,
            "com/onpath/app/recording/RecordingService.kt" to 1_782,
            "com/onpath/app/ui/NavigateScreen.kt" to 1_318,
            "com/onpath/app/cells/MapCells.kt" to 1_296,
            "com/onpath/app/map/MapController.kt" to 1_262,
            "com/onpath/app/ui/Screens.kt" to 1_065,
            "com/onpath/app/ui/SettingsScreen.kt" to 1_033,
            "com/onpath/app/ui/MapDataFlow.kt" to 992,
            "com/onpath/app/ui/MapScreen.kt" to 923,
            "com/onpath/app/MainActivity.kt" to 923,
            "com/onpath/app/ui/PlanPanel.kt" to 837,
            "com/onpath/app/cells/CellStore.kt" to 806,
        )
    }
}
