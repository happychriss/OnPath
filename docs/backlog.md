# Backlog (shared by laptop and cloud Claude)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**.

## In progress
<!-- - [laptop|cloud] short description – since YYYY-MM-DD -->
- [cloud] simplify messages + UX + type scale (review 2026-10-24, owner answers in Next) – since 2026-10-24


## Next (open, checked against the code 2026-10-23)
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
  not visible or an error. All answered – being built (cloud).
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
  on the phone); unused theme tokens `track.edge` / `track.fill` / `track.cover.*` in `tools/themes/classic.json`; unused
  `batteryHintShown` (`AppViewModel`); Ifinger T1 JVM test (`tools/routing/jvm-test/RerouterJvmTest.kt:90` expects
  "unreachable", BRouter found 7.2 km – expectation or gap?); zip import checks only sizes (cell downloads check
  sha256); `actions/checkout@v4` / `setup-java@v4` still in `e2e.yml` and `release.yml` (Node 20 warnings).
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
- **Open source + self-hosted map servers – concept (owner 2026-10-21, not built):** app setting "Map server"
  (default = owner's server; custom URL + key, also via QR code; cells carry the server in their manifest, no mixing);
  `/v1` API as an open spec; (A) all-in-one Docker Compose, (B) the cloud setup with gcp-setup.sh; builder image on
  ghcr.io. Pricing stays on the owner's server; self-hosted = free; F-Droid build without Play Billing. Not before Play launch.
- **Bilingual app EN + DE – coming soon (owner 2026-10-23, replaces B11 "not needed"):** all UI texts to
  `strings.xml` in one go, following the phone's language. Until then don't change single texts' language (e.g. the
  German search chips "Hütten, Gipfel, Wasser, Orte, Stationen" stay as they are) – fix mixed EN/DE in that pass.
- **Ideas (owner 2026-10-21):** outdoor mode, way back on own track, hut info, tour suggestions, first-start intro / help
  page. Dropped (owner B12, "focus on the core"): sunset warning, emergency screen, battery check at start, weather.
- **Map wishes (start only when the owner says so):** shrub ("Sträucher") symbols; trail numbers abroad beyond numeric
  refs (letters like E5 / GR20, `osmc:symbol`); contours below z12 (owner chose "from z12" on 2026-10-20).
- **Unverified paths (owner: keep):** untagged OSM ways joining two paths, ≤ 300 m, grey dashed + point-menu hint,
  never routed. Example way 330547188 near Algund.
- Tune difficulty thresholds (Easy / Moderate / Difficult) with real tours.

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
