# OnPath – guide for AI assistants and contributors

**Two sessions work on this repo (laptop + cloud): follow `docs/working-agreement.md` – pull first, claim in
`docs/backlog.md`, push small green commits, leave a handoff.**

Read this first, then `docs/project-status.md` (where we are, decisions, next steps) and
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
| `  ui/` | `AppViewModel` (all UI state, Plan ⇄ Navigate switching), `AppMode` (PLAN / NAVIGATE + mode on start, see `docs/ux-two-modes.md`), `MapScreen` (map + Plan mode controls), `NavigateScreen` (Navigate: remaining strip, recording chip, arrival check), `SlideToUnlock` (slide-to-plan switch + `SlideThreshold`), `PlanTopBar` (search pill + ⋮ menu: My tours, Import GPX, Settings, Updates; activity chip), `MapFooter` (scale, attribution, small map FABs: locate with GPS dot), `TourBar` (Tour running status + End tour, arrival dialogs), `MapHost` (MapView in Compose; map taps edit only when `canEditMap`), `PlanPanel` (plan sheet: status, stop chips, one primary button, route actions in ⋯; + `PlanExtras`: notice bar, point menu), `RouteBreakdown` (way type / surface bars, difficulty badge), `SettingsScreen`, `Panels`, `Screens`, `ElevationProfile` (gradient colours), `Theme` |
| `  map/` | `MapController` (sources/layers the app adds, camera, location), `StyleLoader` ({DATA_DIR} substitution, fallback style), `LayerSettings` (Settings → POI filters, contour/hillshade visibility) |
| `  routing/` | `RoutingContract.kt` (shared interface – change with care), `BRouterEngine` (offline BRouter), `EngineProvider` (single place choosing the engine, falls back to `StraightLineEngine`), `Planner` (leg-by-leg fallback, splits at pinned POI points), `WaypointInsertion` (where a tapped point goes), `RouteProgress` (stops already passed for "Continue navigation", arrival check), `ArrivalGate` (when to ask "arrived": round trips, poor fixes), `PlanEdits` (reverse, round trip, move), `RouteSummary` (breakdown merge, difficulty formula, gradient classes) |
| `  recording/` | `RecordingService` (foreground location service, owns navigation / re-routing; every DB write guarded, failures are loud – see `safeDb` / `failLoud`), `LocationTracker`, `PointFilter` (which fixes become points: accuracy, jumps, standing still) |
| `  gpx/` | Tolerant `GpxParser`, `GpxWriter` (GPX 1.1), model |
| `  data/` | `TourDb` (SQLite WAL), `Prefs`, `DataFiles` (required data check), `Geo` helpers, `AppSettings` (Settings page model), `FollowProgress` (remaining distance while following) |
| `  poi/` | POIs: model, kinds (+ `defaultHidden`), `PoiFilter` (layer filter JSON), tap lookup, details sheet, Google Maps link-out |
| `app/libs/` | BRouter 1.7.11-beta jars (MIT), built by `tools/routing/build_brouter_jars.sh` |
| `app/src/main/assets/brouter/` | BRouter profiles + `lookups.dat` |
| `app/src/main/assets/style/onpath.json` | Map style – **generated**, edit `tools/make_style.py` instead |
| `app/src/main/assets/fonts/` | Glyph PBFs (Noto Sans), no sprite |
| `tools/` | Data pipeline (Python/Java): terrain, contours, overlays, POIs, style generator, routing jar build + JVM test |
| `tools/cells/`, `tools/cloud/` | Map cells (`build_cell.sh`, zo: `run.sh`); cloud hosting: Cloud Run Job image + `job_build.py`, Cloudflare Worker doorman (`worker/`), `gcp-setup.sh`; deployed by `.github/workflows/maps-deploy.yml` – see `docs/design/map-hosting.md` |
| `tools/hetzner/` | Superseded region server (Hetzner): `onpath_hetzner.py` (laptop: creates only its own labelled Hetzner server), `server/` (install.sh, API, worker, web page) – see its README |
| `docs/` | Requirements, architecture contract, project status |

