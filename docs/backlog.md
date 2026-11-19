# Backlog (shared by all sessions)

Claim / handoff rules: `docs/working-agreement.md` §1. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Tags: `[donald]`, `[laptop]` / `[cloud-plan]`, `[captain]`, `[cloud-web]`;
`[pixel]` / `[emu]` → donald, `[karten]` / `[ram]` → laptop. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see GitHub Releases
and the commit history. Role and claim lines (For the roles, For the captain, In progress) stay complete up to 400 bytes, every other
item is one short title line; detail goes to `docs/items/<date>-<slug>.md` (`tools/dev/backlog_slim.py` moves it). The gate
caps this file (cap in the script, target 20 KB): delete role lines every tag has read and items that are done first.

## For the roles
- → [donald] ([umbau] 2026-11-19, old behaviour seen while fixing L2 – yours to judge, not fixed): **an interrupted tour whose read fails once at the app's start is closed as „done“ without its question when the user then starts a new tour**… → [detail](items/2026-11-19-umbau-to-donald-an-interrupted-tour.md)
- → [umbau] (donald 2026-11-19): FYI – the round-trip search as a wait window (owner) touched your `:state` `SuggestSlice.kt` (`searching`, `laps`, `cancel()`, a guard in `open`), f1f2f174c; `AppViewModel` untouched. „Mehr“ (905a8b9dc) changed core `TourReview` (pause times). Please pull.
- → [umbau] [laptop]… → [detail](items/2026-11-19-owner-s-rule-for-releases-from-now-on.md)
- → [umbau] (captain 2026-11-19, the owner asks why test lines do not go down): measured now – unit lines 55,624 (0.18.0) → 70,512 (`app` 27,603 · `core` 24,912 · `state` 17,997 in 41 files); since 0.18.0 +19,147 / −3,715, 54 test files added… → [detail](items/2026-11-19-captain-to-umbau-measured-now-unit.md)
- → [laptop] (donald: app part running)… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11-4.md)
- → [laptop] (donald: app done bda58d531 – dots; Details stays, see „For the captain“) (captain 2026-11-19, owner's feedback on the route preview, „bitte überprüfen und wenn möglich direkt anpassen“): (1) the dashboard that swipes left /… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11-2.md)
- → [umbau] [laptop] (donald: fix on main b3daed4c0 – cause not proven, see „For the captain“) (captain 2026-11-19, **owner's field report on 0.18.1 – a regression, first in line**): in „Meine Routen“ (Gemacht and Geplant) **the map…** → [detail](items/2026-11-19-donald-to-umbau-laptop-captain-2026-11.md)
- → [laptop] (donald: app done f1f2f174c – steps „Wege lesen · Runden suchen · bewerten“, map taps ignored while searching) round-trip search as form A… → [detail](items/2026-11-19-captain-to-donald-laptop-the-round-trip.md)
- → [laptop] (donald: pictures done, waiting for the owner)… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11-3.md)
- → [laptop] (app + shared core done by donald: the note is no longer made in `TourSuggester` – the planner gets it with the next build; check your display)… → [detail](items/2026-11-19-app-to-laptop-captain-2026-11.md)
- → [laptop] (donald 2026-11-19, **please deploy the planner**): the app **0.18.1 is out** (GitHub Release with APK), but the release run's planner deploy stopped at the parity check… → [detail](items/2026-11-19-please-deploy-the-planner.md)
- → [umbau] (laptop 2026-11-19 ~11:30, my look at step 2 + your three phase-2 questions): **step 2 is sound** – nothing Android-dependent can slip in (`:core` has no Android), `check.mjs` still catches mirrors.… → [detail](items/2026-11-19-step-2-is-sound.md)
- → [laptop] (donald 2026-11-19): parity re-recorded for 4 app mirrors so web.yml goes green for 0.18.1 – please mirror in the planner: **Undo / Redo in the full points list**… → [detail](items/2026-11-19-donald-to-laptop-parity-re-recorded.md)
- → [laptop] (donald: app part running, in 0.18.1) (captain 2026-11-19, owner asked again, his word: „Zuhause abschalten“): **Zuhause off – final.** The branch `wip/donald-2026-11-19-zuhause` is not merged (keep it parked); the „Zuhause“ row… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11.md)
- → [laptop] (captain 2026-11-19, owner: „ich bin jetzt offline, die App soll so bauen, wie sie meint“): the wait window and the two progress forms are **built and merged on your own judgement – no pictures first**; put the pictures as a line… → [detail](items/2026-11-19-captain-to-laptop-the-wait-window.md)
- → [laptop] (captain 2026-11-19, the owner's answers, asked one by one): (1) [umbau] **the three old weaknesses in the tour code: fix all three** right after the tour pieces, each its own small piece, a failing test first.… → [detail](items/2026-11-19-captain-to-laptop-1-umbau-the.md)
- → [umbau-hp] (captain 2026-11-19, owner: „ja, mache es wie deine Empfehlung“): **5b is a go – port the routing engine to Kotlin**, as `docs/design/routing-kmp-spike.md` recommends: after phase 3, 3–5 nights, the hardening list first; Android… → [detail](items/2026-11-19-captain-to-umbau-hp-5b-is-a.md)
- → [laptop] (captain 2026-11-19, the owner's answers): (1) **„Zuhause“ off for now**… → [detail](items/2026-11-19-zuhause-off-for-now.md)
- → [laptop] (captain 2026-11-19, owner: „baue die richtige Lösung vollständig rein, ich brauche die neue Version erst später“) **the wait window gets real progress – before 0.18.1, no interim form.** One bar 0–100 over all steps, never… → [detail](items/2026-11-19-captain-to-laptop-the-wait-window-2.md)
- → [laptop]… → [detail](items/2026-11-19-captain-to-donald-laptop-ich-kann-weiter.md)
- → [laptop] (donald 2026-11-19): **progress design on main** (98 % fix + wait window A + sign B, reviewed): touched `MapLoad`, `MapDataFlow`, `RoutingLoads`, `BackgroundDownloads`, `FirstMapCard`, new `ui/progress/`, `core/domain/WaitProgress.kt`, `CellStore`/`Rd5Parts` report progress, `AppViewModel` only the WaitBar call (`planRun` public) – [umbau] please pull. [laptop] mirror → [detail](items/2026-11-19-progress-design-on-main.md)
- → [cloud-web] (IR 2026-11-19 ~00:30): **round-trip pictures, planner + phone, DE + EN** – real suggestions at Alken (Traumpfad Bleidenberger Ausblicke card first): keys **web-rundtour-wunsch** (N01), **web-rundtour** (N02 card row)… → [detail](items/2026-11-19-round-trip-pictures-planner-phone-de-en.md)
- → [donald] ([umbau] 2026-11-20, seen in an E2E run, old behaviour – yours to judge): **a hold on the map while the first route is still being calculated / snapped can add nothing, silently.** The hold beside the plan is measured (1.5 s limit) and… → [detail](items/2026-11-20-umbau-to-donald-a-hold-on.md)
- → [laptop] ([umbau] 2026-11-19 night, an old race in the sync, found by the review while moving code – not fixed, yours to judge): **a browser plan that is being routed on the phone can overwrite a save the user makes meanwhile.**… → [detail](items/2026-11-19-umbau-to-laptop-a-browser-plan.md)
- → [donald] ([umbau] 2026-11-19 night, found while moving code – yours to decide, not fixed): **a POI taken with „Hier starten“ / „Als Ziel setzen“ from its menu (and „Hierhin“ on a plan that has points) does not remember its kind**… → [detail](items/2026-11-19-umbau-to-donald-a-poi-taken.md)
- → [mehrtage] ([umbau] 2026-11-19 ~13:30): **the flaky `NoStaleDownloadsTest.aMultiDayTour…` was a real bug in your stage 4 area – fixed on main, please read:** `MapCells.request` upgraded a queued entry… → [detail](items/2026-11-19-umbau-to-mehrtage-the-flaky-nostaledownl.md)
- → [mehrtage] [IR] [laptop] [umbau-hp] ([umbau] 2026-11-19 ~09:00) **phase 2 starts** (`docs/design/umbau-phase-2.md`: `AppViewModel` is cut into slices in a new module `:state`, ~5 nights).… → [detail](items/2026-11-19-phase-2-starts.md)
- → [laptop] ([umbau] 2026-11-19 ~09:00): **step 2 is on main (f40c8e6c6) – please look at it, it is your build:** `web/engine` compiles the whole `core/src/main/kotlin` folder (stand-ins override by path, `web/engine/core-excludes.txt` is empty)… → [detail](items/2026-11-19-step-2-is-on-main-f40c8e6c6-please-look.md)
- → [mehrtage] [IR] [laptop] [umbau-hp] ([umbau] 2026-11-19 ~05:30): **253 Android-free files now live in `core/src/main/kotlin/com/onpath/app/<pkg>/`**… → [detail](items/2026-11-19-umbau-to-donald-mehrtage-ir-laptop-umbau.md)
- → [laptop] ([umbau] 2026-11-19 ~05:30): the planner build is green with `:core` (web-build, jvm, 4 of 5 smoke groups; areas-parity was red on the overlay text of your 98 % change). `web/engine` needs `-Pkotlin.incremental=false` once after the pull.… → [detail](items/2026-11-19-umbau-to-laptop-the-planner-build.md)
- → [mehrtage] [umbau] (laptop 2026-11-19 ~00:50): `NoStaleDownloadsTest.aMultiDayTourKeepsItsAreasAndWaitsForWifi` red on main every run („timeout waiting for b loaded on mobile data“, also with the old Planner.kt – not the climb fix).… → [detail](items/2026-11-19-laptop-to-mehrtage-umbau-nostaledownload.md)
- → [mehrtage] (donald 2026-11-18 late, found while taking the multi-day website pictures): reopening the **same** multi-day tour… → [detail](items/2026-11-18-same.md)
- → [cloud-web] (laptop 2026-11-19 ~01:10): **planner pictures in onpath.private `aktuell/` (164d311, 118a392)**: retaken B01–B04, B07, C01, C02, C06; new multi-day B11 Mehrtagestour planen, B12 Als Mehrtagestour planen, B13 Tage festlegen, B14 Übersicht (DE + EN; keys in `docs/design/website-screens.md`). B08–B10 stay the owner's 3D examples. Delete the tag when taken.
- → [cloud-web] (donald 2026-11-18 night): new phone pictures of multi-day tours (Meraner Höhenweg, 3 days), DE + EN: keys **mehrtage-tage** (M01 day overview), **mehrtage-unterwegs** (M02 „Tag 2 von 3“), **mehrtage-karte** (M03 nights on the map) – onpath.private 801d650, aktuell/, Note M in liste.md; flow M in website-screens.md.
- → [IR] [laptop]… → [detail](items/2026-11-18-owner-round-trips-go-on-for-everyone-ton.md)
- → [IR] [mehrtage] (captain 2026-11-18 late, owner: README and website pictures for the new features): the README now names multi-day tours (captain, this commit).… → [detail](items/2026-11-18-captain-to-laptop-ir-mehrtage-the-readme.md)
- → [IR] [mehrtage] (captain 2026-11-18 late, **the owner's answers to all open questions, one by one**): (1) [IR] sketch 8 – „Ja, so bauen“: the round-trip cards in one horizontal swipe row, app + planner.… → [detail](items/2026-11-18-the-owner-s-answers-to-all-open-question.md)
- → [donald] [mehrtage] [laptop]… → [detail](items/2026-11-18-captain-to-donald-mehrtage-laptop-led-by.md)
- → [cloud-web] (donald 2026-11-18): new app pictures after 0.18.0 to build in: **D09, F03, H04, J02 (DE + EN)** (onpath.private 251de76, aktuell/, notes in liste.md). The planner steps `pictures_affected.sh v0.17.10..v0.18.0` also lists (B01–B04, B07–B09, C01, C02, C06) are the laptop's to take; E05, G03, J03, L01 show no change for a viewer.
- → (IR on it 2026-11-18 22:45, owner „Ja, so bauen“ to sketch 8 https://claude.ai/artifact/DkavTPQGNXErH4ADZVdZS6) (captain 2026-11-18, **owner after his first try: „das sieht sehr gut aus“**, round trips): the suggestion cards should sit… → [detail](items/2026-11-18-ir-to-all-captain-2026-11.md)
- → [donald] (IR 2026-11-18): **round-trip suggestions in the app are on main** (stage 4 UI, `ui/suggest/`, behind the developer switch „Tourvorschläge (Test)“, OFF): row „Rundtour von hier“ after „Hier starten“ (empty plan only), wish… → [detail](items/2026-11-18-round-trip-suggestions-in-the-app-are-on.md)
- → [mehrtage] (donald 2026-11-18 08:25): **all of the app is free – nothing is reserved for donald any more** (cleanup done ~03:00): `ui/MapOverlays.kt`, `domain/PinMenuPlace.kt`, `AppViewModel`, `ui/PlanPanel.kt`, `map/MapController`… → [detail](items/2026-11-18-all-of-the-app-is-free-nothing-is-reserv.md)
- → [mehrtage] (IR 2026-11-18 08:20, captain's word: stage 4 app UI now, green on main by ~17:00): **IR's entries in the app:** a new slice `ui/suggest/` (SuggestSlice + `SuggestSheet.kt` wish sheet + the result cards) – in `AppViewModel` only a few… → [detail](items/2026-11-18-ir-to-mehrtage-ir-s-entries.md)
- → (read by mehrtage) (donald 2026-11-18 05:10): **cleanup done – your stage-4 phone part may land now, all three commits** (non-UI `fccb04ca` store + sync + backup with TourDb schema 9, `5be72b60` maps corridor, and the UI `090831dd`).… → [detail](items/2026-11-18-read-to-all-donald-2026-11.md)
- → (mehrtage 2026-11-18): **multi-day planner (stages 2–3) is on main**… → [detail](items/2026-11-18-multi-day-planner-stages-23-is-on-main.md)
- → [mehrtage] (IR 2026-11-18): **planner stage 3 (round-trip suggestions) is on main**, behind the switch (off; `?debug` → „Tourvorschläge (Test)“): `WebSuggest.kt` + one `suggest(op, json)` export in `WebApi.kt`… → [detail](items/2026-11-18-planner-stage-3-round-trip-suggestions-i.md)
- → [mehrtage] (IR 2026-11-17 ~23:40, the captain's order „who takes which entry“): **IR's entries in `web/`, starting when the laptop's hold-rule mirror is on main:** (a) a NEW file… → [detail](items/2026-11-17-ir-to-mehrtage-laptop-ir-s-entries.md)
- → (read by all)… → [detail](items/2026-11-17-read-to-all-captain-2026-11.md)
- → (answered by donald 2026-11-18 05:10, see the line above) (mehrtage 2026-11-17 night): **multi-day phone part ready on `wip/mehrtage-2026-11-17-stage4-phone`, nothing on main – your word decides when.** Non-UI, gate green: `fccb04ca` store + sync… → [detail](items/2026-11-18-answered-to-all-mehrtage-2026-11.md)
- → (read by all)… → [detail](items/2026-11-17-owner-s-word-release-now-then-the-big-ap.md)
- → [cloud-web] (captain 2026-11-17, **owner: „Das Fragenregister brauchen wir nicht mehr … da braucht auch keiner mehr was eintragen“** and „dass ich selbst Rückmeldung geben muss, möchte ich vermeiden“): (1) no more entries on the… → [detail](items/2026-11-17-captain-to-cloud-web-1-no-more.md)
- → (done by donald 2026-11-18, on main) (captain 2026-11-17, **owner: „ja, wir machen die Kartendownloads, so wie du sagst“**): on a new map version the phone copies files whose name + sha256 equal the installed version's and downloads only the… → [detail](items/2026-11-18-done-to-all-captain-2026-11.md)
- → [mehrtage] (IR 2026-11-17, per the captain's order): IR's stage 3 waits until you are through `web/` stages 2–3 – please write here when `web/app` / `WebApi` are free for IR.… → [detail](items/2026-11-17-ir-to-mehrtage-ir-s-stage.md)
- → (captain 2026-11-17, owner: „keine Missverständnisse oder Überlappung“): **five sessions now, who is who** – `docs/roles/README.md` has two new rows: **IR** = tour suggestions (Routenfinder), **mehrtage** = multi-day tours; both run on… → [detail](items/2026-11-17-five-sessions-now-who-is-who.md)
- → [cloud-web] (owner 2026-11-03, comparison card 5): **Play store screenshots** in the store pattern – a short benefit headline on top, the phone on OnPath green, the A-019 pictures.… → [detail](items/2026-11-03-play-store-screenshots.md)
- → [cloud-web] (donald 2026-11-03): pairing-help pictures app part done – `onpath.private/marketing/bilder/2026-11-03-donald/`
  (`app-a1…a4-de|en.png` + `Ergebnis.md`; A1 from the emulator with a real map, A2–A4 from Paparazzi); keys
  `app-koppeln` / `app-pruefen` / `app-neu` / `app-trennen` in `docs/design/website-screens.md`.
- → [cloud-web] (captain 2026-11-07, owner's word): no more question issues – ask the owner in your chat;
  unanswered at the end → "Waiting for the owner" here; the owner's word to all comes as a line here. Pull main and
  read CLAUDE.md once. Delete this line when read (each role removes its tag).
- → [cloud-web] (donald 2026-11-08): new app pictures to build in: D10, E02, F04, F05, G01, G02 (DE + EN) (onpath.private 5875c99, aktuell/)
- → [donald] (laptop 2026-11-17 night): **CELL_SCHEMA 9 is on main** (suggest layer, content 2 with AIMS; TEST doorman deploys with the push).… → [detail](items/2026-11-17-cell-schema-9-is-on-main.md)
- → [mehrtage] (laptop 2026-11-18): **done – the hold-rule mirror is on main**… → [detail](items/2026-11-18-done-the-hold-rule-mirror-is-on-main.md)
- → (read by mehrtage) (laptop 2026-11-18 08:45): **planner deployed with multi-day + round trips (62741bff)**.… → [detail](items/2026-11-18-planner-deployed-with-multi-day-round-tr.md)
- → [mehrtage] (laptop 2026-11-18, owner's field report: Neuss → München sits at 98 % for a long time): **laptop touches `web/app/src/loadoverlay.ts` / the load card (+ the plan-progress hook in `main.ts`) now** – the route-calculation phase gets its own honest progress instead of a frozen 98 %. Small, separate from the header / multi-day code.
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [laptop] (2026-11-19 ~14:30): **all of today's [laptop] lines done and live**: progress design in the planner… → [detail](items/2026-11-19-all-of-today-s-lines-done-and-live.md)
- ← [donald] (2026-11-19): **route preview – page dots on main (bda58d531); „Details“ NOT removed:** it holds more than descent – clock times from–to („Unterwegs 10:12–14:47“), descent ↓, speed uphill and downhill in m/h, a small line… → [detail](items/2026-11-19-route-preview-page-dots-on-main-bda58d53.md)
- ← [donald] (2026-11-19): **My routes' pictures without map – fixed on main (b3daed4c0), cause NOT proven:** v0.18.0 and main draw identical pictures on the emulator.… → [detail](items/2026-11-19-my-routes-pictures-without-map-fixed-on.md)
- ← [laptop] (2026-11-19 ~10:30): read all [laptop] lines; the done ones (≤ 11-18) lose my tag.… → [detail](items/2026-11-19-laptop-to-captain-read-all-laptop.md)
- ← [donald] (2026-11-19): **0.18.1 released – app only; the planner deploy stopped at parity, now fixed, deploy asked of [laptop]** (owner's word in donald's chat) – round trips for everyone, progress design (A window ≤3 steps / B one %, reviewed, 7 promise fixes), Undo in the points list, „Tag 2 starten“, Zuhause hidden; fixed on the way: TourDb 8→9 test, hold-measure E2E, OverviewSwipe E2E, a place menu moved the map twice (Rundgang flow 10). → [detail](items/2026-11-19-donald-fresh-session-state.md)
- ← [IR] (2026-11-19): **IR ready for a fresh session** – card row + round trips on by default on main, README corrected, website pictures N01–N04 in onpath.private (→ [cloud-web] line), handoff `docs/history/handoff-ir-2026-11-19.md`. Nothing of IR runs.
- ← [umbau] (2026-11-19): **checkpoint tags `umbau-phase-0` and `umbau-phase-1` are set on 45293f12f** – unit CI, Web planner run and a full E2E (99 of 99) green; thanks to donald for the three test fixes and the mirrors.
- ← [umbau] (2026-11-19): **the three old tour weaknesses are fixed (owner: fix all three), each with a test that was red before:** L1 on main… → [detail](items/2026-11-19-umbau-to-captain-the-three-old.md)
- ← [umbau] **earlier lines of the rebuild (2026-11-18 late … 2026-11-19 17:00), all in one file** – phase 0, phase 1 pieces A–D, the plan corrections, the test audit numbers, phase 2 design and pieces 1–8. **Still open with you:** (1) the pull request from `tools/ci/pending/core-state-paths-lint-2026-11-19.patch` (3 commits: `core/**` + `state/**` paths, lint fails on new, `--continue`) – until then every core edit needs `python3 tools/dev/core_move.py lock`; (2) the night check-in is not set (the owner's word must be a backlog line); (3) both tags wait for mehrtage's `TourDbMigrationTest` fix. → [detail](items/2026-11-19-umbau-log-for-the-captain.md)
- ← [laptop] (2026-11-19 ~10:00): **all four of tonight's orders done and live**: (1) 98 % fix, (2) pictures, (3) IR's card row + round trips on (planner), (4) leg cache.… → [detail](items/2026-11-19-all-four-of-tonight-s-orders-done-and-li.md)
- ← [umbau-hp] (2026-11-19): **routing spike 5a done: BRouter's core runs as pure Kotlin.** 24 of 24 routes identical to the Java engine (JVM and native linuxX64); Android same speed, native ×1.9 time, ¼ memory; Opus review: no wrong conversion, hardening list for 5b. Recommendation: port, 3–5 nights after phase 3. `docs/design/routing-kmp-spike.md`. Next: phase 4b from [umbau].
- → [laptop] (donald 2026-11-18 night, cleanup piece 2 – **touches `web/engine` imports only**): 33 Android-free files move `com.onpath.app.ui` → `com.onpath.app.domain`… → [detail](items/2026-11-18-touches-web-engine-imports-only-2.md)
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [donald] (2026-11-19): **route preview – page dots on main (bda58d531); „Details“ NOT removed:** it holds more than descent – clock times from–to („Unterwegs 10:12–14:47“), descent ↓, speed uphill and downhill in m/h, a small line… → [detail](items/2026-11-19-route-preview-page-dots-on-main-bda58d53-2.md)
- ← [donald] (2026-11-19): **My routes' pictures without map – fixed on main (b3daed4c0), cause NOT proven:** v0.18.0 and main draw identical pictures on the emulator.… → [detail](items/2026-11-19-my-routes-pictures-without-map-fixed-on-2.md)
- ← [laptop] (2026-11-07) for cloud-web: the planner links privacy at https://onpath.earth/de/datenschutz/ (EN /privacy/) – the address in the privacy-check item, onpath.earth/datenschutz/, answers 404; maybe a redirect on the site. Access comments in web/host fixed (02a16286).
- ← [laptop] (2026-11-06) **One branch – what app sessions see:** an app push that changes a file the planner mirrors… → [detail](items/2026-11-06-one-branch-what-app-sessions-see.md)
<!-- A role's word to the captain (roles cannot message the captain): - ← [donald|laptop|cloud-web] what – YYYY-MM-DD. The captain deletes the line when read. -->
- ← [cloud-web] **A-022 words Plan · Tour · Route (owner decision 2026-11-03)** – for **donald** (app texts, ~60 DE + EN, glossary, screenshots) and **cloud-plan** (planner texts): Plan = what you plan and save, Tour = what you do / did, Route =… → [detail](items/2026-11-03-a-022-words-plan-tour-route-owner-decisi.md)

## In progress
- [donald] Website pictures after 0.18.1 (`pictures_affected.sh v0.18.0..v0.18.1`) – since 2026-11-19
- [umbau] The rebuild, phases 0–4 of `docs/design/architecture-plan-2026-11.md` (owner's go 2026-11-18; spike 5a is [umbau-hp]'s).… → [detail](items/2026-11-18-the-rebuild-phases-04-of.md)
- [umbau-hp] Phase 4b of the rebuild (from [umbau]): screenshot tests – components instead of whole screens where the screen is not the point (549 pictures → ~200), no check lost. Touches only `app/src/test/java/com/onpath/app/ui/**` + `app/src/test/snapshots/**` – since 2026-11-19
- [IR] Tour suggestions (Routenfinder), the whole topic: POC `tools/suggest-poc/` (the lab), the core `app/src/main/java/com/onpath/app/suggest/` (ported, parity), the suggest file (laptop builds the cells), the planner framework branch… → [detail](items/2026-11-14-tour-suggestions-routenfinder-the-whole.md)
<!-- - [donald|laptop|captain|cloud-web] short description – since YYYY-MM-DD -->
- [mehrtage] Multi-day tours – build (owner 2026-11-17: „Ja, es geht los … Let's build it“): stages 0–4 on main (2026-11-18); open: the owner's look on the Pixel (GitHub E2E green 2026-11-18); later: share a hotel to the tour (stage 5). `docs/design/multi-day-tours.md` – since 2026-11-17

## Next
- **[donald] Small: a found place's menu that opens…** → [detail](items/2026-11-19-small-a-found-place-s-menu-that-opens-ab.md)
- **[donald]…** → [detail](items/2026-11-19-nostaledownloadstest-amultidaytour-still.md)
- **[donald] Check with the owner's Pixel on 2026-11-18…** → [detail](items/2026-11-18-check-with-the-owner-s-pixel-on-2026-11.md)
- **[laptop] POI click on a slow phone after a pan…** → [detail](items/2026-11-17-poi-click-on-a-slow-phone-after-a-pan-20.md)
- **[IR] Tour suggestions – state…** → [detail](items/2026-11-17-tour-suggestions-state-docs-history-hand.md)
- **[laptop] Kept places in the planner – UI + browser…** → [detail](items/2026-11-15-kept-places-in-the-planner-ui-browser-st.md)
- **[donald] Full check: POI names on the map without…** → [detail](items/2026-11-15-full-check-poi-names-on-the-map-without.md)
- **[laptop] routes-smoke step 4b flaky…** → [detail](items/2026-11-15-routes-smoke-step-4b-flaky-2026-11-15-1.md)
- **[laptop] Waiting for the owner: new planner smokes…** → [detail](items/2026-11-13-waiting-for-the-owner-new-planner-smokes.md)
- **[donald] Very short recordings go to the recycle bin…** → [detail](items/2026-11-12-very-short-recordings-go-to-the-recycle.md)
- **[donald] Code audit 2026-11-12…** → [detail](items/2026-11-12-code-audit-2026-11-12-owner-asked-clean.md)
- **[donald] Wide translucent green line darkens where…** → [detail](items/2026-11-12-wide-translucent-green-line-darkens-wher.md)
- **[donald] Small rests of the colour round (2026-11-12)** → [detail](items/2026-11-12-small-rests-of-the-colour-round-2026-11.md)
- **[donald] Website pictures for 0.17.9** → [detail](items/2026-11-18-website-pictures-for-0-17-9.md)
- **[donald + laptop] Favourite tours…** → [detail](items/2026-11-13-favourite-tours-owner-2026-11-13-variant.md)
- **[donald] Beta bug button (owner 2026-11-10, chat)…** → [detail](items/2026-11-10-beta-bug-button-owner-2026-11-10-chat-st.md)
- **[donald] Father's first feedback (owner 2026-11-08)** → [detail](items/2026-11-08-father-s-first-feedback-owner-2026-11-08.md)
- **[donald] Off route with a stop: the new route may…** → [detail](items/2026-11-08-off-route-with-a-stop-the-new-route-may.md)
- **[donald] Local Rundgang emulator…** → [detail](items/2026-11-08-local-rundgang-emulator.md)
- **[donald] Design round after 0.17.4…** → [detail](items/2026-11-07-design-round-after-0-17-4-owner-2026-11.md)
- **[laptop] Idea: saved plans open without routing…** → [detail](items/2026-11-07-idea-saved-plans-open-without-routing-ag.md)
- **[donald] Bugs seen while taking the Meran 0.17…** → [detail](items/2026-11-07-bugs-seen-while-taking-the-meran-0-17-pi.md)
- **[donald] (low) Vellau → Leiteralm routes over the…** → [detail](items/2026-11-07-low-vellau-leiteralm-routes-over-the-gam.md)
- **[donald] Smoother on older phones…** → [detail](items/2026-11-07-smoother-on-older-phones-owner-field-202.md)
- **[donald] Handy einrichten: "Links vom Planer in…** → [detail](items/2026-11-07-handy-einrichten-links-vom-planer-in-onp.md)
- **[laptop] Pairing from a phone camera ends in the…** → [detail](items/2026-11-07-pairing-from-a-phone-camera-ends-in-the.md)
- **[donald] In-app privacy texts say more than the code…** → [detail](items/2026-11-07-in-app-privacy-texts-say-more-than-the-c.md)
- **[donald] Follow-ups from v0.17.3 (2026-11-07)** → [detail](items/2026-11-07-follow-ups-from-v0-17-3-2026-11-07.md)
- **[donald] ▶ Sim restarted on the same line…** → [detail](items/2026-11-07-sim-restarted-on-the-same-line-progress.md)
- **[donald] Navigation neu – the rest of the owner's…** → [detail](items/2026-11-01-navigation-neu-the-rest-of-the-owner-s-f.md)
- **[laptop] Planer: gekoppelt = eine Liste, alles…** → [detail](items/2026-11-06-planer-gekoppelt-eine-liste-alles-synchr.md)
- **[donald] 250 tours per phone in the app…** → [detail](items/2026-11-07-250-tours-per-phone-in-the-app-owner-202.md)
- **[donald] Test audit + clean-out (owner 2026-11-06)** → [detail](items/2026-11-06-test-audit-clean-out-owner-2026-11-06.md)
- **[laptop] Planner follows the app's plan rules of…** → [detail](items/2026-11-06-planner-follows-the-app-s-plan-rules-of.md)
- **[cloud-plan] Planner follows the app's plan rules of…** → [detail](items/2026-11-06-planner-follows-the-app-s-plan-rules-of-2.md)
- **[laptop] Web planner – novice walkthrough…** → [detail](items/2026-11-06-web-planner-novice-walkthrough-owner-202.md)
- **[donald] Flow map + novice walkthrough…** → [detail](items/2026-11-06-flow-map-novice-walkthrough-docs-design.md)
- **[donald] Pixel review 2026-11-06 – open bugs** → [detail](items/2026-11-06-pixel-review-2026-11-06-open-bugs.md)
- **[donald → owner] Save-path proposals…** → [detail](items/2026-11-06-save-path-proposals-docs-design-save-pat.md)
- **[pixel] Owner's points of 2026-11-06 – check on…** → [detail](items/2026-11-06-owner-s-points-of-2026-11-06-check-on-th.md)
- **[donald] Voice: the roundabout sentence on the…** → [detail](items/2026-11-06-voice-the-roundabout-sentence-on-the-own.md)
- **[cloud-plan] Flaky web smokes on GitHub – the rest** → [detail](items/2026-11-06-flaky-web-smokes-on-github-the-rest.md)
- **[donald] Onboarding – first-use hints…** → [detail](items/2026-11-05-onboarding-first-use-hints-proposal-2026.md)
- **[cloud-plan] Web: within-a-leg wait bar** → [detail](items/2026-11-05-web-within-a-leg-wait-bar.md)
- **[pixel] Measure long bike routes on the Pixel…** → [detail](items/2026-11-05-measure-long-bike-routes-on-the-pixel-le.md)
- **[donald] Far start: ask before routing the way there…** → [detail](items/2026-11-05-far-start-ask-before-routing-the-way-the.md)
- **[donald] + [laptop] Long bike routes – the rest…** → [detail](items/2026-11-04-long-bike-routes-the-rest-owner-2026-11.md)
- **Docs pictures follow the UI by themselves…** → [detail](items/2026-11-03-docs-pictures-follow-the-ui-by-themselve.md)
- **[pixel] Check: red tile "Neu planen" in "Route…** → [detail](items/2026-11-03-check-red-tile-neu-planen-in-route-ander.md)
- **[pixel] Check: Plan is silent during a tour…** → [detail](items/2026-11-03-check-plan-is-silent-during-a-tour-built.md)
- **[pixel] Feel: holding beside the route…** → [detail](items/2026-11-03-feel-holding-beside-the-route-24-dp-buil.md)
- **[donald] [pixel] Diagnostics: 127 entries on the…** → [detail](items/2026-11-02-diagnostics-127-entries-on-the-owner-s-p.md)
- **[pixel] Field check: no GPS line while lying still…** → [detail](items/2026-11-02-field-check-no-gps-line-while-lying-stil.md)
- **Bike difficulty limit S0 / S1 / S2…** → [detail](items/2026-11-01-bike-difficulty-limit-s0-s1-s2-owner-202.md)
- **Way type – more kinds (owner idea 2026-11-01, rest):** → [detail](items/2026-11-01-way-type-more-kinds-owner-idea-2026-11-0.md)
- **Website and Google Play follow-ups…** → [detail](items/2026-10-31-website-and-google-play-follow-ups-marke.md)
- **From the external review (A-020 §4) – build only…** → [detail](items/2026-10-30-from-the-external-review-a-020-4-build-o.md)
- **On the next real ride (owner)** → [detail](items/2026-10-30-on-the-next-real-ride-owner.md)
- **Plan edits during a tour – built 2026-10-31…** → [detail](items/2026-10-31-plan-edits-during-a-tour-built-2026-10-3.md)
- **[pixel] Voice guidance – the owner's check on a ride** → [detail](items/2026-11-18-voice-guidance-the-owner-s-check-on-a-ri.md)
- **[donald] Germany pre-built on donald → TEST R2…** → [detail](items/2026-10-30-germany-pre-built-on-donald-test-r2-owne.md)
- **Web planner: mirror app changes (keep this list current)** → [detail](items/2026-10-30-web-planner-mirror-app-changes-keep-this.md)
- **Phone setup: check brand menu words on real phones** → [detail](items/2026-11-18-phone-setup-check-brand-menu-words-on-re.md)
- **Plan sync phone ⇄ browser (A-007) – app part…** → [detail](items/2026-11-18-plan-sync-phone-browser-a-007-app-part-b.md)
- **Check on the phone…** → [detail](items/2026-10-28-check-on-the-phone-0-11-6-0-12-0-built-w.md)
- **Smart screen while cycling – built (e6ee6ed)…** → [detail](items/2026-10-30-smart-screen-while-cycling-built-e6ee6ed.md)
- **Map: Knotenpunkte + cliffs/fords (owner 2026-10-30)** → [detail](items/2026-10-30-map-knotenpunkte-cliffs-fords-owner-2026.md)
- **Google Maps place → go there (owner)** → [detail](items/2026-11-18-google-maps-place-go-there-owner.md)
- **Two MainActivity instances at start…** → [detail](items/2026-11-18-two-mainactivity-instances-at-start-seen.md)
- **Route hints – open candidates…** → [detail](items/2026-11-18-route-hints-open-candidates-numbers-in-d.md)
- **requirements.md update (any session, owner checks)** → [detail](items/2026-11-18-requirements-md-update-any-session-owner.md)
- **Map: paths look interrupted at junctions…** → [detail](items/2026-11-18-map-paths-look-interrupted-at-junctions.md)
- **Router classes** → [detail](items/2026-11-18-router-classes.md)
- **[cloud-plan] Planner security headers (CSP etc.)** → [detail](items/2026-11-01-planner-security-headers-csp-etc.md)
- **CI follow-ups:** → [detail](items/2026-11-18-ci-follow-ups.md)
- **Small:** → [detail](items/2026-11-18-small.md)

## Waiting for the owner
- [umbau] 2026-11-19: **Darf der alte…** → [detail](items/2026-11-19-darf-der-alte-start-wachter-test-fur-rec.md)
- **[laptop] For the owner to look at…** → [detail](items/2026-11-19-for-the-owner-to-look-at-no-answer-neede.md)
- [donald] 2026-11-19: **Routenvorschau – Seitenpunkte + „Mehr“ gebaut, Bilder…** → [detail](items/2026-11-19-routenvorschau-seitenpunkte-gebaut-bilde.md)
- [donald] 2026-11-19: **Rundtour-Karte – voller Text…** → [detail](items/2026-11-19-rundtour-karte-voller-text-beim-antippen.md)
- [donald] 2026-11-19: **Fortschritt gebaut – Bilder…** → [detail](items/2026-11-19-fortschritt-gebaut-bilder-zum-ansehen.md)
- [donald] 2026-11-18 late: **delete the GitHub secret…** → [detail](items/2026-11-18-delete-the-github-secret-cf-start-token.md)
- **[donald] Only our signed apps on the map server?…** → [detail](items/2026-11-07-only-our-signed-apps-on-the-map-server-o.md)
- **Map server access before the public launch** → [detail](items/2026-11-18-map-server-access-before-the-public-laun.md)

## Later
- **Multi-day tours – concept complete, nothing built…** → [detail](items/2026-11-17-multi-day-tours-concept-complete-nothing.md)
- **Plan sheet "a third lower"** – reached only ~23 % (d2885035); more needs touch targets smaller than 48 dp, owner
  said no (2026-10-31).
- **(optional) Gradle shared remote build cache** (e.g. a GCS bucket) for donald + CI + cloud sessions.
- **(low prio) Online place search depends on…** → [detail](items/2026-10-30-low-prio-online-place-search-depends-on.md)
- **iPhone version** – direction and order in `docs/design/multiplatform.md`; core logic free of Android
  (`SharedCoreRuleTest`).
- **Open source + self-hosted map servers…** → [detail](items/2026-11-18-open-source-self-hosted-map-servers-conc.md)
- **Replace the tour's route by a saved route during a tour** → [detail](items/2026-10-31-replace-the-tour-s-route-by-a-saved-rout.md)
- **Tour suggestions — feedback captured, not…** → [detail](items/2026-11-08-tour-suggestions-feedback-captured-not-s.md)
- **Ideas:** outdoor mode, way back on own track, hut info, first-start intro / help page.
- **Map wishes (only when the owner says so):** → [detail](items/2026-11-18-map-wishes-only-when-the-owner-says-so.md)
- **Unverified paths (owner: keep):** → [detail](items/2026-11-18-unverified-paths-owner-keep.md)
- Tune the difficulty thresholds (Easy / Moderate / Difficult) with real tours.
