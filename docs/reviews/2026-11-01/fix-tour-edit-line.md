# Field bug 2026-11-01: plan lines vanish when editing during a tour – root cause

Read-only analysis on main at b3def4aa. Paths are relative to `app/src/main/java/com/onpath/app/`.

## How Plan draws during a tour after a split edit (TourSplit.Outcome.Follow)
- `followAhead` (ui/AppViewModel.kt:3759-3790) routes [position] + ahead, then calls `setFollow(line)` (followLine = position→ahead)
  and sets `planBaseline = planLine` (the WHOLE plan's route), at :3786.
- `planIsFollowed()` (:3392) = `planFollowed(route.points)`, which is true because route.points == planBaseline. So
  `planLayers(canEdit, planFollowed=true)` (ui/MapScreen.kt:498, :652) gives showLine=false, and `setPlanned(null)` clears
  the plan line (all legs between the points).
- Now the plan exists on the map ONLY as the pale line:
  `paleBehind = route.points.takeIf { PLAN && passed.isNotEmpty() && planIsFollowed() }` and
  `setNavOriginal(nav?.original ?: paleBehind)` (MapScreen.kt:531-532).
So two things can take the plan off the map: the pale line is gated on `passed` being non-empty, and it is replaced by
`nav.original`. Each of the two causes below hits one of them.

## Cause 1 (high certainty): the "passed" set is not kept. It is computed again from scratch, and after the edit it shrinks
`tourPassed()` (AppViewModel.kt:1872-1881) works passed out again on every call from the CURRENT NavSession: its `stops`
(progress rule), its `visited`, the position (closer rule) and the track (near rule). After `followAhead` the session is
`NavSession.start(line = position→ahead, stops = ahead)`, so the passed points are no longer among its stops. Also,
`visited` and progress no longer know about them. `TourSplit.carry` exists, but only `outcome()` uses it. Nothing stores it,
although tour-edit-position.md §2 says passed points are "carried over every plan edit, matched by coordinate".

Step by step: the user rides past point 2 (by progress, or `visited`, or within 60 m of the line but not within 40/60 m of
the point). They edit, for example by deleting or moving a point ahead → Follow → the new session holds only the ahead stops →
MapSync recomputes `passed` (MapScreen.kt:493, keyed on waypoints / followLine / every 20 track points) → point 2 now depends
only on the position-based closer rule, which fails (the next point is far away, or the point after a deletion is far) → passed = {} or a
shorter prefix → `paleBehind = null` → `setNavOriginal(null)`. The plan line was already hidden (planBaseline), so **the whole plan
disappears**. Only the short blue position→ahead line is left. The ✓ points turn back into normal markers. The closer rule depends on the
current position, so the result changes as the user moves ("while I am moving"). The next edit then gets
`splitOf → passed empty → null → WholePlan → followPlanned()`, and the tour follows the whole plan from point 1 again, behind the user.

Fix (minimal): keep the passed points sticky per tour in the VM, as `tourPassedPts: Set<GeoPoint>` (cleared when `st.tourId` changes,
:1513). Add the parameter `known: Set<GeoPoint>` to `TourSplit.passed`: a plan point that matches `known` (sameSpot) counts as passed,
and the prefix rule still applies. After each `tourPassed` call, take the union of the result's points into the set. In
`change` / `step` / `answerRouteBack`, nothing extra is needed, because the set is keyed by coordinate. A deleted point simply
stops matching.

Test first (TourSplitTest): plan a1(0,0) a2(0,500) a3(0,1000) a4(0,3000), on foot, track along x=60 m up to pos (100,800),
session stops a1..a4 with progress 800 → `passed == {0,1}` (by progress). Delete a4 → after = [a1,a2,a3] → `Outcome.Follow([a3])`.
Then build the Tour the way the VM does after followAhead (stops = [a3 on position→a3], visited = ∅, progress 0) and assert
`TourSplit.passed(after, freshTour, known = {a1,a2}) == {0,1}`. Today, without `known`, it gives {0} (a1 only by the closer rule), so
the next edit would follow back to a2.

## Cause 2 (high certainty): a service re-route replaces the pale plan
`setNavOriginal(nav?.original ?: paleBehind)` (MapScreen.kt:532). `NavState.original` is non-null as soon as the session
is rerouted (navigation/Navigator.kt:180). Step by step: after the edit the user is off the position→ahead line (it starts
where they stood at the edit) → Navigator off-route → re-route taken → `nav.original = position→ahead` → the grey line shows that line
instead of the whole plan. planBaseline still hides the plan line → **all lines between the plan points vanish**. In the same
situation `tourPassed` drops `stops` for a rerouted session (`takeUnless { it.rerouted }`, :1877), which makes Cause 1 worse.

Fix: draw both. Use `setTrack(SRC_NAV_ORIGINAL, listOfNotNull(nav?.original, paleBehind))` (a new `setNavOriginal(lines)`
overload). Better still, decouple the pale plan from `passed`: in PLAN, whenever the plan line is hidden because `planBaseline` matches
but `followLine != route.points`, draw `route.points` pale. Test: extract a pure
`paleLines(mode, passedNonEmpty, planFollowed, navOriginal, planLine, followLine)` from MapSync and assert that it contains the
plan line when navOriginal != null, and when passed is empty but the plan is hidden. Also add a rule test: in PLAN during a tour,
`showLine == false` ⇒ the plan line is in `paleLines` (the plan is never invisible).

## Cause 3 (medium): `followAhead` is not superseded by later edits / clear / tour end
`splitGen` is bumped only inside `followAhead` (:3763). It is checked at :3776 together with `recState != null`. It is NOT checked against
later edits that end in Unchanged / NothingAhead / null / Empty, or against `clearFollow`, Undo or `recalc`. If edit 1 is still routing
(the slow position→ahead route) when edit 2 arrives, edit 1's result lands afterwards. It calls `setFollow` (ahead points of the
old plan, perhaps a deleted point) and sets `planBaseline = r1`. The plan line then flips between hidden and shown against the wrong
line. After "all points deleted" it even starts guidance again with ghost points. Fix: `++splitGen` in `splitOf()`,
`clearFollow()` and `answerRouteBack`, and in `followAhead` also require `waypoints.toList() == planAtStart`. Test: extract a
`SplitRequests` gate (next/isCurrent/invalidate like `RouteRequests`) and assert that an edit invalidates the running one.