## Build and test
- Requirements: JDK 17+ (21 used), Android SDK 35 (`local.properties` → `sdk.dir`, not committed).
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a only, ~32 MB).
- `./gradlew testDebugUnitTest` – GPX/geo/POI unit tests.
- `./gradlew :app:recordPaparazziDebug` / `verifyPaparazziDebug` – JVM screenshot tests of the UI
  (map replaced by a placeholder). Images in `app/src/test/snapshots/images/`. Use these to check layouts
  when no device/emulator is available.
- Routing on the JVM without a phone: `tools/routing/jvm-test/run.sh <segmentsDir>`.
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
  GitHub Release with the APK – once per version (re-runs skip existing releases). Cloud sessions may not
  push tags or create releases themselves; this push-to-main flow avoids both. Manual: Actions → Release → Run.
- The app (Settings → Updates, plus a quiet daily check) installs new releases via `update/AppUpdater.kt`.

## Conventions
- **Kotlin**, Compose + Material 3, package `com.onpath.app`, minSdk 26, target/compileSdk 35.
- **No Google Play Services** (LocationManager directly); keep dependencies few.
- **No silent errors:** every `catch` / `runCatching` that recovers reports to `debug/Problems`
  (`Problems.warn/error(area, plain message, e)`; INFO for expected situations like offline) – never `catch (_: …) {}`
  without an `// expected:` reason. Problems dedupes, persists, shows in Settings → Diagnostics, announces new ERRORs
  once, and goes into the diagnostics export; MapLibre's native warnings/errors and crashes are captured too (`OnPathApp`).
- **Robustness rules** (requirements section 4): never crash on missing files – show a clear screen;
  write every recorded point to SQLite immediately; long work off the main thread; show plain-language
  errors (no stack traces) to the user.
- **Map camera** (lesson of 2026-10-20): MapLibre ends location tracking on every camera move made by the app
  (`moveCamera` / `easeCamera` / `animateCamera`) and reports it like a gesture. While tracking, never move the camera
  from code – turn it via the `CompassEngine` (`HeadingEngine`, TRACKING_COMPASS) and draw extras (the "where I face"
  arrow) as overlays. Before an intended camera fly (search result, fit a route) stop following first.
- **Routing**: always via the `RoutingEngine` interface; blocking calls on a background thread;
  newer requests supersede older ones in the UI.
- **Map style**: one style since 0.7.0 (`tools/themes/classic.json`; why each colour: `docs/design/map-colour-analysis.md`).
  Edit `tools/make_style.py`, regenerate JSON, keep to MapLibre Native features (no JS-only). Ground symbols are
  sprite images (`tools/make_sprite.py`). Layers with metadata `onpath:fallback-for` are drawn only when that source is missing.
  Layer ids the app depends on: `routes-hike`, `routes-bike`, `routes-mtb`, `poi-*`; the app's own layers
  (`planned-route`, `gpx-track`, `recorded-track`, `waypoints`, `position`) go on top.
- **Map labels** in German (`name:de` → `name`); UI strings currently English, hard-coded (to move to
  `strings.xml` with EN + DE).
- **Data/licences**: show "© OpenStreetMap contributors, © OpenMapTiles" + terrain attribution. Google
  content is never stored or drawn; only link out to Google Maps. Exception (owner decision 2026-10-20): the coordinate
  of a place the user shares from Google Maps (share sheet / link, `search/SharedPlace.kt`) may be used to place our own
  marker (orange search ring); nothing else from Google is drawn or stored beyond that point and the shared name.
- **Activities** (exactly six): Bike touring, Bike trekking, E-bike, MTB, MTB e-bike, Hike (SAC T1–T5,
  default T3). Mapping to BRouter profiles is in `BRouterEngine`.
- **Git**: small commits with clear messages on `main` (single developer for now); never commit map data,
  APKs or `local.properties`.
- **Requirements** live in `docs/requirements.md` (exported from the shared Claude doc); keep IDs stable,
  add new ones at the end of a section.
