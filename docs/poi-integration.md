# POI integration (F-MAP-09)

All POI data is OpenStreetMap (ODbL). Google Maps is a link-out only: nothing from Google is fetched, stored or shown.

## Data file (optional)
- Build: `python3 tools/extract_pois.py /home/user/data/trentino.osm.pbf /home/user/data/out 10.85 46.35 11.60 46.85` (~2 min).
- Push: `adb push /home/user/data/out/pois.geojson /sdcard/Android/data/com.onpath.app/files/onpath/`
- The file is OPTIONAL. Without it the app must still start: `map/OptionalSources` drops the POI source and layers when it is missing.
- Mountain huts are drawn from `pois.geojson` only (no base-tile hut layers, to avoid duplicates).

## Style
- Source `pois` (geojson, `file://{DATA_DIR}/pois.geojson`).
- Three symbol layers `poi-tier3`, `poi-tier2`, `poi-tier1` (bottom to top, minzoom 15 / 14 / 12), icons from the
  sprite `asset://sprites/onpath` (`poi-<kind>`). Details and default visibility per kind: `docs/poi-categories.md`.
- The app finds POI layers by the prefix `poi-` (tap lookup, Settings filter), so new tiers need no app change.
- Settings filter (`map/LayerSettings.kt` + `poi/PoiFilter.kt`): each poi- layer's original filter is read once from
  the loaded style JSON and wrapped as `["all", <original>, ["in", ["get","kind"], ["literal", [enabled kinds]]]]`.
  Default hidden kinds: `PoiKinds.defaultHidden`.

## API (package `com.onpath.app.poi`)
- `Poi` (data class) with `Poi.fromFeature(feature)` and `Poi.fromProperties(map, lat, lon)`, plus `title()`, `openingHoursLines`, `cuisineLabel`, `osmUrl`.
- `PoiKinds.of(kind)`: labels (EN/DE), group colour, glyph.
- `PoiLinks` (pure URL builders, unit-tested) and `PoiIntents` (both in `GoogleMapsLink.kt`) (Google Maps app or browser, website, wikipedia, `ACTION_DIAL`).
- "Open in Google Maps" (`PoiLinks.googleMaps` → `PoiIntents.openGoogleMaps`): a named place opens as
  `geo:<lat>,<lon>?z=16&q=<name>` – a name search that Google biases to our coordinate, so it lands on the business
  card when Google knows the place; an unnamed point opens as an exact pin `geo:0,0?q=<lat>,<lon>(<kind>)` (`api=1&query=<name lat,lon>` has no
  location bias and often lands elsewhere). Browser fallback: `maps/search/<name>/@lat,lon,16z`
  or `api=1&query=<lat,lon>`. Built offline; no place IDs (they would need the online Places API).
- `findPoiAt(map, screenPoint, boxDp = 24f)`: queries all `poi-*` layers. The nearest hit wins. It never throws.
- `PoiDetails(poi, onClose)`: the Material 3 sheet content.
- Tests: `app/src/test/java/com/onpath/app/poi/PoiTest.kt`.
