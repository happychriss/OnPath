# Handover cloud → laptop (2026-10-22 evening)

Everything is on `main` (last: `ebd5fea` + this file). Read this, then `docs/backlog.md` (tidied today: every entry
checked against the code; sections Next / Waiting for the owner / Later / Done) and `docs/core-features.md`.

## 1. Released today

| Version | State | Content |
| --- | --- | --- |
| **0.9.0** | released (morning, without the emulator gate – owner) | map + routing data load by themselves, world overview in the APK, junction vibration (2× left / 3× right) + lock-screen info, map ahead on tour in the background (joined after the tour), points editor, simple point menus (Start here / Google / Delete only), tour backup |
| **0.9.1** | released – **DO NOT USE: crashes at start when a plan is saved** | see 0.9.2 |
| **0.9.2** | **current**, full E2E green (run 39819215405) | 0.9.1 content + start-crash fix |

**Owner's phone:** if 0.9.1 got installed, the app crashes before the in-app updater runs → install the 0.9.2 APK from
GitHub Releases in the phone browser (or `adb install -r`). Tours are kept.

### 0.9.1 / 0.9.2 content (owner field test 2026-10-22, hike Schenna / Taser)
Fixes (each with a test):
1. **Inn vanished at some zooms** – trail-number labels (`trail-ref`, "40") were above the POI layers and won the
   collision. `tools/make_style.py`: `pois(); stations()` now after `style_trail_names.layers()`. Test
   `StyleFormatTest.poisArePlacedBeforeTrailNumbers`.
2. **Recorded track broke into pieces** – > 30 s without a fix ≤ 50 m accuracy started a new segment (drawn unconnected,
   distance not counted). Now `PointFilter.bridgesGap` (≤ 5 min, ≤ 15 m/s) keeps the segment and counts the straight
   distance; every gap → `Problems.trace("gps", …)`. Tests in `PointFilterTest`. **Not verified with real data** – see §4.
3. **Position dot under route points** (2nd time) – on a style reload MapLibre re-adds its location layers before
   `MapController` adds its own, plain `style.addLayer` landed above the dot. Now every layer goes through
   `addBelowPosition`. Guards: `PositionDotOnTopTest` (source scan: no `addLayer(` outside `addBelowPosition`) and
   e2e `assertPositionDotOnTop` (Plan, after reload, with highlight, Navigate, Plan during a tour, start with a plan).
4. **Navigate showed a route without points** – markers were drawn only when `canEditMap`. Now also in Navigate for a
   followed plan (`MapScreen` PlanDrawing), not tappable.

Owner-requested changes:
- **End tour dialog = Cancel · Delete (red) · Save** (arrival: Keep going · Delete · Save). Delete asks again →
  `AppViewModel.endTourAndDelete()` → tour to the recycle bin (`db.setDeleted`), no summary. After Save **or** Delete the
  plan is cleared (`clearPlan`, Undo restores) – owner: "I'm done with it".
- **Plan sheet (full):** one fixed height (`FULL_SHEET_FRACTION = 0.62` of the screen) – no jumping top edge; one
  scrolling area: elevation profile (finger scrub → highlight dot on the map) → Points (+ Reverse / Round trip, removed
  from ⋯) → way types & surfaces (expanded). Routing bar has a fixed slot.
- **Very long plans:** `routing/LongRoute` (straight line × 1.3, flat, `Geo.estimateSeconds`) > 24 h → `LongRouteDialog`
  "This route is very long – about N days … Plan anyway / Cancel" before anything is routed or loaded (Cancel = undo).
- **Cancel routing:** after 2.5 s "Calculating the route… Cancel"; `Planner(engine, RouteCancel)` stops BRouter
  (`terminate`), no leg-by-leg fallback; `AppViewModel.cancelRouting()` = undo the last change (owner: keep it so).
  A newer recalc also cancels the older request now.

### The 0.9.1 crash (lesson)
New `by mutableStateOf` properties (`routingSlow`, `longRouteAsk`) were declared **below** `init { recalc() … }` in
`AppViewModel`; init routes the saved plan → NPE in the constructor. Fixed by moving them up; guard
`ViewModelInitOrderTest` (every delegated property recalc() assigns synchronously must be declared above that init).
**Rule: new Compose states in AppViewModel go near `var highlight` (above the first `init` that calls recalc).**

## 2. CI / release flow (owner decisions today)
- **Release (`release.yml`)**: gate = only `StartSmokeE2ETest` (opens empty + opens with a saved plan, position dot on
  top) via `e2e.yml` workflow_call (`tests` input), then unit tests, build, publish. The `skip_e2e` input is gone.
  First run with the smoke gate: run 39821279831 – passed, whole release run 6 min.
