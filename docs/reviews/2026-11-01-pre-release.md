# Pre-release review 2026-11-01 (captain) – before the first real users

Owner's order 2026-10-31 night: code + architecture review – duplicates, unclean code, contradictions to the
requirements, illogical spots from the user's view, strong deviations from standards / other OSS apps, wild user
scenarios, and above all: **app and web planner always in sync**. Read-only review of `main` (15de6d99) and
`web-spike` (f31b9acb) by six parallel reviewers; nothing was run on a device. Full reports (each finding with
scenario, file:line, fix, role, certainty) in `docs/reviews/2026-11-01/`:
`ux.md` · `web.md` · `sync.md` · `recording.md` · `routing.md` · `architecture.md`.

## Verdict
- **App ⇄ web parity: OK.** The web engine compiles the app's shared files from the same `app/` sources
  (`web/engine/build.gradle.kts` `appFiles`); sync format, PlanCode, PlanResult, fingerprint, PlanGpx, ActivityLayers,
  BRouter code + profiles are byte-identical on both branches; `web/parity/check.mjs` ok; plan.onpath.earth serves the
  web-spike head. Everything below is logic, not drift.
- **No lost tracks found:** every recorded point that reached SQLite survives crash / kill / reboot / update.
- **Release-relevant:** 3 UX blockers, 1 web blocker, the owner's 3 field bugs, and two release-pipeline points the
  owner must decide (debug build, public test key).

## A. Fix before the release (captain's proposal – owner confirms in the morning)
App – **cloud-app**:
1. **Owner field bug: the start sentence ("Los geht's") repeats** several times in the first km with a poor fix →
   `fix-voice-repeat.md`. Every one-time sentence at most once per tour.
2. **Owner field bug + UX B2: quick Stop.** The rolled-up tour row ("Tour läuft · 1,7 km · 0:14:38 · Weiter") gets a
   round ■ Stop button (48 dp) left of Weiter → opens the same "Tour beenden?" question as End tour. Today Pause / End
   are only reachable by dragging the sheet up; a tap on the slider gives no "slide" hint (`SlideToUnlock.kt:86-140`)
   → add one.
3. **Owner field bug: route / lines vanish when editing the plan while moving** → `fix-tour-edit-line.md`.
4. **UX B1:** the empty plan says "Select a point on the map" but a tap does nothing – it must say *hold*
   (`strings_plan.xml:59` `plan_hint_first_point`, text already in `ux-two-modes.md:142`). String only.
5. **UX B3:** every fresh install asks "Earlier tracks and routes? … Restore" right after the welcome
   (`AppViewModel.kt:4665-4672`) → only when a backup folder exists, or "Used OnPath before?" with "No, I'm new" as the
   main button.
6. **Recording H1:** opening Plan during a tour and pocketing the phone turns off the off-route warning for the rest
   of the tour (`AppViewModel.kt:3294`, `Navigator.kt:243/415`) while junction cues go on → not visible = navigating.
7. **Recording M1:** two notifications share id 44 – "Recording cannot be saved" is cancelled by Pause / Resume
   (`RecordingService.kt:1181-1183`). One line.
8. **Routing H3 / Arch 4:** catch-all around the new splice (fallback to the first route, `Problems.warn`) and no raw
   exception text to the user (`AppViewModel.kt:2712` `it.message` → `UserText.of`). Shared code → cloud-plan follows.
9. **Arch H1 (part):** every user sees Developer tools because releases are debug builds
   (`AppViewModel.kt:257` `debugBuild || unlocked`) → tie them to the 7-tap unlock only.

Web planner – **cloud-plan** (after merging `main`):
10. **Web B1:** a failed routing-data / engine / search-index download stays failed until reload
    (`engine/worker.ts:146-206`, `92-110`, `215-231`) → drop the cached promise on failure; grey "routing data" line.
11. **Web H2:** no check for WasmGC – on iOS < 18.2 the planner does not work at all and says "reload" → feature
    check + a clear "browser too old" page; test Safari + Firefox once.
