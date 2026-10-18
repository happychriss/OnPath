# OnPath – project status and decision log

Last update: 2026-10-17 (test build 2, versionName 0.2.0 / versionCode 2).

## Last session
<!-- Overwrite at the end of every session: who (laptop/cloud), date, what changed, what's half done, next. -->
- **cloud, 2026-10-18:** released 0.4.0 (in-app updates + release workflow); merged the laptop's plan-panel and
  style changes; wrote `docs/working-agreement.md` and `docs/backlog.md`. Laptop pushed SAC labels and POI-tap
  toggling after 0.4.0 – not released yet (proposed as 0.4.1, see backlog). Nothing half done.

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
