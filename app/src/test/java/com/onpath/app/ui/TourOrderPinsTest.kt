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

    /**
     * Rule 1's real hazard: a SUSPENSION (a launch, a hop of dispatcher) between the file write and its command - the
     * command would then reach the service before the file is written. The text between [write] and [command] holds none.
     */
    private fun noSuspensionBetween(pin: String, scope: String, write: String, command: String) {
        val a = scope.indexOf(write); val b = scope.indexOf(command, a + 1)
        assertTrue("$pin: `$write` and `$command` must both be in the scope", a >= 0 && b > a)
        val between = scope.substring(scope.lastIndexOf('\n', a) + 1, b)   // from the start of the write's line: a launch AROUND it counts
        for (bad in listOf("launch", "withContext(")) assertFalse(
            "$pin, rule 1: no `$bad` between the file write `$write` and the command `$command` - the service reads the file when the command " +
                "arrives, so a suspension between them lets it read the old route (or none)", between.contains(bad))
    }

    /** [text] is the last statement of the scope's body. */
    private fun endsWith(pin: String, rule: String, scope: String, text: String, broken: String) =
        assertTrue("$pin, rule $rule: `$text` must be the LAST statement of the function. If this breaks the user sees: $broken",
            scope.trimEnd().endsWith(text))

    // ---- P1–P3: the follow files are written before the command that makes the service read them (rule 1) ----

    private val oldRoute = "the guidance keeps following the old route, or a deleted route comes back (the service reads the files when the command arrives)"

    @Test fun p1_setFollow_filesThenStateThenTheCommand() {
        val s = scope("P1", "private fun setFollow(", BLOCK_END)
        noSuspensionBetween("P1", s, "followStore.appFollows(session, name, points)", "recording.followChanged(checkNow)")
        p1(s)
    }

    private fun p1(s: String) = ordered("P1", "1, 18", s,
        "a late re-route of the old line overwrites the new route; voice and cues keep the old line, or $oldRoute",
        "guidanceEndedByEmptyPlan = false",
        ".copy(token = newSessionToken(), version = (recState.value?.nav?.version ?: navVersion).coerceAtLeast(0) + 1,",
        "followStore.appFollows(session, name, points)",
        "followLine = points",
        "if (recState.value != null) recording.followChanged(checkNow)")

    @Test fun p2_followAgain_filesThenStateThenTheCommand() {
        val s = scope("P2", "private fun followAgain(", BLOCK_END)
        noSuspensionBetween("P2", s, "followStore.appFollows(session, session.name, session.active)", "recording.followChanged()")
        p2(s)
    }

    private fun p2(s: String) = ordered("P2", "1, 20", s,
        "Undo routes anew and loses \"Guide me\", or $oldRoute",
        "s.copy(version = maxOf(s.version, versionNow) + 1, token = newSessionToken())",
        "followStore.appFollows(session, session.name, session.active)",
        "followOriginal = session.original",
        "recording.followChanged()")

    @Test fun p3_clearFollow_requestsStaleThenFilesGoneThenTheCommand() {
        val s = scope("P3", "fun clearFollow() {", BLOCK_END)
        noSuspensionBetween("P3", s, "followStore.appEnds()", "recording.followChanged()")
        p3(s)
    }

    private fun p3(s: String) = ordered("P3", "1, 19", s,
        "a slow route result re-follows after the guidance ended, or a deleted route comes back",
        "splitRequests.invalidate()",
        "planCopy { notFollowed() }",
        "followStore.appEnds()",
        "followLine = null",
        "if (recState.value != null) recording.followChanged()")

    // ---- P4–P6 and P24 (the activity check: rules 1, 23, 24) are gone: piece 16b moved the functions to ActivityCheckSlice and
    // ---- ActivityCheckFlowTest proves each rule on the real slice (the commit message maps pin → flow) ----

    // ---- P7: the end of a tour (rules 7, 8, 9) ----

    @Test fun p7_theFinishedCollector_seenThenReadThenOutcomeThenCleanUp() = ordered("P7", "7, 8, 9",
        scope("P7", "recording.finished.collect { id ->", "fun initialCamera(): Triple"),
        "the end handled twice; the followed line of the ended tour stays; the app stays in Navigate; the plan is cleared before the tour is listed; \"DEMO\" stays on the map",
        "recording.finishedSeen()",
        "val done = withContext(Dispatchers.IO) { db.getTour(id) }",
        "tour.finishedOutcome(",
        "tour.showFinished(done,",
        "val parts = recording.takeFinishedParts()",
        "refreshTours()",
        "clearFollow()",
        "demo.navSimLine = null",
        "enter(AppMode.PLAN)",
        "tour.afterFinished()",
        "afterTourEnded()")

    // ---- P8: stop, delete decision, ride choice (rules 10, 11, 32); one test per scope ----

    @Test fun p8a_endTourWith_theRideChoiceIsStoredBeforeTheTourEnds() = ordered("P8", "11",
        scope("P8", "fun endTourWith(", BLOCK_END),
        "\"Save only until …\" is ignored: wrong km / time (the service applies the stored choice when it finishes)",
        "RideApply.choose(", "endTour()")

    @Test fun p8b_endTour_theDecisionIsSetBeforeSTOP() = ordered("P8", "10",
        scope("P8", "fun endTour() {", BLOCK_END),
        "\"Save\" deletes the tour – or the next tour is binned",
        "tour.end(delete = false)", "stopRecording()")

    @Test fun p8c_endTourAndDelete_theDecisionIsSetBeforeSTOP() = ordered("P8", "10",
        scope("P8", "fun endTourAndDelete() {", BLOCK_END),
        "\"Löschen\" saves the tour – or the next tour is binned",
        "tour.end(delete = true)", "stopRecording()")

    @Test fun p8d_stopRecording_theQuestionIsClosedBeforeSTOP() = ordered("P8", "32",
        scope("P8", "fun stopRecording() {", BLOCK_END),
        "a question of the old tour (\"route back\") in the next one",
        "routeBackAsk = false", "tour.stop()")

    // ---- P9–P12: the end question, interrupted tours, edit rides, Rückgängig (rules 12, 26, 27, 35) ----

    @Test fun p9_askEndTour_aRideCheckOfAnEndedTourIsDropped() = ordered("P9", "12",
        scope("P9", "fun askEndTour(", BLOCK_END),
        "an end sheet for a tour that already ended",
        "arrivalAsk = false",
        "val s = withContext(Dispatchers.IO) {",
        "if (recState.value?.tourId != id) return@launchSafe",
        "TourEndFlow.afterRideCheck(")

    private val interruptedBroken = "the \"Tour interrupted\" notification stays; a restored tour is \"unfinished\" again; or a tour the service records is closed under it"

    @Test fun p10_saveUnfinished_logThenQuestionThenStickyCheckThenWrites() = ordered("P10", "26",
        scope("P10", "fun saveUnfinished(", BLOCK_END), interruptedBroken,
        "InterruptedLog.Choice.SAVE, ::clearInterruptedNote)",
        "tour.closeUnfinished()",
        "if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }",
        "enter(AppMode.PLAN)",
        "db.updateStats(",
        "RideApply.choose(",
        "RideApply.finish(",
        "Backups.tourChanged(getApplication(), t.id)",
        "withContext(Dispatchers.Main) { refreshTours() }")

    @Test fun p11a_deleteUnfinished_logThenQuestionThenStickyCheckThenDoneThenBin() = ordered("P11", "26",
        scope("P11", "fun deleteUnfinished(", BLOCK_END), interruptedBroken + "; a deleted tour comes back as \"unfinished\" from the recycle bin",
        "InterruptedLog.Choice.DELETE, ::clearInterruptedNote)",
        "tour.closeUnfinished()",
        "if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }",
        "enter(AppMode.PLAN)",
        "db.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, \"done\")",
        "db.setDeleted(",
        "if (ok) Backups.tourChanged(")

    @Test fun p11b_continueRecording_logAndNoteBeforeTheStart() = ordered("P11", "26",
        scope("P11", "fun continueRecording(", BLOCK_END), interruptedBroken,
        "InterruptedLog.Choice.CONTINUE, ::clearInterruptedNote)", "tour.continueTour(t)")

    @Test fun p12a_applyEditRides_closedThenEditThenBackupThenListThenReopen() = ordered("P12", "35",
        scope("P12", "fun applyEditRides(", BLOCK_END),
        "the map shows the tour as before the edit; a backup of a failed edit",
        "editRides = null",
        "RideApply.edit(",
        ".also { if (it) Backups.tourChanged(",
        "refreshTours()",
        "openTour(it)")

    @Test fun p12b_undoShortRecording_restoreThenBackupThenBoxThenNameThenList() = ordered("P12", "27",
        scope("P12", "fun undoShortRecording() {", BLOCK_END),
        "a short tour gone without its box; the saved box shows no name",
        "routes.restore(t.id)",
        "Backups.tourChanged(getApplication(), t.id)",
        "tour.showFinished(done,",
        "nameRecordedTour(done)",
        Last("refreshTours()"))

    // ---- P13–P15: the plan the tour follows (rules 21, 25) ----

    private val oldPlan = "\"Gemacht → Planen\" brings an old plan or none; the plan and the track both followed"

    @Test fun p13a_followPlanned_followThenFlagThenCopy() = ordered("P13", "21",
        scope("P13", "fun followPlanned(", BLOCK_END), oldPlan,
        "setFollow(", "followFromPlan = true", "copyFollowedPlan(activity, sac)")

    @Test fun p13b_followShown_followThenFlagThenCopyStaysThenBaseline() = ordered("P13", "21",
        scope("P13", "fun followShown() {", BLOCK_END), oldPlan,
        "setFollow(s.name, s.points, isTrack = true)",
        "followFromPlan = false",
        "planCopy { notFollowed() }",
        "planAtTrackFollow = waypoints.toList()")

    @Test fun p14_thePlanCopy_oneSerialThreadTheTourIdCollectorAndTheSavedRouteCopy() {
        ordered("P14", "21", src, oldPlan,
            "private val planCopyDispatcher = Dispatchers.IO.limitedParallelism(1)",
            "recState.map { it?.tourId }.distinctUntilChanged()",
            ".collect { id -> planCopy { tour(id) } }")
        ordered("P14", "21", scope("P14", "private fun setSaved(", BLOCK_END), oldPlan,
            "plan.setSaved(name, id, points)", "recopyFollowedPlan()")
    }

    @Test fun p15_syncNavActivity_originalThenBaselineThenTheActivity() = ordered("P15", "25",
        scope("P15", "private fun syncNavActivity(", BLOCK_END),
        "a switch taken from the notification makes the app \"follow the edited plan\" and resets the session",
        "navActivitySeen = a",
        "followOriginal = s.original",
        "routeEvent(RouteRun.Event.AdoptBaseline)",
        "selectActivity(a, userChange = false)")

    // ---- P16–P18: what a plan edit means for the tour (rules 16, 17, 36) ----

    private val neverStop = "the tour is stopped or its guidance kept by an edit (the recording must never stop by an edit); or Undo no longer brings the guidance back"

    @Test fun p16a_endGuidanceIfPlanEmpty_clearThenFlag() = ordered("P16", "17",
        scope("P16", "private fun endGuidanceIfPlanEmpty()", BLOCK_END), neverStop,
        "clearFollow()", "guidanceEndedByEmptyPlan = true")

    @Test fun p16b_answerRouteBack_staleThenPlanThenEventThenRecalc_andTheNoBranchEndsTheGuidanceAfterClear() = ordered("P16", "17",
        scope("P16", "fun answerRouteBack(", BLOCK_END), neverStop,
        "splitRequests.invalidate()",
        "if (recState.value == null) return",
        "this.plan.replace(back)",
        "routeEvent(RouteRun.Event.PlanEdited)",
        "recalc()",
        "{ clearFollow(); guidanceEndedByEmptyPlan = true }")

    @Test fun p16c_oneLeftGoesFromHere_everyGuidanceEndClearsThenSetsTheFlagAfterTheSplitIsForgotten() {
        val s = scope("P16", "private fun oneLeftGoesFromHere(", BLOCK_END)
        val pair = Regex("""pendingSplit = null\s+clearFollow\(\)\s+guidanceEndedByEmptyPlan = true""")
        assertEquals("P16 (rule 17): `pendingSplit = null` / `clearFollow()` / `guidanceEndedByEmptyPlan = true` exactly twice, in this order. " +
            "If this breaks the user sees: $neverStop", 2, pair.findAll(s).count())
        assertEquals("P16: clearFollow() twice in oneLeftGoesFromHere", 2, count(s, "clearFollow()"))
        assertEquals("P16: guidanceEndedByEmptyPlan = true twice in oneLeftGoesFromHere", 2, count(s, "guidanceEndedByEmptyPlan = true"))
    }

    @Test fun p17_followAhead_staleBeforeTheRouteAndOnlyTheNewestResultIsApplied() = ordered("P17", "16",
        scope("P17", "private fun followAhead(", "\n    fun followShown()"),
        "deleted points come back in the guidance; the tour follows a plan the map does not show",
        "val gen = splitRequests.next(waypoints)",
        "followCancel?.cancel()",
        "if (cancel.isCancelled) {",
        "if (!splitRequests.isCurrent(gen, waypoints.toList()) || recState.value == null) return@launchSafe",
        "setFollow(name, line, isTrack = false, checkNow = true,",
        "copyFollowedPlan(act, level)")

    @Test fun p18_followShownNow_thePlanLeavesTheMapBeforeTheTrackIsFollowed() = ordered("P18", "36",
        scope("P18", "private fun followShownNow() {", BLOCK_END),
        "the plan and the track both followed; Undo brings nothing back",
        "if (recState.value == null) return",
        "clearPlan()",
        "followShown(); closeShown(); continueNavigation()")

    // ---- P23-P26: single texts of rules 20, 23, 30, 34 ----

    @Test fun p23_followEditedPlan_theSessionIsRememberedBeforeItIsReplacedInBothPlaces() {
        val s = scope("P23", "private fun followEditedPlan() {", BLOCK_END)
        val rem = "followHistory.remember"; val again = "followAgain(back, r.points, now?.version ?: 0)"
        assertEquals("P23: remember twice", 2, count(s, rem)); assertEquals("P23: followAgain twice", 2, count(s, again))
        val r1 = s.indexOf(rem); val a1 = s.indexOf(again); val r2 = s.indexOf(rem, r1 + 1); val a2 = s.indexOf(again, a1 + 1)
        assertTrue("P23, rule 20: `$rem` must come before `$again` in both places (a session replaced before it is remembered is lost; " +
            "Undo then routes anew and loses \"Guide me\")", r1 < a1 && a1 < r2 && r2 < a2)
    }

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
        scope("P21", "recording.state.collect { st ->", "recording.finished.collect"),
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