- **Full E2E (`e2e.yml`)**: every push to main touching `app/**`, beside the release; red → fix at once.
  Concurrency group is now `e2e-<calling workflow>-<ref>` (the release's smoke run and the push run no longer cancel
  each other – that cost several runs today). Pending runs of the same queue still replace each other.
- **Emulator snapshot cache** (`actions/cache` of `~/.android/avd`, key `avd-34-x86_64-google_apis-pixel_6-v1`): boot
  is seconds instead of minutes once the cache exists.
- An attempt to remove the gate first was blocked by the session's safety check; it went through after the owner said
  it explicitly. Don't weaken gates without the owner's explicit words.
- `CLAUDE.md` "Build and test" updated accordingly.

## 3. Owner decisions today (keep)
- Point menus minimal: set point = set point, delete = delete; menu only Go here / Start here (round trip: Start & finish
  here keeps the points) / Google / Delete.
- Off-route warning and junction vibration stay during a bus ride (vehicle detection: nothing during the ride, summary
  with four choices at End tour – `docs/design/vehicle-detection.md`, agreed, **not built**).
- Tests run in UTC (owner moves between places).
- Releases are batched (several things per release, one phone test).
- Long plans: only the > 24 h question (A1), no download-size question.
- Cancel routing = undo the last change.
- Multi-day tours: later, not now (the 24 h line is where it would hook in).

## 4. Open – needs the laptop / phone
1. **Track gaps – check with real data.** Pull the GPX of today's tours (`Documents/OnPath` on the phone, or My routes
   → Export) and the diagnostics export (Settings → Diagnostics → Export): are there `<trkseg>` breaks / time gaps
   > 30 s at the spots of the owner's screenshots (Schenna, below Gasthaus Egger / near Greitererhof, ~13:30–13:45)?
   Real GPS loss or accuracy > 50 m? From 0.9.2 every gap is traced ("No usable GPS fix for N s …").
2. **"Route without points while a tour runs"** (owner screenshot 14:52, Plan mode, "No points yet", followed blue line
   still drawn, tour 41 m / 16:50). Most likely the plan was cleared / points deleted during the tour (by design the
   tour keeps following; Undo would restore). Confirm from the diagnostics trace (plan edits are traced); if the owner
   didn't delete anything, it's a bug. UX idea if it is by design: in Plan during a tour with an empty plan, offer
   "Show the followed route's points" / restore.
3. **Phone test of 0.9.2** (owner): fixed sheet height, scrub on the profile in Plan, End tour Delete, long-route
   question, Cancel, points in Navigate, dot on top.
4. **Smoke gate:** done – run 39821279831 passed (6 min).

## 5. Next (from the backlog, owner order)
1. **Bus / car detection** (`docs/design/vehicle-detection.md`) – build next.
2. **Approach 1 "the router decides, the map shows it"** (`docs/design/map-vs-router-audit.md`, handover-2026-10-22-laptop
   §3.6) – owner reviews renders before main. Laptop is the natural owner (pipeline + renders).
3. Battery batch 2; arrival forecast blend after plan edits; safety audit batch B (amber warning line + route warnings);
   track-safety leftovers (raw exception texts in messages, Photon exact position, planning total budget – partly done by
   Cancel, Paparazzi/lint in CI, compass accuracy, DEM ascent); smooth Plan ⇄ Navigate; EN + DE strings.

## 6. Ideas noted today (not decided)
- After Cancel / long-route question: a "plan in stages" (multi-day) later.
- Plan during a tour with an empty plan: show / restore the followed route's points (see §4.2).
- Learned pace: a deleted (End tour → Delete) tour is still learned from (`learnAcrossTours` runs in the service before
  the app moves it to the bin) – consider skipping it.

## 7. Known small problems
- `ScreenshotTest.recycleBin` snapshot changes with the date (relative "deleted N days ago") – re-recording shows a diff
  every day; it was left unchanged today. Pin the clock in that test.
- E2E `RoutingDataFreshInstallE2ETest` is skipped unless `routingDownload=true` (by design).
- Node 20 deprecation warnings in Actions (checkout/setup-java v4 …) – bump actions some time.

## 8. Owner manual tasks
- Delete remote branches: `downloads`, `e2e`, `fix/arrival-estimate`, `wip-build2`, `wip-map-style`, `wip-0.6`,
  `wip-no-silent-errors`.
- `gsutil rm gs://onpath-maps-onpath-sources/osm/trentino_alto_adige.osm.pbf` (map-hosting §7g).
- Answers pending in backlog "Waiting for the owner": attribution style, remove "Update maps now", search list mixing.
