# OnPath – build contract (first test build)

## Ownership (who edits what)
| Area | Owner | Paths |
| --- | --- | --- |
| Map data, style, fonts | map agent | `tools/` (except routing), `app/src/main/assets/style/`, `app/src/main/assets/fonts/` |
| Routing engine | routing agent | `app/libs/`, `app/src/main/assets/brouter/`, `app/src/main/java/com/onpath/app/routing/BRouterEngine.kt`, `tools/routing/` |
| App (UI, GPX, recording, Gradle) | app agent | everything else under `app/`, root Gradle files |
| Shared contract | lead | `app/src/main/java/com/onpath/app/routing/RoutingContract.kt`, this file |

## Package and SDK
- applicationId / package: `com.onpath.app`, minSdk 26, target/compileSdk 35, Kotlin, Jetpack Compose (Material 3).
- Map: MapLibre Native Android `org.maplibre.gl:android-sdk:13.7.1` (supports `pmtiles://` sources).
- Android SDK: `/home/user/android-sdk`.

## Data on the phone
Big files are not in the APK. They live in
`/sdcard/Android/data/com.onpath.app/files/onpath/` (= `context.getExternalFilesDir(null)/onpath`), copied with `adb push`:

| File | Content |
| --- | --- |
| `base.pmtiles` | OpenMapTiles-schema vector tiles (Planetiler), zoom 0–14 |
| `terrain.pmtiles` | Terrarium-encoded elevation PNG tiles, zoom 5–13 |
| `routes.geojson` | Official route relations: property `kind` = `hike` / `bike` / `mtb`, plus `ref`, `name` |
| `trails.geojson` | Paths with `sac` (1–6) and optional `mtb` (0–6) |
| `segments/E10_N45.rd5` | BRouter routing data |

Test region bbox: 10.85, 46.35, 11.60, 46.85 (Bozen / Meran / Algund).

## Map style
- Asset `app/src/main/assets/style/onpath.json`. The app reads it, replaces every `{DATA_DIR}` with the absolute data directory path, and loads it with `Style.Builder().fromJson(...)`.
- Sources: `base` (vector, `pmtiles://file://{DATA_DIR}/base.pmtiles`), `terrain` (raster-dem, terrarium, `pmtiles://file://{DATA_DIR}/terrain.pmtiles`), `routes` and `trails` (geojson, `file://{DATA_DIR}/routes.geojson`).
- Glyphs: `asset://fonts/{fontstack}/{range}.pbf`. No sprite; no icons needed.
- Overlay layer ids the app toggles: `routes-hike`, `routes-bike`, `routes-mtb` (visible by default per activity: hike → hike, bikes → bike, MTB → mtb).
- The app adds its own sources/layers on top: `planned-route`, `gpx-track`, `recorded-track`, `waypoints`, `position`.

## Routing
- Contract in `RoutingContract.kt`. Implementation `BRouterEngine(context: Context, segmentsDir: File)`; profiles shipped in `assets/brouter/`.
- Activity → profile: BIKE_TOURING → fastbike-verylowtraffic, BIKE_TREKKING → trekking, EBIKE → trekking (higher power), MTB → mtb, EMTB → mtb (higher power), HIKE → hiking-mountain with SAC limit.
