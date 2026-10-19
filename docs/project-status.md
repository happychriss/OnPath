# OnPath – project status and decision log

Last update: 2026-10-19 (release 0.6.2 – POIs; map package incl. Ultental).

## Last session
<!-- Overwrite at the end of every session: who (laptop/cloud), date, what changed, what's half done, next. -->
- **laptop, 2026-10-19 (arrival estimate + map packages):** arrival/time-to-go fix merged (see backlog Done,
  `docs/plan-time-estimate.md` "Field test 2026-10-19"). Map packages: owner chose **grid cells (z10) built on
  demand**, server first on the owner's home server *zo* (code `~/onpath`, data `~/onpath-data`, Cloudflare tunnel,
  domain free-your-pictures.org for now), later Hetzner (small always-on doorman + builder created from a snapshot,
  deleted after). Running: build-time test of one cell on zo; proposal "map data size on the phone"
  (`docs/design/map-data-size.md`, coming) – pipeline changes need the cloud's agreement.
- **cloud, 2026-10-19 (0.7.1 owner todos):** compass cycle in both modes (`AppViewModel.onCompass`: turned → north
  → walking direction (follows) → north; no fix = hint; editing the plan stops the turning map); Navigate re-centres
  when the app comes back (`onReturnToApp`, MapHost ON_START); profile scrub in Navigate (`navScrub`/`navScrubEnd`:
  dot + map at the spot, back to the position 4 s after release); search ring added below MapLibre's position layers
  (`addBelowPosition`); "Clear route from map" in the Tour-saved dialog when an unsaved plan is left. Arrival time:
  laptop.
- **cloud, 2026-10-19 (map design 0.7.0, owner pick):** one map style from a colour analysis
  (`docs/design/map-colour-analysis.md`, script `tools/style_analysis/route_green.py`): route band #168403 at 40 %
  without edges; no white casings; streets = two grey edges; tracks brown, one dashed line < z14.5, then two dotted
  edges whose spacing shows tracktype (paved track = street); paths/tracks drawn from new `paths.geojson` (exact, from
  `extract_overlays.py`) from z13, base-map lines only as fallback (`onpath:fallback-for`, OptionalSources); routes
  merged into continuous lines (+ `majoronly` copies for the pink highlight); ground symbols in the sprite (wetland,
  farmland, rock, sand); residential light grey; lighter forest/meadow; greenish hill shadow; wider cable-car ropes;
  high-contrast theme removed (Settings shows no style choice; an old "high-contrast" pref falls back silently).
  New map package on `downloads` (part2 44 MB; both zips carry the new package.json). Also 0.6.8 trail safety and
  0.6.9 (saved plan leaves the map after the tour). Field-test status: `docs/field-test-2026-10-19.md`.
  Next: owner tests 0.7.0 + new map part; open owner todos in backlog (dot on top, recentre, compass, ...).
- **cloud, 2026-10-19 (trail safety 0.6.8, owner hiking):** audit batch A (routing profiles) + Navigate tweaks.
  Profiles (`# OnPath:` comments): hiking SAC above the limit forbidden (was 9000), preferred = min(limit, T3), via
  ferrata / abandoned / trail_visibility=no forbidden, bad visibility +1.5, informal +1, fords ×3/+500; trekking,
  e-bike, touring: via ferrata and T3+/S3+ forbidden, T2/S2 +30; MTB: via ferrata and T4+ forbidden (9000 timed out),
  T3 +15. JVM check: `KTTEST=tools/routing/jvm-test/SafetyTest.kt SAFETY_VF=<via-ferrata node list> run.sh <segments>`
  – no case uses a via ferrata any more (before: 820 m Heini-Holzer on a T2 hike). Plan shows a message when the
  router stops ≥ 150 m from a point (summit above the T level). Navigate: open off-route question re-vibrates,
  "Hairpin" wording, "straight line" on the arrow banner, own position line (tap = copy) in the opened card, network
  fixes ignored after the first GPS fix, "last fix N min ago". Next: audit batches B (plan warning line) and D (pipeline).
