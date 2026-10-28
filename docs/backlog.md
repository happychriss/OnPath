# Backlog (shared by the laptop, desktop and cloud sessions)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**.

## In progress
<!-- - [laptop|desktop|cloud] short description – since YYYY-MM-DD -->


## Next (open, checked against the code 2026-10-23)
- **Field bug (0.11.7, bike, 2026-10-28 evening): roundabout sign never shown** – 2–3 roundabouts ridden, the turn card
  showed only left / right. BRouter does deliver them: 25 random Hilden bike routes (JVM harness, E5_N50) gave 13
  ROUNDABOUT cues with exits 2–3, mostly within ±40 m of a drawn turn (so `turnHint` should pick them). Next: the
  owner's planned + ridden GPX and the trace ("Followed line vN: X junctions, R roundabouts" since 0.11.9) – 0 → the
  followed line had no turn data (GPX track? old session?); > 0 → the screen logic. Test first, then fix.
- **Position on the route ("snap to route") as an option (owner question 2026-10-28, like Komoot)** – today the dot
  is always the GPS fix. Idea: Settings → Navigation "Position: on the route / GPS"; on the route = the dot drawn at the
  matched point on the followed line (`Navigator` progress → `NavLine` point) while within ~25 m of it, the real fix as
  soon as one is off the route (so leaving it is seen); recording always GPS. Owner to choose the default.
- **Planned-route GPX has no route points (owner question 2026-10-28)** – a planned route exports one `<trk>` with
  the routed line (lat / lon / ele, no times) and the sport `<type>`, but no `<wpt>` for its route points (`exportTour`
  passes none for PLANNED), so a re-import cannot rebuild the plan's points. Idea: write them as `<rte>` / `<rtept>`
  (or `<wpt>`); fits "GPX import as a plannable route" (Later).
- **Shown GPX track: keep the square export button? (owner question, 2026-10-28)** – 0.11.6 removed "Export GPX"
  from the plan ⋯ menu; the track panel (`Panels.TrackPanel`) still has its export button beside "Start navigation".
- **Re-routing: smarter choice of where to rejoin (owner 2026-10-28; the release after next)** – today
  `navigation/RerouteTargets` offers the next stop (≤ 5 km ahead) and line points 500 / 1500 / 3000 m ahead, and picks
  among them (docs/plan-auto-reroute.md §5). Owner question: when must the way back go to the next route point, and when
  should it skip it and go to the one after (much closer from where one is now) – or ask? Work from real cases: the
  owner's bike ride of 2026-10-28 (events trace + recorded track) and sample routes the owner will upload. Ideas:
  skip a point when the detour to it is far longer than to the next one and it is not POI-pinned / a named stop; ask
  only when the difference is large and the point might matter (named, pinned).
- **Navigate over the lock screen (owner 2026-10-28, bike)** – with the screen off during navigation, a short press of
  the power button should show OnPath's Navigate map at once, without unlocking (like Google Maps / Komoot). Today only
  the notification on the lock screen shows the next junction; the app has no `setShowWhenLocked`. Idea: while a tour
  with a followed route runs, MainActivity `setShowWhenLocked(true)` (map only; Plan, Settings, My routes need unlock).
  Fits with "screen off when no turn is near" (wake shortly before a turn: `setTurnScreenOn`).
- **Navigate: pinch zoom vs direction-up – check on the phone (0.11.6)** – 0.11.6 sets MapLibre's
  `trackingGesturesManagement(true)` (zoom around the dot, higher two-finger move threshold); a deliberate turn still
  ends direction-up (owner wants that). If a pinch still drops it, the events trace names the gesture.
- **Field bug, bike: turn vibration never comes (owner 2026-10-28; phase 2)** – no vibration before turns, app on
  screen or locked. 0.11.6 added the 3-way setting (Off / Always / Screen locked), 1 = left / 2 = right and trace lines
  ("Followed line vN: X junctions", "Junction cue: … vibrated=…"). Next: read the trace of the next ride – 0 junctions →
  a followed GPX track (no junctions) or lost turns; cues without "vibrated=true" → the vibrator; no cue lines → cue
  logic. Core feature rule: a test that reproduces it, then the fix.
- **Screen off while cycling when no turn is near (owner 2026-10-28, idea)** – today "keep screen on" (Settings →
  Navigation, `KeepScreenOn` in `PocketScreen.kt`) keeps it on the whole time, which costs battery on the bike. Wanted: a
  setting so the display goes off when the next turn is far away (e.g. > 500 m / 1 km) and wakes again shortly before it
  (plus the junction vibration as today). Owner to choose the distance and whether it is the default for bikes.
- **App colours closer to the website onpath.earth (owner 2026-10-27, Drive order A-009)** – `ui/Theme.kt` only: primary
  `#1F6E43` → `#245F46`; primaryContainer minty `#DDEFE3` → a warmer olive light (≈ `#E6EDDC`, a bit stronger than the
  website's `#EDF1E3` for sunlight); background `#F7F8F5` → `#FAFAF6`; text towards `#243C31` only if contrast stays ≥ 7:1.
  **Not** the map / route colours, not warning / error colours, not the app icon. Paparazzi before/after, check contrast.
  Suggested timing: with or after the German UI pass. Palette source: repo `happychriss/onpath.earth` `docs/COLORS.md`.
- **Route hints: open candidates (desktop, 2026-10-26, numbers in `docs/design/bike-routing.md` "Route hints")** –
  (1) *faint trail* (trail_visibility=bad / horrible): 2 % of hikes, 11 % of MTB routes – a hint for hikers? owner
  question; (2) *ferry name / landing* in the hint: BRouter's data has no names – would need the ferry from our map
  overlays / POIs; (3) *times and seasons* (access:conditional, opening_hours, seasonal closures) and *borders* are not in
  BRouter's lookups – would need our own data (overlay pipeline); (4) hints in Navigate (e.g. "Ferry in 2 km") – Navigate
  has no hint list today; (5) MTB pushing noise – done (see Done); (6) Bike / Gravel routes over smoothness=
  very_horrible tracks (2 % of routes, up to 1.4 km) – a profile question, not a hint. Not planned: bollards, gates, lift
  gates, cycle barriers (on 12–67 % of routes, passable), way access=destination / agricultural / no (mostly opened again
  by bicycle / foot tags), node bicycle=no (crossings), toll (only ferries).
- **Map cells: partial rebuild (desktop, 2026-10-26, map-hosting.md §7h)** – a look-only schema bump rebuilds every
  layer today. Per-layer schema in `cells.py` / the manifest; `job_build.py` reads the current manifest from R2 and, when
  only some layers changed and the extract date is the same, `build_cell.sh REUSE=…` cuts and rebuilds only those
  (overlays: cut + overlays + tiles) and copies the rest (R2 CopyObject, sha256 kept; terrain / contours never depend
  on OSM). Measured Hilden 10-531-342: cloud 9.7 min full → ~4.2 min overlays-only; donald 179 s → ~80 s. Backward
  compatible (the app ignores unknown manifest fields and downloads all files; copy, don't point into old folders –
  cleanup deletes them).
- **[laptop] Does the plan sheet follow the finger 1:1 on the phone? (desktop, 2026-10-26)** On the CI emulator it moves
  only 0.69–0.80 of the injected finger travel (see Done: E2E swipe flake). Every release now writes "Sheet let go at
  X px (steps a / b / c px, v px/s) → step N" to the events trace: drag the handle a measured distance on the Pixel 8
  and compare. If the phone trails too, the sheet lags the finger (field 0.8.9 "the swipe is jumping").
- **Bike routing follow-ups (desktop, 2026-10-26, after the merge):** (1) and (2) done (see Done); (3) requirements.md –
  see "requirements.md sync" below; (4) answered (owner 2026-10-26): short ferries automatically, long only with a point
  on them (as today), plus a ferry hint on the route (see Done: route hints).
- **[for the cloud / owner] requirements.md sync (senior review, desktop 2026-10-26)** – `docs/requirements.md` is the
  copy of the shared Claude doc, so the review did not edit it. Needed in the shared doc: (a) activity table (line ~88):
  "Bike – e-bike: Same as trekking" → E-bike routes like the Touring bike (touring.brf: paved bike ways, quiet ways,
  traffic and rough gravel avoided), assist ~25 km/h; activity names as in the app (Touring bike, Trekking bike, E-bike,
  MTB, E-MTB, Hike); (b) F-GPX-08 / a new F-GPX id: Komoot's `<trk><type>` preselects the activity on import (T level
  kept inside the type's band), OnPath writes `<type>` on export; (c) F-PLAN-06 "difficulty": the label counts distance
  + ascent and trail technique (hike T3+, bike S2+ / T3+), hike bands like Komoot (easy ≈ 2 h and ≤ 300 m up).
- **[for the cloud / owner] requirements.md: activity names Bike / Gravel bike (owner decision 2026-10-26)** – the app
  now says Bike (was Touring bike), Gravel bike (was Trekking bike), E-bike, MTB, E-MTB, Hike; the shared doc's
  activity table still uses the old names (see (a) above).
- **Map: paths look interrupted at junctions (owner 2026-10-25, Hilden screenshots vs Komoot)** – the drawn
  footways / sidewalks stop short at streets, Komoot draws them connected. Routing is not affected (the router has its
  own data, fully connected). Leads: Hilden has many `footway=crossing` / `sidewalk` pieces (in one 1.3 × 1.3 km box: 162
  crossings, 172 sidewalks); `extract_overlays.py` leaves sidewalk / crossing out of paths.geojson and trails, and the
  crossings may be hidden under the road casing. Check with a headless render of a Hilden cell (z15–17) before changing.
- **Hilden routing follow-up (owner 2026-10-25):** footways without a bicycle tag (Itter path) –
  our trekking / touring profiles avoid them (push penalty). Option asked: allow paved footways for trekking /
  touring with an amber "footway – may need to push" note. The false "no way" gap is fixed (283bcaf, Planner spurs).
- **Simplify messages + UX + text sizes (owner 2026-10-24, review `docs/review-simplify-2026-10-24.md`) – answers so
  far:** (1) the system A as proposed: INFO grey text, ACTION blue strip only for questions, WARNING amber + ⚠ tappable,
  ERROR red with one way out; dialogs only for decisions that can lose something, verb buttons; (2) type scale 12 / 14 /
  16 / 22 / 30 bold (nothing a hiker must read below 14; Navigate time 22, turn distance + arrival 30 bold); (3) C1
  off route = ONE amber card "Off route · 120 m north-east" with a big arrow, duplicates removed; (4) C2 never ask
  "Allow all the time" (only the hint in Settings → Navigation); (5) C3 Navigate recording line only when it matters
  (Paused / No GPS), else the orange dot; moving / breaks in the opened card. Done already: "Overview only" hint hidden
  while the strip asks for the map of here (field 0.10.2 overlap), strip text shortened. (6) C4 pace note in plain words
  ("Faster than planned uphill"); (7) C5 one release APK only: developer tools hidden until the version in About is tapped 7× (remembered) – no dev variant (owner 2026-10-24); (8) C6 Diagnostics count grey ("For bug reports"), red only while a new error is unseen.
  (9) C7 Settings list in two groups: "Your settings" (Pace, Points of interest, Map, Navigation, Offline maps, Backup)
  and a smaller "App" group (Updates, Privacy, Diagnostics, About); (10) C8 statuses without a decision (offline, waiting
  for position, …) as a quiet grey pill under the search bar, no ×; blue only for questions; (11) C9 progress
  ("Importing GPX…", "Preparing the export…") as a spinner where it was started; the top bar only for a result that is
  not visible or an error. Built and released in 0.10.3 (cloud).
- **[for the laptop – 2026-10-23 late] Two MainActivity instances at start – find the trigger, then guard.** Seen only on
  the first start after an install (10-22 22:50 + 22:51 after `adb install -r && monkey -p com.onpath.app 1`; 10-23
  20:45 = 0.10.1 start): one process, two activities, each its own ViewModel; the first ViewModel is cleared ~1–2 s later
  while routing. Read in code: singleTask, no `recreate()` / `finish()` / CLEAR_TASK at start, notification
  PendingIntents SINGLE_TOP/NEW_TASK – so the trigger is in the launch path (system / launcher / installer), not in our
  code; not proven. Done in `36bb950`: the older ViewModel no longer clears the newer one's `MapCells.relevance`
  (`releaseRelevance`, CellDownloadTest), and the event log now has "Screen #n created (task, root, restored, intent
  action/flags, referrer)" / "Screen #n destroyed (finished / configuration change / by the system)"; a second live
  screen is a WARN in Diagnostics (`debug/ScreenLifecycle.kt`). **Next:** after the next install on the Pixel read
  `scratchpad/a.sh ev 40` → the "Screen #" lines name the second launch. If it is a plain MAIN/LAUNCHER start while #1
  lives: guard in `onCreate` (another screen alive + launcher intent → `finish()` the new one). Bigger, more robust
  option (owner OK first): one app-wide state holder instead of a ViewModel per activity. Still doubled while it
  happens: plan restore + route, search index, style load (+70–150 MB native), location tracker, cell requests.
