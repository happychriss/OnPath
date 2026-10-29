# OnPath – architecture contract

The shared interface is `app/src/main/java/com/onpath/app/routing/RoutingContract.kt` (plus this file) – change it with
care, every engine and fake implements it.

## Package and SDK
- applicationId / package: `com.onpath.app`, minSdk 26, target/compileSdk 35, Kotlin, Jetpack Compose (Material 3).
- Map: MapLibre Native Android `org.maplibre.gl:android-sdk:13.7.1` (supports `pmtiles://` sources).
- Android SDK: `/home/user/android-sdk`.

## Data on the phone
Big files are not in the APK. They live in
`/sdcard/Android/data/com.onpath.app/files/onpath/` (= `context.getExternalFilesDir(null)/onpath`); the file list and
what builds each file are in `CLAUDE.md` ("Map data") and `docs/map-pipeline.md`.

## Map style
- Asset `app/src/main/assets/style/onpath.json` (format 2: `assets/style/f2/`). The app reads it, replaces every `{DATA_DIR}` with the absolute data directory path, and loads it with `Style.Builder().fromJson(...)`.
- Sources: `base` (vector, `pmtiles://file://{DATA_DIR}/base.pmtiles`), `terrain` (raster-dem, terrarium, `pmtiles://file://{DATA_DIR}/terrain.pmtiles`), `routes` and `trails` (geojson, `file://{DATA_DIR}/routes.geojson`).
- Glyphs: `asset://fonts/{fontstack}/{range}.pbf`.
- Overlay layer ids the app toggles: `routes-hike`, `routes-bike` (hike → hike, every bike activity incl. MTB → bike; there is no `routes-mtb`: mtb.brf ignores route=mtb).
- The app adds its own sources/layers on top: `planned-route`, `gpx-track`, `recorded-track`, `waypoints`, `position`.
- POI categories: the `kind` property of `pois.geojson` features; groups are defined in `poi/PoiKinds.kt`. The app filters POIs by
  setting a filter on every layer whose id starts with `poi-`: `["all", <original filter>, ["in", ["get","kind"], ["literal", [...enabled kinds]]]]`.
  Style layers must therefore keep `kind` as a feature property and use expression filters (not legacy filters).
- POI icons: sprite at `asset://sprites/onpath` (`onpath.json/.png` + `@2x`), icon names `poi-<kind>`.
- Contour layers have ids starting with `contour-` (app can toggle them).

## Routing
- Contract in `RoutingContract.kt`. Implementation `BRouterEngine(context: Context, segmentsDir: File)`; profiles shipped in `assets/brouter/`.
- Activity → profile: BIKE_TOURING → touring, EBIKE → touring (higher power), BIKE_TREKKING → trekking, MTB → mtb, EMTB → mtb (higher power), HIKE → hiking-mountain with SAC limit. touring.brf = OnPath's trekking.brf copy for a normal bike (owner; `TouringProfileTest` pins the differences, `docs/design/bike-routing.md`). Names the user sees (`Activity.label` + `line`, owner): BIKE_TOURING "Bike", BIKE_TREKKING "Gravelbike" (chip "Gravel"), EBIKE "E-bike", MTB "MTB", EMTB "E-MTB", HIKE "Hike"; stored values keep the enum names.
- `RouteResult` has `wayTypes` and `surfaces` (`List<Share>`, defaults empty). Keys are listed in `RoutingContract.kt`.
- `RouteResult.matched` (`List<GeoPoint?>`, default empty): where the engine snapped each input waypoint onto a way (BRouter crosspoint).
  `Planner` turns it into `PlannedRoute.snapped`; the app moves free (not POI-pinned) plan points there (`WaypointSnap`).

## Re-routing while navigating
- `RoutingEngine.route(waypoints, activity, sacLimit, limits: RouteLimits)` – optional overload with a default
  implementation (calls the plain `route`), so existing engines and fakes stay valid. `RouteLimits(timeoutMs, cancel)`:
  upper bound incl. waiting for another request, and a `RouteCancel` (BRouter `terminate()`). `BRouterEngine` uses a
  static `ReentrantLock` (`tryLock` within the budget). Used by `navigation/Rerouter` (8 s per request, 12 s per re-route).
- `RoutingException(message, timedOut = false)`: `timedOut` = the search budget ran out or the router stayed
  busy; `Planner` then shows straight lines at once instead of retrying leg by leg. BRouter's own search limit per
  request comes from `routing/RouteTimeLimit` (by straight-line km and profile, 15 s … 45 s hike / 90 s bike / 120 s MTB).

## Way attributes on the route line (time estimate P4)
- `GeoPoint.way: WayAttrs?` (default null): what the way from this point to the next is like (way type / surface keys
  as in `Share`, SAC 1..6, mtb:scale, smoothness, trail_visibility, rough surface, bike access `bicycle`
  (null = unknown; 8th field of the text code, older codes parse with `bicycle = null`), `cycleRoute`
  (route_bicycle_icn|ncn|rcn|lcn, 9th field "r", older codes = false) and `pedestrian`
  (highway=pedestrian, flag "p" in the same 9th field); route hints: `ford` (flag "f": a ford way,
  or the span ends at a ford node), `construction` (flag "c": highway=construction) and `barrier` (10th field: stile /
  kissing_gate / turnstile / full-height_turnstile at the node ending the span – node tags of BRouter's message, other
  barriers are not kept); older codes = none. Set by `BRouterEngine` on every
  routed point (`BRouterWayStats.perNode`, from the track messages); null on straight legs, imported tracks and lines
  stored without it. **`GeoPoint.equals`/`hashCode` ignore `way`**, so comparisons see only the position.
- Because the attributes ride on the points, they survive leg-by-leg fallback, chunking at pinned points, the
  re-route splice (`NavLine.pointAt` keeps the span's `way`) and the navigation session file (`NavSession` writes
  optional `originalWays` / `currentWays` run lists; old files load without them). Saved routes (`TourDb`) store no
  attributes; their list time comes from `time/PlanTimesStore` written at save, else from the bare points.
- Consumers: `time/RouteTimes` (per 50 m piece) and `time/TimeBreakdown` ("By way" table). Unknown → the plain times without way attributes.
  `ui/RouteWarnings` (amber line in the plan sheet): ferry (wayType "ferry") / construction / pushing / steps / above T
  level / ford / barrier (bikes) / steep; `bicycle` = yes / designated / permissive on a footway or path → rideable,
  `dismount` → pushing.
