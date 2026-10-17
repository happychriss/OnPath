# OnPath – guide for AI assistants and contributors

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
| `  ui/` | `AppViewModel` (all UI state), `MapScreen`, `MapHost` (MapView in Compose), `Panels`, `Screens`, `ElevationProfile`, `Theme` |
| `  map/` | `MapController` (sources/layers the app adds, camera, location), `StyleLoader` ({DATA_DIR} substitution, fallback style) |
| `  routing/` | `RoutingContract.kt` (shared interface – change with care), `BRouterEngine` (offline BRouter), `EngineProvider` (single place choosing the engine, falls back to `StraightLineEngine`), `Planner` (leg-by-leg fallback) |
| `  recording/` | `RecordingService` (foreground location service, off-route warning), `LocationTracker` |
| `  gpx/` | Tolerant `GpxParser`, `GpxWriter` (GPX 1.1), model |
| `  data/` | `TourDb` (SQLite WAL), `Prefs`, `DataFiles` (required data check), `Geo` helpers |
| `  poi/` | POIs: model, kinds, tap lookup, details sheet, Google Maps link-out |
| `app/libs/` | BRouter 1.7.11-beta jars (MIT), built by `tools/routing/build_brouter_jars.sh` |
| `app/src/main/assets/brouter/` | BRouter profiles + `lookups.dat` |
| `app/src/main/assets/style/onpath.json` | Map style – **generated**, edit `tools/make_style.py` instead |
| `app/src/main/assets/fonts/` | Glyph PBFs (Noto Sans), no sprite |
| `tools/` | Data pipeline (Python/Java): terrain, contours, overlays, POIs, style generator, routing jar build + JVM test |
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
Built per region by the pipeline, copied to the phone into
`/sdcard/Android/data/com.onpath.app/files/onpath/` (`adb push onpath-data/. <that dir>`):

| File | Built by | Required |
| --- | --- | --- |
| `base.pmtiles` | Planetiler (OpenMapTiles schema), z0–14 | yes |
| `terrain.pmtiles` | `tools/build_terrain.py` (AWS Terrarium tiles), z5–13 | yes |
| `routes.geojson`, `trails.geojson` | `tools/extract_overlays.py` (pyosmium) | yes |
| `contours.pmtiles` | `tools/build_contours.py` | optional |
| `pois.geojson` | `tools/extract_pois.py` | optional |
| `segments/E10_N45.rd5` | download from https://brouter.de/brouter/segments4/ | yes |

Current test region: Bozen / Meran / Algund, bbox `10.85,46.35,11.60,46.85`. OSM source:
`https://download.openstreetmap.fr/extracts/europe/italy/trentino_alto_adige-latest.osm.pbf`
(Geofabrik and Overpass are blocked from the cloud build machine).

Example pipeline:
```
java -Xmx6g -jar planetiler.jar --osm-path=region.osm.pbf --output=out/base.pmtiles --download \
  --bounds=10.85,46.35,11.60,46.85 --maxzoom=14 --force
python3 tools/build_terrain.py out/terrain.pmtiles 10.85 46.35 11.60 46.85 13
python3 tools/extract_overlays.py region.osm.pbf out 10.85 46.35 11.60 46.85
python3 tools/build_contours.py ...   # see script header
python3 tools/extract_pois.py ...     # see script header
python3 tools/make_style.py           # regenerates assets/style/onpath.json
```

## Conventions
- **Kotlin**, Compose + Material 3, package `com.onpath.app`, minSdk 26, target/compileSdk 35.
- **No Google Play Services** (LocationManager directly); keep dependencies few.
- **Robustness rules** (requirements section 4): never crash on missing files – show a clear screen;
  write every recorded point to SQLite immediately; long work off the main thread; show plain-language
  errors (no stack traces) to the user.
- **Routing**: always via the `RoutingEngine` interface; blocking calls on a background thread;
  newer requests supersede older ones in the UI.
- **Map style**: edit `tools/make_style.py`, regenerate JSON, keep to MapLibre Native features (no JS-only).
  Layer ids the app depends on: `routes-hike`, `routes-bike`, `routes-mtb`, `poi-*`; the app's own layers
  (`planned-route`, `gpx-track`, `recorded-track`, `waypoints`, `position`) go on top.
- **Map labels** in German (`name:de` → `name`); UI strings currently English, hard-coded (to move to
  `strings.xml` with EN + DE).
- **Data/licences**: show "© OpenStreetMap contributors, © OpenMapTiles" + terrain attribution. Google
  content is never stored or drawn; only link out to Google Maps.
- **Activities** (exactly six): Bike touring, Bike trekking, E-bike, MTB, MTB e-bike, Hike (SAC T1–T5,
  default T3). Mapping to BRouter profiles is in `BRouterEngine`.
- **Git**: small commits with clear messages on `main` (single developer for now); never commit map data,
  APKs or `local.properties`.
- **Requirements** live in `docs/requirements.md` (exported from the shared Claude doc); keep IDs stable,
  add new ones at the end of a section.
