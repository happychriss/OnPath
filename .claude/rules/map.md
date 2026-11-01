---
paths:
  - "app/src/main/java/com/onpath/app/map/**"
  - "app/src/main/java/com/onpath/app/poi/**"
  - "app/src/main/java/com/onpath/app/cells/**"
  - "app/src/main/assets/style/**"
  - "app/src/main/assets/fonts/**"
  - "app/src/main/assets/sprites/**"
  - "tools/make_style.py"
  - "tools/make_sprite.py"
  - "tools/themes/**"
  - "docs/map-style-notes.md"
  - "docs/design/map-cells.md"
---
# Map, style, POIs, map cells (loads when you touch map/, poi/, cells/, the style or the sprites)

| Path | What |
| --- | --- |
| `  map/` | `MapController` (sources/layers the app adds, camera, location; applies `ActivityLayers`), `ActivityLayers` (pure, **shared with the web engine**: activity + T level → layer visibility and the hiking band filter – the map look per activity, identical in app and browser), `StyleLoader` ({DATA_DIR} substitution, format 1 / 2 style choice, fallback style), `LayerSettings` (Settings → POI filters, contour/hillshade visibility), `CandidateMarker` (a search result / shared place not yet in the route: dashed route-blue ring with "+", thin dashed preview line to where it would join – `WaypointInsertion.joinPoint`) |
| `  poi/` | POIs: model, kinds (+ `defaultHidden`), `PoiFilter` (layer filter JSON), tap lookup, details sheet, Google Maps link-out |
| `  cells/` | Map cells on the phone: `MapCells` (API for the UI), `CellStore` (one PMTiles per layer, rewritten as generations), `CellApi` (download client), `AppCells` (app-wide instance), `RouterClassCheck` (cells' router-class hash vs the app's profiles, `routing/RouterClassHash` → warning + quiet refresh) – see `docs/design/map-cells.md` §19 |
| `app/src/main/assets/style/onpath.json` | Map style – **generated**, edit `tools/make_style.py` instead |
| `app/src/main/assets/fonts/` | Glyph PBFs (Noto Sans), no sprite |

- **Map camera**: MapLibre ends location tracking on every camera move made by the app
  (`moveCamera` / `easeCamera` / `animateCamera`) and reports it like a gesture. While tracking, never move the camera
  from code – turn it via the `CompassEngine` (`HeadingEngine`, TRACKING_COMPASS) and draw extras (the "where I face"
  arrow) as overlays. Before an intended camera fly (search result, fit a route) stop following first.
- **Map style**: one style (`tools/themes/classic.json`; why each colour: `docs/design/map-colour-analysis.md`).
  Edit `tools/make_style.py`, regenerate JSON, keep to MapLibre Native features (no JS-only). Ground symbols are
  sprite images (`tools/make_sprite.py`). Layers with metadata `onpath:fallback-for` are drawn only when that source is missing.
  Layer ids the app depends on: `routes-hike`, `routes-bike` (MTB shows the cycle band), `poi-*`; the app's own layers
  (`planned-route`, `gpx-track`, `recorded-track`, `waypoints`, `position`) go on top.
- **Data/licences**: the map shows a short "© OpenStreetMap"; a tap opens Settings → About with the full credits
  ("© OpenStreetMap contributors, © OpenMapTiles" + terrain attribution) and About → Licences (every bundled library
  with version + licence, texts in `assets/licences/`, list from the build – `data/Licences.kt`, `LicencesTest`). Google
  content is never stored or drawn; only link out to Google Maps. Exception (owner): the coordinate
  of a place the user shares from Google Maps (share sheet / link, `search/SharedPlace.kt`) may be used to place our own
  marker (the candidate marker, `map/CandidateMarker.kt`); nothing else from Google is drawn or stored beyond that point and the shared name /
  address. A short link is only followed through its redirects – the Google Maps page is never loaded or read (owner
  2026-10-31, A-020); without a coordinate: our offline index → Photon → Android's `Geocoder` (on most phones a Google
  service), each hit checked against the shared town (`SharedPlaceResolve`, `SharedPlaceLookup`).

- Map style on the phone in a minute: `tools/dev/push_style.sh [theme.json | style.json | --remove]` (make_style.py → `dev/style.json` in the data folder + app restart; used only with developer tools on – Diagnostics shows it, Remove / Reload map style; `docs/map-style-notes.md` "Test style on the phone").
- Map style check without a phone: render `onpath.json` with maplibre-gl JS in headless Chromium (replace `{DATA_DIR}` / `asset://` URLs with a local HTTP server).
