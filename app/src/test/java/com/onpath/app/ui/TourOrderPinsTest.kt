package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the tour's statements in `AppViewModel.kt`, pinned as source text until the tour pieces 16b–19 move the
 * functions (docs/design/umbau-phase-2-tour.md §3 = the 36 order rules, §4.2 = these pins P1–P22). The tour code cannot
 * run on the JVM, so no behaviour test sees a swap of two lines in it – and the one thing a swap breaks is a tour: the
 * guidance follows the old route, a finished tour is handled twice, the service is told before the file it must read.
 *
 * A pin = a scope (from an anchor to an anchor, both found exactly once) and texts that must occur in that order inside
 * it (each exactly once, unless written [last]). Every pin is deleted in the piece that moves its function and replaced
 * by the flow test of paper §4.4. A failure names the rule and says what a user would see.
 *
 * If an anchor is not found: the code was edited – fix the pin to the new text first, never the order.
 */
class TourOrderPinsTest {
    private val src: String by lazy { sourceFile("ui/AppViewModel.kt").readText() }

    /** The text meant is the LAST one in the scope (the same statement also stands earlier, on a failure path). */
    private class Last(val text: String)

    private val BLOCK_END = "\n    }\n"

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }

    /** From [from] (kept) to the next [to] (dropped). [from] must be in the file once; [to] once when it is not the block end. */
    private fun scope(pin: String, from: String, to: String): String {
        assertEquals("$pin: the start anchor must be found exactly once in AppViewModel.kt: $from", 1, count(src, from))
        val a = src.indexOf(from)
        val b = src.indexOf(to, a + from.length)
        assertTrue("$pin: the end anchor is not found after the start: ${to.trim()}", b > a)
        if (to != BLOCK_END) assertEquals("$pin: the end anchor must be found exactly once: ${to.trim()}", 1, count(src, to))
        return src.substring(a, b)
    }

    private fun idx(pin: String, scope: String, t: Any): Pair<String, Int> {
        val text = when (t) { is Last -> t.text; else -> t as String }
        val n = count(scope, text)
        if (t is Last) assertTrue("$pin: not found in the scope: $text", n >= 1)
        else assertEquals("$pin: must occur exactly once in the scope: $text", 1, n)
        return text to (if (t is Last) scope.lastIndexOf(text) else scope.indexOf(text))
    }

    /** [texts] occur in this order inside the scope; [broken] = what a user would see if it did not. */
    private fun ordered(pin: String, rule: String, scope: String, broken: String, vararg texts: Any) {
        val found = texts.map { idx("$pin (rule $rule)", scope, it) }
        for (i in 1 until found.size) assertTrue(
            "$pin, rule $rule of docs/design/umbau-phase-2-tour.md: `${found[i - 1].first}` must come BEFORE `${found[i].first}`. " +
                "If this order breaks the user sees: $broken", found[i - 1].second < found[i].second)
    }

    /** [text] is the last statement of the scope's body. */
    private fun endsWith(pin: String, rule: String, scope: String, text: String, broken: String) =
        assertTrue("$pin, rule $rule: `$text` must be the LAST statement of the function. If this breaks the user sees: $broken",
            scope.trimEnd().endsWith(text))

    // ---- P1–P3 (the follow files are written before the command: rules 1, 18, 19, 20) and P13–P15 (the plan the tour follows:
    // ---- rules 21, 25) are gone: piece 18a moved the functions to TourFollow and TourFollowFlowTest proves each rule on the real
    // ---- slice (the commit message maps pin -> flow -> proof) ----

    // ---- P4–P6 and P24 (the activity check: rules 1, 23, 24) are gone: piece 16b moved the functions to ActivityCheckSlice and
    // ---- ActivityCheckFlowTest proves each rule on the real slice (the commit message maps pin → flow) ----

    // ---- P7–P12 (the end of a tour: the finished collector, stop / delete decision / ride choice, End tour, the interrupted tour,
    // ---- Edit rides, Rückgängig: rules 7–12, 26, 27, 35) are gone: piece 17 moved the functions to TourEndSlice and TourEndFlowTest
    // ---- proves each rule on the real slice (the commit message maps pin -> flow) ----

    // ---- P16–P18 (what a plan edit means for the tour: rules 16, 17, 36) are gone: piece 18b moved the functions to TourEdits and
    // ---- TourEditsFlowTest proves each rule on the real slice (the commit message maps pin -> flow -> proof) ----

    // ---- P25, P26: single texts of rules 30, 34 ----

    // P23 (followEditedPlan: the session is remembered before it is replaced, rule 20) is gone with its function: piece 18b,
    // TourEditsFlowTest.undoAndRedoFollowTheSessionsExactly… (two tests, one per place) prove it on the real slice.

    @Test fun p25_sayEvent_onlyDuringATour() = ordered("P25", "30",
        scope("P25", "private fun sayEvent(what: String) {", BLOCK_END),
        "the sentence \"no destination\" spoken without a tour (the service would be started for it)",
        "if (recState.value != null) recording.say(what)")

    @Test fun p26_theProvisionalPositionIsAskedOnlyOnAChange() = ordered("P26", "34",
        scope("P26", "var asked = false", "Provisional position request not"),
        "indoors no position; or the fused request never stops (asked again on every state)",
        "Provisional.asksPosition(",
        "if (ask != asked) {",
        "asked = ask",
        "tracker.setFusedWanted(ask)")

    // ---- P19–P22: start, start-up, the service's answers, mode, continue (rules 2–6, 13, 14, 15, 22) ----

    @Test fun p19_startNavigation_guardsThenWhatIsFollowedThenSTARTThenWaitThenNavigate() {
        ordered("P19", "2, 3", scope("P19", "fun startNavigation(", BLOCK_END),
            "a second tour beside the interrupted one; a tour that follows nothing; the plan not saved at \"Los geht's\"; Navigate with nothing recorded, or never in Navigate",
            "if (recState.value != null) { continueNavigation(force); return }",
            "if (!tour.canStartNew)",
            "straightAsk = true; return",
            "PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); save.atTourStart() }",
            "startRecording()",
            "startJob?.cancel()",
            "withTimeoutOrNull(START_TIMEOUT_MS) { recording.state.filterNotNull().first() }",
            "if (started) enter(AppMode.NAVIGATE)",
            "else if (errorCard == null) sayError(Txt.s(Res.string.msg_recording_not_started), NoticePlace.MAP)")
        assertEquals("P19 (rule 3): the six-second wait", 1, count(src, "private val START_TIMEOUT_MS = 6_000L"))
    }

    @Test fun p20_theStartUpWork_modeStaysPlanThroughRecalcThenTheCheckInItsOrder() = ordered("P20", "4, 5, 6",
        scope("P20", "    init {\n        recalc()", "// The service stopped by itself"),
        "the stored plan is routed as \"not in Plan\"; a new tour beside an interrupted one; the \"interrupted tour\" question although the tour runs; every start refused for good (the flag stuck); a blue line nothing can remove",
        "recalc()",
        "refreshTours()",
        "if (!active && waypoints.isEmpty() && recState.value == null && followLine != null && !followIsTrack) {",
        "if (recording.state.value != null) mode = AppMode.NAVIGATE",
        "tour.unfinishedPending = true",
        "viewModelScope.launchSafe(\"app\", \"start-up work\", Dispatchers.IO) {",
        "val active = db.findActiveRecording()",
        "withContext(Dispatchers.Main) { enter(start); startModeKnown = true }",
        "delay(1500)",
        "if (recording.state.value == null) {",
        "withContext(Dispatchers.Main) { tour.showUnfinished(active, line) }",
        "} finally {",
        "withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { tour.unfinishedPending = false }")

    @Test fun p21a_theStartFailedCollector_seenThenErrorThenBackToPlanOnlyFromNavigate() = ordered("P21", "13",
        scope("P21", "recording.startFailed.collect { why ->", "// Re-routing (service)"),
        "the reason shown again and again; Navigate with nothing recording",
        "if (why != null) { recording.startFailedSeen(); sayError(why, NoticePlace.BACKGROUND); if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN) }")

    @Test fun p21b_theStateCollector_newTourClearsHistoryThenArrivalThenTheServicesLine() = ordered("P21", "14",
        scope("P21", "recording.state.collect { st ->", "tourEnd.start()"),
        "a taken re-route not drawn; Undo brings a route of another tour; the arrival question twice",
        "followHistory.clear()",
        "guidanceEndedByEmptyPlan = false }",
        "onArrived(st?.arrived == true)",
        "val n = st?.nav ?: run { navActivitySeen = null; return@collect }",
        "if (n.version != navVersion) { navVersion = n.version; if (followLine != n.line) followLine = n.line }",
        "syncNavActivity(n.session)")

    @Test fun p22a_enter_modeFirstDialogClosesAndTheServiceIsToldLast() {
        val s = scope("P22", "private fun enter(m: AppMode) {", BLOCK_END)
        ordered("P22", "15", s, "off-route questions in Plan / none in Navigate; a dialog behind the other mode",
            "mode = m",
            "if (save.ask != null) save.cancel()",
            "if (recState.value != null) recording.navigating(m == AppMode.NAVIGATE)")
        endsWith("P22", "15", s, "if (recState.value != null) recording.navigating(m == AppMode.NAVIGATE)",
            "the service is told the mode before the app has settled in it")
    }

    @Test fun p22b_continueNavigation_straightLegsAskThenFollowThenNavigateLast() {
        val s = scope("P22", "fun continueNavigation(force: Boolean = false) {", BLOCK_END)
        ordered("P22", "22", s, "back in Navigate on the old route; no off-route check after Continue",
            "straightAsk = true; return",
            "followPlanned(checkNow = true)",
            "routeEvent(RouteRun.Event.FollowNext)",
            "enter(AppMode.NAVIGATE)")
        endsWith("P22", "22", s, "enter(AppMode.NAVIGATE)", "back in Navigate on the old route")
    }
}
