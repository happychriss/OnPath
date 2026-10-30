# OnPath – guide for AI assistants and contributors

**Several sessions work on this repo (laptop, desktop "donald", cloud): follow `docs/working-agreement.md` – pull first, claim in
`docs/backlog.md`, push small green commits, leave a handoff.**

Read this first, then `docs/owner-preferences.md` (how the owner likes to work), `docs/project-status.md` (where we are, decisions, next steps) and
`docs/requirements.md` (full requirements with IDs like F-PLAN-04, R-07).

## What OnPath is
A simple, robust, fully offline hiking and biking app for Android (Komoot-like maps, much simpler).
Owner: happychriss. Licence: Apache-2.0. Distribution: Google Play (later); test builds are sideloaded.

Guiding principles, in priority order: **robust first** (never lose a track, never crash on missing
data/GPS/network) → **offline by design** → **everything on the phone** → **simple** → smart routing → open data.

## Repository map
| Path | What |
| --- | --- |
| `app/src/main/java/com/onpath/app/` | Kotlin app (Jetpack Compose + MapLibre) |
| `  ui/` | `AppViewModel` (all UI state, Plan ⇄ Navigate switching), `AppMode` (PLAN / NAVIGATE + mode on start, see `docs/ux-two-modes.md`), `MapScreen` (map + Plan mode controls), `NavigateScreen` (Navigate: remaining strip, recording chip, arrival check), `SmartScreen` + `RestView` (cycling: dim rest view on black far from the next turn, map paused under it – when it shows, `ScreenPolicy` hike vs bike), `SlideToUnlock` (slide-to-plan switch + `SlideThreshold`), `PlanTopBar` (search pill + round ☰ menu: My routes, Start recording, Pair with browser, Settings; Import GPX is in My routes), `MapFooter` (scale, attribution, small map FABs: locate with GPS dot), `TourBar` (Tour running status + End tour, arrival dialogs), `MapHost` (MapView in Compose; map taps edit only when `canEditMap`), `PlanPanel` (plan sheet in three steps – summary with activity, profile, Points list; one primary button, route actions in ⋯; route lines by kind: red ERROR, amber WARNING, grey INFO), `RouteBreakdown` (way type / surface bars, difficulty badge), `SettingsScreen` (topic list → one page per topic, `SettingsPage` in `SettingsSummary`: Pace, POIs, Map, Navigation, Offline maps, Backup, Browser sync (`SyncSettings`), Privacy, Updates, Diagnostics, About), `Panels`, `Screens`, `ElevationProfile` (gradient colours), `Theme` (`OnPathColors` message tokens + `OnPathType` type scale 12/14/16/22/30 – no hand-set `fontSize` in ui/, `FontSizeRuleTest`), `Notices` (`vm.say(text, kind, place)` → `NoticeRouting`: a line in My routes / Settings where the user acted, else the light `ErrorCard` for errors only; `Busy` = spinner where work was started) |
| `  map/` | `MapController` (sources/layers the app adds, camera, location), `StyleLoader` ({DATA_DIR} substitution, format 1 / 2 style choice, fallback style), `LayerSettings` (Settings → POI filters, contour/hillshade visibility) |
| `  routing/` | `RoutingContract.kt` (shared interface – change with care), `BRouterEngine` (offline BRouter), `EngineProvider` (single place choosing the engine, falls back to `StraightLineEngine`), `Planner` (leg-by-leg fallback, splits at pinned POI points), `WaypointInsertion` (where a tapped point goes), `RouteProgress` (arrival check), `ArrivalGate` (when to ask "arrived": round trips, poor fixes), `PlanEdits` (reverse, round trip, move), `RouteSummary` (breakdown merge, difficulty label = distance + ascent raised by trail technique T3+ / S2+, a hike's ≥ 2 km at T2, a Bike / E-bike route ≥ 25 % unpaved or ≥ 5 km loose (`rating`), gradient classes) |
| `  recording/` | `RecordingService` (foreground location service, owns navigation / re-routing; every DB write guarded, failures are loud – see `safeDb` / `failLoud`), `LocationTracker`, `PointFilter` (which fixes become points: accuracy, jumps, standing still), `DemoLocation` + `DemoPath` (Developer tools → Demo: simulated fixes along the plan into both trackers, real GPS ignored, tours "Demo · …" – `docs/core-features.md`) |
| `  gpx/` | Tolerant `GpxParser`, `GpxWriter` (GPX 1.1), model, `SportType` (Komoot's `<trk><type>` ⇄ activity + T level on import / export) |
| `  data/` | `TourDb` (SQLite WAL), `Prefs`, `DataFiles` (required data check), `Geo` helpers, `AppSettings` (Settings page model), `FollowProgress` (remaining distance while following) |
| `  poi/` | POIs: model, kinds (+ `defaultHidden`), `PoiFilter` (layer filter JSON), tap lookup, details sheet, Google Maps link-out |
| `  cells/` | Map cells on the phone: `MapCells` (API for the UI), `CellStore` (one PMTiles per layer, rewritten as generations), `CellApi` (download client), `AppCells` (app-wide instance) – see `docs/design/map-cells.md` §19 |
| `  navigation/`, `time/`, `rides/` | Off-route + re-routing (`Navigator`, `Rerouter`, `NavSession`); junction cues (`JunctionCues`, `CueRule` – vibrate only where one could go wrong; vibration / screen / lock screen per activity: `docs/design/navigation-cues.md`); time estimate (`PaceModel` per-segment model, `RouteTimes`) + pace during a tour (`TripPace`); bus / car / lift in a tour (`RideDetector`, core feature C12) |
| `  search/`, `update/`, `debug/` | Place / address search (offline index + Photon); in-app updates (`AppUpdater`); `Problems` log, health / jank logging |
| `  setup/` | Phone setup rules (pure): `PhoneCheck` (location precise / on, notifications, vibration felt, battery, brand killers, battery saver, unused-app reset; required vs recommended; "removed by the phone"), `SetupGuide` (step flow) – UI in `ui/setup/` (`PhoneStateReader`, `SetupGuideScreen`, `PhoneSetupSection`), see `docs/design/onboarding-phone-check.md` |
| `  backup/` | Backups: auto GPX copy to Documents/OnPath, restore, recycle bin – see `docs/core-features.md` C11 |
| `  sync/`, `plansync/` | Plan sync phone ⇄ browser (A-007, `docs/design/web-sync.md` on `web-spike`): `sync/` = the pure code shared with the web planner (pairing code + keys, AES-GCM, item format with plans and imported tracks – `TrackCode`, ledger, merge "newer wins, older stays as a copy", `SyncRound`; `SharedCoreRuleTest`); `plansync/` = the app's side: `PlanSync` (background thread, triggers, never during a recording; `SyncPoll` – about every 60 s while the app is visible on Plan / My routes / Browser sync), `PlanSyncPass` (routes / imported tracks ⇄ items; a delete from outside → recycle bin), `HttpMailbox` (plan.onpath.earth/sync/v1), `SyncFiles` (files/sync/, atomic, not in Android backup); Settings → Browser sync. Test against the real mailbox in Node: `tools/sync/e2e.sh` |
| `app/libs/` | BRouter 1.7.11-beta jars (MIT), built by `tools/routing/build_brouter_jars.sh` |
| `app/src/main/assets/brouter/` | BRouter profiles (`touring.brf` – OnPath's own, `trekking.brf`, `mtb.brf`, `hiking-mountain.brf`) + `lookups.dat` |
| `app/src/main/assets/style/onpath.json` | Map style – **generated**, edit `tools/make_style.py` instead |
| `app/src/main/assets/fonts/` | Glyph PBFs (Noto Sans), no sprite |
| `tools/` | Data pipeline (Python/Java): terrain, contours, overlays, POIs, style generator, routing jar build + JVM test |
| `tools/cells/`, `tools/cloud/` | Map cells (`build_cell.sh`, zo: `run.sh`); cloud hosting: Cloud Run Job image + `job_build.py`, Cloudflare Worker doorman (`worker/`), `gcp-setup.sh`; deployed by `.github/workflows/maps-deploy.yml` – see `docs/design/map-hosting.md` |
| `tools/dev/` | Map cells built on this PC with the cloud job image: `push_cell.sh <cell>` (working tree → straight onto the phone, dev loop), `publish_cells.sh <cells…>` (origin/main → TEST R2 fast lane, the doorman adopts them) – see `docs/design/map-hosting.md` §7h |
| `strings/` | User texts EN + DE as Compose Multiplatform resources (small KMP module), `Txt` / `str()` – see `docs/i18n.md`; review round trip `tools/i18n/` |
| `docs/` | Requirements, architecture contract, project status |
| *(other repo)* | **Website onpath.earth** lives in its own repo `happychriss/onpath.earth` (Cloudflare Pages, owner) – not in this repo; app sessions don't add website files here. The browser planner (`web/`, branch `web-spike`) is a different thing: its code stays here, it is served under onpath.earth (`plan.onpath.earth`, owner). onpath.earth is OnPath's main address (welcome, help, privacy, imprint); 44-2.de is parked. |

## Build and test
- Requirements: JDK 17+ (21 used), Android SDK 35 (`local.properties` → `sdk.dir`, not committed).
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a only, ~32 MB).
- `./gradlew testDebugUnitTest` – JVM unit tests (run in UTC, so snapshots don't depend on the machine's time zone).
- `./gradlew :app:recordPaparazziDebug` / `verifyPaparazziDebug` – JVM screenshot tests of the UI
  (map replaced by a placeholder). Images in `app/src/test/snapshots/images/`. Use these to check layouts
  when no device/emulator is available.
- **Emulator (core features end to end):** `./gradlew connectedDebugAndroidTest -Ponpath.e2e=true` (needs an x86_64
  emulator; CI: `.github/workflows/e2e.yml` on every push to main, beside releases; a red run is
  fixed at once. Release gate = only the short `StartSmokeE2ETest`, the app opens). `-Ponpath.e2e=true` adds
  x86_64 libs – never for a released APK.
- Routing on the JVM without a phone: `tools/routing/jvm-test/run.sh <segmentsDir>` (header lists the test mains, e.g.
  `GpxCompare.kt` + `gpx_compare_report.py`: our route vs a reference GPX line – aggregate numbers only in the repo).
- Map ↔ router classes (format 2 overlays): `tools/routing/wayclass/build.sh` (classifier jar, needs a JDK) and
  `tools/routing/wayclass/consistency.py <package> <segmentsDir>` (routes never cross a way the map shows forbidden).
- Map style on the phone in a minute: `tools/dev/push_style.sh [theme.json | style.json | --remove]` (make_style.py →
  `dev/style.json` in the data folder + app restart; used only with developer tools on – Diagnostics shows it, Remove /
  Reload map style; `docs/map-style-notes.md` "Test style on the phone").
- Map style check without a phone: render `onpath.json` with maplibre-gl JS in headless Chromium
  (replace `{DATA_DIR}` / `asset://` URLs with a local HTTP server).

## Map data (not in git – too big)
Map data pipeline: see `docs/map-pipeline.md` and `tools/build_region.sh`.

Built per region by the pipeline, copied to the phone into
`/sdcard/Android/data/com.onpath.app/files/onpath/` (`adb push onpath-data/. <that dir>`):

| File | Built by | Required |
| --- | --- | --- |
| `base.pmtiles` | Planetiler (OpenMapTiles schema), z0–14 | yes |
| `terrain.pmtiles` | `tools/build_terrain.py` (AWS Terrarium tiles), z5–13 | yes |
| `routes.geojson`, `trails.geojson` | `tools/extract_overlays.py` (pyosmium) | yes |
| `contours.pmtiles` | `tools/build_contours.py` | optional |
| `pois.geojson` | `tools/extract_pois.py` | optional |
| `addresses.sqlite` | `tools/extract_addresses.py` (streets, house numbers, bilingual towns; SQLite, prefix word index; per cell also `addresses/<cell>.sqlite`) | optional (without it: no offline addresses) |
| `ways.geojson` | `tools/extract_overlays.py` (tracktype, loose surface, smoothness) | optional |
| `lowways.geojson` | `tools/extract_overlays.py` (all paths/tracks, coarse, drawn below z13) | optional |
| `trailnames.geojson` | `tools/extract_overlays.py` (trail names t="n", AVS numbers t="r") | optional |
| `paths.geojson` | `tools/extract_overlays.py` (every path/track, exact; drawn from z13 instead of the base map, tracktype → dots) | optional (without it the base map's lines are drawn) |
| `overlays.pmtiles` | **format 2** (`build_region.sh --format 2`): `extract_overlays.py --tiles --smooth` + `tools/build_overlays.sh` (tippecanoe) – routes, trails, ways, paths (+ former lowways), streets (small streets, smoothed), trailnames as source-layers of one vector file; base map then trimmed by `tools/trim_base.sh` (no path / track / small-street lines at z13–14) | yes in format 2, replaces the overlay `.geojson` files (app: `DataFiles.formatOf`, style `assets/style/f2/`; schema `docs/map-pipeline.md` 5b) |
| `segments/E10_N45.rd5` | download from https://brouter.de/brouter/segments4/ | yes |

Current test region: Bozen / Meran / Algund / Ultental (Zoggler Stausee), bbox `10.70,46.30,11.60,46.85`. OSM source:
`https://download.openstreetmap.fr/extracts/europe/italy/trentino_alto_adige-latest.osm.pbf`
(Geofabrik and Overpass are blocked from the cloud build machine).

Example pipeline:
```
java -Xmx6g -jar planetiler.jar --osm-path=region.osm.pbf --output=out/base.pmtiles --download \
  --bounds=10.70,46.30,11.60,46.85 --maxzoom=14 --force
python3 tools/build_terrain.py out/terrain.pmtiles 10.70 46.30 11.60 46.85 13
python3 tools/extract_overlays.py region.osm.pbf out 10.70 46.30 11.60 46.85
python3 tools/build_contours.py ...   # see script header
python3 tools/extract_pois.py ...     # see script header
python3 tools/make_style.py           # regenerates assets/style/onpath.json
```

## Releases (updates on the phone)
- Version: default `appVersion` in `app/build.gradle.kts` (versionCode = major*10000+minor*100+patch).
- To release: bump that version, add `docs/release-notes/vX.Y.Z.md`, push to `main`.
  `.github/workflows/release.yml` then tests, builds, signs (test key), creates tag `vX.Y.Z` and the
  GitHub Release with the APK – once per version (re-runs skip existing releases). The release runs
  **risk-based unit tests** (`tools/ci/risk_tests.py`: core-feature packages always + packages changed since the last
  release; build files → all) beside the emulator start test; the **full** unit suite runs after every push in
  `unit.yml` with the screenshot check (`verifyPaparazziDebug` against the committed images) and a lint job that only
  reports new findings (known ones in `app/lint-baseline.xml`) – not a gate; a red run is fixed at once, like E2E.
  Actions in the workflows are pinned to a commit (`# vX.Y.Z` beside it). Cloud sessions may not
  push tags or create releases themselves; this push-to-main flow avoids both. Manual: Actions → Release → Run.
- The app (Settings → Updates, plus a quiet daily check) installs new releases via `update/AppUpdater.kt`.

## Conventions
- **Kotlin**, Compose + Material 3, package `com.onpath.app`, minSdk 26, target/compileSdk 35.
- **No Google Play Services** (LocationManager directly); keep dependencies few.
- **Core features** (`docs/core-features.md`, e.g. hold a route point → menu → Delete): each has regression tests;
  a field bug in one first gets a test that reproduces it, then the fix (owner).
- **No silent errors:** every `catch` / `runCatching` that recovers reports to `debug/Problems`
  (`Problems.warn/error(area, plain message, e)`; INFO for expected situations like offline) – never `catch (_: …) {}`
  without an `// expected:` reason. Problems dedupes, persists, shows in Settings → Diagnostics, announces new ERRORs
  once, and goes into the diagnostics export; MapLibre's native warnings/errors and crashes are captured too (`OnPathApp`).
- **Messages** (owner): INFO = grey text, no box; ACTION = blue strip
  only for a question with buttons; WARNING = amber + ⚠ (tappable); ERROR = red, what failed + one way out. Colours from
  `OnPathColors`, sizes from `OnPathType` only. Dialogs only for decisions that can lose something, verb buttons, no body
  that repeats them. Progress = a spinner where the user started it. **No dark message bar** (owner): a
  result goes where the user acted (`vm.say(text, NoticeKind, NoticePlace)` → grey / red line at the top of My routes or
  the open Settings page, gone when that screen is left; route state → plan sheet lines); a result visible anyway is not
  said; an ERROR without such a place (background, link / share into the app) → the one light `ErrorCard` (red edge, ⚠,
  OK, never self-dismissing); an INFO without a place → problem log only (`NoticeRouting`, `NoticesTest`). Developer tools (Settings → Diagnostics) appear
  after 7 taps on the version in Settings → About (remembered; debug builds always).
- **Robustness rules** (requirements section 4): never crash on missing files – show a clear screen;
  write every recorded point to SQLite immediately; long work off the main thread; show plain-language
  errors (no stack traces) to the user.
- **Map camera**: MapLibre ends location tracking on every camera move made by the app
  (`moveCamera` / `easeCamera` / `animateCamera`) and reports it like a gesture. While tracking, never move the camera
  from code – turn it via the `CompassEngine` (`HeadingEngine`, TRACKING_COMPASS) and draw extras (the "where I face"
  arrow) as overlays. Before an intended camera fly (search result, fit a route) stop following first.
- **Routing**: always via the `RoutingEngine` interface; blocking calls on a background thread;
  newer requests supersede older ones in the UI.
- **Map style**: one style (`tools/themes/classic.json`; why each colour: `docs/design/map-colour-analysis.md`).
  Edit `tools/make_style.py`, regenerate JSON, keep to MapLibre Native features (no JS-only). Ground symbols are
  sprite images (`tools/make_sprite.py`). Layers with metadata `onpath:fallback-for` are drawn only when that source is missing.
  Layer ids the app depends on: `routes-hike`, `routes-bike` (MTB shows the cycle band), `poi-*`; the app's own layers
  (`planned-route`, `gpx-track`, `recorded-track`, `waypoints`, `position`) go on top.
- **Map labels** in German (`name:de` → `name`). **UI texts EN + DE** (owner, `docs/i18n.md`): every user
  text in `strings/…/composeResources/values[-de]/strings_<area>.xml` (Compose Multiplatform resources), used via
  `str(Res.string.x)` in Compose / `Txt.s(…)` elsewhere – no new hard-coded texts; review sheet round trip
  `tools/i18n/i18n.py export|import`. Diagnostics, Developer tools and the problem log stay English.
- **Data/licences**: the map shows a short "© OpenStreetMap"; a tap opens Settings → About with the full credits
  ("© OpenStreetMap contributors, © OpenMapTiles" + terrain attribution). Google
  content is never stored or drawn; only link out to Google Maps. Exception (owner): the coordinate
  of a place the user shares from Google Maps (share sheet / link, `search/SharedPlace.kt`) may be used to place our own
  marker (orange search ring); nothing else from Google is drawn or stored beyond that point and the shared name.
- **Activities** (exactly six): Bike, Gravelbike, E-bike, MTB, E-MTB, Hike (SAC T1–T5,
  default T2, chosen in the activity picker; the app remembers the last activity and T level). Names + the one picker line
  under each: `Activity.label` / `line`, chip `Activity.chip` ("Gravel") (enum names BIKE_TOURING / BIKE_TREKKING … unchanged and the only
  stored form). Profiles
  (`BRouterEngine.profileFile` – the one source; map layers, route warnings and the activity check ask it): Bike +
  E-bike → `touring.brf`, Gravelbike → `trekking.brf`, MTB + E-MTB → `mtb.brf`, Hike → `hiking-mountain.brf` with the
  T limit (E-bike / E-MTB change only power, speed and mass). In a sentence the activity reads "Hike T3"
  (`LastActivity.name`), on the chip "Hike · T3".
- **Git**: small commits with clear messages on `main` (single developer for now); never commit map data,
  APKs or `local.properties`.
- **Requirements** live in `docs/requirements.md` (exported from the shared Claude doc); keep IDs stable,
  add new ones at the end of a section.