- **cloud, 2026-10-19 (Hetzner map server):** `tools/hetzner/` – `onpath_hetzner.py plan|apply|status|destroy` on the
  laptop creates ONE separate labelled server (+ firewall, + SSH key if new) via the Hetzner API and nothing else;
  the server installs itself (`server/install.sh`: Caddy HTTPS on `<ip>.sslip.io` + password, API, worker, weekly
  refresh). Web page: search a place / my position → 100/150/200 km square → build from Geofabrik leaf extracts
  (osmium extract + merge) → `build_region.sh` → public unguessable download link. Tested here end to end (30 km
  around Zoggler Stausee: 2 min, 31 MB) and 13 offline unit tests; not yet run on a real Hetzner account (owner).
- **cloud, 2026-10-19 (Ultental package + POIs, 0.6.2):** region bbox extended to `10.70,46.30,11.60,46.85` (Ultental
  / Zoggler Stausee test), package rebuilt with OSM 2026-10-19 and the laptop's new overlay files; published as two
  zips on the `downloads` branch (README there has the phone steps). POI rules per owner: bus stops and all food on
  by default from z14; bus-stop pairs are one symbol (`merged`) until z16, then each stop (`detail`) – needs the new
  package. Safety audit of the whole app (routing profiles, navigation, planning): backlog "Safety audit" link,
  batches A–D wait for the owner's pick. **Test at the lake:** POIs at z14 (bus "Zoggler Staudamm", Café Seerast,
  water), zoom to z16 → two bus stops; routing into the upper valley (Weißbrunn) now inside the map.
- **laptop, 2026-10-18 (late evening):** merged many owner-driven changes (all on main, installed on the Pixel 8 as
  clean installs): hold-only gestures + smart menus, re-routing (ask always), time estimate P0–P4 + Time details
  screen, plan sheet redesign (tour controls on top, mini profile, Undo on map), layout (search + ☰, route actions in
  sheet), Komoot-like map (black paths solid/dotted by SAC, track grades, faint trails, routes as thin lines zoomed out
  then bands, major routes pink, yellow road scale, relief stronger zoomed out), search pin (orange ring) + Bozen fix,
  POI menu on empty plan, start at current location. **New data files** (`routes.geojson` with `net`, `trails`/`ways`/
  `lowways.geojson`) are on the phone only – **cloud: rebuild the region package / downloads zip.** Open owner
  questions: attribution style, unverified paths (todo). Field tests pending: re-routing, times, activity switch,
  Google link.
- **cloud, 2026-10-18 (0.6.1 track safety):** read-only robustness review of 0.6.0 (five areas, report:
  https://claude.ai/artifact/DRLGzEaWQBYKW4KVSFJeDd), then the "must fix" items as release 0.6.1 (notes in
  `docs/release-notes/v0.6.1.md`): `TourDb` throws on a failed point insert and keeps a corrupt DB file; the service
  wraps every write in `safeDb` (notification "Recording cannot be saved" + `RecState.saveFailed`, shown in the app),
  stops with `failLoud()` instead of a silent `stopSelf()` (precise location / GPS / foreground refused → notification
  "Recording stopped: …" + `RecordingService.startFailed` → message in the app), calls `startForeground` on every start,
  renews the 12 h wake lock, counts real time per tick, uses `recording/PointFilter` (50 m accuracy, jitter by
  accuracy) and a cached track snapshot. `MapController` ignores updates while a new style loads (theme change /
  import crashed before). `AppViewModel.startNavigation` enters Navigate only after the service's first state (6 s,
  else message); plans with straight legs ask "Navigate anyway?" (`StraightLegsDialog`); `routing/ArrivalGate` decides
  arrival (away first, accuracy ≤ 50 m, asks again after leaving). `DataImporter` renames over the old file.
  376 tests + Paparazzi verify green. **Field test (short):** (1) start a recording with location set to
  "Approximate" → "Recording stopped: … precise location" and Plan stays; (2) a round trip: no "arrived" at the start,
  asked at the end; (3) switch the map theme in Settings during a tour → no crash; (4) plan a route with an unreachable
  point → "Navigate anyway?"; (5) under trees the track has no gaps and "Searching GPS…" is rare; (6) double-tap Start.
  **Next (from the review, in order):** plain-language error texts; Online-search switch + coarse position to Photon;
  validate downloaded routing tiles; BRouter cancel + planning budget; CI on every push; backup rules.
