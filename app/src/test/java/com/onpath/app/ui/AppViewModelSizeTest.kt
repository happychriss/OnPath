package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The view model only shrinks (code audit 2026-11-12, owner: one slice per round out of AppViewModel after the
 * TourController pattern): this fails above the line count the last slice left. New state and logic go into a state
 * holder of their own (ui/tour/TourController, ui/routes/RoutesState, ui/routes/RecentPlans, ui/sync/SyncDriver, ui/plan/PlanDocument …), the view model keeps
 * a thin forwarder. Lower [MAX_LINES] after each slice; never raise it to make room.
 */
class AppViewModelSizeTest {
    @Test fun theViewModelDoesNotGrow() {
        val lines = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readLines().size
        assertTrue("AppViewModel.kt has $lines lines, more than $MAX_LINES: put new state / logic into a state holder " +
            "(ui/tour/TourController, ui/routes/RecentPlans) and forward to it", lines <= MAX_LINES)
    }

    private companion object {
        /**
         * After cleanup piece 4: slice 1 (Zuletzt + recycle bin → ui/routes/RecentPlans) 5,970 → 5,900; slice 2 (sync
         * triggers → ui/sync/SyncDriver, plan links → ui/sync/PlanLinkFlow) → 5,826; slice 3 (the plan's points, history,
         * pins and saved link with their storing → ui/plan/PlanDocument) → 5,812; the review's dead copy and unused
         * imports out → 5,792. Cleanup piece 5: the service's Intents → recording/RecordingClient, the followed route's
         * files → navigation/FollowStore → 5,760.
         */
        const val MAX_LINES = 3_209
    }
}