12. **Web H3 (cheap part):** an unsaved route is lost when the planner is reopened without `#p=` → restore the last
    draft like the app, or at least a `beforeunload` warning while unsaved.
13. Hide the developer line "Routing engine ready (N ms)" (`main.ts:292`); no "Unexpected: java.lang…" texts.

## B. Owner decisions
- **D1 – Debug build + public test key for real users** (Arch H1/H2): releases are `assembleDebug` (debuggable,
  slower) and signed with the key that is in the public repo – anyone could build an "update" that installs over
  OnPath. Fine for a small invited group? Before Play / a wider group: release build type + private key with
  **key rotation** (`apksigner rotate`) so testers keep their data. Changing `release.yml` / signing = PR to the owner.
- **D2 – Install path for testers:** APK from the GitHub release (Play Protect warning) or wait for the Play internal
  test (A-003 not started).
- **D3 – Sync, closed folders (Sync H2):** after an unpair the browser's routes are invisible and one quiet button
  deletes them without asking – this follows A-017 rule 16 ("no question"). Proposal: list them read-only with
  "Move to current folder" and ask before removing.
- **D4 – Test servers for real users:** map server TEST (one shared key, IP limits), sync DB `onpath-sync-test`,
  routing data straight from brouter.de (third-party volunteer server, no User-Agent – add one now). OK for day one?

## C. Next release (in order of risk)
1. **Recording H2 – arrival only works with the app on screen**; in the pocket there is no "arrived", and walking on
   to the car park raises off-route alarms + re-routes every 2/4/8 min. Move the arrival check into the service
   (OsmAnd / Organic Maps end guidance at the destination and keep recording).
2. **Recording H3 – after process death the service may come back recording nothing** (no background location) –
   watchdog: no fix ~5 min while not paused → loud notification; honest "Recording interrupted – open OnPath".
3. **Sync H1** – re-pairing the same phone drops browser routes the phone never pulled (`WebSync.kt:205-214`).
   **Sync H3** – Save over a route that sync changed meanwhile silently loses the other edit (app + web) → one shared
   rule in `sync/`: changed elsewhere since opened → save as copy or ask.
   **Sync M4** – a mailbox that comes back empty (365-day purge / DB reset) stops sync for good, silently.
4. **Routing H1** – one routing thread for everything: pairing with 10 browser plans blocks the user's own plan for
   minutes. **Routing H2** – GPX with both `<rte>` and `<trk>` is merged into one line (double distance).
5. **Recording M2/M3/M4** – unwritten points dropped for good; no exception handler in the service scope; the
   activity-switch question counts as "off route".
6. **UX HIGH** – app (hold) vs web (click / drag) gestures not explained; tap on a POI in Plan does nothing;
   "✕ Close" right under End tour; two different off-route questions; error texts say "move the point" (the app can't);
   route / plan / tour / track mixed up → a small glossary for EN + DE.
7. **Web** – edits queued while the engine is busy; `navigator.storage.persist()`; security headers (CSP – PR to the
   owner); Points list rows differ from the app (move the row text into shared `PlanPoints`).
8. **Architecture** – `AppViewModel` 4,994 lines → split out Updates, Plan sync, Phone setup, Cells UI, Shared places;
   one download helper (APK sha256 check); one shared-files list on `main` read by both builds (today 19 shared files
   are only compiled on `web-spike`); copied geo math.

## D. Checked and fine
Problems log + atomic writes; corrupt DB kept aside; cell manifests checked (names, sha256); zip import safe; GPX
parser without external entities; only `MainActivity` exported; no Play Services; HTTPS everywhere; camera moves only
in `MapController`; EN / DE strings 1,213 keys each; 544 catch sites, only a handful silent; supersede / cancel in
recalc, rd5 writes tmp + rename, undo / redo after snapping; web: names escaped, plans and pairing secrets never leave
the address, tab locking, new-version box, cell retry / back-off; TTS audio focus, offline voices, calls.