- **laptop (background agent), 2026-10-18 (time estimate):** implemented `docs/plan-time-estimate.md` P0–P3 on branch
  **`feat/time-estimate`** (merged with main, not merged into main): new `time/` package (one model for plan sheet,
  route list, Navigate, re-route, activity check; pace chips in Settings; arrival clock; moving time / breaks;
  learning on the trip in the recording service; learning across tours with Reset). Details and old vs. new times in
  the plan's "Implementation status". **Field test:** (1) plan a mountain hike – note the sheet time, Start navigation:
  "to go" must be the same; (2) Settings → Your pace Fit / Relaxed changes plan and route-list times, not the route;
  (3) walk ≥ 20 min on the route: card stays calm (no jumps > a few min per 100 m), opened card shows "Your pace on
  this trip" once it differs; (4) a 5+ min stop counts as break ("moving · breaks" line), a photo stop does not; slow
  steep climbing must not count as break; (5) arrival clock moves later during a long break, not while walking
  normally; (6) End tour: "Tour saved" shows moving time, breaks and "On the route: planned … – you needed …";
  (7) after ≥ 30 min on a route, Settings → Your pace shows "Learned · Hike …" with Reset; the next plan uses it;
  (8) kill the app / reboot during a tour: breaks and learning continue; (9) bikes: e-bike / MTB times plausible?
  (10) export plan + track GPX of the tours for `app/src/test/resources/tracks/`.
- **laptop (background agent), 2026-10-18 (re-routing):** implemented `docs/plan-auto-reroute.md` P0–P4 on branch
  **`feat/reroute`** (not merged): `navigation/` (OffRouteDetector, RerouteTargets, Rerouter, Navigator, NavSession,
  NavText), optional timeout/cancel in `RoutingContract` + `BRouterEngine`, the service owns the flow (ask every time,
  question in the app and in the ongoing notification, grey original line, arrow mode), Continue navigation no longer
  edits the plan. Unit + JVM (real data) + Paparazzi tests green. **Next:** owner field test (checklist in the plan's
  status table / report), then merge to `main` and release.
