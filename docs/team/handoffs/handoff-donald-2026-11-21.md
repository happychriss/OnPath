# Handoff donald, Saturday 2026-11-21 evening (for the fresh donald session)

Start: `docs/team/roles/start.md` → this file → backlog lines for [donald]. Tokens: helpers on Sonnet, one big-model review per
piece, handoff at ~350k. **The owner's weekend word still holds until Mon 2026-11-23:** his fixes go out as 0.18.x after gate +
the change's tests, no further question. The Pixel is not on USB. Picture first for anything he sees (`onpath.private/ideen/`;
`SendUserFile` does not work in this session, give him the GitHub link in the question box).

## State, Sunday 2026-11-22 late: the follow-camera hold is settled, **fix on main, NOT released**
**DO NOT RELEASE main as it is (review of be2e6c159, the captain's condition for 0.18.9).** The longer wait (up to 2 s) widens two windows,
read in the code, not run: (1) a plan edit during the wait (Neu planen, Undo) does not end the waiting press, so the held point lands on
the changed plan afterwards; (2) a second hold during the wait starts the first hold's measure and cancels it at once, so the first
hold is lost silently. Both exist within the first second already (0.18.8); this change makes them likelier. The captain decides: fix forward or revert.
- **Real bug, fixed (be2e6c159):** in Plan with the walking direction up, the finger-down ends following, the camera stops and a read is
  due. On a slow map (GitHub: two frames in 770 ms) no idle came within the press's second and the hold on a drawn symbol became a plain
  press. A due read now gets the same bounded wait as a running one (at most 2 s in all). Unit test red before, green after.
- **North up, the still follow camera works:** the press's own repaint brings an idle in 12–20 ms. The old forcing was wrong: removing the
  test GPS provider let the emulator's own far-away fix fly the camera off, which closed the menu (6e0308905 keeps the provider).
- **Checks:** gate; `CorePoiTapE2ETest` 16 of 16 on GitHub at 6e0308905; `CorePoiTapStillMapE2ETest` green at be2e6c159. No big-model review
  (a two-line rule). Limit: on a very slow map an ordinary hold beside a symbol can now take up to 2 s when a read is due.
- **Not touched:** the second press ending a waiting one, and nothing on screen during the wait (below). Whether this is the owner's delay
  is still open: his export decides.
- Replaces item 1 below and `wip/donald-2026-11-22-poi-follow-test`. Probe branches for the normal prune list: `ci/donald-poi-diag`,
  `ci/donald-poi-wait`, `ci/donald-poi-follow` (this one holds throwaway probe code, never to merge).

## State, Sunday 2026-11-22 evening: **0.18.8 is out** (fresh session; the captain's word: lean, nothing held back)
**The owner's report (0.18.7):** a tap on a symbol sometimes waits, a second tap gets no answer, then the other places work. He will
export Diagnostics next time. **0.18.8 writes one `[poi] Press …` line per press** (e0603c68f, no change in behaviour): `nth` since the
camera, `camStill`, `finger`, `upToApp` (finger up → the map's click), `ring` (none / hidden / seen), the list (`list`, `age`, `due`,
`reading`, `styleOk`, `styleLoads`), the wait (`wait`, `repaint`, `idle`, `read`, `landed`, `asked`), `end` and `action`. Plus one line
„First POI list after the style load“. `end=newerPress` is the second tap ending the first.

**Read in the code, not fixed, not seen on his phone:**
- A second press ends a waiting one as a **plain press** (`handle` → `finishWaitingPress`). For a tap that is nothing; for a **hold on a
  symbol it can set a route point or open the first-point menu**. Fix with a test first.
- While a press waits (up to 1 s, 2 s with a read under way) **nothing shows**: the ring ends at finger-up, or at the long click.
- Whether this is his delay is open: the first line from his phone decides.

**The red E2E on main (run 45000657049) was its precondition, not the app:** format 2, three holds, each opened the right menu, none
logged a list miss, a time-out or „no POI“, so the list already had the symbol (reads 279 ms and 108 ms before holds 2 and 3). At
e0603c68f `CorePoiTapE2ETest` (14 cases) and `CorePoiTapStillMapE2ETest` are green on GitHub. **Still open: item 1 below**, the forced still
follow camera. Parity: `PoiTap.kt`'s watch entry was already behind before this change, so it was left for the laptop to record.
The release run's planner smoke went red after publishing (a line for the laptop in the backlog); the planner online is unchanged.
`ci/donald-poi-diag` is younger than the prune tool's 12 h: remove it through the normal list.

## State, Sunday 2026-11-22: this session stopped on the captain's word (weekly tokens). Read this first
No fresh donald until the owner wants work before Tuesday. **0.18.7 is out, the planner is online** (deploy-quick at 8cee9a2ec,
contains 0.18.7). Items 1–3 and 5 are done. Two things are open, neither on main:

1. **POI hold while the camera follows: the scheduled E2E on main is red, possibly a real bug in 0.18.7.**
   `CorePoiTapE2ETest.aHoldOnASymbolMissingFromTheListWorksWhileTheCameraFollows` (run 45000657049, 59604cb08, line 107). In that run
   every hold opened the right menu, but no hold went through the map: a follow step had (likely, round 1 unproven) already read the
   symbol. `wip/donald-2026-11-22-poi-follow-test` (c60aeab15, deliberately red) forces the case: it removes the GPS test provider
   after the camera landed, one symbol, one hold. **Then the hold ends as a plain press in both map formats** („No map idle in time“
   after the 1 s bound, first-point menu). Unknown: is that the app (the press's `triggerRepaint` brings no idle on a still follow
   camera) or the forcing (no provider, no frames)? First step: log whether the repaint brings an idle there; try a gentler forcing.
   Do not weaken or just rerun the check. If it is real, the owner's symbol bug is not fixed while following.
2. **Detour hint, built, NOT merged:** `wip/donald-2026-11-22-detour-hint` (dfe3f0917). Owner chose the wording „② macht 4,2 km
   Umweg“. Gate --full green; **no big-model review, no E2E, no device.** It does not fire on his own case (Vellau → Mutkopf →
   Leiteralm, T1: 30.3 km against 10.8 km at T2), because ② stays 552 m off the line, counts as a gap, and the gap line has priority.
   His answer is open (backlog „Waiting for the owner“). When merged: `AppViewModel.kt` is marked checked in `web/parity.json`, so
   **the check will not remind the laptop** to wire `PlanDetour.find` + `PlanGaps.detourText` (`WebPlanRules.gapsJson`): add a line.

On main, not released: arrival with an unreachable stop (cb49cbc22). Rests of all reviews:
`items/2026-11-21-review-rests-off-route-ride-question.md`. Helper worktrees `agent-*` stay until their branches are merged.

## State, later the same evening (fresh session): items 1–3 are done, **0.18.7 is out**
POI fix, ride question and the review fixes are on main and in the release; the `wip/…-poi-lookup*`, `…-ride-question*` and
`…-offroute-fixes` branches are merged in content (prune them through the normal list). The planner was **not** deployed with it
(areas-smoke red in the release run, green on the commit before): the laptop has it. `release.yml` publishes the app before its
planner check ends, so a green push run on the commit before is not the same as green on the release commit.
**Item 5 is done and on main (cb49cbc22), not released:** arrival no longer waits for a stop the route cannot reach; reviewed, its
five findings fixed, gate + `CoreFieldE2ETest` + real-data replay green, no device run with such a stop. Its limits:
`items/2026-11-21-review-rests-off-route-ride-question.md`. Go on at item 4.

## Order of work
1. **POI symbol bug (owner's, first).** Tap / hold on a drawn symbol in Plan finds nothing, „wieder das gleiche Problem“.
   **Cause on his phone unproven.** On the emulator the list was right after every pan and style load; POI parsing loses no kind.
   Branch `wip/donald-2026-11-21-poi-lookup` (NOT merged), two commits:
   - Good: the read wish survives an idle during a style load; one `triggerRepaint()` after a camera stop / style load (measured: one idle ~10 ms later).
   - **Stopped by review:** `atOrQuery` runs `queryRenderedFeatures` inside the press on every list miss, so every ordinary hold on the empty map. „Idle“ does not mean the render thread is free: this can repeat the 1447 ms stall (dffa99143).
   - Review's fixes: never query inside the press – on a miss store the press, repaint once, resolve in the next `onMapIdle`, after a short bound treat it as a plain press. Clear idle on style change / load and on every `onCameraMoveStarted`; `became()` only when ready. Repaint not while `locationComponent.cameraMode != NONE`, with a `repaintPending` flag. Still-map E2E must assert the list itself (the fallback hides a failure). Remove the test hook `ShownPois.current` / `dropListForTest` from production. Restore the real guard in `tapAndHoldNeverQueryTheRenderedMap`.
   - Keep the field log (list size, read age, style loads): the next report then says whether the list was stale.
2. **Save question after a ride + „Fahrten bearbeiten“.** Built, gate --full green, **no big-model review yet**:
   `wip/donald-2026-11-21-ride-question` (a3a5d2160, da93e3eae). Owner approved the picture (`ideen/2026-11-21-fahrt-beim-speichern/skizze-2-zwei-knoepfe.png`).
   Two buttons Ohne / Mit Fahrt; splitting into two tours is no longer offered at the end (owner: editing stays as it is; he was told and can object).
   Edit rides now redraws the row picture, overview picture and line. Compile androidTest (the helper did not), review, merge.
3. **Release 0.18.7** when 1 and 2 are in (or without 1 if it drags: tell him the symbol bug is still open). On main since v0.18.6, unreleased:
   eye on a round-trip suggestion (3468737dd); far-start warning line from 25 km + Abbrechen (7ed35111c…bde8c538f, **no big-model review yet**);
   off-route sentence with side and metres (8856a6ecc, 6a246bc9b: reviewed, the five findings fixed, **fixes not re-reviewed**, no device run).
   **E2E:** the red run at 6a246bc9b was only my wrong test name (`CoreFieldE2ETest` without package). Rerun with the full name:
   https://github.com/happychriss/OnPath/actions/runs/44923838753 – still running at this handoff, **read its result first**.
   Planner parity for `OffRouteSay` / `RouteSide`: done by the laptop (57b0c7ca0).
4. **T1 detour hint (owner's word „Hinweis, du entscheidest“).** The route stays; the red line says clearly that the point makes a
   detour; he changes the level or deletes the point. Picture first, nothing built. A cheap test without routing: the way via the
   point ≥ 1.5 × and ≥ 2 km over the straight line of its neighbours. `wip/donald-2026-11-21-route-past-unreachable` is parked (the
   automatic way; review findings in `items/2026-11-07-bugs-seen-while-taking-the-meran-0-17-pi.md`). Do not merge it.
5. **Arrival with an unreachable stop (found by a helper, read in code, not reproduced):** `RecordingService.checkArrived` (~line 748)
   counts every interior stop not visited as open, so „Du bist angekommen“ may never come when a stop is unreachable. Core feature: test first.
   Related: `Rerouter.fresh` / `leadThere` route via such a stop with a plain `engine.route`.

## Closed today (checked in code / with real data)
I03 picture retaken and delivered; very short recordings, green band, colour rests were long done; off route with a stop (138 / 138
real walk-offs keep or ask, pin test); Sim restart (no navigation bug). Far start: the search stays inside its 30 s and a cancel works.

## The rest of Next, from a read-only audit (17 items; a helper's reading, check before building)
Without the owner: 250 tours in the app (picture), „Links vom Planer“ setup check, Pixel review rests, Navigation neu rest (quiet-stretch
sentence, repeat on tap). Needs him: beta bug button, tester's word, S10 numbers, onboarding, roundabout sentence, privacy texts (PR).

## Loose ends
Answered the laptop on the detour stitch (no objection; `TourDb` is SQLite, not Room; donald takes the app's menu row + migration at
the writers stage). Helper worktrees `agent-*` stay until their branches are merged.