- **[for the cloud – owner 2026-10-23, start 2026-10-24] Web planner, as one codebase where possible.** Handover and
  proposal: `docs/design/web-planner.md` (Kotlin Multiplatform: shared logic + Compose for Web; BRouter compiled to
  Wasm with TeaVM so routes are computed in the browser – no routing server; link/QR hands the plan to the phone).
  First ask the owner the purpose (A: plan at home, send to phone – recommended; B: full planner for non-Android),
  then two spikes on a separate branch (BRouter in the browser; Compose web + maplibre-gl), report with numbers
  before any change to the app's code.
- **[for the laptop – owner 2026-10-23 evening, phone test 0.10.1] TODO:**
  1. **DONE (laptop 2026-10-23)** – cause: two activities (each its own ViewModel + MapHost) lived at once at the
     0.10.1 start (event log: "Plan restored at app start" twice in 1 s, Style load #1 and #2 with the native heap at
     93 MB). They shared process-wide state: the short-lived host's onDispose cleared `reloadRef`/`ctrlRef` after the
     other had set them → every later reload a silent no-op (styleVersion 5, no "Style load", `map: not ready`); and the
     ViewModel's "map behind the data?" check read the global `StyleLoader.lastMapDir`, which another host may already
     have moved on. Fix: per-host state `ui/MapReload.kt` (`MapHostView`: what this map shows, its own reload/controller;
     `OwnedRef`: only the owner clears; `StyleReloadRule`: each ViewModel asks by what it asked last), catch-up reload
     per host (MapReloadTwoHostsTest). Also found on the Pixel: leaving the app while the camera still moved (app camera
     moves count as a gesture) left `userBusy` stuck → downloaded areas waited unjoined until the app was opened again;
     `MapCells.setForeground(false)` ends it (CellDownloadTest.leavingTheAppEndsAStuckGesture). Screen-off / background
     reloads themselves worked on the Pixel (style loads while stopped are fine). Not phone-verified after the fix
     (install blocked at the end) – check on the next build.
     Original report: **New map areas never showed until an app restart (bug).** Hilden areas 10-531-341/342 were downloaded and joined
     (generations 49–53, 20:52–21:21) – the map stayed blank beige at Hilden; force-closing the app fixed it. Diagnostics
     (exported 21:36): `styleVersion` 5 (the reload was asked 5×) but NO "Style load #n" trace after 20:45 (0.10.1 start);
     `map: not ready` in the export (ctrlRef null; the export runs with Settings open). Health: "no frames drawn".
     Not found by reading the code (MapHost single instance; QuietGate ok). Cloud added (not released yet): a trace on
     every reload ask ("Map style reload asked …") and every load ("Style load #n asked / done"), MapHost only clears the
     global refs it owns, a warning when a reload is skipped (map not ready), and a catch-up reload 20 s after the app is
     back on screen / a new generation when the map still shows an older store. Please reproduce on the Pixel (adb
     logcat -s OnPath): plan in a new area, let areas load (screen off / app left for some minutes, MapLoadService
     notification), come back – does the map switch? Then find the root cause.
  2. **DONE (laptop 2026-10-23)** – the 2 areas were new corridor neighbours (10-532-341/342, joined at g54 21:59 and
     22:08), not installed ones; the server prepared them ~10 min and the strip counted only downloaded bytes → 0 % the
     whole time. Fix: the strip's % uses the loading window's time-based progress per area (`MapLoadProgress`), and a
     plain "newer version?" check of an installed area (Requested, no server answer yet) is not counted as loading
     (BackgroundDownloadsTest). Original: After the restart the blue bar showed "Loading map · 2 areas · 0 %" for areas that were already there – check why
     (refresh / corridor re-request?) and whether the 0 % stays.
  3. ~~Saved name on a plan in Hilden~~ **done (laptop 2026-10-23):** `RouteRules.linkedToSaved` – the plan belongs to the
     saved / loaded route while at least one of its points is one of that route's points; after Clear or with every
     point replaced: no saved name, Save asks for a new name; Undo back restores the link (`PlanLinkTest`).
  4. ~~Point names~~ **checked (laptop 2026-10-23), no bug:** "intensiū" is a genuine OSM name (restaurant, Hilden,
     Poststraße 42, node 10736819730); "Unterstand" is the German kind label of an unnamed shelter added as a point
     (`Place.title()`).
  5. ~~Sights and viewpoints on by default~~ **done (laptop 2026-10-23):** churches and chapels left `defaultHidden`
     (viewpoints, castles, ruins, caves, museums were on already); a stored set equal to an old default is upgraded
     once (`PoiKinds.upgradeHidden`, `poiDefaultsVer`), a set the user changed stays (`PoiDefaultsTest`).
  6. ~~Restaurant POIs change while zooming (owner, Bad Waldsee, 0.10.1).~~ **Done (laptop 2026-10-23), needs the map
     rebuild after the next release:** every POI gets a fixed priority `pr` (`extract_pois.priority()`: tag importance –
     wikipedia/wikidata, website, opening hours, phone, tag count – plus a position hash as tie-break), sort key =
     rank × 100000 + unnamed × 50000 + (49999 − pr) (StyleFormatTest, `tools/test_extract_pois.py`). Cell schema 4
     (builder + doorman; app stays 3, s3 cells keep today's order and are served while rebuilt at refresh priority).
     Headless maplibre-gl check (Meran Laubengasse, Bozen Lauben, z13 → 17): POIs vanishing while zooming in 10 → 5 and
     9 → 7; the rest comes from labels appearing at the label zoom (z14 / z15) and variable label anchors – not ties.
     Still to check on the Pixel at Bad Waldsee once the s4 cells are there.
- **Text + UX review 2026-10-23 (cloud, in progress)** – reports `docs/review-texts-2026-10-23.md`,
  `docs/review-ux-2026-10-23.md`. Owner answers so far: texts Q1 languages: keep as is (EN + DE come at the end);
  Q2 last point = "Destination" (English standard; round trip keeps "Start & finish here"); Q3 middle points = "Stop"
  (Google Maps standard); Q4 recorded line = "Track" everywhere ("Track saved", "Delete this track?"; "End tour" stays);
  Q5 never name routing data (it is part of "the map"); Q6 activity names Touring bike, Trekking bike, E-bike, MTB,
  E-MTB, Hike everywhere (chip may shorten); Q7 Time details formulas → Settings → Diagnostics, ⋯ keeps moving time,
  with breaks, pace level; Q8 "Direction of travel" instead of "Walking direction" (North up / Turned by hand stay);
  Q9 new-problem pop-up = fixed sentence "Something went wrong. It was noted in Settings → Diagnostics."; Q10 tour bar
  button "End tour". UX Q1 activity change in Plan during a tour opens the same check sheet as Navigate (Take / Keep);
  Q2 "Clear route" allowed during a tour (= delete all points: guidance ends, recording goes on, Undo); Q3 search
  "As start" behaves like the map's "Start here" (drops the points before); Q4 unsaved plan at End tour: keep as is
  (Undo only); Q5 ride summary also on arrival Save and on the interrupted-tour Save (when a ride was found); Q6 Wi-Fi
  only: a one-time "Load now over mobile data" in the strip / loading window; Q7 after following a track, an edited plan
  makes "Continue" follow the plan (track drawn grey); Q8 Undo and Redo both as map buttons (Redo only when possible).
  Built and merged 2026-10-23 (cloud) – see Done.
- **Battery batch 2 – rest** (`docs/design/battery-audit.md`): slower GPS at rest is done (0.10.0, `RestWatch`); open:
  slower GPS with the screen off, frozen track + tail. The GPS slow-down rules are under discussion with the owner
  (2026-10-23). No battery % in the tour summary (owner decision A10).
- **Router classes – open:** the app does not compare the cells' `wayclass` hash with its own profiles (a profile
  change needs a rebuild of all cells); tertiary and bigger roads have no class (`docs/map-style-notes.md`).