- **agent, 2026-10-18 (branch `feat/polish-1`, not merged):** polish batch. App start in Plan centres once on the
  position (`ui/StartCamera.kt`, never after the user moved the map); own position = small blue dot + accuracy circle +
  direction triangle in both modes (MapLibre COMPASS render mode instead of the big GPS puck); compass always in the map
  controls (MapLibre's own compass off); "Start from my location" button removed from the empty sheet; "Tour saved" →
  Delete asks first; ⋮ Save route offers Save (overwrite) / Save as new… for a loaded or saved plan
  (`RouteRules.saveMode`, `TourDb.replacePlanned`); Manage routes items have ⋮ Rename / Export GPX / Delete.
  **Test on the phone:** position dot look + direction triangle while standing / walking (Plan and Navigate, heading-up
  still smooth?), start centring (cold start indoors / outdoors, pan before the fix → no jump), compass tap in Plan,
  save over a loaded route → list shows the new distance, rename → sheet "Saved as" follows.
- **agent, 2026-10-18 (branch `feat/route-menus`, not merged):** route menus per owner spec – top ⋮ Load / Save /
  Manage routes / Start recording / Settings / Updates (enabled-when rules in `ui/RouteRules.kt`), sheet ⋯ = Reverse /
  Round trip / Clear, Manage routes list with import / export / delete / pick, "Replace current plan?" question.
  Details: `docs/ux-two-modes.md` "Menus". **Test on the phone:** save → "Saved as …" in the sheet; edit → "changed";
  Load over an unsaved plan (Save first / Replace); delete dialog; menu items greyed during a tour.
- **laptop, 2026-10-18 (evening):** merged three background branches into `main`: Gradle 9 toolchain (AGP 9.4.1,
  Gradle 9.6, Kotlin 2.2.10, Paparazzi 2.0.0-alpha05.1), snapping of free plan points onto the routed way
  (`WaypointSnap`), plan gestures v2 (see backlog Done). Also: hiking via-point spike correction 250 → 50 m (owner's
  point on the Algunder Waalweg was skipped). In progress (laptop): design proposal for automatic re-routing
  (`docs/plan-auto-reroute.md` on branch `docs/auto-reroute-plan`, not implemented). Owner installs: laptop dev
  builds start as a clean first install (app data + permissions reset, map files kept).
- **cloud, 2026-10-18 (0.6.0):** three features landed. (1) Navigate: heading-up / north-up (`map/HeadingEngine`,
  orientation FAB above locate, setting "Map direction while navigating") and next-turn hints (`routing/TurnDetector`,
  green turn card on top + arrow on the map, only on route and ≤ 500 m ahead). (2) Place / address search
  (`search/`, `ui/search/`): offline index (POIs, base-map places, trails) + Photon online when available, results
  "near your route" first, one place card for search results and tapped POIs ("Add to route" with a preview of where it
  goes, As start / As destination, Google Maps link-out); stops keep their names (`vm.stopName`). (3) Material 3 UI
  cleanup: Plan top = one search pill with a ⋮ menu (My tours, Import GPX, Settings, Check for updates) + activity
  chip; plan sheet = M3 drag handle, status text (time, distance, ↑/↓, highest point, difficulty badge), named stop
  chips, undo/redo, one filled primary button (Start navigation / Start recording / Continue navigation); profile and
  breakdown behind "Elevation & surfaces" (sheet opens COMPACT); reverse / round trip / from my location / save / GPX /
  clear in the sheet's ⋯. Tour running bar is status + End tour only. Map controls are 48 dp round small FABs on the
  right; scale + attribution smaller. Navigate: turn card → status card (to go + recording) → off-route banner on
  top, FABs right, slide-to-plan bottom. The place card replaces the plan sheet while open. Theme: neutral surface
  containers / outlines (no M3 purple tint). Screenshot tests now render at 390 dp width and include both menus.
  **Test on the phone:** heading-up while walking (smooth? jumps when standing still?), turn card distances and arrow
  position, north-up toggle; search offline (airplane mode) and online, add a result as stop / start / destination,
  Google Maps link; stop names in the sheet; ⋮ Import GPX and Check for updates; sheet ⋯ actions; swipe the sheet
  (mini / compact / full) and "Elevation & surfaces"; Continue navigation + End tour while recording; one-hand reach of
  the FABs and the slider with gloves.
- **cloud, 2026-10-18 (two modes, 0.5.0):** implemented `docs/ux-two-modes.md`. `AppMode { PLAN, NAVIGATE }` replaces
  BROWSE/PLAN; Plan is home (top bar My tours / Settings / activity, GPS dot on the locate button, eye button, GPS chip,
  browse bar and record panel removed). "Start navigation" follows the plan, a shown track or nothing (free recording)
  and starts recording; Navigate = remaining strip + recording chip + "Slide to plan" (`SlideToUnlock`, ≥ 85 %,
  TalkBack action), map taps add/move nothing, POI tap = read-only details. Recording never stops on a switch; in Plan a
  "Tour running" bar offers Continue navigation (re-route from the position through the stops not yet passed,
  `RouteProgress.remainingStops`) and End tour (confirmation). Arrival within 50 m asks once "end tour?". The service
  pauses off-route checks in Plan (`ACTION_NAVIGATING`). App start opens Navigate when a recording is active.
  Version bumped to 0.5.0 + release notes. Next: feel test on the Pixel 8 (slider with gloves, re-route, arrival,
  pocket safety), then tune.
- **laptop, 2026-10-18 (after 0.4.1, all in 0.5.0/0.6.0):** fixed the `LayerSettings` crash on style reload; blocked
  ways (OSM access=no/private) get a red tint + no-entry signs (`access_restrictions()` in `make_style.py`, sprite
  `access-no`); plan-mode POI card (now the cloud's place card); adding a point needs a 0.3 s hold with a ring
  (`HOLD_MS` in `MapHost`). Owner confirmed the in-app update 0.4.0 → 0.4.1 works; phone now on 0.6.0 via the
  updater. Open owner question: show trails / hiking bands earlier when zoomed out (around z10)? Nothing half done.
- **cloud, 2026-10-18:** released 0.4.0 (in-app updates + release workflow); wrote `docs/working-agreement.md`
  and `docs/backlog.md`.

## Where we are

Note (build 2): the way types & surfaces breakdown in the planning panel is collapsed by default to a one-line summary so the panel leaves enough map to tap on.

**Test build 1.1** (versionName 0.1.0, build 1 + POIs) was delivered for a first field test on a Pixel 8 in South Tyrol.
It compiles, unit and screenshot tests pass, routing was verified on the JVM – but it has **not yet run on
a real device**. Expect first-run issues (MapLibre pmtiles loading, permissions, background service).

### New in build 2 (app side, versionName 0.2.0)
- **Eye button** in the plan panel: hides planned line, markers and followed/GPX line; the panel collapses to a slim
  "Route hidden" bar and map taps are paused (a POI tap still opens its details). Tap the eye again → route is back unchanged.
- **Smart insertion** (F-PLAN-13, `WaypointInsertion`): a tap within ~24 dp of the line becomes a stop in that leg;
  elsewhere cheapest insertion vs. new destination. A notice above the panel says what happened ("Added as stop 2 of 4")
  with one alternative ("Make it the destination" / "Insert as stop instead"); one undo reverts add + correction.
  POI taps in plan mode use the same logic.
- **Pinned POI points**: a point added by tapping a POI is kept exactly (gold ring on the marker). `Planner` splits the
  request at pinned points so BRouter's out-and-back spur correction never removes a deliberately chosen hut/viewpoint.
- **Drag & edit on the map** (F-PLAN-04): long-press a marker and drag → moves it (dashed preview, reroute on release);
  long-press without moving → menu (Remove / Make start / Make destination; also from the number chips); long-press the
  route line (optionally drag) → inserts a stop into that leg. **Redo** next to undo; **Reverse route** and
  **Round trip (back to start)** in the panel's ⋮ menu.
- **Route summary**: way types and surfaces as two slim stacked bars with km legend (only when the engine sends data;
  <3 % merged into "Other"); elevation profile coloured by gradient (<5 % route blue, 5–10 amber, 10–15 orange, >15 red,
  slope over a 100 m window); **difficulty badge** Easy/Moderate/Difficult (formula in `RouteSummary.difficulty`).
- **Settings page** (gear on the home screen): POI groups with per-kind checkboxes, Show all / Hide all / Defaults
  (calm defaults like Komoot, `PoiKinds.defaultHidden`); route networks, contours, hillshade; off-route warning on/off and
  25/40/75 m; keep screen on while following; About (version, data folder + status, attributions, Apache-2.0).
  Applied live: POI filters wrap each `poi-*` layer's original filter (`LayerSettings`, `PoiFilter`).
- **Remaining distance / ascent / time** strip at the top while following a route (F-NAV-01, `FollowProgress`).
  The follow file now stores elevations (old files still load).
- Tests: unit tests for PoiFilter, RouteSummary (merge, difficulty, gradient), PlanEdits, Planner chunking (fake engine),
  FollowProgress, breakdown folding; Paparazzi screenshots incl. plan with breakdown + gradient, eye-off, notice,
  point menu, settings (+ full page), follow strip.

### Working in build 1
- Offline map of Bozen/Meran/Algund: Komoot-like style, two-direction hillshade, contours, trails coloured by
  SAC difficulty (T1–T2 brown dashed, T3 orange, T4+ red dotted), official route bands (hike green, bike
  apricot, MTB lilac), German labels.
- Own position (blue dot, accuracy, heading), GPS status chip, center-on-me, compass, scale bar.
- Missing-data screen with the exact `adb push` command (app never crashes without data).
- Activity selector with six profiles; route planning by tapping points, delete by long-press, undo, clear,
  from-my-position; offline BRouter routing; distance / ascent / descent / time; interactive elevation
  profile; leg-by-leg fallback with dashed straight lines and plain-language errors.
- GPX import (file picker, open-with, share; tolerant parser incl. Komoot exports) and GPX 1.1 export.
- Recording as a foreground service, every point in SQLite, resume after kill/restart, live track,
  off-route warning (>40 m for 15 s) while following.
- "My tours" list (planned, recorded, imported).

### Routing test results (JVM, desktop)
| Route | Profile | km | ascent m | min |
| --- | --- | --- | --- | --- |
| Bozen → Meran | Bike touring | 30.4 | 101 | 92 |
| Bozen → Meran | Bike trekking | 33.4 | 63 | 98 |
| Bozen → Meran | E-bike | 33.4 | 63 | 83 |
| Bozen → Meran | Hike | 32.9 | 62 | 393 |
| Meran 2000 area | Hike T3 | 8.1 | 481 | 126 |

### Known gaps (build 1)
- No drag-to-move / reorder of points, no redo, no place search.
- No "reroute back to route" after off-route alert; no remaining-distance view while navigating.
- Off-route only while recording; followed route drawn in GPX purple.
- Missing GPX elevation not filled from terrain; no "recently deleted"; no DB backup.
- English UI only; strings hard-coded.
- Unreachable points take ~30 s to fail (BRouter search); very long routes (>80 km) may time out on phone.
- Huts appear from z14 only; hillshade softens above z13.
- POIs (F-MAP-09) integrated in build 1.1: ~12,000 OSM POIs (viewpoints, restaurants, huts, water, …) as
  coloured dots; tap (outside plan mode) opens a details sheet with "Open in Google Maps". In plan mode a tap
  on a POI adds a route point snapped to it. `pois.geojson` is optional (style strips POI layers if missing).
  Huts now come only from `pois.geojson`. See `docs/poi-integration.md`.

### Known gaps (build 2, app side)
- Drag gestures are untested on a device (MapLibre touch handling verified only by reasoning): check long-press → drag
  on the Pixel 8 first; if it misbehaves, tap-on-line insertion and the point menu still cover all edits.
- Pinned points are not saved with tours ("My tours" → reopen: points are routed normally); a route straightened
  around an unpinned via is still possible.
- Difficulty ignores trail technique (SAC / MTB scale); thresholds are first guesses – tune after field tests.
- Remaining strip uses straight-segment matching to the followed line; no "reroute back to route" yet.
- Plan panel is tall with breakdown bars (~60 % of the screen); the eye button helps, a collapsible details
  section may be needed.
- UI strings still English and hard-coded.

### Known gaps (0.5.0, two modes)
- Untested on a device: slide switch with gloves, map following in Navigate, re-route on Continue (BRouter call from
  the current position), arrival dialog (50 m and at most 150 m of the followed line left).
- Pause is only in the notification (Navigate shows Resume when paused); no pause button in the app.
- Continue navigation replaces the plan's start with the current position (one Undo brings the old plan back).
- Navigate shows the followed line only (no stop markers); the followed line is still drawn in GPX purple.
- Back in Navigate leaves the app (recording goes on); only the slide switch goes to Plan.
- A GPX opened from another app while navigating switches to Plan to show it (recording goes on).

## Decisions (from the planning discussion)
| Topic | Decision |
| --- | --- |
| Platform | Android first (Kotlin, Compose), iOS later |
| Name / repo | OnPath, github.com/happychriss/OnPath |
| Licence | Apache-2.0 |
| Distribution | Google Play only to start; test builds via `adb install` |
| Map rendering | MapLibre Native Android 13.7.1, PMTiles files, own style generated by `tools/make_style.py` |
| Routing | BRouter (MIT) embedded, fully offline, rd5 segment files |
| Activities | Bike touring, Bike trekking, E-bike, MTB, MTB e-bike, Hike T1–T5 (covers ~70% of use cases); road bike and gravel later |
| Priorities | Bike and hike first; MTB overlays/profiles "Should" |
| Route overlays | Hiking, cycling, MTB route relations as soft bands (Komoot style), auto-selected by activity |
| Voice | Not needed in v1; off-route warning (vibration + notification) is enough |
| Komoot GPX | Must import correctly (F-GPX-08) |
| Offline corridor | Always download ~10 km around saved/imported routes (F-OFF-02, Must) – not built yet |
| Place search | Hybrid: offline OSM index + optional online provider (Google Places or Photon/Pelias) behind one interface; cost decided later |
| POIs | From OSM, offline; "Open in Google Maps" link-out for reviews/photos/tips; never store Google data |
| Building data on the phone | Rejected: raw data bigger than result, too heavy; phone renders, routes, records |
| Test data delivery | `adb push` into `Android/data/com.onpath.app/files/onpath/` |

## Proposed next steps
1. **Field test feedback** on build 1 → fix crashes and layout issues (user sends `adb exec-out screencap`
   screenshots and `adb logcat` output).
2. ~~Build 1.1: integrate POIs~~ (done).
3. **Build 2 – "works everywhere" like Komoot** (proposed, not yet confirmed by the owner):
   - Outside prepared regions, stream the base map from **OpenFreeMap** (free, OpenMapTiles schema, same
     style works) and terrain from AWS Terrain Tiles; MapLibre caches tiles automatically.
   - "Save area offline" and automatic route-corridor download (MapLibre OfflineManager).
   - Automatic download of BRouter rd5 tiles from brouter.de for the current position / planned route.
   - Extras (trail difficulty, route bands, POIs, contours) stay per-region packages for now; later a
     monthly worldwide build on a server and static hosting (GitHub Releases or a storage bucket).
4. Place search (F-PLAN-12), German UI strings, DB backup.
5. From `docs/komoot-comparison.md` "Adopt later": reorder points in a list sheet; route warnings list (F-PLAN-11:
   steps, push sections, >15 %, ferry, private); per-leg "straight line" option (F-PLAN-09); offline "reroute back
   to route / to destination" after an off-route alert (F-NAV-02); fitness/speed setting for time estimates;
   route corridor download (F-OFF-02). (POI layer filter: done in Settings in build 2.)

## Environment notes (for cloud/AI sessions)
- No Android emulator available in the cloud container (no KVM) – rely on unit tests, Paparazzi screenshots,
  JVM routing tests and headless-browser map rendering; the owner tests on a real Pixel 8.
- Reachable: download.openstreetmap.fr, brouter.de, AWS S3 terrain tiles, osmdata.openstreetmap.de,
  naciscdn.org, raw.githubusercontent.com, maven.google.com, Maven Central (sometimes HTTP 429 – retry).
  Not reachable: download.geofabrik.de, overpass-api.de.

## Planned: updates directly on the phone (decided 2026-10-18)
1. GitHub Actions workflow builds every tagged version (`v*`) and publishes a GitHub Release with the APK
   (cloud sessions may not create releases themselves, but they may push tags).
2. In-app "Check for updates" (Settings → About, plus a quiet daily check when online) reads the latest
   release, shows the notes and installs the APK in place via the Android installer (requirement F-OFF-06).
3. Later: Google Play Console developer registration + secret release key to avoid Play Protect warnings.
