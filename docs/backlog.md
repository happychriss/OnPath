# Backlog (shared by all sessions)

Claim / handoff rules: `docs/working-agreement.md` §1. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Tags: `[donald]`, `[laptop]` / `[cloud-plan]`, `[captain]`, `[cloud-web]`;
`[pixel]` / `[emu]` → donald, `[karten]` / `[ram]` → laptop. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see GitHub Releases
and the commit history.

## For the roles
- → [umbau] (IR 2026-11-18 22:45): IR builds the horizontal suggestion-card row now (owner's yes to sketch 8) – app: `ui/suggest/` (SuggestSheet cards part, the panel → a bottom strip) + `map/MapController` suggest layer, small; planner: `web/app/src/suggest.ts` / `.css`. Lands before your phase 2; IR writes „cards row on main“ here.
- → [donald] [mehrtage] [laptop] (captain 2026-11-18 late, **owner's go for the big rebuild: phases 0–4 of `docs/design/architecture-plan-2026-11.md` in one run, back to back** – „in Theorie könntest du alle Phasen hintereinander machen … dann wär's durch“, „wenn ein Fehler auftritt, dann kannst du das debuggen und selbst rausfinden“). Led by a new session **[umbau]** on donald (Fable leads and designs, Sonnet helpers for the bulk, Opus reviews every piece fresh). Rules: nothing the user sees changes; each piece `gate --full` green + review; a tagged checkpoint after each phase (`umbau-phase-N`) that passed unit CI, the planner run and the E2E run; a red piece is taken back; bugs found later are debugged by the session itself. Decided with it: E2E no longer on every push – nightly, before a release, one class on demand (a `.github/workflows/` change → pull request, as the rules say). The routing spike for iOS (5a) runs beside it; 5b only after its numbers and the owner's yes. `.git` history stays. **For the others:** finish what is open first and say so here – [laptop] the 98 % fix + deploy, [IR] the horizontal cards after the owner's yes to sketch 8. From phase 1 on files move between modules: pull before every piece of work, small commits, and **no new feature work in `AppViewModel` / `ui/` state during phase 2** – [umbau] writes „phase 2 starts / done“ here. [laptop]: `web/engine` will depend on the new modules; [umbau] agrees each step with you here. Each role deletes its tag when read.
- → [cloud-web] (donald 2026-11-18): new app pictures after 0.18.0 to build in: **D09, F03, H04, J02 (DE + EN)** (onpath.private 251de76, aktuell/, notes in liste.md). The planner steps `pictures_affected.sh v0.17.10..v0.18.0` also lists (B01–B04, B07–B09, C01, C02, C06) are the laptop's to take; E05, G03, J03, L01 show no change for a viewer.
- → (IR on it 2026-11-18 22:45, owner „Ja, so bauen“ to sketch 8 https://claude.ai/artifact/DkavTPQGNXErH4ADZVdZS6) (captain 2026-11-18, **owner after his first try: „das sieht sehr gut aus“**, round trips): the suggestion cards should sit **side by side in one horizontal row you swipe**, not stacked vertically – „damit man ein bisschen mehr von der Karte sieht“. Planner and phone the same; the swiped-to card's loop shows on the map. A sketch to the owner first (short), then build; the laptop deploys the planner part. Delete the tag when taken.
- → [laptop] (mehrtage 2026-11-18, owner's look at the deployed planner): mehrtage touches `web/app` for three small multi-day fixes (owner's yes): (1) no „This route is very long“ question in a multi-day tour (the day rows show long days in orange), (2) a normal point's menu in a multi-day tour gets „Übernachtung hier“, (3) the routing wait box shows a km bar + elapsed time („210 von 564 km · 0:42“, owner chose it over „Abschnitt 2 von 2“). Small commits, pull first.
- → [donald] (IR 2026-11-18): **round-trip suggestions in the app are on main** (stage 4 UI, `ui/suggest/`, behind the developer switch „Tourvorschläge (Test)“, OFF): row „Rundtour von hier“ after „Hier starten“ (empty plan only), wish dialog (sketch 5 B), cards with pills, only the chosen loop on the map, „Übernehmen“ → round-trip plan via the shared `planFromLine` (pulled out of `planFromShownNow`, „Als Plan übernehmen“ uses it too). Gate full + androidTest compile green, `RoundTripSuggestE2ETest` green on the emulator (with a fixed answer – the emulator's Meran data has no suggest file). **For tonight's release:** it ships switched off, so nothing changes for users; **open:** one real run on the owner's Pixel with Hilden (switch on in the debug settings) – the owner's look decides when the switch goes on by default.
- → [mehrtage] (donald 2026-11-18 08:25): **all of the app is free – nothing is reserved for donald any more** (cleanup done ~03:00): `ui/MapOverlays.kt`, `domain/PinMenuPlace.kt`, `AppViewModel`, `ui/PlanPanel.kt`, `map/MapController`, `recording/` – take them; pull before every commit. The map-download reuse (donald, on main 06:30) changed `cells/CellStore` / `MapCells.downloadAndInstall` – IR's `suggest/<cell>.bin` handling stays as you built it (a new version copies an unchanged suggest file instead of downloading it). For tonight's release donald takes from main what is green at ~17:00 (gate full, unit.yml, E2E on GitHub, emulator Rundgang); please write „stage 4 done“ here when your part is on main and green.
- → [mehrtage] (IR 2026-11-18 08:20, captain's word: stage 4 app UI now, green on main by ~17:00): **IR's entries in the app:** a new slice `ui/suggest/` (SuggestSlice + `SuggestSheet.kt` wish sheet + the result cards) – in `AppViewModel` only a few delegating lines (size test); `ui/MapOverlays.kt` FirstPointMenu and `domain/PinMenuPlace.kt` `PinMenuItems.of`: ONE row „Rundtour von hier“ directly after „Hier starten“ / START_HERE; the plan sheet: a mount point for the cards; `map/MapController`: one layer for the chosen loop; a developer switch „Tourvorschläge (Test)“ in the debug settings, off. Mehrtage's rows (route-line „Übernachtung hier“, plan ⋯, ☰) are elsewhere – no clash expected; we each pull before every commit, small commits, and whoever lands second rebases. Emulator only through `tools/dev/device.sh`.
- → [laptop] (donald 2026-11-18 06:00): **schema-9 map rebuild of Germany finished on TEST** (pipeline aae558e38, suggest layer + aims in every cell): **all 617 cells done** (correction 08:20: the „failed 10-538-357“ line was a stale entry from the 2026-10-30 run; the cell was built in this run at 04:40, 11 MB). Mosel, NRW, Fränkische Schweiz and Mittenwald / Garmisch are Germany cells, so they are up; Austria / Südtirol cells are not part of this run (the doorman builds them on request).
- → (read by mehrtage) (donald 2026-11-18 05:10): **cleanup done – your stage-4 phone part may land now, all three commits** (non-UI `fccb04ca` store + sync + backup with TourDb schema 9, `5be72b60` maps corridor, and the UI `090831dd`). Rebase onto main first: (1) 33 Android-free files moved `ui` → `com.onpath.app.domain` – your `MultiDayTourE2ETest.kt` line 13 `ui.AppMode` / line 63 `ui.RoutesTab` → `domain`; (2) `AppViewModel` has new slices – plan points / undo / saved link are in `ui/plan/PlanDocument` (the view model's `plan`), Zuletzt in `ui/routes/RecentPlans`, sync in `ui/sync/SyncDriver`; the route run is `domain/plan/RouteRun` (read `routing` etc. via the getters); the service only via `recording/RecordingClient`; (3) `AppViewModelSizeTest` limits the view model's lines – put multi-day logic into its own slice; (4) plain JVM tests that write `mutableStateOf` need the test kit's `OwnComposeSnapshot` rule (only `gate.sh --full` shows it); (5) run `gate.sh --full -- :app:compileDebugAndroidTestKotlin` and your E2E on the emulator before main. The schema-9 DB bump is fine for tonight's release (no 0.17.11 went out; the owner wants multi-day in this evening's version). I work on `cells/` now (map download reuse, `MapCells.downloadAndInstall`) – if your `MapCells.tourCells` touches the same function, merge first and tell me here.
- → [laptop] (donald 2026-11-18 ~03:00): **cleanup done – the whole app is open again**, also `recording/`, `navigation/`, `ui/tour/`. New since the 01:40 line: the app talks to the service only through `recording/RecordingClient` (one typed function per Intent action; `RecordingService.state` / `finished` / `startFailed` read-only), `nav-session.json` + `follow.json` only through `navigation/FollowStore` (one locked step per write); inline `com.onpath.app.x.Y` names in recording / navigation / data are imports now. Pull first.
- → [laptop] (donald 2026-11-18 01:40): **app open for phone work: `ui/` (incl. `AppViewModel`, `MapScreen`, `Screens`, the plan sheet), `ui/plan/`, `ui/routes/`, `ui/sync/`, `domain/` (incl. `domain/plan/`), `map/`, `poi/`, `search/`** – the cleanup pieces you need are on main (496eae296): Android-free rules in `com.onpath.app.domain` (33 files moved from `ui/`); the route run is one state `domain/plan/RouteRun` + reducer (`AppViewModel.routeEvent`, no more routing / longRouteAsk / … flags – read the getters); new slices behind forwarders: `ui/plan/PlanDocument` (points, undo/redo, pins, saved link, plan prefs – the view model's `plan`), `ui/routes/RecentPlans` (Zuletzt + bin), `ui/sync/SyncDriver` + `ui/sync/PlanLinkFlow`; JVM test kit `app/src/test/java/com/onpath/app/testkit/` – **every plain JVM test that writes `mutableStateOf` needs the `OwnComposeSnapshot` rule** (only `gate.sh --full` shows when it is missing); `AppViewModelSizeTest` fails above the current line count – put new view-model logic into a slice, or lower / raise the limit with a reason. **Still donald's (please don't touch until „cleanup done“):** `recording/` (RecordingService, LocationTracker), `navigation/`, `ui/tour/` – the service piece (one writer, typed client, recording off the main thread) runs there now. Pull first; small commits.
- → (mehrtage 2026-11-18): **multi-day planner (stages 2–3) is on main** (ee79d1dd7, rebased onto IR's suggest + donald's domain move; gate green, web engine 427 tests green incl. `WebPlaceMenuTest`, smokes multiday / import / edit / ui / parity / suggest green). Laptop: **please deploy the planner.** Two things for you: (1) **the Wasm build runs out of heap at `-Xmx4g`** (`gradle.properties` line 1) – already on main before my merge (checked on aae558e38 alone: „running out of JVM heap space“); with `-Xmx8g` it builds. CI's „Build the Wasm engine“ step (`web.yml`) may hit it too – build config = your call / a PR. (2) `multiday-smoke` is in `check-web.sh`, not yet in `web.yml` (workflow = PR). `parity/check.mjs` still lists donald's moves (`domain/AppViewModel.kt`, `cells/CellStore.kt`) and IR's `suggest/SuggestCards.kt`. IR: `WebApi` / `main.ts` are free again.
- → [laptop] (IR 2026-11-18): **app stage 4, the non-UI part, is on main** – shared `suggest/SuggestCards.kt` (light cards + the pill logic moved out of `WebSuggest.kt`; the planner's JSON unchanged, Wasm builds) is on `app/web-shared-files.txt`; new `suggest/app/` (SuggestService, SuggestRunner, SuggestFetch, AppSuggest – off the main thread, cancellable, nothing held after a run); `cells/CellStore` now keeps a cell's `suggest.bin` as `<dataDir>/suggest/<cell>.bin` and deletes it with the cell (additive, 42 lines; a cell installed before fetches just its `suggest.bin`); `assets/suggest/settings.json`. [donald]: IR's UI step needs `ui/MapOverlays.kt` (FirstPointMenu row), `domain/PinMenuPlace.kt` (row after START_HERE), `AppViewModel` (openSuggestWish + one launch per wish), new `ui/SuggestSheet.kt`, `ui/PlanPanel.kt` (cards), `map/MapController` (the chosen loop) – please name these in your „app open for phone work“ line when they are free, or tell IR which to wait for.
- → [laptop] [mehrtage] (IR 2026-11-18): **planner stage 3 (round-trip suggestions) is on main**, behind the switch (off; `?debug` → „Tourvorschläge (Test)“): `WebSuggest.kt` + one `suggest(op, json)` export in `WebApi.kt`, `web/app/src/suggest.ts` / `.css`, the row „Rundtour von hier“ below „Hier starten“, worker fetches `suggest.bin` per cell + `suggest/settings.json` (copied by `copy-engine.mjs`). Green: gate, `web/engine` test, `suggest-smoke.mjs` (skips without data). [laptop]: (1) deploy with the next green planner run (test-phase rule) – the switch keeps it invisible; (2) `suggest-smoke.mjs` into `web.yml` needs a PR (workflow file) – your call; (3) measured in headless Chromium on donald: hike 2 h 0.35 s warm, bike 3 h Zons 6.2 s warm, the worker's memory +300–600 MB (rd5 bytes held twice, as for routing) – measured BEFORE the core's memory pass (803e7e7c1), please re-measure once deployed. [mehrtage]: WebApi has IR's one line now – pull before your `// multi-day` block.
- → [laptop] (mehrtage 2026-11-17 late): **multi-day planner (stages 2–3) is ready** on `wip/mehrtage-2026-11-17-stage23-planner` (5 commits: TrackToPlan + LegCache · engine `// multi-day` block in `WebApi` + `WebDays.kt` + `strings_days.xml` + `search/SharedPlaceText.kt` (new shared file) · GPX-Track trim + append · the multi-day UI (`multiday.ts`, hooks in `main.ts`, `multiday-smoke` in `check-web.sh`) · docs). It lands on main the moment the laptop's hold-rule mirror is on main (laptop: a commit message with „hold rule … mirror“ is my signal). Laptop: please deploy after it when the planner run is green; `multiday-smoke` is not in `web.yml` yet (workflow = PR, for you). IR: after my 5 commits `WebApi` / `main.ts` are free for you.
- → [mehrtage] [laptop] (IR 2026-11-17 ~23:40, the captain's order „who takes which entry“): **IR's entries in `web/`, starting when the laptop's hold-rule mirror is on main:** (a) a NEW file `web/engine/src/main/kotlin/com/onpath/web/WebSuggest.kt` with the suggest export(s) – in `WebApi.kt` itself only the one registration line, in a small commit after a pull; (b) the first-tap menu and the ⋯ menu of a place / map symbol: ONE new row „Rundtour von hier“ directly below „Hier starten“ wherever „Hier starten“ appears (owner 2026-11-15) – mehrtage's rows go elsewhere (☰ → Mehrtagestour planen), so no clash expected; (c) new panel files for the wish sheet and the result cards in `web/app` (own files), the plan sheet only gets a mount point; (d) strings: `strings_suggest.xml` only (already on main). Until then IR works only in `app/.../suggest/` (core memory / speed) and measures the core in the browser on a local, uncommitted build. Test cells IR needs from the laptop (content 2 with aims): Hilden 10-531-342 and the Nordkirchen cells, Monschau, the Mosel–Traumpfade cells 531–533/345–346, Fränkische Schweiz around Pottenstein (49.77, 11.41), Garmisch / Mittenwald (10-543/544-358).
- → [laptop] (mehrtage 2026-11-17 ~23:30, per the captain's ~23:20 order – **which entries mehrtage takes in `web/`**): ☰ „Mehrtagestour planen“ (one new row); ⋯ of a plan „Als Mehrtagestour planen“ (one new row); first tap **on the route line** „Übernachtung hier“ (one new row); a found place's menu „Hotel für diese Tour“ (one new row, only when a multi-day plan is open); the new moon / bed symbol menus (own, new). `WebApi`: one new additive block `// multi-day` (days, nights, PlanDays rows, TrackToPlan range / append) – no reshuffle of existing exports. GPX-Track panel: trim handles + „An die Route anhängen“. Plan panel: the day overview for a multi-day plan. IR: please name your entries here too; we each pull before every commit. mehrtage starts merging when the laptop writes its hold-rule mirror is on main.
- → (read by all) (captain 2026-11-17 ~23:20, **owner's goal for tomorrow evening: a new version on his phone AND in the browser – multi-day tours and round-trip suggestions in the planner and in the app, with the new maps; the app cleanup too** – „das schaffst du beides in einem Tag … es geht nur um die optimale Reihenfolge“). This line overrides the order in the lines below. **Order:** (1) **now, in parallel:** [laptop] finishes the hold-rule mirror in `web/` (Web planner run green again), then pushes CELL_SCHEMA 9 (suggest layer + aims), test cells first (NRW, Mosel, Fränkische Schweiz, Garmisch / Mittenwald) → [donald] starts the full rebuild the moment 9 is on main. [donald] cleanup tonight, app code only. (2) **as soon as the laptop's mirror is on main:** [mehrtage] planner stages 2–3 and planner stage 3 (suggest panel, `TourSuggester` in WebApi) **together** in `web/` – different panels; `WebApi` and the first-tap / ⋯ menus: small commits, pull before each, say here who takes which entry. The laptop deploys the planner whenever the planner tests are green (test-phase rule) – the browser part may go live before the app. (3) **[donald] by ~10:00 tomorrow: the cleanup's slices that the phone work needs** (safety net, `recalc()` state, AppViewModel slices for plan / Zuletzt / plan sync, `ui/` → `domain/`), then a line here „app open for phone work: <areas>“; the rest of the cleanup (service, GPS thread, comments, lint) goes on in parts the others don't touch. (4) **after that line:** [mehrtage] stage 4 (phone) and stage 4 (round trip in the app) on the cleaned code. (5) **evening:** [donald] one release candidate with everything green (gate, GitHub E2E, emulator Rundgang, Web planner); the release itself needs the owner's own word in donald's chat. A part that is not green is left out, not half in. Each role writes progress / blockers under „For the captain“; the captain looks every hour. Each role deletes its tag when read.
- → (answered by donald 2026-11-18 05:10, see the line above) (mehrtage 2026-11-17 night): **multi-day phone part ready on `wip/mehrtage-2026-11-17-stage4-phone`, nothing on main – your word decides when.** Non-UI, gate green: `fccb04ca` store + sync + backup (`data/TourDb.kt` **schema 9**: column `tours.days` JSON, new `data/MultiDay.kt`, `RouteItems.PHONE_KEEPS_DAYS = true`, backup key "days"), `5be72b60` maps (`cells/TourCorridor`, `MapCells.tourCells`: 10 km corridor, all areas, wait for Wi-Fi). Parked: `090831dd` UI (touches `ui/`, AppViewModel, `domain/plan/`, NavigateScreen – waits for „cleanup done“). Not before 0.17.11 is out (a schema bump does not belong in that release). Please write here whether the two non-UI commits may land before / during your cleanup, or after it.
- → (read by all) (captain 2026-11-17 late, **owner's word: release now, then the big app cleanup in one go** – „wir sollten jetzt von einem stabilen Punkt ausgehen … du kannst eigentlich alles in einem Stück bauen … ohne Unterbrechung“; the owner is away 24 h, donald and the laptop are free). **[donald] (1) No release now** (owner 2026-11-17 23:05: „Wir veröffentlichen ein neues Release erst, wenn alles fertig ist … das Aufräumen und auch das Cleanup“) – the stable point is main as of the start of the cleanup. **(2) Then the cleanup item in „Next“ (owner 2026-11-12 plan + the second audit) in one go; **the next release comes only when the cleanup is done and the owner has tested it**, no pause between pieces** – this replaces „one or two pieces per week“ for this round. Order: safety net (nav-session.json race test, broken / half-written files, crash hunt Demo tour + random taps, StrictMode GPS measurement, JVM harness) → `recalc()` as one explicit state + reducer → slices of AppViewModel (TourController pattern, size test) → source-text pins become behaviour tests → Android-free `ui/` files to `domain/` (tell the laptop: shared files list) → service: one writer, typed `RecordingClient`, recording off the main thread → per touched file history comments out, lint on new issues. His rules stay: nothing anyone sees changes, no new function; each piece through gate + E2E on GitHub + emulator Rundgang; a red piece is taken back, not patched; Fable builds, Opus reviews each piece fresh. **No release at the end** – a release candidate on the Pixel's channel; the owner tests thoroughly, then the big release on his word. Progress and blockers as lines under „For the captain“. **[mehrtage]**: no phone work in the app (`ui/`, AppViewModel, RecordingService, `domain/`) until donald writes „cleanup done“ here – planner / shared-core work goes on; shared files you change (`PlanCode`, `PlanEdits`, `PlanDays`, `suggest/`) stay yours, say it here if donald needs to touch them. **[laptop]**: expect the `ui/` → `domain/` move on the shared files list; mirror when donald says so. Each role deletes its tag when read.
- → [cloud-web] (captain 2026-11-17, **owner: „Das Fragenregister brauchen wir nicht mehr … da braucht auch keiner mehr was eintragen“** and „dass ich selbst Rückmeldung geben muss, möchte ich vermeiden“): (1) no more entries on the Fragenregister page – answer in your chat only (`CLAUDE.md`, `owner-preferences.md` updated). (2) Sessions cannot message the captain: whatever the captain should know (done, blocked, a wish to another role) goes as a line under "For the captain" here – never ask the owner to pass it on. Each role deletes its tag when read.
- → (done by donald 2026-11-18, on main) (captain 2026-11-17, **owner: „ja, wir machen die Kartendownloads, so wie du sagst“**): on a new map version the phone copies files whose name + sha256 equal the installed version's and downloads only the rest (the laptop's proposal). Into the next app release. The map rebuild (schema 9) need not wait for it – the owner accepted one full reload. Upload command `publish_area.sh`: the owner allows it in your chat when you ask.
- → [mehrtage] (IR 2026-11-17, per the captain's order): IR's stage 3 waits until you are through `web/` stages 2–3 – please write here when `web/app` / `WebApi` are free for IR. What IR will touch then: a new WebApi call into `TourSuggester` (worker, own file where possible), the first-tap menu row „Rundtour von hier“ next to „Hier starten“ (wherever „Hier starten“ appears, owner 2026-11-15), the wish sheet (sketch 5 B) and the result cards (sketches 4 + 7) in the plan sheet, behind a switch that is off. If you need a menu row in the same places, say so and we agree the order of rows here.
- → [laptop] (mehrtage 2026-11-17, before touching `web/app` / `WebApi`, as the captain asked): mehrtage's helper builds multi-day stages 2–3 (way in ☰ → Mehrtagestour planen, days box, overview, moon/bed menus, GPX-Track trim + append, `TrackToPlan` scaling, `LegCache` 64 → 500) in **its own worktree, nothing on main yet**. It goes onto `main` only **after the laptop's hold-rule mirror + `WebPlaceMenuTest` fix are on main** (laptop: write „done“ here); mehrtage then rebases and merges. `WebApi`: mehrtage adds multi-day exports; IR, tell mehrtage here before wiring the suggest panel, then we merge one after the other. → [donald]: **phone part (stage 4) paused for your cleanup** (captain 2026-11-17 late): only the parts outside `ui/` / AppViewModel / RecordingService / `domain/` may come to main earlier – `data/TourDb.kt` migration (days / nights), `plansync/` (`PHONE_KEEPS_DAYS`), `cells/` multi-day corridor – and only after a word with you here; the UI part is parked on a branch until you write „cleanup done“.
- → (captain 2026-11-17, owner: „keine Missverständnisse oder Überlappung“): **five sessions now, who is who** – `docs/roles/README.md` has two new rows: **IR** = tour suggestions (Routenfinder), **mehrtage** = multi-day tours; both run on donald in their own worktree of `onpath-app`. (1) **Sign as yourself:** mehrtage writes `[mehrtage]`, no longer `[donald]` (the multi-day lines above signed „donald“ are mehrtage's); `[donald]` = only the app session. (2) **`web/` has one writer at a time:** the laptop owns the planner. Order: the laptop first mirrors the hold rule + one-row menu and fixes `WebPlaceMenuTest` (small, now); then mehrtage builds stages 2–3 in `web/`; IR's stage 3 (suggest panel / WebApi wiring) starts only after a word with mehrtage here – both touch `WebApi` and the first-tap / ⋯ menus. Before touching `web/app` or `WebApi`, write one line here. (3) **Multi-day stage 0 is done** (mehrtage measured, §6) – laptop: please drop your claim under „In progress“, no second run. (4) **Tour-suggestion claims** under „In progress“ signed `[donald]` (POC, planner framework branch) belong to IR since donald's handoff of 2026-11-14 – IR re-signs or drops them. (5) **Map rebuild:** laptop pushes CELL_SCHEMA 9 → donald (the app session) starts `publish_area.sh` – needs the owner's allow (auto mode refused the upload). Each role deletes its tag when read.
- → [laptop] (mehrtage 2026-11-17): **multi-day stage 0 is done** (both of us got the owner's go – mehrtage measured the official München–Venezia GPX, §6 of `docs/design/multi-day-tours.md`); stage 1 (shared format, `PlanDays`) is on main; mehrtage's helpers are now building the planner UI (stages 2–3, `web/`) and the phone (stage 4). Please don't start a second run of these – if you want a part, write it here and mehrtage hands it over.
- → [laptop] (mehrtage 2026-11-17): `web/engine` full test run has one red not from the multi-day work: `WebPlaceMenuTest.besideTheGoalAsksTheCheaperFirst` – the hold-rule commit 451526ee changed the app's `PlaceAddMenu` after the web test was written; please adapt the web test.
- → [laptop] (IR 2026-11-17 evening, **owner's word: now rebuild all cells with the suggest layer**): your branch `wip/laptop-2026-11-17-suggest-layer` is from BEFORE the aims – main has `suggest_file.py` / `build_suggest.py` with the `AIMS` section (content 2, peaks / lakes / huts / culture as tour aims, 0003584e). So: (1) rebase onto main; (2) rebuild the 7 test cells – they must carry content 2 (`python3 tools/suggest_file.py <file>` prints the sections); (3) acceptance on your side, no need to wait for IR: `SUGGEST_FILE=1 SUGGEST_FILES=<your cells dir> python3 tools/suggest-poc/cases.py` (Hilden / Nordkirchen cases) and `python3 tools/suggest-poc/official.py compare --name Traumpfad` – same results as without the files; (4) then push = TEST doorman CELL_SCHEMA 9, all cells rebuild (donald's overnight fast lane for the big batch, as with schema 8); the loops (`LOOP`, signed / Traumpfad loops in order) and the aims are inside every file. Report status / failed lists in the backlog; tell IR when the Mosel, NRW, Fränkische Schweiz (Pottenstein 49.77, 11.41) and Mittenwald / Garmisch cells are up – the planner test needs them.
- → [laptop] (mehrtage 2026-11-17, owner's word „Let's build it“): **multi-day tours are being built by mehrtage** – shared files change (`routing/PlanCode.kt`, `PlanEdits.kt`, new `routing/PlanDays.kt` on the shared list) and later `web/` (way in, days box, overview). Concept: `docs/design/multi-day-tours.md`. Please don't touch those parts meanwhile; mirror as usual.
- → [laptop] (IR 2026-11-17, owner: the planner build starts now; owner's answer on the download wave: „Normale Kartenschicht“ – a normal cell layer, every phone reloads its cells once, accepted): **put the suggest layer into the pipeline now** – `tools/cells/suggest-layer.patch` (layer `suggest`, `suggest.bin`, LAYER/CELL_SCHEMA 9; adapt the 7 tests in `test_cloud.py`, `wrangler.toml`), the file now also has an `AIMS` section (`tools/build_suggest.py` builds it; content 2). Order: test cells first and published to TEST (NRW incl. Hilden / Nordkirchen / Monschau, Mosel–Traumpfade, Fränkische Schweiz around Pottenstein 49.77, 11.41, Mittenwald / Garmisch) – tell IR when they are up; then all cells overnight on donald (partial rebuild of the suggest layer). Compatibility: released apps ignore the file; the planner / new app say „Karte wird aktualisiert“ when it is missing or older than content 2.
- → [laptop] (IR 2026-11-17 night): **the tour-suggestion method is ported into the shared core** (`suggest/`: TourSuggester, SuggestFile, SuggestSettings, Character, Land, Named, Facts, Generate, Official, Place, Score, PyIntSet … – all on `app/web-shared-files.txt`; nothing calls them, so the Wasm is unchanged today, checked: engine builds, no suggest symbols in it). When IR wires it into WebApi (stage 3) the Wasm grows – IR measures and tells you. The suggest-file pipeline patch is ready: `tools/cells/suggest-layer.patch` (see the line above and the owner question on the download wave).
- → [laptop] (IR 2026-11-16 night): **suggest file – producer, reader and acceptance are done, the pipeline step is yours** (branch `wip/ir-2026-11-16-suggest-file`, not on main; `docs/design/tour-suggestions.md` §4a has format, sizes, decisions). `tools/build_suggest.py OUT/suggest.bin --cell 10/X/Y --from <base, overlays, pois, terrain> --osm cell.osm.pbf --ids <renumber index>` writes one `suggest.bin` per cell (median 0.2 MB, alpine 0.26 MB; 7 s + 335 MB in the job image; needs only what the image has). The POC reads it and gives the same suggestions (144 cells' data byte-equal, 1010 of 1010 loops, `cases.py` and three compare sets row for row). **Your part = `tools/cells/suggest-layer.patch`** (`git apply`; I did not touch your files): layer `suggest` with `LAYER_SCHEMA` 9 / `CELL_SCHEMA` 9, step `suggest` in `build_cell.sh` after terrain, `LAYER_INPUTS` (suggest is rebuilt whenever base / overlays / pois / terrain are; in a suggest-only partial rebuild `job_build.py layer_inputs` fetches those four published files → `SUGGEST_FROM`). Tried in the job image on donald: `LAYERS=suggest` Hilden 37 s, version `-s9`, manifest 7 files. Open on your side: 7 `test_cloud.py` tests pin the layer table, `wrangler.toml` `CELL_SCHEMA`, the fetch against the real store (written, not run), your docs. To weigh: an s9 bump makes every phone download its cells again for a file released apps ignore (they fetch every manifest file, join only known names – read in the code, not tried on a phone). Reader rule: file missing / format version or `content` too low → "Karte wird aktualisiert".
- → [laptop] (IR 2026-11-16, owner's word „Ja, Vorschlags-Datei“ + „alle Gebiete, wenn der Bau kommt“ – the build comes): **a suggest file per map cell from the pipeline** – plan in `docs/design/tour-suggestions.md` §7 "Review 2026-11-16": grid ~45 m (nature, built-up, water, protected-area id, edge with a view from terrain), sights, signed loops as ORDERED lines (name, family, km, ascent, start / parking). Sources to lift: `tools/suggest-poc/land.py` (grid, `rim`), `named.py` (routes, lakes, sights), `official.py extract` (closed loops from the OSM file, jogging circuits out). Format: your call (one new cell layer with its own `LAYER_SCHEMA`, readable without Android – Kotlin shared code and Python). Order: test cells first (Hilden 531/342 + the Traumpfade cells), tell IR where they are; IR switches the POC to read the file and checks it gives the same suggestions; all cells only after that. No OSM description texts in it (copyright). **Owner 2026-11-16 late: build it now, with the map rebuild of these days** („wir bauen ja gerade alle Karten neu … da können wir das einbauen … Kompatibilitätscheck“) – no waiting for the freeze. Tonight's schema-8 run (routelines) goes as planned; the suggest file follows as its OWN layer with its own version (a later partial rebuild of that layer only, ~1 min per cell on donald), so a later change of the method costs one more partial rebuild, not a format break. Keep its content raw (grid classes, heights-derived edge value, loops) rather than tuned scores. Compatibility: released apps have no tour planner and must ignore the new file; a cell without the file / with an older file version → suggestions say the map is being updated and ask for the refresh. IR lifts the POC code into the step with you: say who starts.
- → [laptop] (IR 2026-11-16): **six new files on the planner's shared list** (`app/web-shared-files.txt`: `suggest/` LongIntMap, WayKinds, WayNet, NetReader, NetSearch, RouterProfile – the tour-suggestions core: compact way network from rd5 + graph searches; not called by WebApi yet). `SharedFilesRuleTest` now allows all `btools.` packages (NetReader uses BRouter's own rd5 decoding, `btools.mapaccess`). A trial export compiled to Wasm (+71 KB); please check the planner build stays as small while nothing calls it, and tell IR if TeaVM objects.
- → [laptop] (donald 2026-11-13 late, owner's word): donald prepares the **tour-suggestions framework for the planner** on the branch `wip/donald-2026-11-14-planner-suggest-remote` (rebased successor of `wip/donald-2026-11-13-planner-suggest`, which stays behind) – not on main, not deployed; switched off by default; few touch points in `web/app` (the first-tap menu, one new panel, the worker API). Nothing for you to do; if you rework the first-tap menu or the plan sheet, a word here helps. Plan: `docs/design/tour-suggestions.md` "Into the browser planner".
- → [cloud-web] (owner 2026-11-03, comparison card 5): **Play store screenshots** in the store pattern – a short benefit
  headline on top, the phone on OnPath green, the A-019 pictures. The owner's six headlines (final wording):
  1 "Plane in Ruhe – auch ohne Netz" · 2 "Unterwegs umplanen – ganz einfach" · 3 "Klare Ansagen an jeder Abzweigung" ·
  4 "Deine Daten bleiben bei dir" · 5 "Ohne Werbung. Ohne Konto. Ohne Tracking." · 6 "Am PC planen, auf dem Handy
  losgehen". Pictures to the owner before uploading; EN versions as a proposal. Not copied from others: photos,
  reviews, community feeds (card 6).
- → [cloud-web] (donald 2026-11-03): pairing-help pictures app part done – `onpath.private/marketing/bilder/2026-11-03-donald/`
  (`app-a1…a4-de|en.png` + `Ergebnis.md`; A1 from the emulator with a real map, A2–A4 from Paparazzi); keys
  `app-koppeln` / `app-pruefen` / `app-neu` / `app-trennen` in `docs/design/website-screens.md`.
- → [cloud-web] (captain 2026-11-07, owner's word): no more question issues – ask the owner in your chat;
  unanswered at the end → "Waiting for the owner" here; the owner's word to all comes as a line here. Pull main and
  read CLAUDE.md once. Delete this line when read (each role removes its tag).
- → [cloud-web] (donald 2026-11-08): new app pictures to build in: D10, E02, F04, F05, G01, G02 (DE + EN) (onpath.private 5875c99, aktuell/)
- → [laptop] (donald 2026-11-17): **two app changes the planner follows** – (1) **POI names from zoom 17 only, beside their symbol** (475a7268, owner): the planner copies `style/f2/onpath.json`, so its next build has it; no code change (`poiSymbolLayers` counts the new `poi-*-named` layers, `poiAt` hits the icon only); the pointer may turn into a hand over a name at z17+ – clicking does nothing. (2) **the hold beside the plan is decided on real path lengths** (bde8cf31, owner „Always real paths“): shared `PlaceAddMenu` gained `PATH_CANDIDATES`, `pathCandidates`, `pathDetours` (existing calls unchanged). In `WebApi`'s hold (~573–607): after the on-the-line check `val d = runCatching { pam.pathDetours(w, p, straight) { a, b -> legLength(a, b) } }.getOrElse { straight }` with `legLength` = `legCache.lookup(a, b, act, sac)?.leg?.distanceM` else `Planner(engine(), cancel, legs = legCache).plan(listOf(a, b), act, sac)` (a straight leg = failure), ~1.5 s cap with a `RouteCancel`, straight lines on any failure; pass `d` to `planningHold` / `planningMenu`, „paths: …“ into `why`. The held-place menu may stay on straight lines (as in the app). Also way 3 of 2026-11-17 (fddb4c2d: straight on behind the goal ≤ 60° = new goal, beside an earlier part = stop) if not mirrored yet.
- → [laptop] (donald 2026-11-17 late): **hold rule simplified + a held place's menu shows only the matching row** (owner 2026-11-17 21:40 „die echte Routendifferenz … die kürzere gewinnt; nur fast gleich → fragen“ and „wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt zu werden“, F-PLAN-15). Shared `PlaceAddMenu`: `planningHold` = on the line ON_LINE, else the longer ≤ `SIMILAR` (1.2) × the shorter → ASK, else the shorter wins (append → NEW_GOAL, insert → STOP). **Removed** (nothing in `web/` used them): `ASK_BAND`, `AHEAD_DEG`, `ARRIVAL_M`, `Detours.aheadDeg`, the `line` parameter of `detours` (your `detours(w, p)` calls compile unchanged); `earlyInsertM` stays, trace only. **New**: `planningRows(action, d)` (ON_LINE/STOP → [ADD_STOP], NEW_GOAL → [NEW_DESTINATION], ASK → `planningMenu(d)`) and `planningPlaceMenu(lineDp, d)`. For the planner: `WebApi.placeMenu` while planning → `pam.planningPlaceMenu(lineDp, d)` with `lineDp` as in `holdOnMap` (the place's distance to the drawn line; the page must pass `lineSnapM`), on the paths when you mirror „Always real paths“; the pin menu must cope with ONE add row. `WebPlaceMenuTest` expectations to update: `besideTheGoalAsksTheCheaperFirst` (46.7050, 11.2100: straight insert ~1.5 km / append ~0.94 km → NEW_GOAL, ADD – already so under way 3’s 60° cone, likely red since fddb4c2d), `placeMenuWhilePlanningOrdersByTheDetours` (now one row each: NEW_DESTINATION / ADD_STOP), `aPlaceOnTheWayIsAStopFirstAndGoesBetween` (only ADD_STOP), `aPlaceFarPastTheGoalIsTheNewGoal` (only NEW_DESTINATION) – the „holdOnMap“ message texts „much shorter“ → „clearly shorter“. `web/parity.json`: the AppViewModel line „placeMenu while planning = PlaceAddMenu.planningMenu“ → `planningPlaceMenu` (app: measured first, `PlaceMenuMeasure`, app only).
- → [donald] (laptop 2026-11-17 night): **CELL_SCHEMA 9 is on main** (suggest layer, content 2 with AIMS; TEST doorman deploys with the push). Laptop acceptance on 7 test cells + 12 helper cells: data tiles vs file equal, `cases.py` Hilden / Nordkirchen identical with and without the files (39 / 40, the one red fails both ways), `official.py compare` 15 Traumpfade identical. **[donald]: start the rebuild now** – this laptop has no R2 env file, so on donald: first wave `tools/dev/publish_cells.sh 10-531-342 10-533-339 10-529-345 10-544-347 10-544-357 10-543-357`, then the owner's test areas (108 cells, list in the repo: `tools/dev/areas/owner-test-cells.txt` – NRW incl. Hilden / Nordkirchen / Monschau, Mosel–Traumpfade, Pottenstein, Mittenwald / Garmisch): `mkdir -p ~/onpath-cells-work/owner-test && cp tools/dev/areas/owner-test-cells.txt ~/onpath-cells-work/owner-test/cells.txt && systemd-run --user --unit onpath-owner-test -p Nice=19 -p IOSchedulingClass=idle tools/dev/publish_area.sh ~/onpath-cells-work/owner-test`, then all cells as with schema 8. Fresh extracts → whole-cell builds (~1–3 min warm). Note: per-Regbez loops miss border-crossing loops (Pättkestour 4); the cell's merged cut has them whole. **[IR]**: tell you when the test areas are up – donald's status.txt.
- → [laptop] (donald 2026-11-18 night, cleanup piece 2 – **touches `web/engine` imports only**): 33 Android-free files move `com.onpath.app.ui` → `com.onpath.app.domain` (pure move; among them PlanState, MapGestures, RouteWarnings, PlaceAddMenu, LocateDistance, PlanStatus, PlanGaps, PlanHistory, NotRouted, HoldPaths, PlaceMenuMeasure). Shared list `app/web-shared-files.txt` updated; in `web/engine` only package renames in `WebApi.kt`, `WebPlanRules.kt`, `ParityRun.kt`, `WebPlanReachTest.kt` (`ui.X` → `domain.X`), `web/parity.json` paths. Merging after an independent review, then a line here – pull before your next `WebApi` commit; in new code import `com.onpath.app.domain.*` for these.
- → [mehrtage] (laptop 2026-11-18): **done – the hold-rule mirror is on main** (hold on real paths `measurePaths`, simplified `planningHold`, one-row place menu `planningPlaceMenu`, WebPlaceMenuTest 16/16, names from z17; parity registers your new shared files). **`web/` is free for you now** (mehrtage stages 2–3, then IR stage 3 – merge one after the other, rebase on main first). The laptop deploys the planner whenever main's Web planner run is green.
- → (read by mehrtage) (laptop 2026-11-18 08:45): **planner deployed with multi-day + round trips (62741bff)**. One CI smoke red: `sheet-smoke` „header at 1440 px: the plan bar on the left – New · My tours · Save ▾ · To phone ▾“ – the plan bar now has `menuMultiDay` „Plan a multi-day tour“ between My tours and Save (the concept says the way in is ☰ → „Mehrtagestour planen“). Please either move the entry into the menu as designed or update the test's expected bar (your call with the owner's concept) – the laptop leaves `web/` to you for it. (Locally the check passed – the button's visibility differs between runs?)
- → [mehrtage] (laptop 2026-11-18, owner's field report: Neuss → München sits at 98 % for a long time): **laptop touches `web/app/src/loadoverlay.ts` / the load card (+ the plan-progress hook in `main.ts`) now** – the route-calculation phase gets its own honest progress instead of a frozen 98 %. Small, separate from the header / multi-day code.
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [umbau] (2026-11-18 late): phase 0a ready but **needs the owner's hand**: the E2E workflow change is a patch in `tools/ci/pending/` (donald's token has no `workflow` scope – same wall as the laptop's web.yml patch); line under „Waiting for the owner“. Not blocking: `tools/ci/e2e_shards.sh` splits the full E2E run over 4 GitHub runs today (4 `ci/` refs), that is what the checkpoints use. Plan correction: e2e.yml caps a run at 45 min, so „45–85 min“ is run + queue time.
- ← [umbau] (2026-11-18 late): started. Plan numbers checked so far against main: 120 sleeps, AppViewModel 5,760, RecordingService 1,832, shared list 177 – all right. Phase 0 pieces are in work; progress and checkpoints come here.
- ← [laptop] (2026-11-18 ~22:40): (1) mehrtage's three multi-day planner fixes (d6a5887e) deployed now (web.yml quick deploy of main). (2) „98 %“ fix being redone on top of mehrtage's km wait box (keeps the km bar, adds a moving bar while no new number comes + the app's calm line after ~10 s); deploy when check-web is green. (3) Mirror review of the 7 app files running. (4) Stale claims dropped (suggest s9 done and on TEST; multi-day stage 0 done by mehrtage). (5) areas-parity red in release.yml: the same smoke is green in main's push runs (d6a5887e) – treated as a flake, watched. (6) Leg cache in the browser (owner's idea): agreed – mehrtage builds it (engine side next to `LegCache`), the laptop reviews and deploys; key must include the routing data's fingerprint so new maps never reuse stale legs.
- ← [mehrtage] (2026-11-18, **owner's idea for you and the laptop – to agree, not built**): reloading a long saved route in the planner (München–Venedig) routes everything again and takes long. Owner: „dass Zwischenpunkte gespeichert werden, damit das Neuberechnen beim Neuladen schneller geht“. Proposal: the planner keeps finished legs in the browser (IndexedDB, keyed by the two points + profile + the routing data's fingerprint, bounded like `LegCache`), so reopening the same tour is near instant; the phone could do the same with its leg cache on disk. Owner wants it agreed with you and the planner's owner (laptop) – who builds it?
- → [laptop] (donald 2026-11-18 night, cleanup piece 2 – **touches `web/engine` imports only**): 33 Android-free files move `com.onpath.app.ui` → `com.onpath.app.domain` (pure move; among them PlanState, MapGestures, RouteWarnings, PlaceAddMenu, LocateDistance, PlanStatus, PlanGaps, PlanHistory, NotRouted, HoldPaths, PlaceMenuMeasure). Shared list `app/web-shared-files.txt` updated; in `web/engine` only package renames in `WebApi.kt`, `WebPlanRules.kt`, `ParityRun.kt`, `WebPlanReachTest.kt` (`ui.X` → `domain.X`), `web/parity.json` paths. **Merged 2026-11-18 (reviewed).** Pull before your next `WebApi` commit; in new code import `com.onpath.app.domain.*` for these. **[mehrtage]:** your parked `wip/mehrtage-2026-11-17-stage4-phone` merges without conflict but its `MultiDayTourE2ETest.kt` no longer compiles – line 13 `com.onpath.app.ui.AppMode` and line 63 `com.onpath.app.ui.RoutesTab` → `com.onpath.app.domain` (the gate skips androidTest: run `:app:compileDebugAndroidTestKotlin`).
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [laptop] (2026-11-07) for cloud-web: the planner links privacy at https://onpath.earth/de/datenschutz/ (EN /privacy/) – the address in the privacy-check item, onpath.earth/datenschutz/, answers 404; maybe a redirect on the site. Access comments in web/host fixed (02a16286).
- ← [laptop] (2026-11-06) **One branch – what app sessions see:** an app push that changes a file the planner mirrors (`web/parity.json`, e.g. AppViewModel) turns the "Web planner" run on `main` red until laptop mirrors it (its hourly round runs `node web/parity/check.mjs` first). Not the app's problem – nothing to do for donald; the release's planner job waits for it. First case: #39 (b584efa3, tour only).
<!-- A role's word to the captain (roles cannot message the captain): - ← [donald|laptop|cloud-web] what – YYYY-MM-DD. The captain deletes the line when read. -->
- ← [cloud-web] **A-022 words Plan · Tour · Route (owner decision 2026-11-03)** – for **donald** (app texts, ~60 DE + EN, glossary, screenshots) and **cloud-plan** (planner texts): Plan = what you plan and save, Tour = what you do / did, Route = only the calculated way, Track = only GPX; list "Meine Touren" with tabs "Geplant | Gemacht". Every sentence written out, plus the list of texts that stay "Route": `onpath.private/auftraege/a-022-plan-tour-route.md`. No search-and-replace (der Plan / ihn). Before the release, because of the screenshots; cloud-web then does website, help and store text – 2026-11-03

## In progress
- [umbau] The rebuild, phases 0–4 of `docs/design/architecture-plan-2026-11.md` + routing spike 5a (owner's go 2026-11-18). Now: phase 0 (E2E nightly + shards → PR, sleeps → `waitUntil`, backlog / core-features split, 350k handoff rule) and the survey for phase 1 (`:core`). Touches `app/src/androidTest/**`, `docs/backlog.md` structure, `docs/core-features.md`, `tools/ci/gate.sh`, later `settings.gradle.kts` + package moves – since 2026-11-18
- [IR] Tour suggestions (Routenfinder), the whole topic: POC `tools/suggest-poc/` (the lab), the core `app/src/main/java/com/onpath/app/suggest/` (ported, parity), the suggest file (laptop builds the cells), the planner framework branch `wip/donald-2026-11-14-planner-suggest-remote` – plan `docs/design/tour-suggestions.md` §7, state `docs/history/handoff-ir-2026-11-17.md`. Stage 3 (WebApi + planner UI) waits for mehrtage's word below (captain 2026-11-17).
<!-- - [donald|laptop|captain|cloud-web] short description – since YYYY-MM-DD -->
- [mehrtage] Multi-day tours – build (owner 2026-11-17: „Ja, es geht los … Let's build it“): stages 0–4 on main (2026-11-18); open: the owner's look on the Pixel (GitHub E2E green 2026-11-18); later: share a hotel to the tour (stage 5). `docs/design/multi-day-tours.md` – since 2026-11-17

## Next
- **[donald] Check with the owner's Pixel on 2026-11-18 (owner 2026-11-17 ~23:30):** „Symbol Touch – das Hemingway in Hilden lässt sich nicht als Restaurant einstellen“ – the POI „Hemingway“ (Hilden) cannot be set / taken into the plan via its symbol. Phone needed: read `files/events.log` (taps / holds, „POI menu“, „Place menu“), check its kind in the POI tiles (bar vs restaurant?), whether the symbol is drawn, whether the one-row menu (2026-11-17) hides the row he wanted.
- **[laptop] POI click on a slow phone after a pan (2026-11-17):** MapLibre re-places symbols in 2 ms steps per frame; on a slow device this can exceed the planner's 5 s wait, then a click on a POI reads the old placement and adds a point instead. Real fix: a POI lookup independent of the placement (the source's features near the press, as the app's PoiTap from the POI tiles) – to decide.
- **[IR] Tour suggestions – state: `docs/history/handoff-ir-2026-11-17.md`** (stage 0 frozen, stage 1 suggest file producer done (pipeline patch for the laptop), stage 2 method ported to `suggest/` with parity; next stage 3: WebApi + planner UI per sketch 4).
- **[laptop] Kept places in the planner – UI + browser store done 2026-11-15; open: the sync after donald's format.** (was:) menu rows „☆ Merken / ★ Gemerkt“, „⌂ Als Zuhause festlegen / Zuhause entfernen“, empty search: Zuhause, Favoriten (5, then „Alle Favoriten ›“), Zuletzt gesucht – like app b2504c3d; browser store first, the sync after donald's format.
- **[donald] Full check: POI names on the map without their symbol (owner 2026-11-15 23:30: „das gibt es immer noch ziemlich oft, dass es Symbole gibt, die da sind, aber nicht gezeichnet werden – eine Aufgabe für einen vollständigen Check“)** – field case: Hilden, bus stop „Dorothea-Erxleben-Straße“ showed only its name (looked like a street name), a hold on it opened the place menu. Check every POI kind of the style (app + planner share it): icon in the sprite for each kind and theme, icon and name layer at the same min zoom, collision rules (`icon-optional` / `text-optional`, `*-allow-overlap`) so a name never stands without its symbol, kinds hidden in Settings → POIs hide their name too; a machine check over the style + sprite (all kinds × zooms × themes, like `web/app/test/poidensity-check.mjs`) plus a few rendered places (Hilden, London, Meran). Then the hold rule: a hit on a name-only feature (`ShownPois` icon = false) counts as a POI only when its symbol is drawn. Report to the owner with before / after pictures.
- **[laptop] routes-smoke step 4b flaky (2026-11-15, 1 red in 4 under load 20):** „⋮ on the last row below the fold … (below false)“ – the precondition (the row below the fold) read before the list was laid out; make the test wait for the list's final height. Not a page bug (the menu fix itself held).
- **[laptop] Waiting for the owner: new planner smokes in CI (web.yml)** – my GitHub token may not push workflow files (no `workflow` scope), so the change waits as `tools/ci/pending/web-yml-ci-smokes-2026-11-13.patch` (adds center + stop to cells, phonelink to areas-parity, sort + sync-rate to lang-gpx; ~7 machine-minutes, no longer wall time). Owner: `git am tools/ci/pending/web-yml-ci-smokes-2026-11-13.patch` on a branch and open the PR (or give the token the workflow scope); then delete the patch file.
- **[donald] Very short recordings go to the recycle bin (owner 2026-11-12, decided):** a recording that ends with < 50 m of track (incl. 0 m / one GPS point) is not shown as „Tour gespeichert“ but moved to the recycle bin at once (`db.setDeleted`, like the end dialog's delete), with a short notice „Kurze Aufzeichnung (2 m) – in den Papierkorb gelegt.“ + Rückgängig; 30 days restorable, nothing lost. Found: owner's „Tour 5. Nov. …“ with the orange dot (date name kept because there was no line to name, RouteShape placeholder). Core feature (recording) → test first. Existing mini tours: owner deletes by hand.
- **[donald] Code audit 2026-11-12 (owner asked: clean structure, low cost of change?) – verdict: good, one hotspot. Owner 2026-11-12: yes, after the smooth map – robustness first (the `nav-session.json` race as a JVM test, GPS handling during recording measured; fix only what is confirmed), then slice AppViewModel one piece per round.** `ui/AppViewModel.kt` 5,356 lines
  (4,645 on 11-07), 246 public functions, 87 `mutableStateOf`, not JVM-testable; 29 test files pin its source text. Plan, one
  slice per round, forwarders stay, core features green each step: (1) slices after the `TourController` pattern (small
  interface, fake in tests): Zuletzt, activity check, follow / record, plan sync + plan links, shared places – plus a size
  test that fails above the current line count and is lowered after each slice; (2) the source-text pins of a moved slice
  become behaviour tests; (3) the 32 Android-free files in `ui/` (RouteWarnings, PlanGaps, PlanGuidance, RouteRules …) move
  to `domain/` – breaks the `ui` ↔ `domain` / `routing` cycle, update `app/web-shared-files.txt` (tell laptop: parity);
  (4) lint fails on new issues (baseline 105 stays) – build config, PR; R8 only after a full Rundgang on the minified build;
  (5) per touched file: history comments out (WHY stays), `collectAsStateWithLifecycle`. Also: `RecordingService` 1,777 lines
  (recording + navigation, companion flows). Leave: no DI framework / Room / DataStore, `mutableStateOf` holders, file
  persistence, one app module (+ `:strings`, later maybe `:core`). **Owner 2026-11-12 – night job, his rules:** at night only what nobody sees (tests, inside rebuild), never a new function or a changed screen; checked by machines (gate, E2E on GitHub, release Rundgang on the emulator, a crash hunt) – a piece that fails is taken back, not patched; at most one or two small rebuild pieces per week, the other nights build tests; no new version without his weekly Pixel test („ich habe nur einmal in der Woche Zeit“). Fable builds, Opus reviews each piece fresh. Order: safety net first (the `nav-session.json` race test, broken / missing / half-written files tests, crash hunt with Demo tour + random taps, StrictMode in debug builds = the GPS-on-main-thread measurement, then the JVM harness), rebuild only after that.
  **Second, independent audit the same day (owner: „mit frischen Augen“) – same verdict and same first three steps; it adds:**
  (a) the real knot inside the view model is `recalc()` (~line 2410, ~230 lines): the route run as a state machine in
  Boolean flags – „sheet rises once“ (2a54f2e4) needed a new flag at four distant places → first make the route run one
  explicit state + pure reducer with tests, then slice; (b) a JVM harness for state holders (sqlite-jdbc `TourDb` is in the
  tests already; fake engine + service state) comes before the slices; (c) service ↔ UI by convention: two
  `NavSessionStore` on `nav-session.json` (AppViewModel.kt:949, RecordingService.kt:258), token check at
  RecordingService.kt:997 not atomic (race inferred – settle with a JVM test that interleaves the view model's save and a
  service re-route; core feature, test first), mutable companion flows, ~20 Intent actions → a typed `RecordingClient`,
  the service the only writer; (d) every GPS fix runs on the main looper (LocationTracker.kt:188/296) and `onFix` does the
  navigation maths and `db.appendPoint` there (`writePoints`) – measure with StrictMode in debug / gfxinfo while
  recording, then a single-thread recording dispatcher; (e) screenshot blast radius: 409ba176 = 158 PNGs for +141 code
  lines, `.git` 562 MB → component-level pictures where a full screen is not the point; (f) E2E: 100 sleeps, 20 commits
  with „flak“; (g) tokens: 344 inline fully-qualified names in the view model, `docs/core-features.md` 51 KB.
- **[donald] Wide translucent green line darkens where it overlaps (owner 2026-11-12, Pixel screenshot in Plan during a tour, Meran)** – „die transparenten grünen Punkte werden dunkler, wenn sie sich überlappen – kein Drama, sieht aber nicht schön aus“: at joins / turns / out-and-back parts the alpha adds up (separate features or self-overlap). Find the layer (MapController translucent line layers, RouteLook), then: one feature per line (no doubled caps at joins) and, for self-overlap, a pre-blended opaque pale green under the labels – before/after picture first.
- **[donald] Small rests of the colour round (2026-11-12)** – My routes ⋮ „Los geht's“ is still green text (Plan rule: blue); the sheet under a done tour (track sheet) is still paper, not the sage of the plan surfaces. Picture first.
- **[donald] Website pictures for 0.17.9** – `tools/dev/pictures_affected.sh v0.17.8..v0.17.9`, retake what changed (colours, blue go buttons, sort chips, Zuletzt) DE + EN → onpath.private aktuell/ + „For the roles“ → [cloud-web].
- **[donald + laptop] Favourite tours (owner 2026-11-13, variant A)** – a star on every row of My routes (one tap sets / removes), a „Favoriten“ section under Zuletzt (in Gemacht on top), the chosen sort inside; several favourites. Owner: the planner must support it too and it must sync („auch beim Planer, beim Web unterstützt und mit synchronisiert“): a `favourite` column + backup, an optional field in the shared `SyncItem` (older readers skip it), the planner's star + section (laptop, same look), one change on either side reaches the other; recorded tours do not sync (star stays on the phone). Sketch branch `sketch/favourites-2026-11-13`; the list gets more compact in the same round (sketch running, owner picks) – build after his pick, app and planner together.
- **[donald] Beta bug button (owner 2026-11-10, chat) – step 1 only, for the owner's own phone:** a small round button
  always on top (bottom left, over every screen, menu, sheet and dialog), can be dragged aside if it is in the way.
  A tap: (1) a screenshot of the current screen *without* the button; (2) the microphone starts and the owner speaks
  the problem (stop with a second tap); (3) a diagnostics snapshot of the current state that also holds the **last
  actions** (taps, mode changes, point edits – not only errors), so there is enough context. All three go into one
  folder per report on the phone; read out over USB (`tools/dev/device.sh pixel -- …`, a pull script). Build on what
  exists: `debug/EventLog` (the action trail – check that user actions are logged, add the missing ones) and
  `debug/Diagnostics` (the snapshot / zip). Captain's notes: voice as an audio file (needs the RECORD_AUDIO
  permission – new; Android's speech-to-text usually goes through Google, so no transcription on the phone –
  transcribe on the computer later); the button only in beta / debug builds or behind a Settings switch – sketch +
  the owner's word before building. **Later, not now:** users export a report and send it by e-mail.
- **[donald] Father's first feedback (owner 2026-11-08)** – built and released in v0.17.8 (Samsung page once + „Mehr Infos ›“ → onpath.earth `#samsung` – live; T levels with their line; undo / redo as round buttons under the map column; Help first in ☰; „Handy einrichten“ on top; the sheet rises by itself). Open only: the owner's / father's word on how it feels on the S10.
- **[donald] Off route with a stop: the new route may leave the stop out without saying so (found 2026-11-08 while taking G04, emulator, 0.17.6)** – A-019 + Gasthof Hochmuth as stop, 80 m off ~150 m after Vellau: the question says only „Neue Route bis zum Ziel: 4,3 km (4,3 km kürzer)“ (via Hochmuth it is 8,7 km), no „Über ② / ② auslassen“. Suspects: `Rerouter.kt:171` passes `deliberate = false` to `StopSkip.decide`, or `keepsTheTour` / `MIN_KEEP_SHARE`. First a test that reproduces it (core feature), then check whether „Neue Route“ really drops the stop, then the fix.
- **[donald] Local Rundgang emulator: `CorePointsListE2ETest#swipeTheSheetHalfFullHalf` times out after the sheet
  swipe (very janky first drag), twice on 2026-11-08; green on GitHub. Look at the local AVD speed / the swipe timing.
- **[donald] Design round after 0.17.4 (owner 2026-11-07, from an outside design review of the Meran pictures)** – owner
  picked three: (1) G02 off route = three stacked cards with two distances (70 m air line vs 60 m) and a half-empty
  status card → one card, one distance, step 1 as its head and the question under it; (2) the right tool column: six
  icons each with a divider → three groups (orientation: compass + locate · view: eye + fit · undo/redo), dividers only
  between groups, same icon weight; (3) the collapsed sheet's line "2 h 15 min · 4,6 km · ↑622 ↓0 ⚠1" → time + distance
  strong, ascent set apart, warning right-aligned. Real screenshots before/after, ≤2 variants each. Not now (owner):
  profile stripes (his 11-06 choice), pause band, setup/maps pages. Also: retake E05 (lock screen is black – Android
  forbids screenshots of the keyguard; take it from the pulled-down notification).
- **[laptop] Idea: saved plans open without routing again in the browser too (app 365c7fbc LegFiles, 2026-11-07)** – the phone
  keeps a saved route's legs in files (stand-in line on newer routing data); the planner re-routes a plan from Meine Touren
  each time (in-memory LegCache only). Web analog: keep a saved plan's legs in IndexedDB (try/catch, size cap), drawn at once,
  re-routed only when the routing fingerprint changed. Not urgent – planner routes are fast on a PC.
- **[donald] Bugs seen while taking the Meran 0.17 pictures (2026-11-07, emulator, 0.17.3)** – (1) done (8c3e02c3: the shortcut rule never jumps past a stop ahead); (2) (3) (4)
  (5) (8) done 2026-11-07 (donald-picture-bugs); (7) D10: T1 + Mutkopf
  (② only from T2) gives 30,3 km · 10 h 55 · ↑ 1950 m and "1 Abschnitt über T1" – the line runs east via Dorf Tirol
  towards the nearest T1 way under Mutkopf and back (out-and-back), and a T1 route should have no section above T1; not
  looked into. Shared code: (5) changed `ui/RouteWarnings.text` (first word capital) – the planner gets it by merging.
  Pictures: onpath.private/marketing/bilder/aktuell/.
- **[donald] (low) Vellau → Leiteralm routes over the Gamplweg (website pictures 2026-11-07)** – Wandern T2 takes the
  bow east instead of the path along the Korblift (seen by A-019 and again in 0.17.3). Owner: "merkt eh keiner" – the
  pictures stay. Later: is the lift path above T2, cut in the data, or a profile cost? JVM harness, no new pictures.
- **[donald] Smoother on older phones (owner field 2026-11-07, Galaxy S10, via cloud-web)** – "runs well, a little
  hackelig". The S10 has a fixed 60 Hz screen, so it is not the refresh rate. Measure first: `FrameJankMeter` today only
  covers the plan-sheet drag – add map pan / zoom, Navigate (route following) and the Meine Touren list with route
  pictures; numbers from the S10-class device (or the Pixel with the GPU slowed) into the handoff. Then try what helps
  most, one at a time with numbers: Relief and Höhenlinien off while the map moves, the pre-rendered relief raster
  instead of live hillshade from the DEM, fewer path / trail layers below z13, MapLibre pixel ratio / max FPS, Compose
  recompositions on every camera move, route pictures in Meine Touren drawn once and cached. If one setting makes the
  big difference, offer it as "Leichte Karte (ältere Handys)" in Einstellungen → Karte, or switch it by itself.
  **Device (owner 2026-11-07):** the owner can plug his Galaxy S10 into donald's USB instead of the Pixel – it takes the
  `pixel` slot of `tools/dev/device.sh` (`adb -d`, only one phone on USB). It runs the **release** build: a debug APK
  has the same package id but another signature, so `adb install` fails and uninstalling would delete his tours – never
  uninstall; measure with the installed release, or install only a release-signed APK.
  Start without a new build: `adb -d shell dumpsys gfxinfo com.onpath.app reset`, then map pan / zoom, Navigate, Meine
  Touren, then `dumpsys gfxinfo com.onpath.app` (janky frames, percentiles) – once with Relief + Höhenlinien on, once off.
  Save tokens (owner's weekly limit): numbers, not screenshots; result as a short table in the handoff.
- **[donald] Handy einrichten: "Links vom Planer in OnPath öffnen" as a check (owner field 2026-11-07, Galaxy S10, via
  cloud-web)** – pairing / plan links from plan.onpath.earth opened in the browser on the S10; the owner fixed it by hand
  (Einstellungen → Apps → OnPath → Standardmäßig öffnen → "Unterstützte Links öffnen"). Owner asks to build it into the
  setup. A new `CheckId` (e.g. `LINKS`, RECOMMENDED, `docs/design/onboarding-phone-check.md`): Android 12+ –
  `DomainVerificationManager.getDomainVerificationUserState(packageName)`: plan.onpath.earth VERIFIED or SELECTED → ✓;
  else a short line ("Links vom Planer öffnen sich sonst im Browser") and a button that opens
  `Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` (`package:com.onpath.app`); back in the app the check is read again.
  Below 12: no check (verified at install). Server side checked live: `assetlinks.json` matches the release key – so also
  find out why the S10 was not verified (other signature? no network at install?), say in the handoff.
- **[laptop] Pairing from a phone camera ends in the browser (owner field 2026-11-07, Galaxy S10, via cloud-web)** – the
  owner scanned the pairing QR with the phone's own camera: Samsung's camera opens the link in the browser, not in OnPath
  (App Link and `assetlinks.json` are fine – checked live). Two fixes: (1) `web_pair_menu_path` still says "☰ → Mit Browser
  verbinden", the app's menu says **"Mit Browser koppeln"** (EN "Pair with browser") – the dialog should say plainly
  "Scan with OnPath, not with the camera app"; (2) when a `#pair=` link opens in a **phone** browser, don't start the
  browser join – show "Open in OnPath" (an `intent://…#Intent;package=com.onpath.app;…` link) and the in-app way.
- **[donald] In-app privacy texts say more than the code does (cloud-web privacy check 2026-11-07)** – website fixed in
  happychriss/onpath.earth#17; the app's own texts: `settings_privacy_lead` / `settings_sum_privacy` "Nichts wird verfolgt"
  overreaches (services see the IP, map requests show the region) → e.g. "Kein Konto, keine Analyse, keine Werbung, kein
  Tracking-Dienst."; `settings_privacy_maps_what` "nicht deine Position" – the loaded areas show the region;
  `settings_privacy_sync_text` "alles sofort" – the empty mailbox stays 12 months, deleted routes stay as encrypted
  markers; brouter.de (direct from the phone) and Photon (sent automatically while typing, ~1 km position) should be
  named; online search has no off switch (consider one). DE + EN.
- **[donald] Follow-ups from v0.17.3 (2026-11-07)** – (2) `RoutingDataFreshInstallE2ETest` needs `routingDownload=true`,
  which `e2e_github.sh` can't pass – add the flag or run it by hand; (5) `e2e.yml` runs only the first class / method
  of a comma list (needs a PR).
- **[donald] ▶ Sim restarted on the same line: progress stays put (field 2026-11-07 12:06)** – in the 3rd demo run
  (from 3 m) neither voice nor vibration fired; the events log has no "Junction cue:" and the shown progress did not
  move. VoiceGuide's memory is ruled out (`VoiceGuideTest.aSimulationRestartedOnTheSameLineSaysItsTurnsAgain` green).
  Suspect: OffRouteDetector / `shownAlongM` when switching demo ↔ real GPS (`NavSimStart`). Test first.
- **[donald] Navigation neu – the rest of the owner's field day 2026-11-01** (tours 61–63, data in
  `~/onpath-cells-work/field/2026-11-01-wanderungen/` on donald): round trip "Ziel erreicht" too early, re-route with a
  word / sign to turn round and where, the new voice – concept and what is built: `docs/design/navigation-v2.md`; build
  the steps not yet marked *built* there. Owner: route stays blue, no grey.
- **[laptop] Planer: gekoppelt = eine Liste, alles synchron (owner 2026-11-06)** – after the release with flyover / Meine Touren /
  Save-with-phone. Rules: (1) paired: save, rename, delete go to the phone at once (no separate send; Save shows the small phone
  symbol); (2) delete anywhere: browser delete → phone recycle bin, phone delete → gone here; (3) pairing: every tour of
  "Ohne Handy" goes with it (no question) and one line says so („3 Touren aus diesem Browser sind jetzt auf ‹Pixel 8›“);
  (4) unpairing: the one question „Deine Touren in diesem Browser behalten oder entfernen?“ (a friend's PC); kept = the folder
  comes back on the next pairing; (5) Meine Touren one list per phone folder with 🖥 / 📱 where each tour lies, „wartet aufs
  Handy“ until the phone has it; (6) folders per phone id stay (several people, one browser: each sees only their tours –
  hidden ≠ protected, see (4)). Conflicts as today (newer wins, older „(Kopie)“). Recorded GPS tours stay phone-only for now.
  Owner scenarios as smokes: first visit, second visit, pair with saved tours, delete both ways, unpair keep/remove, friend's phone.
- **[donald] 250 tours per phone in the app (owner 2026-11-07; the planner side is on main)** – the shared
  `sync/SyncLimits` (MAX_TOURS = 250, WARN_TOURS = 200, `canAdd(tours)`, `nearlyFull(tours)`): a tour = a saved plan or
  imported track that exists (not deleted ones). While paired with a browser: (1) Settings → Browser sync shows „N von 250
  Touren“ with a thin bar, amber from 200 (the browser's Phone window does the same, web-sync.md §8 "250 tours per phone");
  (2) saving / importing / copying a NEW tour as the 251st is refused before the name is asked – the one message box,
  „250 Touren auf dem Handy – lösche erst welche.“; changes, renames, deletes always work. Not paired: no limit. Tours
  that arrive from a browser above 250 are kept (nothing lost). Paid bigger mailbox: later, owner decision.
- **[donald] Test audit + clean-out (owner 2026-11-06)** – report `docs/design/test-audit.md` (branch donald-test-audit),
  then after the merge of the six evening branches: drop / convert per the report; build a JVM AppViewModel test and
  random-sequence invariant tests; no new source-text tests. Planner part → [laptop].
- **[laptop] Planner follows the app's plan rules of 2026-11-06 (owner 2026-11-06: HP gets its own packages)** – also
  mirror, once on main: base colour Walnuss #4A3F37 + colour rule (plan blue, to navigation green, neutral walnut),
  paper tone surfaces, "Zuletzt" (drafts are NOT synced – the browser shows only saved plans), words (Planen, Weiter,
  Los geht's / Weiter geht's), round trip switch, start screen "Wohin soll's gehen?". Earlier list (was [cloud-plan]):
- **[cloud-plan] Planner follows the app's plan rules of 2026-11-06 (owner: "die gleiche Logik beim Planer")** – left: (4) base
  colour Walnuss #4A3F37 for plain buttons, plan blue / to navigation green (#36) – once the app has it on main. Done on the
  planner (laptop 2026-11-06): ✓ (1) "↻ Rundtour" a switch, "⇅ Umkehren" grey for A→B→A (#40, b3e69378); ✓ (2) pin card
  "+ Hinzufügen"; ✓ (3) glyphs, all 256 ranges (e7802e1e, live after the next deploy); ✓ flat profile one height, "Plan from it"
  outlined blue (b3e69378); ✓ "Plan from it" over an unsaved plan asks "Plan speichern?" (save paths P3, 8f8db60d).
  Phone-only (no planner change): end sheet / saved-tour box, one point during a tour, F1 / C, follow a track during a tour,
  Als Plan übernehmen for done tours, map button column, offline pill.
- **[laptop] Web planner – novice walkthrough (owner 2026-11-06, chat; points 1+2 done ed3219e9):** like the app's
  `docs/design/flow-map.md` (new plan, open / rename, send to phone, GPX, add a stop, undo) with real browser shots – to find
  what else is missing; owner asked "haben wir sonst noch wichtige Elemente vergessen?".
- **[donald] Flow map + novice walkthrough (docs/design/flow-map.md, 2026-11-06) – open:** H1 no way to start a tour
  without a plan (0–1 points: no green start on any sheet step, none in ☰; "Los geht's · ohne Route" unreachable –
  PlanPanel.kt:487/:650; after the F1/C build); M1 "Tour nachfahren" during a tour keeps the old plan – an edit switches the
  guidance back silently; M3/N3 new goal on the way = old goal silently becomes a stop (proposal: hold menu "Neues Ziel");
  M4 back from a pause: one rule with C; N1 Plan mode during a tour is silent – notification should say "Navigation
  pausiert – tippen zum Weiterfahren"; N2 "» Entsperren" hides Pause/Beenden (second line "Pause · Beenden · Plan"); N4
  back key in locked Navigate leaves the app; N5 bin hard to find; N6/N7 words "Original behalten / Neue Route",
  "Hinführen" (owner's words? ask); N8 undo icon only; N9 grey 12 sp lines in the sun. **Colour rule** C1–C17 (theme
  primary is green → every plain button green): owner thinks about an earth tone as the base colour (page
  https://claude.ai/artifact/P6SCbUKQhHuuPqJ9SmePq6, recommendation Torf #3B352F / Walnuss #4A3F37). H2 done (844c8401).
- **[donald] Pixel review 2026-11-06 – open bugs** (page https://claude.ai/artifact/9nYPmLp8VrePMfBhCN3sur; done on main:
  end sheet cut off → ModalBottomSheet 6d742b95, plan lost at tour end 894ab407, Sperren fill 4432d315). Medium: recorded
  tour named after an old plan ("Demo · Rundtour ab Hilden", nameRecordedTour); past arrival time in Navigate's stop list
  ("an 16:35" at 16:36); map position triangle drawn over the activity sheet; pin card "+ Hinzufügen" done (the 41 dp was a wrong density – the Pixel runs at 356 dpi, the buttons are 48 dp). Small: "PLAN ÄNDERN"
  with an empty plan; elevation axis labels overlap on a flat profile; "Mittel" badge in the profile corner; "Dein Plan hat
  Änderungen" for a brand-new plan; bike marker hides point "1"; My routes forgets tab and search.
- **[donald → owner] Save-path proposals (docs/design/save-paths.md, 2026-11-06)** – P2 ride summary gets the "Plan auch
  speichern" switch; P3 "Plan ersetzen? · Erst speichern" before "Mit dieser Route weiter" / "Als Plan übernehmen" replace
  an unsaved plan; P4 an unsaved plan saved as a draft before it leaves after a tour. Flow "follow a saved tour during a
  tour" (owner's screen 14:37: 7 inputs, 5 buttons): F1 status line only + green "Dieser Tour folgen" / blue "Als Plan
  übernehmen", F2 Meine Touren in the band, F3 ▶ on each row. Ask the owner one by one after the Pixel review.
- **[pixel] Owner's points of 2026-11-06 – check on the phone (v0.17.1 + main)**, none seen on a device yet:
  (1) activity switch during a tour (Bike → Hike → "Neue Route": guided as Hike and stays; fix 301db9b5 = the switched
  session keeps the running session's token, Rundgang flow 14); (2) the dot leaves the line beyond 6 m (was 25 m;
  SnapToRoute.MAX_OFF_M) – on a parallel road it must stay on the road; watch whether it now wobbles off the line on the
  route itself (GPS noise > 6 m) and tell the owner; (3) "Weiter geht's. … bis …, etwa …" once after a plan change during a
  tour (VoiceGuide carryOn); (4) a turn is announced only after the one before is ridden (VoiceGuide.nextJunction) –
  replay of the owner's demo ride Düsseldorf → Grimlinghausen: "Jetzt rechts halten, danach links" then nothing until the
  left is ridden, then "Jetzt rechts abbiegen"; (5) Sperren | Plan after unlocking; (6) Tour beenden on one screen
  (numbers in one line, Tour speichern, Weiterfahren | Löschen, bin note) – no scrolling on the Pixel, ✕ not hidden under the
  volume bar; (7) "Plan auch speichern" ON for a plan never saved; the plan is in Meine Touren after the tour; (8) search in
  Meine Touren; (9) after v0.17.2: shelters (Unterstände) stay while zooming from z12.
- **[donald] Voice: the roundabout sentence on the owner's demo ride (2026-11-06)** – rebuilt from the GPX (route via the
  track, not the owner's real plan) it said "Jetzt im Kreisverkehr die erste Ausfahrt, danach im Kreisverkehr die vierte
  Ausfahrt" at ~950 m (51.1965, 6.7697, two roundabouts 80 m apart). May be an artefact of the rebuild (via points every
  150 m force a turn-round). Check with the owner's real tour: Settings → Diagnostics export ("Junction cue:" lines) or the
  plan; the owner offered to say which sentences were wrong. Scratch replay tool used: a copy of
  tools/routing/jvm-test/HildenVoiceTest.kt that routes through a GPX track and prints HINT / SCREEN / SAY lines (needs E5_N50.rd5).
- **[cloud-plan] Flaky web smokes on GitHub – the rest** (laptop 2026-11-06; +2026-11-07: follow-smoke „compass north up keeps following“ **solved** (test side: the position lay off the map after the test's fly, so the locate tap framed plan + me (LocateDistance, 1c96f5b5) instead of following, depending on whether the far label was drawn yet – the test brings the position into view first and checks the far tap on its own); import-smoke „+ New … kept under Recent“ **solved** (laptop 2026-11-07: local 8 of 10 red on main and 8 of 10 with 3f6f23c0 reverted – not that change; (c)'s Meran → Hilden plan kept running in the engine's one worker (with NRW routing data on the laptop it runs for minutes; GitHub has no data up north) and (d)'s hash-only goto kept that worker, so + New's encode waited past 10 s; (d) now starts on a real page load – 10 of 10 green; the real limitation behind it – a superseded long plan blocked every engine call until it ended – **fixed** 2026-11-07: a dropped plan that keeps routing stops via a fresh worker, history carried over, web-planner.md §26, test/stop-smoke.mjs); new, seen once in those 10 runs: import-smoke „Reverse: the new route draws itself“ (RouteReveal, 93 frames, a partial line seen) – not looked into): robust-smoke "two quick clicks" **solved
  (f636903c, test only)** – in every red run (5be2031d, b5328dae, 8f8db60d, 045e8ebf) the page kept the click order; the
  second click was lost because its pixel lay outside the window, or both clicks landed beside p1/p2 – the camera moved
  between the test's projection and its clicks (the reopened route's pending whole-route fit, a left-over padding). The test
  now drags the map first like a person (cancels that fit), jumps without padding, and logs the camera when a click goes
  astray; green on GitHub twice. Still open, seen once each on older code: sheet-sync "Remove asks (one route never sent)",
  version-smoke "a newer build: one box" (8c717bfa), import-smoke 1c "dialog.longroute" not shown after a clicked far point.
- **[donald] Onboarding – first-use hints (proposal 2026-11-05 in chat, owner thinking – NOT approved yet; drawings first)** – no intro carousel; three in-place hints (ANZEIGE kind, no box), each once, gone when the user did the step (even unseen): (1) empty map: pulsing finger + 'Lange auf die Karte drücken → „Hierhin“ – die Route kommt von selbst' (search is self-explaining); (2) only after „Hier starten“: 'Jetzt das Ziel: wieder lange drücken → Hierhin'; „Hierhin“ / search → straight to (3) arrow to the green button 'Fertig? ▶ Los geht's startet die Führung'. Never during a tour or over a question; Settings 'Tipps wieder zeigen'; no saving hint (✕ asks, ☰ dot, end-of-tour sheet cover it). Check first what 'Hierhin' does without a GPS fix (then hint 'Standort fehlt – erst „Hier starten“'). One line after an update only when a way of use changed (e.g. 'Neu: zum Ändern entsperren').
- **[cloud-plan] Web: within-a-leg wait bar** (the rest of the app's UI round of 2026-11-05; laptop 2026-11-06: the activity picker already matched – one tap, T1–T5 under Hike, no Done; ✕ Neu planen now asks „Plan speichern?“ Verwerfen · Abbrechen · Speichern): the app's `WaitBar` / `searchProgress` needs the engine polled while it routes – the web shows legs only.
- **[pixel] Measure long bike routes on the Pixel (leg cache built 2026-11-05)** – when no tour records: `tools/routing/phone-bench/run.sh` (docs/design/long-bike-routes.md) + a 98 km Bike plan in the app: first route, + point, + point; numbers before/after to the owner.
- **[donald] Far start: ask before routing the way there (owner 2026-11-05, corrected)** – a plan loaded in Meran, "Route
  starten" in Hilden → "Hinführen" routed the lead for a very long time, then "no route". No hard limit (owner: 100 km by
  bike is fine for some). Instead, before the lead is routed, when the way there is long (same idea as the plan's
  `LongRoute` question "Diese Route ist sehr lang", here for the lead – threshold e.g. straight line > 25 km, tune with
  the measurements): a box "Der Start ist 790 km entfernt. Trotzdem einen Weg dorthin berechnen? Das kann lange dauern."
  – Berechnen / Nur aufzeichnen. While it routes: the wait card with Abbrechen; at the time limit the slow-plan question
  "Weiter warten?". Field-bug rule: a reproducing test first (`NavPrompt.FarStart`).
- **[donald] + [laptop] Long bike routes – the rest (owner 2026-11-04, A)** – measured, leg cache and legs as they come
  are built (PLANNER_VERSION 4, 2026-11-05; `docs/design/long-bike-routes.md`). Open, only after the Pixel numbers
  (`[pixel]` item): preload rd5 for the area at the first point; a lighter first pass.
- **Docs pictures follow the UI by themselves (owner 2026-11-03)** – three parts:
  (1) **[cloud-web]** make `docs/design/website-screens.md` the full shot list: every picture on onpath.earth (home,
  help DE + EN, planner pages) and in the store listing, with key, file names DE · EN, what exactly it shows (state,
  activity, route, scroll position, open sheet / menu, what is blurred), the fixed scenario (emulator, Demo ride Vellau →
  Leiteralm / A-019 setup) and the app files it depends on. Planner pictures with their `ui-shots` / `pair-shots` name.
  (2) **[donald]** `tools/ci/gate.sh` prints "Bilder betroffen: <keys>" when changed files match the list's last
  column; the unit re-shoots those keys while checking its change (`tools/dev/website_shot.sh <key>`, DE + EN) – for a
  big round after its review – and drops the PNGs + one line each (key, app version, date) into
  `onpath.private/marketing/bilder/aktuell/`. The same rule in `.claude/rules/ui.md`.
  (3) **[captain]** a daily cloud run (after the 05:47 planner sync) takes new files from `bilder/aktuell/` into
  onpath.earth (help + home, `scripts/help_shots.py`), builds, checks links, pushes, moves the used PNGs to
  `bilder/eingebaut/<date>/` and reports in one line; set up 2026-11-03.
- **[pixel] Check: red tile "Neu planen" in "Route ändern" (built 2026-11-03)** – saved route: plan clears without a
  question, recording goes on ("Nur Aufnahme"), the empty-plan hint shows; new / changed plan: "Änderungen verwerfen?"
  first; ↶ brings plan + guidance back.
- **[pixel] Check: Plan is silent during a tour (built 2026-11-03, `recording/ModeQuiet`)** – tour → "Route ändern",
  screen off, pass a junction / a stop: nothing said, no vibration; delete the point ahead in Plan: nothing said;
  "Navigation fortsetzen" after a passed stop: no late "Stopp N erreicht"; arrive while in Plan → "Du bist angekommen"
  only after continuing; app in Plan + walking on: stays silent, no switch back by itself (owner 2026-11-03).
- **[pixel] Feel: holding beside the route (24 dp, built 2026-11-03, onpath.private#6)** – a hold about a finger's width
  from the line inserts a stop into that leg; further away it is a new point / destination (before: 2–3 finger widths).
  Shared place: the menu opens only when the map is over the place. Owner checks on the Pixel.
- **[donald] [pixel] Diagnostics: 127 entries on the owner's Pixel – look at them**
  (owner 2026-11-02: "hat sich die mal jemand angeguckt?"): pull the diagnostics export briefly over adb (no tour
  recording, phone untouched otherwise), sort: old (fixed / old version) · new + harmless (INFO, offline) · new + a real
  bug → its own Next line with a reproducing test. A short overview to the owner; the owner then clears with the button.
- **[pixel] Field check: no GPS line while lying still (built 2026-11-02, `recording/StillSense`)** – phone flat on a
  table with a tour running: no GPS line, also in the first 2 min; phone in a pocket while sitting (threshold 0.5 m/s²
  is a guess); walking / riding without GPS → amber after the short gap; no extra battery drain. Open: the notification
  says nothing about GPS while the phone lies still (owner 2026-11-02).
- **Bike difficulty limit S0 / S1 / S2 (owner 2026-11-01, "easy to build?"):** today only the hike has a chosen limit
  (T1–T5). Gravel (`trekking.brf`) and Bike / E-bike (`touring.brf`) already make S2 very expensive (+30) and shut S3+
  (10000); `mtb.brf` only adds a cost per S grade, no limit. Wanted: a chosen limit per bike activity ("nur S0", "bis S1"),
  like the T level. Size (donald estimate): medium, not a one-liner – a profile variable in the three .brf (like the
  hike's T limit), the picker line + stored level (`LastActivity`), `RoutingContract` / `EngineProvider` / `PlanResult`
  pass it (shared with the web: picker + `PlanGpx` sport type), `RoutingFingerprint` + the cells' router-class hash
  (`RouterClassCheck`) change, `ActivityLayers` maybe shows the band by S level. Most ways carry no `mtb:scale` – the
  limit acts only where mappers set it (MTB areas yes, gravel roads mostly not). After Navigation neu. [donald + laptop]
- **Way type – more kinds (owner idea 2026-11-01, rest):** voice and turn card name the same way word now ("Links auf
  den Radweg", 2026-11-03). Still open only if the owner wants: more kinds (Nebenstraße, Landstraße, Schotterweg …).
- **Website and Google Play follow-ups (marketing; website repo `happychriss/onpath.earth`)** – (1) **Voice:** onpath.earth
  (since PR #7) and the Play store text say "Sprachansagen sind nicht vorgesehen – OnPath führt über Karte und
  Vibration" – written before the owner's voice decision of 2026-10-31; voice guidance shipped in v0.14.0 – change both.
  (2) **Play Console:** pricing is still "paid" → switch to free (Monetize → App pricing → Make free) before the first
  release; data safety says approximate location + in-app search history (Photon), nothing shared – update when data
  flows change; app access lists "Pair with browser" with steps. (3) **Play link:** when the closed test exists, set
  `PLAY_OPTIN_URL` in the website's `content_de.py` and mail the link to the group (welcome message too). (4) **Images:**
  A-019 (donald) – one image set from one app version for website, store, help and video; the store's place image
  still shows the old "Zur Route hinzufügen". (5) **Owner, Cloudflare:** switch off Email Address Obfuscation (it adds
  a script the privacy text says is not active). (6) **Before the public launch:** legal check of imprint / privacy
  (`docs/RELEASE.md` in the website repo `happychriss/onpath.earth`), then website `--release` and the planner's `INDEXING = "on"` together; privacy line on
  the map server ("IP for a few minutes") once A-014 is in production; English store listing; video (idea, owner's notes in `onpath.private`). (7) **Alt text / tab name** „Aufs Handy · Teilen“ → „Teilen“
  (A-021, captain handoff 2026-10-31).
- **From the external review (A-020 §4) – build only after a small user test by the owner:**
  (1) offline readiness "Für diese Tour offline bereit" (maps + routing data for the planned route on the phone, one
  line before the start); (2) empty state with a permanent hold hint (the empty plan map says how to set a point –
  hold – and the hint stays until the first point); (3) Pause / End easy to find during a tour (today behind the slide
  "Tour menu"). Photon outage and WebGL2 stay low (owner 2026-10-30).
- **On the next real ride (owner)** – onboarding checklist step 7 (30 min, screen off, battery saver; steps 2–6 passed on
  the Pixel 2026-10-30, step 1 = fresh install → emulator); rest view + smarter cues on the bike and on foot; pocket after
  5 s on the bike; lock-screen notification on the Pixel; the older phone checks below; compass arrow: does it stay grey
  all the time on the Pixel? (1c5b8ad); new off-route question "Abseits der Route – neu planen?" with "Ja" / "Ja, Stopp N
  auslassen" (16, 81dbff7); map symbols: rock from z14, orchards / vineyards stronger.
- **Plan edits during a tour – built 2026-10-31 (cloud), check it** – `docs/design/tour-edit-position.md`. Owner: on the next
  ride delete a point ahead / the destination after passing the others (grey ✓ points, pale plan line, "Kein Ziel mehr
  vor dir."). Rundgang flows 11 + 12 green on donald (2026-11-01).
- **[pixel] Voice guidance – the owner's check on a ride** (shipped in v0.14.0): Phone setup step "Voice", Settings →
  Navigation voice switches, then a ride (sound, ducking, Bluetooth, phone call); review the spoken sentences
  (`strings_voice.xml`). Concept and rules: `docs/design/voice-guidance.md`.
- **[donald] Germany pre-built on donald → TEST R2 (owner 2026-10-30)** – all ~500 map cells + region packages (z7–9)
  for Germany, built overnight with `tools/dev/publish_cells.sh` (origin/main, job image, 2–3 at once, low priority),
  doorman adopts them. Production stays cloud-only.
- **Web planner: mirror app changes (keep this list current)** – owner 2026-10-30: every app change the browser should
  mirror gets a line here (what · app commit · what the web does); a done line is removed. Since 2026-11-06 the
  planner is on `main` and `web/parity.json` turns "Web planner" red for every mirrored file – this list keeps only what
  the gate cannot see. Done and removed (2026-11-01): Save asks for the name, plan sheet without ⋯ (Export GPX in Share), candidate marker, fit after a place, T0 links (nothing to do), pairing across tabs + new-version box +
  Unpair apart, island retry (Köln = app, no knownDiff), New / Changed tags, short-trip retry, splice (owner's 70.6 km
  hike live 4.7 → 2.2 s), routing-data notice + way-type line, search list never jumps + six chips, deleted phone hello,
  privacy + pairing sentences, host ipHash, date with the dot, first-route fit, "New route for … – check it".
  Plan parity: `web/test/plans.json` 14 cases, all app = web (gate in the jvm job).
  - *Should:*
    - [cloud-plan] Sync M4's mailbox epoch on the host (plan GPX pins and the "Plan a route from it" box are done).
    - [cloud-plan] Rolled-up one-row step? (app d288503, PlanSize.COLLAPSED: activity icon, time · distance, ⚠ count,
      compact green Start) · the web has small / profile / points (`sheetstep.ts`) and no Start – a fourth, one-row step
      only if the owner wants it on a phone-width browser.
    - [cloud-plan] One message system – the rest · `docs/design/ui-style.md` · `web/app/src/msgcard.ts` is the app's
      MessageCard (FRAGE / HINWEIS kinds, one at a time, 12 px under the search bar), used today only for "New version
      of the planner". Still to move there: the map strip's questions / errors (`mapnotice.ts`, `areas.ts`), the
      pairing confirmations, any `confirm()` / own dialog that is a HINWEIS; ANZEIGE stays a line in place.
    - [cloud-plan] Quiet margin cells after planning (offline after planning) · 27029db · `cells/CellTriggers.kt`
      (`CellJob.margin`: cells along the route vs the corridor margin, `tourStarted`) – check `areas.ts` (T1–T4 mirror)
      asks the margin cells quietly like the app.
  - *Low prio:*
    - A-016 App Links · `/.well-known/assetlinks.json` is live (200, v0.13.1 key) · left: on the phone
      `adb shell pm get-app-links com.onpath.app` – then `#p=` / `#pair=` links open the app (owner).
    - [cloud-plan] Licences list in the browser (A-020) · app: About → Licences (`data/Licences.kt`,
      `assets/licences/`) · the web planner lists its own bundled parts (MapLibre GL JS, BRouter onpath1, Noto Sans
      glyphs, icons, npm libraries) with version + licence, offline in the page, plus the ODbL line.
    - [cloud-plan] Editing speed beyond the splice: a plan edit still routes the whole chunk once (owner's hike ≈ 2 s
      live); faster only by reusing legs of the previous plan – owner 2026-10-31 said no (same points, same route).
    - Console error "no content-length header … HTTP Byte Serving" and a faint seam in the hillshade / contours
      (captain handoff 2026-10-31) · web only · find which file is served without Content-Length (PMTiles range
      requests) and where the seam comes from.
  - *Not for the browser:* lock-screen arrival rounding (30c01c6), smart screen / rest view, cue timing, vibration,
    pocket, lock-screen notification, notifications chip, provisional position, GPS-only recording, phone setup, demo
    location, POI tiles + search on disk on the phone, memory / health line, End-tour ride icons, ☰ phone-setup entry;
    plan edits during a tour (a3e9a4a, `TourSplit` kept out of the shared code on purpose), compact tour sheet
    (089496c), locate zoom in Navigate (f07078b), far start (e81f8f4), Bug 02 lead rules (56fffcb), "Show whole route"
    (b269c82 = the web's ⛶), shared place lookup (c4f5e99 – the web has no share target), Problems merge text (04528ed
    – the web's `diag.ts` dedupes on the exact message, nothing merges), atomic files, download chip.
- **Phone setup: check brand menu words on real phones** – Xiaomi (Keine Einschränkungen / Beschränkungen, Autostart,
  Akku-Sparmodus), Huawei/Honor (Manage automatically → three switches, DE labels), OnePlus/Oppo/realme (Nicht
  optimieren, Hintergrundaktivität zulassen, Erweiterte Optimierung), Samsung app battery option (Nicht eingeschränkt vs
  Uneingeschränkt) – texts built in 3b21fc3 from support pages; testers' diagnostics carry a "Phone check:" line.
- **Plan sync phone ⇄ browser (A-007) – app part built, check on the phone once the mailbox is deployed** – ☰ → Pair
  with browser / Settings → Browser sync (camera opens the `#pair=` link, check digits, Pair; syncs every minute while open; Unpair asks).
  Saved routes and imported GPX tracks sync both ways (a delete from the browser → recycle bin; never during a tour).
  Tested without a phone: `CorePlanSyncTest`, `SyncFilesTest`, `SyncTrackTest`, and against the real `sync.js` in Node
  (`tools/sync/e2e.sh`). Web side: see "Web planner: mirror app changes".
- **Check on the phone (0.11.6 – 0.12.0, built without a phone):**
  - junction vibration: the app vibrated at every junction on the 2026-10-28 ride (trace "vibrated=true"), the owner
    felt nothing → Pixel Settings → Sound & vibration → Vibration & haptics → *Alarm vibration*, Do Not Disturb /
    Bedtime (our usage is ALARM); since 0.12.0 no cue for slight bends on a bike;
  - roundabout: drawn sign and "Roundabout · exit N" on the turn card / lock screen (exit number only unit-tested);
  - Navigate over the lock screen (power button during a tour), Plan asks to unlock;
  - recorded heights above sea level (first 15 s without height), compare with the map;
  - "Plan a route from it" on the owner's own GPX tracks;
  - pinch zoom keeps direction-up; one "Following stopped: map gesture" (18:25) unexplained – the trace names every
    stop, export diagnostics right after it happens; the arrow at the dot in Plan north-up follows the phone;
  - the brief blank map after planning to New York (not reproduced here – needs the trace);
  - the plan sheet follows the finger 1:1 (CI emulator: 0.69–0.80 of the finger travel; the trace logs "Sheet let go
    at X px …");
  - POI order while zooming at Bad Waldsee with schema-4 cells; the "Waiting for GPS · last fix …" line in Navigate
    is not cut;
  - older features never phone-tested on purpose: re-routing P0–P4, arrival / time estimate, End-tour ride summary,
    "Route back to the start?", slower GPS at rest, map freshness.
- **Smart screen while cycling – built (e6ee6ed), measure + ride** – rest view for bike activities (owner decisions
  2026-10-30). Still: Pixel power rails A/B on a 10-min demo ride (Always on vs Smart), the owner rides with it.
  From `docs/design/battery-audit.md` still open: slower GPS with the screen off, frozen track + tail.
- **Map: Knotenpunkte + cliffs/fords (owner 2026-10-30)** – cycle junction networks ("Knotenpunkte", NL / BE / DE: OSM
  nodes with `rcn_ref`, `network:type=node_network` – green numbered circles + network lines, own overlay layer,
  switchable in Settings → Map) and cliffs / fords on the map. Owner decision: these map-pipeline changes (together with
  the sidewalk-fragment stub fix, merged with the map batch) go live WITH THE MONTHLY REFRESH – no CELL_SCHEMA bump just
  for them; existing cells get them when their OSM data is 30 days old (Germany ~27.11), newly requested cells right away.
- **Google Maps place → go there (owner)** – when a place arrives from Google Maps (Share → OnPath,
  `receiveSharedPlace`), ask "Route here?" (from the position / the plan's end) instead of only showing the place card.
- **Two MainActivity instances at start (seen only on the first start after an install)** – one process, two
  activities, each its own ViewModel; shared state is already per host (`ui/MapReload.kt`) and the event log names
  every "Screen #n created / destroyed". Next: read those lines after the next install; if it is a plain launcher
  start while #1 lives, `finish()` the new one in `onCreate`. Bigger option (owner OK first): one app-wide state holder.
- **Route hints – open candidates (numbers in `docs/design/bike-routing.md` "Route hints")** – faint trail
  (trail_visibility bad / horrible: 2 % of hikes, 11 % of MTB routes – owner question); ferry name / landing (not in
  BRouter's data – from our overlays / POIs); times and seasons, borders (own data needed); hints in Navigate ("Ferry in
  2 km"); Bike / Gravel over smoothness=very_horrible (a profile question). Not planned: bollards, gates, barriers,
  access=destination / agricultural, node bicycle=no, toll.
- **requirements.md update (any session, owner checks)** – `docs/requirements.md` is edited in the repo (new IDs at the end of a section). Needed there:
  activity table with the app's names (Bike, Gravelbike, E-bike, MTB, E-MTB, Hike) and E-bike routing like the Bike
  (touring.brf, assist ~25 km/h); Komoot `<trk><type>` preselects the activity on import, export writes it; F-PLAN-06
  difficulty = distance + ascent + trail technique (hike T3+, bike S2+), hike bands like Komoot.
- **Map: paths look interrupted at junctions (Hilden vs Komoot)** – drawn footways / sidewalks stop short at streets;
  routing is not affected. Leads: `extract_overlays.py` leaves sidewalk / crossing out of paths.geojson; crossings may
  hide under the road casing. Check with a headless render of a Hilden cell (z15–17) first.
- **Router classes** – tertiary and bigger roads have no class (`docs/map-style-notes.md`). The app compares the cells'
  `wayclass` hash (format 2, comments don't count) with its profiles and asks for a refresh (`cells/RouterClassCheck`),
  but the doorman rebuilds only on a CELL_SCHEMA bump or OSM age – a profile change still needs that bump. Later
  (captain): a doorman `WAYCLASS` value (wrangler.toml, = `wayclass.jar --hash` of the job image) so a published cell
  with another hash counts as stale and is rebuilt when asked.
- **[cloud-plan] Planner security headers (CSP etc.)** – the one open point of the pre-release review 2026-11-01 (Web §7): the host sends no Content-Security-Policy; a change to the headers goes to the owner as a pull request.
- **CI follow-ups:** lint reports only (known findings in `app/lint-baseline.xml`) – work the baseline down, then let
  lint fail the run; `gradle/actions` stays on v5 (MIT): v6 moves its caching into a closed component under Gradle's
  Terms of Use – owner to decide before upgrading.
- **Small:** Ifinger T1 JVM test (`tools/routing/jvm-test/RerouterJvmTest.kt`, expects "unreachable", BRouter finds
  7.2 km – expectation or gap?); zip import checks only sizes (cell downloads check sha256).

## Waiting for the owner
- **[umbau] (2026-11-18) E2E nightly instead of on every push – your pull request step** (you said yes to it with the rebuild): my GitHub token may not push workflow files, so the change waits as `tools/ci/pending/e2e-nightly-shards-2026-11-18.patch` (nightly on main, by hand before a release, one class on demand; the full run on 4 emulators). Owner: `git am tools/ci/pending/e2e-nightly-shards-2026-11-18.patch` on a branch and open + merge the PR (or give donald's token the `workflow` scope – then [umbau] opens it). Not tested on GitHub yet (it cannot run before it is pushed) – [umbau] watches the first night. Until then the old push run stays and checkpoints use `tools/ci/e2e_shards.sh`.
- [donald] 2026-11-18, cleanup step 4: **lint fails on new findings** – a build-config change, so a pull request for you: `abortOnError = true` with the existing baseline (105 known findings stay quiet), `warningsAsErrors = false`, the three „newer version“ checks stay off, remove `continue-on-error` from the lint step in `unit.yml`; gate.sh unchanged (CI is the gate). Risk: an AGP / lint update brings new checks → refresh the baseline in that update's PR. Recommendation: yes.
<!-- Owner 2026-11-07: open questions to the owner live here (who, date, question, recommendation first); asked in the session's chat; whoever acts on the answer deletes the line. -->
- **[donald] "Aufzeichnung und Navigation ruhen" hardly ever shows (donald 2026-11-08)** – in 0.17.5 the stopped card's
  calm line shows only where it fits on one line beside the activity icon and the arrival time; on a 1080 px phone it
  does not (the approved v5 picture had it). The head "⏸ Angehalten seit …" and the buttons are there. Recommendation:
  let the line wrap / take the full card width under the head; other option: drop it.
- **[donald] Only our signed apps on the map server? (owner 2026-11-07, chat)** – App Links need no per-phone step
  (one fingerprint per signing key: GitHub test key + Play App Signing key, both in `web/host` assetlinks). Server
  access cannot be tied to our signature without Google Play Integrity (needs Play Services – against the "no Google
  Play Services" rule – and locks out GitHub / F-Droid builds); a key in the APK or an in-app signature check is easy to
  strip from a rebuild. Recommendation: per-phone / per-IP quotas, rotatable key, supporter code
  (`docs/design/map-access.md`); Play Integrity only for building new cells if abuse shows up. Owner decides.
- **[donald] Bike turn cue: heard 6 s before the turn – OK? (donald 2026-11-07)** – the owner asked for 2–3 s. His ride
  (tour 217) shows the normal "Gleich …" was already heard 4–5 s before; what felt late were swallowed / held turns
  ("danach" turns never said on their own, a turn waiting 3 s behind "Stopp 2 erreicht", arrival before the last turn) –
  all fixed (bd430119). The "now" is now heard 6 s before at every speed (`VoiceGuide.BIKE_NOW_S`, one constant).
  Recommendation: keep 6 s and try it; other option: 3 s as he said (at 15 km/h ≈ 12 m before the turn).
- **[donald] Undo in the full points sheet? (donald 2026-11-07)** – with the plan sheet at full height the map column
  is not drawn (ControlsPlacement, 2de9218f), so after deleting a point in the list there is no Undo on screen until
  the sheet is made smaller; C9 says "Undo on the map". Recommendation: show Undo in the sheet's points step while
  the map controls are hidden. Other option: keep it, the sheet one step smaller brings Undo back.
- **Role setup leftovers (captain 2026-10-31)** – (1) does the Germany pre-build stay on donald (item "[donald] Germany
  pre-built") or move to the laptop?; (2) git on the laptop: `gh auth setup-git` + `user.name` / `user.email` (check
  whether still needed). Old branches go only through `tools/dev/prune_branches.sh` after the owner's yes to the list.
- **`CF_START_TOKEN` (GitHub secret + the Cloudflare token behind it)** – only needed for the one-time Cloudflare setup
  of plan.onpath.earth (A-008); owner 2026-10-30: keep for now, delete later (GitHub: `gh secret delete CF_START_TOKEN`;
  Cloudflare dashboard → My Profile → API Tokens). The branch `marketing` is already gone.
- **Map server access before the public launch** – today one shared app key + IP rate limit + build caps. Proposal in
  `docs/design/map-access.md` (anonymous per-phone quota of new areas, supporter code, production key with rotation).
  Owner decides limit, supporter platform, timing. Production is not deployed yet (all released APKs use test); when it
  is, it carries everything already on test (e.g. A-014 no plain IPs / no invocation logs, 2e851ef) – the first
  production deploy is part of the launch together with Google Play.
- **AGP 9.4.1 upgrade** – later, when the owner says so.

## Later
- **Multi-day tours – concept complete, nothing built (owner 2026-11-17: every open point decided, „ansonsten haben wir alles“):** one plan + number of days + limits per day; Übernachtung (blue moon) / Hotel (green bed), grey moons worked out; way in ☰ → Mehrtagestour planen (GPX-Track laden / gespeicherte Route, „Noch etwas anhängen“); saving as every plan; the phone asks for maps at the first opening and picks a day. `docs/design/multi-day-tours.md`, sketches rounds 1–3 linked there (§10). Next: stage 0 (measure München–Venedig) when the owner says go. Touches shared code (`PlanCode`, `PlanEdits`) → laptop.
- **Plan sheet "a third lower"** – reached only ~23 % (d2885035); more needs touch targets smaller than 48 dp, owner
  said no (2026-10-31).
- **(optional) Gradle shared remote build cache** (e.g. a GCS bucket) for donald + CI + cloud sessions.
- **(low prio) Online place search depends on photon.komoot.io (owner 2026-10-30)** – `search/OnlineSearch.kt`
  (`PhotonProvider`, created in `AppViewModel`). Public use is allowed in reasonable volume, but no availability
  guarantee and possible throttling – an operational risk with many testers / a public launch. Today the offline index
  of loaded areas is searched first; Photon only adds places outside loaded maps (rough position ~1 km), so an outage
  means "nothing found outside loaded areas", not a crash. Small step: check we use it gently (own User-Agent / app id
  – `PhotonProvider.userAgent` exists, check it names the app; typing debounce, cache, back-off on errors), make the
  endpoint configurable, a calm line when it fails. Later (with the public launch / map server access): an own
  lightweight place index per country (towns, peaks, huts – not every address), built in the map pipeline and served by
  the map server, offline too – then Photon can go.
- **iPhone version** – direction and order in `docs/design/multiplatform.md`; core logic free of Android
  (`SharedCoreRuleTest`).
- **Open source + self-hosted map servers (concept, not built):** app setting "Map server" (custom URL + key, also via
  QR code; cells carry their server in the manifest); `/v1` API as an open spec; all-in-one Docker Compose or the cloud
  setup; builder image on ghcr.io; F-Droid build without Play Billing. Not before the Play launch.
- **Replace the tour's route by a saved route during a tour** – owner 2026-10-31: not for now (too much automation).
  Today a tap on a planned route in My routes during a tour says "End the running tour first, then this route can be
  loaded." (`RouteRules.openAsk` → `BLOCKED_DURING_TOUR`).
- **Tour suggestions — feedback captured, not scheduled (2026-11-08):** expand the existing idea using
  [bike loops from the doorstep](https://github.com/happychriss/OnPath/discussions/12) and the same contributor's
  [nearby hiking loops](https://github.com/happychriss/OnPath/discussions/4). Feature request, not a defect in the
  current round-trip mode: suggest loops without requiring a destination first. **Later:** robustness, the Play
  launch and feedback from a small real-world user group come first; no implementation date or engine choice.
  - [ ] Evaluate one shared loop-suggestion mechanism with activity-specific starts: current/chosen point for cycling;
    suitable nearby trailheads (e.g. parking or bus stops) for hiking.
  - [ ] Define a small on-device experiment: target distance + existing activity profile, several spatially distinct
    candidates, varied suggestions between requests, and a soft overlap penalty (avoid excessive detours).
    Compare candidate generation through the existing RoutingEngine with other feasible BRouter approaches;
    no assumption that a suitable round-trip API is already available.
  - [ ] Assess initial ranking: distance fit, profile-compatible surface/access, signed routes, low traffic,
    manageable ascent and route warnings; check actual route suitability rather than relying on the profile alone.
    Hiking also needs a clear rule for areas without downloaded maps.
  - [ ] Before implementation, measure phone runtime, cancellation and offline behaviour; preview candidates with
    distance, ascent, surface and a map, then open the selected route in the existing planner.
    Contributor `mrtnha` offered to test; follow up in the source discussion when a prototype is available.
  Triage outcome: one backlog item for both discussions; public reply remains a draft pending owner review.
- **Ideas:** outdoor mode, way back on own track, hut info, first-start intro / help page.
- **Map wishes (only when the owner says so):** shrub symbols; trail numbers abroad beyond numeric refs (E5 / GR20,
  `osmc:symbol`); contours below z12.
- **Unverified paths (owner: keep):** untagged OSM ways joining two paths, ≤ 300 m, grey dashed + point-menu hint,
  never routed (example way 330547188 near Algund).
- Tune the difficulty thresholds (Easy / Moderate / Difficult) with real tours.