## Cause 4 (lower, race): a service re-route overwrites the app's new session files
App `setFollow` (AppViewModel.kt:3809-3820) writes nav-session.json and follow prefs, then sends FOLLOW_CHANGED. If a re-route
job of the OLD line finishes first (RecordingService.kt:643 → `handle` :605-611, `lineChanged`), the service writes the old
session and the old follow line over the app's files. `loadFollow` (:555, `cancelReroute()` first) then reads the old line back.
It publishes a new version, and the app's collector (:1528) sets `followLine = old line`. `planBaseline` = the new plan keeps the
edited plan hidden, so the user sees the pre-edit route. The window is small (both on Main), but it is likely in exactly this
case, because the user is off route. Fix: the app puts a session token/id in the file and the intent, and `startReroute`
remembers the token. Its result is dropped (not saved) if the file's token differs. Test: a pure check
`RerouteResult.applies(jobToken, fileToken)`, or a Navigator-level test.

## Side notes
- `NavSession.start` always has version 0 (NavSession.kt:57). The app's version check (:1528) and JunctionCues (`load`: version +
  size) then lean on size alone. Bump to `old.version + 1` in `setFollow`.
- `recalc` onFailure sets `route = null` (AppViewModel.kt:2712). During a tour, one failed run removes the whole plan line.
  Consider keeping the old route while a tour runs.
- Outcome.Unchanged sets `planBaseline = r.points`, while followLine keeps the old line through the deleted passed point. That is
  harmless but looks inconsistent.

## Device check
Use Demo mode (Developer tools → Demo) with a 4-point plan. Ride past point 2 at 50–70 m (or with a progress pass), go to Plan,
and delete or move point 4. Watch whether the ✓ on 2 and the pale line stay over the next ~200 m. Then leave the line until a
re-route is taken, and check that the pale plan stays. In Diagnostics, check the trace lines "Edit during the tour: N of M
points passed" on a second edit (N should not drop).