- **Safety audit batch D – rest:** cliffs and fords on the map.
- **CI:** `verifyPaparazziDebug` and lint do not run in CI.
- **Compass accuracy gating; DEM-based ascent** (today GPS altitude with 5 m hysteresis).
- **Small:** Navigate "Waiting for GPS · last fix …" line may be cut (`NavigateScreen.recordingLine`, maxLines 1 – check
  on the phone); Ifinger T1 JVM test (`tools/routing/jvm-test/RerouterJvmTest.kt:90` expects
  "unreachable", BRouter found 7.2 km – expectation or gap?); zip import checks only sizes (cell downloads check
  sha256); `actions/checkout@v4` / `setup-java@v4` still in `e2e.yml` and `release.yml` (Node 20 warnings);
  `tools/themes-proposals/` is history (rejected a/b/c themes, `roads-post.py` reads a `styles/d-merged.json` that is not
  in the repo) – archive it together with its links in `docs/design/map-colour-analysis.md`; code kept alive only by tests
  (e.g. `NavLine.bearingAt`, `PlanEdits.endAt`, `RouteProgress.visitedStops`, `Poi.osmUrl`) – fine as tested helpers,
  delete when touched.
- **Owner, by hand (unverified):** delete the unused Trentino extract from the GCS source bucket – `gsutil rm` command in
  `docs/design/map-hosting.md` §7g.

## Waiting for the owner
- **Map server access before the public launch** (owner discussion 2026-10-23, laptop): today one shared app key
  (readable from the APK) + IP rate limit + build caps. Proposal in `docs/design/map-access.md`: anonymous per-phone
  quota of *new* areas per month (hashed Android ID, no registration), no region lock, supporter code raises the limit,
  separate production key with rotation. Owner decides limit size, supporter platform, timing.
- **Google Play internal test track** (owner A11: after vehicle detection + approach 1 – both done): create the app,
  secret release key + `PLAY_SERVICE_ACCOUNT_JSON` in GitHub secrets, upload from release.yml (today: fixed test key).
  Removes Play Protect warnings.
- **Field tests:** 0.10.0 was released without a separate phone test (owner B10) – map colours from router classes,
  route warnings, End-tour ride summary, "Route back to the start?", slower GPS at rest, Settings pages. Older, still
  unconfirmed: re-routing P0–P4, arrival / time estimate, junction vibration + lock screen, map freshness.

## Later
- **GPX import as a plannable route, with as few points as possible (owner 2026-10-28; for the release at home, not
  the next one)** – on importing a GPX (ridden before or received), ask: *load as GPX track* (follow it as today) or
  *load as a plannable route*. The second turns the track into route points so it can be edited like a plan. Smart:
  the fewest points for which our router (activity profile) reproduces the track – not 1000 points. Idea: start with
  start + end, route, find where our route leaves the track by more than ~30–50 m, add one point there (on the track,
  ideally at a junction), route again; repeat until the route follows the track (greedy, like Douglas–Peucker but
  checked with the router). Round trips: start = end plus one point far along. Show how close the result is ("follows
  the track to 98 %") and keep the original track as the grey GPX line to compare. Needs tests (synthetic tracks,
  JVM routing harness) and a cap on routing calls per import.
- **iPhone version (owner 2026-10-26)** – not now. Direction and order in `docs/design/multiplatform.md`: EN + DE via
  Compose Multiplatform resources (not Android `res/values`), module split on a quiet release, BRouter on iOS only
  when iOS is a real goal. Rule from today: core logic free of Android (`SharedCoreRuleTest`).
