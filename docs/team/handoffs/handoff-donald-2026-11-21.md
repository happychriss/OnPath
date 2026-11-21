# Handoff donald, Saturday 2026-11-21 evening (for the fresh donald session)

Start: `docs/team/roles/start.md` → this file → backlog lines for [donald]. Tokens: helpers on Sonnet, one big-model review per
piece, handoff at ~350k. **The owner's weekend word still holds until Mon 2026-11-23:** his fixes go out as 0.18.x after gate +
the change's tests, no further question. The Pixel is not on USB. Picture first for anything he sees (`onpath.private/ideen/`;
`SendUserFile` does not work in this session, give him the GitHub link in the question box).

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