- **Open source + self-hosted map servers – concept (owner 2026-10-21, not built):** app setting "Map server"
  (default = owner's server; custom URL + key, also via QR code; cells carry the server in their manifest, no mixing);
  `/v1` API as an open spec; (A) all-in-one Docker Compose, (B) the cloud setup with gcp-setup.sh; builder image on
  ghcr.io. Pricing stays on the owner's server; self-hosted = free; F-Droid build without Play Billing. Not before Play launch.
- **Bilingual app EN + DE – coming soon (owner 2026-10-23, replaces B11 "not needed"):** all UI texts to
  `strings.xml` in one go, following the phone's language – as **Compose Multiplatform resources**
  (`composeResources/values*/strings.xml`, owner 2026-10-26, so an iPhone version later reuses them;
  `docs/design/multiplatform.md`). Until then don't change single texts' language (e.g. the
  German search chips "Hütten, Gipfel, Wasser, Orte, Stationen" stay as they are) – fix mixed EN/DE in that pass.
- **Ideas (owner 2026-10-21):** outdoor mode, way back on own track, hut info, tour suggestions, first-start intro / help
  page. Dropped (owner B12, "focus on the core"): sunset warning, emergency screen, battery check at start, weather.
- **Map wishes (start only when the owner says so):** shrub ("Sträucher") symbols; trail numbers abroad beyond numeric
  refs (letters like E5 / GR20, `osmc:symbol`); contours below z12 (owner chose "from z12" on 2026-10-20).
- **Unverified paths (owner: keep):** untagged OSM ways joining two paths, ≤ 300 m, grey dashed + point-menu hint,
  never routed. Example way 330547188 near Algund.
- Tune difficulty thresholds (Easy / Moderate / Difficult) with real tours (long T2 and unpaved Bike rules: decided
  2026-10-26, see Done).

## Owner decisions 2026-10-25
- **Difficulty like Komoot (owner: "Yes, both"):** the label counts trail technique (hike T3+, bikes S2+) besides
  distance + ascent, and Hike "Easy" is tightened to Komoot's band (≈ 2 h and ≤ 300 m up; Moderate ≈ 5 h and ≤ 900 m).
  Built, see Done.
- **Komoot GPX import preselects the activity (owner: "Yes, preselect"):** from `<trk><type>`; unknown → last used
  activity; the user can still change it; export writes `<type>` too. Built, see Done.

## Owner decisions 2026-10-22 (kept for reference – all built unless marked)
- **A – laptop Q&A:** (1) attribution: short "© OpenStreetMap" on the map, tap → full credits; (2) keep "Update maps
  now"; (3) approach 1: route-band sections above the user's T level dashed/greyed; (4) approach 1: three levels per
  activity – green = good, black = possible, red/dashed = forbidden ("avoid" looks like black; refined by B4);
  (5) search: one mixed list (best first, duplicates removed); (6) addresses show the village ("Völlan"), municipality
  small on line 2; (7) shared place positioned from the address → small persistent note "Position from the address";
  (8) pace: learning per tour only – every plan and tour starts from the Settings pace, during the tour the arrival
  adapts gradually after ~15 min / 1 km (down / flat / up separately, breaks excluded), nothing carried to the next
  tour, cross-tour learning (PaceStore) dropped, no "change your setting" suggestions; (9) deleting the goal of a 2-point
  plan during a tour → persistent question "Route back to the start?" (Yes = route from position to start, No = plain
  recording); (10) battery: slower GPS when standing still (never lose a point), NO battery % in the tour summary;
  (11) Play internal test track later (after vehicle detection + approach 1) – **waiting**; (12) safe ✕ in the points
  list (the list doesn't shift under the finger) + deleting all points ends the route guidance (tour keeps recording;
  route gone in Plan and Navigate; Undo restores) – what Plan shows = what Navigate follows; (13) Navigate shows only
  the route line – no numbered plan points (reverts 0.9.1 fix 4; Plan keeps the points).
- **B – laptop, later:** (1) pace: 5 levels (Relaxed · Easy · Average · Fit · Very fit, DIN 33466 descriptions), table B
  kept as the hiking model, e-bike / e-MTB flat speed capped at ~25 km/h; (2) far plan points (≥ 150 m from the route)
  stay where placed + grey dashed gap to the route; (3) unreachable notice = red bold line in the plan sheet (+ Show), no
  extra action; (4) map: difficulty = grey line (incl. via ferrata, MTB steps), red only for access bans, via ferrata
  keeps its ticks; (5) hill shading "balanced" + format-2 terrain to z13; (6) Offline maps "Clean up" (keeps plan, saved
  routes, running tour, current area; shows what is removed first) and a red "Clear all" (second confirmation);
  (7) Settings as a topic list – no switches on the main page, each topic its own page; (8) Privacy page + online search
  sends only a rough position (~1 km); (9) route warnings: one amber line in the plan sheet + list of spots, "Route
  changed for …" notice after an activity change; (10) release 0.10.0 without a separate phone test; (11) German UI: not
  needed for now; (12) ideas dropped – sunset warning, emergency screen, battery check at start, weather.
- **Vehicle detection** (`docs/design/vehicle-detection.md`): nothing during the ride (warnings + junction vibration stay
  on), summary with four choices at "End tour", raw points kept.

## Done (recent)
- **0.11.8 (cloud, 2026-10-28):** search opens empty; far big cities found (Photon places request + exact-name bonus);
  a very long plan's new point loads nothing until "Plan anyway"; T4 corridor capped at 30 regions
  (`CellTriggers.CORRIDOR_MAX_CELLS`); export names "… - planned / recorded.gpx". Still to check with the owner's
  trace: the brief blank map after the New York test (not reproduced here).
- **0.11.7 (cloud, 2026-10-28):** dot jumps to the current position after a pause (`map/PositionDot`, fresh last GPS
  fix on start in `LocationTracker`), larger dot, softer / smaller turn badge (`TURN_HINT_COLOR`), U-turn sign on the
  map (`NavState.uTurnAt`, until 40 m along the new line), roundabout exit from BRouter (`OnPathHints` 4th value →
  `GeoPoint.exit` → session file "RNDB:2" → turn card "Roundabout · exit 2" + lock screen). Check on the phone: the
  exit number on a real roundabout (only unit-tested with synthetic rows – no routing data in the cloud).
- **0.11.6 bike field fixes (cloud, 2026-10-28):** pinch zoom keeps direction-up (`trackingGesturesManagement`), start
  zoom Hike 17 / bikes 16 (`AppMode.navStartZoom`), time + km to go side by side (`Format.durationShort`, FlowRow,
  `NavStripScreenshotTest`), junction vibration Off / Always / Screen locked + 1 = left / 2 = right + trace, "turn
  round" in the reroute question (`TurnRound`), no Export GPX in the plan ⋯, Wi-Fi fix when GPS is weak
  (`useNetworkFix` / `showGpsFix`). The "take new route does nothing" report was a way back on the same road.
- **[desktop] Bug: an old plan link came back after a reinstall (2026-10-27):** Android restarts the screen with the
  task's first intent (a VIEW link handled long ago) – it was handled again ("This plan link can't be read").
  `IncomingIntent`: a link / share / GPX is handled once (fingerprint of the last one in the prefs); onNewIntent always
  handles, another link on a cold start too. Test `IncomingIntentTest`.
- **[desktop] No dark message bar (owner 2026-10-27: "die komischen schwarzen Status-Codes … rausnehmen"):** the ~45
  producers of the old top bar now say where the user acted – `vm.say(text, NoticeKind, NoticePlace)`, pure
  `NoticeRouting`: a grey INFO / red ERROR line at the top of My routes or the open Settings page (gone when the screen
  is left); an ERROR without its place (recording / backup in the background, a crash, a plan link / shared place / GPX
  opened from another app, map buttons) → the one light `ErrorCard` (red edge, ⚠, OK, never by itself); an INFO without
  a place → problem log. Dropped: "Track saved to My routes" (it is in My routes), "moved to the recycle bin", "Copied",
  "route already ends at the start" (⋯ hides Round trip then). Route changed by newer routing data → the plan sheet's
  grey "changed" line (tap = whole route); GPX parts skipped → grey line in the track panel; "Update maps now" → the
  Offline maps result line; restore from the first-start offer opens My routes with "… restored". Tests `NoticesTest`.
- **[desktop] Plan links (A-005, web planner phase 1, core feature C15):** `https://plan.onpath.earth/#p=<code>`
  (PlanCode v1 from `feat/plan-code`) opens as the plan – camera / QR code and tapped links (intent filter, App Link
  `autoVerify`, path `/`), text shared to OnPath, a link pasted into the search box; loaded like a saved route (points,
  activity, T level, names, pinned points; "Replace current route?" with unsaved changes; not during a tour – grey
  line); broken link → red line, nothing changes. Sheet ⋯ → Share plan writes the same link (`PlanLinks`). Open: the
  cloud publishes `/.well-known/assetlinks.json` on the planner host (test key SHA-256 in the A-005 report); until
  then Android opens the links in the browser unless "Open by default" allows them for OnPath.
- **[desktop] No pushing / steep hints for MTB and E-MTB (owner 2026-10-26: "remove the rules for MTB – we don't want to
  look stupid"):** the amber line was on 87 % of MTB routes, 75 % "… m pushing". `RouteWarnings`: MTB / E-MTB get no
  "steep" and no "pushing" (unsigned footways, T4+ / S4+ trails). Signed ways stay as legal information (owner, same
  day: keep them, not as pushing): `SpotKind.NO_BIKES` bicycle=no on a footway / path → "1.2 km no bikes allowed" (row
  "No bikes allowed – sign on the way"), `SpotKind.DISMOUNT` bicycle=dismount → "300 m dismount (sign)". Ferry, ford,
  construction, stile / kissing gate / turnstile and steps stay. Other bikes keep "pushing". `HintInventory.kt` (same
  90 MTB routes): amber line 87 → 55 %, pushing 75 → 0 %, no bikes allowed 43 %, dismount 1 %, steep 28 → 0 %. Tests
  `RouteWarningsTest.mtbHasNoPushingOrSteepHint` / `mtbSignedWaysAreLegalInfoNotPushing` / `mtbKeepsTheHintsToCheck`.
- **[desktop] Difficulty raised by long T2 and by unpaved Bike routes (owner 2026-10-26):** `RouteSummary.technique`
  – Hike: ≥ 2 km at T2 or harder (summed) → at least Moderate, badge "Moderate · T2". Bike and E-bike only: ≥ 25 %
  of the route unpaved (gravel, unpaved, ground, trail, alpine; whole %, ≥ 100 m) or ≥ 5 km on loose ground (not
  "gravel", which folds in compacted; or smoothness bad or worse) → at least Moderate, badge "Moderate · unpaved",
  Route details "Moderate because 31 % is unpaved (3.1 km)" / "… because of 5.4 km on loose ground". Gravel bike, MTB,
  E-MTB unchanged; a grade (S2, S3+, T3+) is named first; never lowers. Constants `HIKE_T2_MIN_M`,
  `BIKE_UNPAVED_SHARE_PCT`, `BIKE_LOOSE_MIN_M`. Tests `RouteSummaryTest` (both sides of each threshold).
- **[desktop] Opening a tour switches the activity (owner 2026-10-26, question (3) of the GPX activity review):**
  opening a recorded *or imported* tour from My routes chooses its stored activity (imports: the one their `<type>`
  gave) with the import's rules – the user's T level stays inside the tour's band (T1–T2 / T3–T5), nothing during a
  tour, no activity stored = nothing changes – and says so in one grey line in the track panel "Activity set to Bike
  from the tour" (`SportType.openChoice`, shared with `importChoice`). No "came from the file" flag: the stored
  activity counts. Tests `SportTypeTest.openingATour*`.
- **[desktop] Region view – routes by zoom (2026-10-26, owner, for 0.11.4):** for hiking and bikes, below z11 only
  long-distance routes, regional ones from z11, every signed route from z12.5 (`route_tiers()`, new `-regional` layers,
  switched by `MapController`). Below z10 is left to the region / overview data work (`routes-<kind>-major-low`).
- **[desktop] Route hints: ferry and what to check before going (2026-10-26, owner decision):** the amber line in the
  plan sheet now also says "Ferry 320 m – check times" (several: "2 ferries – check times"; every activity, first in the
  line), "construction site" ("Construction – may be closed"), "ford" / "3 fords" ("Ford – water to cross") and for bikes
  "stile" / "kissing gate" / "turnstile" ("Stile – lift the bike over"); tap → list → map. Ferry / ford / barrier /
  construction come from BRouter's way and node tags (`WayAttrs.ford / barrier / construction`). Inventory on 356 random
  routes (`HintInventory.kt`): ferry 3–6 %, ford 6–16 %, hard barriers 0–3 %, construction ≤ 1 % of routes; JVM
  `FerryHintTest` (Zons ⇄ Urdenbach, Kaiserswerth ⇄ Langst: 8 / 8 routes show the hint).
- **[desktop] Map look from the Hilden field review (2026-10-26, owner tested each step on the phone):**
  - town contrast: street edges, lighter built-up land, darker buildings; paths end at the street edge;
  - bike view like hiking: green line = good bike way, mint band = signed route (names, bicycle streets, slim band,
    lanes at the road edge, main-road edges);
  - quiet bike tracks; zoomed-out overview for every activity;
  - one-way arrow colours (new sprites); quieter forbidden ways; street space and underpasses (schema 7).
  - Docs: `map-style-notes.md`, `design/map-colour-analysis.md`.
- **[desktop] Test style on the phone (2026-10-26):** `tools/dev/push_style.sh` → `dev/style.json`, used only with
  developer tools (Diagnostics: "Test style active" · Remove · Reload map style).
- **Faster map refreshes (desktop, 2026-10-26, owner: "build here and push to the cloud"; map-hosting.md §7h):** the
  s5 → s6 Hilden refresh took ~48 min (4 cells serial in one background execution, 8–10.5 min each, then the app's
  15-min idle poll). Now: app polls an updating cell at `poll_s` (doorman 15 s); background rebuilds spread over the
  free slots (6 → 3 × 2); pre-warm of cells used in 30 days after a schema bump; dev loop `tools/dev/push_cell.sh`
  (working tree → phone, kept 24 h against refreshes); fast lane `tools/dev/publish_cells.sh` (origin/main → TEST R2,
  the doorman adopts a current published version). donald builds a dense cell in ~3 min vs ~10 in the cloud.
- **Clearer bike activity names (desktop, 2026-10-26, owner decision, labels only):** picker rows name + one line –
  Bike "Paved cycleways, quiet roads", Gravel bike "Gravel tracks welcome", E-bike "Bike with motor, 25 km/h", MTB
  "Trails and singletrails", E-MTB "MTB with motor", Hike "T1 … T5" (T row unchanged). One source `Activity.label` /
  `line` (RoutingContract.kt); buttons, messages, "not suited for gravel bikes" (`ActivityCheck.who`, now the
  plural of the name) follow it. Plan sheet chip says "Gravel" (`Activity.chip`): with "Gravel bike" a 112 km tour
  showed "11…" (rule 2026-10-22: time and distance in full). Enum names, stored values, GPX `<type>`, profiles and
  routing unchanged.
- **Quiet towns + path gap fix + forbidden ways (desktop, 2026-10-26, branch `map-quiet-towns`, owner/captain picks
  after the Phase A renders):**
  - Pipeline, `extract_overlays.py`:
    - town flag `u` on overlay paths / streets (`TownIndex`: built-up landuse + 25 m or dense buildings; 2 = town green;
      never tracks or graded trails);
    - link pieces `k` = "l" for path ends at hidden sidewalks / crossings (node-based, ≤ 30 m, no detours / spurs < 20 m
      / chains to loose ends);
    - `ar` on pedestrian squares.
  - Style:
    - Zoomed out: no town paths, `path-low` / `track-low` dropped in format 2, built-up land greyer.
    - Close in, towns: grey thin footways from z14, white town streets, pale pedestrian streets. Park paths and the
      countryside unchanged.
    - Hiking: no green on town footways.
    - Bikes: thin teal / violet route lines instead of bands; soft glow under bike-friendly ways (`*-glow`, switched by
      `MapController`).
    - Forbidden ways: tint band gone; quiet dash on service roads; private driveways / parking aisles hidden below z17;
      sparser signs.
  - Cell schema 6, look-only: `cells.py`, `wrangler.toml` and `config.js` at 6; serve and the app stay at 3. It also
    carries the bike footway-cost router classes.
  - Docs: `map-colour-analysis.md` "Quiet towns", `map-style-notes.md`, `map-pipeline.md` 5b, `map-hosting.md` §7c,
    `bike-routing.md`.
  - Tests:
    - `StyleFormatTest` (`format2QuietTowns`, `forbiddenNeverLouderThanAStreet`, updated ids) – 1207 unit tests green;
    - `tools/test_extract_overlays.py` (synthetic OSM; skipped without pyosmium);
    - worker schema tests updated (CI, Node 24).
  - Before/after renders are private (`external-docs/map-quiet/after/`). Needs maps-deploy and a phone check after the
    s6 rebuild.
- **Trekking gets Touring's busy-traffic penalty (captain decision 2026-10-26, [desktop]):** trekking.brf charges
  busy traffic classes 4+ only (+0.2 / +0.4 / +0.8), as touring.brf does; the two profiles now differ only in
  unpavedPenalty and "v_rough" (`TouringProfileTest`, `BikeProfileCostTest`). Hilden random pairs, Trekking: busy roads
  7.3 → 2.4 %, bike ways 42.5 → 48.0 %, +2.5 % length; Meran SafetyTest / OnewayTest, FerryTest, IslandTest,
  ItterPathTest unchanged (docs/design/bike-routing.md decision 3). Map router classes unchanged (profile hash only).
  Also: `GpxCompare` counts a timed-out / empty trace run as a gap (pieces looked traced when they were not).
- **Hilden Itter path: bike route left the shared path between plan points 3 and 4 (owner field report 2026-10-26,
  [desktop]):** the path (highway=path, bicycle=designated, surface=compacted) cost 5.15 (Touring 11.15) because the
  profiles treated "compacted" as loose ground; side streets were cheaper. trekking.brf / touring.brf: compacted is
  firm like fine_gravel; the +1 for a footway is now only for footways without a bicycle tag (bicycle=no / dismount
  keep +4). Leg 3 → 4 696 → 459 m on the path; guard rails held (docs/design/bike-routing.md decision 11).
  Tests: `BikeProfileCostTest` (unit), `tools/routing/jvm-test/ItterPathTest.kt`, WayClass selftest. The map's router
  classes change (the Itter path becomes "good" = green for bikes) – reaches published cells with the look-only cell
  schema 6 (quiet towns, below).
- **E2E CorePointsListE2ETest.swipeTheSheetHalfFullHalf flaky – "half sheet after a swipe down" (desktop, 2026-10-26,
  red once on 687745a, rerun green):** the message said nothing more, so StepSheet now writes one events line per
  release ("Sheet let go at X px (steps 536 / 938 / 1602 px, 0 px/s) → step N", `SheetDrag.describe`, unit tested) and
  the test's size waits fail with size, state, events and a screenshot. Those lines showed the cause: on the CI
  emulator (4–15 frames/s) the sheet moves only 0.69–0.80 of the injected finger travel, release speed 0. The last
  swipe (480 px, 20 % of the screen) thus stopped 317–367 px below the points against a 332 px midpoint – a coin toss;
  the swipe up to the points (600 px) needed a ratio ≥ 0.57. Now: up to the points 35 % of the screen (top step, only
  rubber-bands), back down 110 % of the measured points → profile gap (settles on the profile for ratios 0.47–1.22;
  CI let go at 1088–1113 px, ≥ 157 px past the midpoint). Same checks and timeouts. Why the sheet trails the injected
  finger is open (Next: check on the phone). E2E full runs 40261092003 (908cc1e) and 40262661152 (aa8307c) green; class only 40261157640,
  40261159593, 40261161546 (908cc1e) and 40262675663, 40262677839, 40262680045 (aa8307c) green.
- **One time model for imported tracks (desktop, 2026-10-26, captain decision):** `Geo.estimateSeconds` and its own
  bike speeds are gone – imported GPX without timestamps (import, My routes, the shown track; re-timed with the pace
  level like planned routes), straight-line fallbacks and the "longer than a day?" question use `PaceModel` /
  `RouteTimes`, so the same line shows the same time everywhere. 20 km flat at Average: Trekking 1:15 → 1:00, Touring
  1:00 → 0:55, MTB 1:40 → 1:15, Hike unchanged (5:00); planned routes unchanged. `OneTimeModelTest`; numbers in
  docs/plan-time-estimate.md "One time model for lines without timestamps".
- **Bike ferries + Köln Dom route (desktop, 2026-10-26):** a ferry that does not take bikes (bicycle=no, …) is now
  forbidden in touring / trekking / mtb.brf, one without foot access in hiking-mountain.brf (test set of 228 OSM ferries:
  bike routes over such a ferry 56–67 → 0, Hike 6 → 0; `FerryTest.kt`, WayClass selftest). Köln Dom → Neumarkt "no
  route" for every activity: the point snapped onto an unconnected footway inside the cathedral and BRouter's own
  island retry kept that way – `BRouterEngine` now re-snaps such a point to the next way (`IslandTest.kt`,
  `IslandPointTest`, core feature C3). Guard rails unchanged; details in
  `docs/design/bike-routing.md`.
- **Senior consistency + dedup review (desktop, 2026-10-26):** bug: every recorded tour was stored as T3 (reopening a T2
  hike set T3, its GPX said "mountaineering") – the start now sends the T level (`NewTourSacTest`); one form "Hike T3"
  for the activity in a sentence (`LastActivity.name`; Navigate said "Hike (T3)"), one `DEFAULT_SAC`, one tour-name
  format (`RideApply.dateName`); dead code removed (start-time cell refresh gone since 0.8.3, `routeOverlay` setting,
  `stripPoisIfMissing`, `PlanClear`, `batteryHintShown`, ~12 more) and 21 unused theme tokens (styles regenerate
  byte-identical). Docs: CLAUDE.md / README activity names and profiles, repo map, map-pipeline (E-bike in `cf`),
  map-hosting (schema, start-time refresh), dated fastbike notes marked, dated reviews / field tests marked as history,
  working agreement with donald `[desktop]`.
- **Bike routing tuned, merged (desktop, 2026-10-26; branch `feat/bike-routing`, verified,
  then deleted):** Touring bike + E-bike → touring.brf (trekking copy: unpavedPenalty 2.5, traffic on busy classes),
  trekking.brf tuned (ferry 1 km, footway rule, busy road on a cycle route not "perfect", off-route 0.15), Touring time
  ~21.7 km/h, WayClass threshold 1.20. **v_rough** (captain): on a cycle route a rough track (grade 3–5, smoothness bad+,
  ground / dirt / sand / grass / mud / earth / rock) is not "perfect" in touring.brf –
  Alps loose 10.4 → 5.6 %, Hilden busy roads 2.7 → 3.2 %, Meran guard rails unchanged. Review nits:
  "pushing" on a footway of a cycle route is left out only for touring.brf / trekking.brf activities (MTB keeps it,
  mtb.brf costs it as pushing), a pedestrian zone without bicycle=yes / designated stays pushing (`WayAttrs.pedestrian`);
  the old fastbike-verylowtraffic.brf is deleted from the phone on profile install. **Cell schema 5** (look-only, app
  stays 3): map router classes are rebuilt once with the new profiles. `docs/design/bike-routing.md`.
- **GPX import keeps the T level + says when the activity changes (desktop, 2026-10-26, captain "no surprises"):**
  `SportType.forImport` keeps the remembered T level inside the type's band (hike / jogging T1–T2, mountaineering
  T3–T5; else T2 / T3); `SportType.importChoice` decides the switch (not during a tour) and the grey INFO line
  "Activity set to Hike T3 from the file" in the track panel (`ShownTrack.note`), gone when the track is closed /
  replaced or the activity / T level changes. Tests `SportTypeTest`, snapshot `trackImportedActivityNote`.
- **Review of difficulty + Komoot GPX type (desktop, 2026-10-26):** formula used everywhere the label shows (plan sheet,
  Route details; nowhere else shows one), no old call left. Fixed: unit tests run in UTC (`app/build.gradle.kts`) – the
  recycleBin snapshot had changed only by the machine's time zone, not the date; clearer import diagnostics line;
  two more parser tests. Open questions above ("Waiting for the owner").
- **Way attributes lost on 22 % of planned routes (desktop, 2026-10-25, found by the GPX comparison tool):** BRouter can
  open its message list with a 0 m run at the first node; `BRouterWayStats.perNode` then returned nothing, so those routes
  had no "… m pushing" / steps hint and times without surfaces. Fixed with a test first
  (`perNodeSkipsAZeroLengthRunAtTheCurrentNode`). Also on main: the GPX comparison tool `tools/routing/jvm-test/GpxCompare.kt`.
- **Difficulty with trail technique, like Komoot (desktop, 2026-10-25, owner "Yes, both"):** `RouteSummary.rating` =
  distance + ascent (Hike now easy ≤ 8 effort km and ≤ 300 m up, moderate ≤ 20 and ≤ 900 m; bike bands unchanged),
  raised by the hardest grade with ≥ 100 m on the route (summed; less = tagging noise): hike T3 → at least Moderate,
  T4+ → Difficult; every bike S2 → at least Moderate, S3+ or a T3+ hiking trail → Difficult. Never lowers it; lines
  without way attributes rate on fitness only. Badge "Moderate · T3" when technique raised it; Route details adds a grey
  line "Moderate because of 250 m at T3 or harder". Tests `RouteSummaryTest`, snapshots `planTechniqueBadge`,
  `routeDetailsTechnique`.
- **Komoot GPX `<type>` → activity (desktop, 2026-10-25, owner "Yes, preselect"):** `gpx/SportType` – touring_bicycle /
  racebike / e_racebike → Touring, e_touring_bicycle → E-bike, mtb_easy → Trekking, mtb / mtb_advanced → MTB, e_mtb* →
  E-MTB, hike / jogging → Hike T2, mountaineering → Hike T3 (underscores optional); unknown → last used activity. The
  import stores and selects it (not during a tour). Share export, backups and Export all write `<trk><type>` in
  Komoot's spelling (Hike T1–T2 `hike`, T3–T5 `mountaineering`). Tests `SportTypeTest` (synthetic GPX only).
- **E2E CorePointsListE2ETest flaky – "Points section shown" / "points shown" (desktop, 2026-10-25, red on 8174874 and
  283bcaf, a different test each time):** the failure screenshot shows the full sheet with all rows and ✕, plan size
  FULL, yet UiAutomator did not find "Delete point" for 10 s. The list appears in place in the same window (PlanPanel
  drops `clearAndSetSemantics` at the full step); on Android 14 UiAutomator kept serving its cached tree without it
  (the mirror of the stale node `goneFresh` fixed on 10-22). `E2EBase.findFresh` clears the cache before every look; the
  plan sheet lookups use it, same assertions and timeouts; failures now list the content descriptions UiAutomator sees.
  E2E 40239844287 green (full run), 40241249080 green (class only).
- **Offline search index per map area, no empty moment (laptop, 2026-10-25, field 0.10.1 "zons" found nothing for a
  minute: `Search index g000059: 129358 entries in 59121 ms`):** each map area (installed cell id + version, or the
  data-folder package) is extracted once into `files/search-areas/<area>@<version>.tsv` (`search/SearchAreas.kt`); a
  new store generation only reads the changed cells (their tiles / POIs) and reuses the rest from memory – the index is
  the union. The previous index keeps answering while a new one is built (swap when ready); only the very first build
  shows "Preparing offline search…" (spinner, `SearchUi.indexing`). Old installs migrate on the first build (the old
  `search-places-v1.tsv` goes). Log: "Search index: +n areas −m areas in N ms". JVM, 130 k entries / 40 areas: full
  build ~800 ms, one area changed ~220 ms, from files after a start ~700 ms (`SearchAreasTest`, `SearchIndexSwapTest`).
  Chip labels unchanged (owner: bilingual version comes).
- **Map for here only on a yes (cloud, 2026-10-24, owner answer A after "in the car it should not load"):** opening the
  app (or coming back to it) where there is no detailed map no longer loads by itself and shows no big loading window;
  owner 2026-10-24: when the server has it ready (`MapCells.builtOnServer`, GET status only – starts no build) it loads
  quietly in the background; only when it must be built first (minutes) or the status is unknown the blue strip asks
  "Detailed map for here? (≈ N MB) · Not now · Load" (`AppViewModel.hereAsk`, `HereAskStrip`).
  Load = in the background; Not now = that area is not asked again in this app run; setting a point there loads as
  always (T1, with the loading window) and lifts the "Not now". Not released yet.
- **Loading chip with names (laptop, 2026-10-23, not released yet):** the blue background-loading strip in Plan is now a
  small chip under ☰ ("⬇ Bad Waldsee +1 · 34 %", name cut at ~half the width; Navigate chip has the name too); details
  list grouped lines ("Along your route to Gasthof Taser Alm (2 areas) · 13 MB · being prepared"). Names by reason
  (`ui/LoadNames.kt`, tags set where the app asks) else the nearest overview town (`map/OverviewTowns.kt`). MapCells unchanged.
- **Text + UX review fixes (cloud, 2026-10-23, not released yet):** owner naming (Destination, Stop, Track, activity
  names, Direction of travel, End tour; routing data never named), plain words, 5 small text bugs; Cancel undoes only
  what started the routing run (`PlanStatus.Cause`); ride summary on every tour end, Unfinished tour = Continue ·
  Delete · Save; activity change in Plan during a tour = check sheet; edited plan replaces a followed track; Clear route
  during a tour; search "As start" = map "Start here"; Wi-Fi only → one-time "Load now over mobile data"; message bar
  below the blue strip; Undo + Redo map buttons; "Continue · <activity>" (full width, Pause / End tour below); Time
  details plain, formulas in Diagnostics → Developer tools; loading window % follows the time (`LoadSmoother`).
- **After 0.10.0 (cloud, 2026-10-23, not released yet):** no technical error texts in the app (`debug/UserText`, source
  scan `UserTextRuleTest`; details only in Settings → Diagnostics); map loading goes on with the screen off / app left
  (`cells/MapLoadService`, quiet progress notification, ends by itself; `MapCells.loadInBackground`); one blue strip at
  the top for map downloads – the plan sheet's map card is gone, its questions (mobile data: Only on Wi-Fi / Load now),
  failures (Try again), offline and first-start notes show in the strip (`mapNotice` / `MapNoticeStrip`).
  Owner answers 2026-10-23: keep the automatic slower GPS when standing (RestWatch); no slow-down with the screen off;
  Navigate shows only the route line; keep the router's internal time limit.
- **0.10.0 (released 2026-10-22; laptop work merged into main):** (1) map colours from the router classes
  (`tools/routing/wayclass/WayClass.java`, classes 0 good / 1 possible / 2 forbidden / 3 too hard = grey line, red only
  for access bans, via ferrata ticks kept, MTB steps grey; cell schema `-s3`; hiking band above the T level grey via
  `MapController.setSacLimit`), balanced hill shading, format-2 terrain to z13, overview raster tweaks; (2) bus / car /
  lift in a tour (core feature C12, `rides/`): `RideDetector`, End-tour ride summary with four choices, Edit rides,
  map + export follow the choice; (3) "Route back to the start?" when the goal of a 2-point plan is deleted during a
  tour + slower GPS at rest (`RestWatch`); (4) smooth Plan ⇄ Navigate transition (`ui/ModeSwitch.kt`, ~250 ms);
  (5) search: one mixed list, village + municipality, rough position to Photon (2 decimals), "Position from the
  address" note for shared places, late online rows below the shown ones; (6) route warnings (safety audit batch B):
  one amber line + list of spots, "Route changed for …" after an activity change, `WayAttrs.bicycle`; (7) Settings as a
  topic list with sub-pages, Privacy page, Offline maps Clean up / Clear all (core feature C14), short "© OpenStreetMap"
  on the map → About credits; (8) arrival forecast after plan edits: same route keeps the session, time already lost
  kept as an offset after a new line, says why once when the arrival moves (d3b0940); pace learning per tour only
  (PaceStore dropped); (9) 5 pace levels around DIN 33466, e-bike cap ~25 km/h, old pace keys migrated;
  (10) performance – style reload debounced (`ui/QuietGate.kt`), routing time limit by distance / profile and no
  leg-by-leg retry after a timeout (8b4a337), one rd5 write per batch, corridor work off the main thread, Offline maps
  size cache, elevation profile downsampled, health / watchdog logging (`debug/Health.kt`); (11) activity fix (keys
  `lastActivity` / `lastSac`, stale `activity` key ignored) + unreachable plan point (`ui/PlanGaps.kt`, grey dashed
  `plan-gaps` layer, hollow marker, red line in the sheet); (12) safe ✕ in the points list, deleting all points ends
  the route guidance, Navigate shows only the route line; (13) offline map downloads follow the plan – stale corridor /
  routing downloads dropped, queue revalidated at start, region joins batched, blue background-download strip +
  Navigate chip, "Cancel all" (core feature C13, `NoStaleDownloadsTest`); (14) T level (T1–T5) in the activity picker,
  fresh install Hike · T2, last activity + level remembered (`data/LastActivity.kt`), activity chip with short names;
  (15) smooth three-step plan sheet (`ui/StepSheet.kt`, `ui/SheetWithFooter.kt`), frame jank meter
  (`debug/FrameJank.kt`), leg distances cached.
- **After 0.10.0 on main (2026-10-23):** Offline maps Clean up / Clear all result as a line on the page; one loading
  indicator; hillshade as zoom-band constants; E2E CoreBackup with German folder-picker buttons; JVM routing test
  compiles `RouteTimeLimit.kt`; README rewritten.
- **Repo housekeeping (2026-10-22/23):** the 7 old remote branches are deleted – only `main` exists (the `downloads`
  zips mentioned below are history; maps come as cells now). Handovers `docs/handover-2026-10-2*.md` are fully done.
- **0.9.1 → 0.9.2 (cloud, owner field test 2026-10-22; 0.9.1 crashed at start, fixed in 0.9.2):** POIs above trail
  numbers; GPS gaps ≤ 5 min bridged (+ Diagnostics trace per gap); position dot on top after a style reload (all app
  layers via `addBelowPosition`, source check `PositionDotOnTopTest` + e2e `assertPositionDotOnTop` in Plan / reload /
  highlight / Navigate); End tour = Cancel · Delete (recycle bin) · Save, plan cleared after either; full plan sheet =
  one scrolling page, fixed height; > 24 h plans asked first; Cancel routing (= undo); E2E boots from a cached emulator
  snapshot; release gate = `StartSmokeE2ETest` only, full E2E runs beside it. ("Points in Navigate" was reverted in
  0.10.0, owner A13.)
- **Checked 2026-10-22 and moved here from Next / Later:** 0.9.0 (tour backup, junction vibration + lock screen, map
  ahead on tour in the background, points editor, simple menus); re-routing P0–P4 incl. reroute back after off-route
  (43923b2); grid cells + cloud hosting (doorman, Cloud Run, any area via Geofabrik, sha256 checks), per-cell size work
  (WebP terrain, overlays as one vector PMTiles = format 2, routing data by 1° part), world overview in the APK,
  "overview only" hint, plan-sheet map card; bike map (oneway arrows, both ways, green cycleways, cycle lanes – format 2);
  path smoothing, via ferrata look + profiles, route band offset, contours from z12, trail visibility, bus stops,
  manifest date/bbox; draggable sheet (B4); reorder points in a list; pinned POI stops kept on reopen; Save =
  overwrite / Save as new + Rename; one compass setting Plan + Navigate; distances 150 / 25 / 40 m; backup rules
  exclude map data; routing data download checks HTTP 206 + structure; BRouter terminate on cancel; style without
  missing files at first start (ecacf8c); no self-dismissing messages anywhere. Superseded: Hetzner packages (→ cells),
  OpenFreeMap streaming (→ cells), "End tour" in the notification (owner: no notification button ends a tour), first-tour
  battery pop-up (→ quiet hint in Settings), dropping background location (owner keeps "Allow all the time").
- `downloads` zips rebuilt (laptop, 2026-10-20, downloads 0e7db5e): paths.geojson + addresses.sqlite, same two-zip scheme; importer now accepts addresses.sqlite / addresses/*.sqlite.
- Grid cells phase 1 on zo (laptop): `tools/cells/` builder image + build_cell.sh, 2 neighbouring cells in ~2.7 min each, no seams; results in map-cells.md §17. Next: doorman container, then Cloudflare hostname (owner), then format 2.
- Share a place into OnPath (laptop, owner): Google Maps share text/short+full links (coordinate from the link, owner decision 2026-10-20, see CLAUDE.md), geo: URIs → Plan mode, fly there, orange ring; fallback address search. The "Position from the address" note followed in 0.10.0.
- Address search (laptop, owner): offline `addresses.sqlite` (`tools/extract_addresses.py`, optional step in build_region.sh; 2.5 MB region / 0.6 MB per cell), German+Italian names, house numbers; online Photon re-ranked (town in query, same country, distance; street pieces merged). Owner decided later (A5, A6): one mixed list, village shown, municipality small.
- Smart route names (laptop, owner): save dialog proposes "Algund → Meran" / "Round trip from Algund" (`search/RouteNamer.kt`); finished tours named "Tour <date>" are renamed in the background from the track.
- Map style (laptop, owner): track junctions clean (lane fill `track-own-lane` drawn after all track edges); ground symbols: no symbol on meadow/grass/fields, marsh tufts only on wetland, new orchard rings + vineyard stakes. Streets too (owner): light street lane `road-service-lane`/`road-minor-lane` (#f9f7f1), one order for all ways (edges/casings → track edges → fills smallest first → rail → paths), side streets stop at the bigger road's edge, tracks pass under yellow roads. Open: street bridge over a small street can be cut by the lower fill.
- Arrival / time-to-go robustness (laptop, owner field test 2026-10-19): plan edits followed at once (same route
  within 10 m / 1 % keeps the session), walking backwards raises the time and asks after 200 m, out-and-back picks
  the stretch by walking direction, "— not on route", arrival moves during breaks, "estimate paused", mid-route
  start; Navigate card distance from the service. Replays + fixtures in `app/src/test/.../time/NavReplay*`.
  Later: "Route back to the start?" (0.10.0, A9); an unnamed target shows no name line by design (`NavigateScreen`).
- No silent errors (cloud, 0.6.7, owner): `debug/Problems` central log (dedupe, persisted, crash capture,
  MapLibre native logger), ≈120 catch sites in all packages reviewed and reported (or marked `// expected:`),
  Settings → Diagnostics problem list + Clear, new ERROR announced once per run, last crash announced at start,
  `problems.json` in the diagnostics export.
- Region package rebuilt (cloud, 2026-10-19): bbox extended west/south to `10.70,46.30,11.60,46.85` (whole Ultental /
  Zoggler Stausee for the field test), OSM 2026-10-19, all new overlay files (`ways`, `lowways`, `vis`), published as
  two zips on the `downloads` branch (GitHub 100 MB limit). POIs (owner): bus stops + all food on by default from z14,
  bus-stop pairs merged zoomed out and separate from z16 (`extract_pois.py` merged/detail, `make_style.py`
  poi-bus layers) – app 0.6.2.
- Way quality (laptop agents): track grades, faint trails, MTB S-labels (MTB only; T-labels hike only), loose-surface
  edge in bike modes, trail geometry ~0.5 m (no double lines). Time P4: SAC / surface / smoothness / MTB factors.
- Komoot-like overview (laptop): routes thin lines below z13.5 then bands, all paths/tracks from z11 (new optional
  `lowways.geojson`), yellow road scale. (Region packages are superseded by cells.)
- Track safety 0.6.1 (cloud, from the 0.6.0 robustness review at
  https://claude.ai/artifact/DRLGzEaWQBYKW4KVSFJeDd): point writes throw instead of returning -1, every DB write in
  the service guarded with a "Recording cannot be saved" notification + `RecState.saveFailed`, `failLoud()` instead of
  silent `stopSelf()` (precise location, GPS, foreground), Navigate only once the service reports, "Navigate anyway?"
  for straight legs, `ArrivalGate` (round trips, poor fixes), `MapController` no-ops while a style reloads,
  `PointFilter` (50 m accuracy, jitter by accuracy), clock-based elapsed time, wake-lock renewal, corrupt DB moved
  aside, importer keeps the old file. Review leftovers since done: rough position to Photon,
  routing-data download checks, BRouter terminate + time limit + Cancel routing, backup rules; dropping background
  location superseded (owner keeps it). Still open: see Next (error texts, CI, compass, DEM ascent).
- Time estimate P0–P3 merged (laptop agent): one model everywhere (`time/`), pace setting, arrival clock, moving
  time / breaks, learning on the trip and across tours (cross-tour learning dropped in 0.10.0, owner A8). P4 done
  later (see Way quality).
- Owner-approved batch (laptop): Clear without question (Undo restores), Pause / Resume in the tour bar (two-line
  status), Round trip greyed when already a loop, no "Searching GPS…" / "Still missing" messages, "Export GPX"
  everywhere, blocked-way tint grey-red, Feldwege with finer lighter dots (Komoot-like).
- Activity in Navigate (laptop agent): icon in the to-go card (own 48 dp target), "Start navigation · Hike", tap =
  check-and-switch sheet (routes the rest for each activity, fits / problems, switching asks Take new route / Keep).
- UX audit (laptop agent): outdated labels, dead Load-route screen, save choice without Cancel, no OK after import /
  copy, settings rows; proposals decided by the owner (see next entry).
- Route bands (laptop, owner): one green band for every activity; major routes (OSM network regional / national /
  international, `net` ≥ 2 – Via Claudia Augusta, E5, EuroVelo 7, Etschradroute, Meraner Höhenweg …) get a Komoot-like
  semi-transparent green/pink dashed band + name label. `routes.geojson` now has `net` (0–4) (`tools/extract_overlays.py`); older data just
  shows no highlight.
- Layout (laptop, owner): top = search pill (search only) + round ☰ app menu (My routes, Start recording, Settings,
  Check for updates). Route sheet = everything about this route: activity button (icon ▾) at the start of the first
  line, ⋯ = Save route, Export GPX, Reverse, Round trip, Clear. Activity chip removed from the map; "Load route" and
  "Manage routes" merged into My routes (opening a planned route loads it, asks "Replace current plan?").
- Point menu concept (owner): "Start here" drops the points before, "End here" drops the points after (menu says how
  many; Undo restores); round trip: "Start & finish here" rotates the loop (same points, same order). Start and
  destination of a normal route get neither (⋯ Reverse). `PlanEdits.startAt/endAt/rotateLoop/isRoundTrip`.
- Navigate (laptop, owner): "to go" card shows ↑ and ↓; tap it for the elevation profile of the rest of the route
  (stays open until tapped again).
- Google Maps link (laptop agent): named places open `geo:lat,lon?z=16&q=<name>` (name search biased to our point),
  unnamed ones an exact labelled pin `geo:0,0?q=lat,lon(label)`; browser fallbacks. Needs owner test on the phone.
- Re-routing P0–P4 merged (laptop agent): ask every time, grey original, rejoin ahead, NavSession in the service,
  notification with answers. Field-test checklist in `docs/plan-auto-reroute.md` / project status.
- One blue line (owner): the followed route is drawn route-blue (no purple); while the plan is edited during a tour it
  is thin grey under the blue plan; only a merely shown GPX track keeps purple (`MapController.setTrackLook`).
- Polish 1 (branch `feat/polish-1`, owner): start at my location, small blue position dot with direction triangle,
  compass always in the map controls, no "Start from my location" button, "Tour saved" → Delete asks first, Save =
  overwrite / Save as new…, Rename in Manage routes (⋮ per item). See `docs/ux-two-modes.md`.
- Settings → Default activity (laptop, owner): the app always starts with it (hike + SAC level); the activity chip
  only changes the current route. Direction arrows (white chevrons) on the planned route and shown/followed track,
  to see out-and-back sections (`MapController.addDirectionArrows`, hidden by the eye).
- Route menus (branch `feat/route-menus`, owner spec): top ⋮ = Load route / Save route / Manage routes / Start
  recording / Settings / Updates, each enabled only when it makes sense (`ui/RouteRules.kt`); plan sheet ⋯ = Reverse,
  Round trip, Clear; no primary button without a route; Manage routes list (import, export, delete with confirmation,
  open / pick to load); "Replace current plan?" (Save first / Replace) when loading over an unsaved plan; sheet shows
  "Saved as …". See `docs/ux-two-modes.md` "Menus". Overwrite / Save as new + Rename followed in Polish 1;
  empty-sheet hint points to ☰ → Start recording.
- Plan gestures v3 (laptop): hold only, 1×/2× vibration, smart point/POI menus, first-hold "Start here / Go here from my
  location", no dragging, no self-dismissing messages (the shared snackbar now stays until OK).
- Map controls grouped (laptop, owner): one rounded group on the right – compass (Navigate always; Plan only when the
  map is turned, tap = north up), eye (hold to hide route lines/markers, position stays; only when something
  route-like is shown; layers in `MapController.ROUTE_OVERLAY_LAYERS`), locate with GPS dot.
- Style (laptop): hiking route bands from z10 (fully opaque by z11, all bands fade in over one zoom), graded trails
  from z10 – Meran no longer looks empty at town zoom. Untagged paths still from z12 (base tiles).
- Plan gestures v2 (laptop, owner spec): POI quick tap = small "Open in Google Maps" bubble, hold on POI = add as
  pinned stop, tap a route point = Delete / End route here / Cancel overlay (26 dp zoom-independent hit radius,
  marker > POI > line > map), stop chips list and PointMenuDialog removed, "point added" notice only when the app
  guessed (middle insertion, no numbers). Rules in `ui/MapGestures.kt`, overlays in `ui/MapOverlays.kt`.
  Open owner questions: "Make it the start" only via ⋯ Reverse / search "As start"; bubble timeout 8 s.
- Snap free plan points onto the way the route uses (laptop, owner: no off-road guidance): BRouter's matched
  crosspoints (`RouteResult.matched`, read by reflection), pure `routing/WaypointSnap`, no extra undo step, POI-pinned
  and named stops keep their coordinates. JVM test `tools/routing/jvm-test/SnapTest.kt`.
- Toolchain (laptop, owner approved): **AGP 9.4.1, Gradle 9.6.0, Kotlin 2.2.10, Paparazzi 2.0.0-alpha05.1** (1.3.5
  breaks on Gradle 9). AGP-9 compatibility flags in `gradle.properties` (builtInKotlin=false, newDsl=false …) –
  clean up before AGP 10. All snapshots re-recorded (antialiasing only). **Cloud:** first build downloads Gradle 9.6;
  still JDK 21; on snapshot conflicts run `recordPaparazziDebug`.
- Bug fixed (laptop): a hiking via point on a spur was cut off and left unconnected (owner's point 3 on the Algunder
  Waalweg, 100 m off the route) – BRouter's via-point spike correction for HIKE lowered 250 m → 50 m
  (`VIA_CORRECTION_HIKE_M`). **Cloud:** please re-run your `ViaTest.kt` cases (the VIA_CASES file is not in the
  repo – consider committing it) and check bikes (400 m) for the same effect.
- 0.6.0 (cloud): navigation heading-up / north-up + next-turn card and arrow; place / address search (offline index +
  Photon online, place card with "Add to route" preview, Google Maps link-out, named stops); Material 3 UI cleanup
  (search pill + ⋮ menu, activity chip, plan sheet with status text, named stop chips and one primary button, route
  actions in ⋯, small FABs, Navigate status on top). `app/` is free again for the laptop. **Needs a phone test** (see
  `docs/project-status.md`, last session).
- 0.5.0 (cloud): two modes Plan / Navigate (`docs/ux-two-modes.md`): Plan is home (My tours + Settings in the top bar,
  GPS dot on the locate button, no eye button, no Plan/Record/My tours bar), "Start navigation" (route, shown track or
  free recording), Navigate with remaining strip and slide-to-plan (≥ 85 %), map taps change nothing; recording keeps
  running in Plan ("Tour running" bar, Continue navigation re-routes past passed stops, End tour with confirmation);
  arrival dialog within 50 m; off-route warnings only in Navigate. **Needs a feel test on the Pixel 8** (slider with
  gloves, re-route, arrival).
- Plan mode POI card (laptop): tap a POI → card above the plan panel with opening hours, "Add to route" /
  "Remove from route" and "Google Maps"; planning is not interrupted. Replaces the long-press Google dialog.
  Adding a point needs a short hold (0.3 s, ring + vibration) so stray taps add nothing. Blocked ways (access=no) marked on the map.
- LayerSettings crash on style reload fixed (laptop): skips a stale style instead of throwing; tested on the
  Pixel 8 by starting without base.pmtiles and putting it back.
- 0.4.1 (laptop, released via the workflow; in-app update 0.4.0 → 0.4.1 confirmed working on the Pixel 8 by the owner): resizable plan panel
  (full / compact / mini), eye hides the route only while held, POI tap = add/remove route point, POI long press =
  ask to open Google Maps, SAC labels (T1–T6) on trails, Komoot-like Feldweg (light lane, dotted edges),
  solid darker ways below z13, tiny POI dots, darker forest / brighter hiking band.
- Tried and **rejected by the owner:** a thin centre line inside the route bands below z13 (reverted in 7a1abf0) –
  keep the bands as plain green/orange/lilac overlays.
- 0.4.0: in-app updates via GitHub Releases, release workflow.
- 0.3.0: trail names + AVS numbers, contrast pass + high-contrast theme, stronger railways/stations,
  map data import on the phone, Clear with confirmation, map pipeline script + docs.
- Parking POIs: off by default (optional in Settings).
