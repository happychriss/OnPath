# POI integration (F-MAP-09)

All POI data is OpenStreetMap (ODbL). Google Maps is a link-out only: nothing from Google is fetched, stored or shown.

## Data file (optional)
- Build: `python3 tools/extract_pois.py /home/user/data/trentino.osm.pbf /home/user/data/out 10.85 46.35 11.60 46.85` (~2 min).
- Push: `adb push /home/user/data/out/pois.geojson /sdcard/Android/data/com.onpath.app/files/onpath/`
- The file is OPTIONAL. Without it the app must still start. Strip the POI source and layers when it is missing (see step 1).
- Mountain huts are now drawn from `pois.geojson` (the old base-tile `hut-dot` and `hut-label` layers were removed, to avoid duplicates).

## Style
- Source `pois` (geojson, `file://{DATA_DIR}/pois.geojson`).
- Layers (all ids start with `poi-`, placed below the place/peak labels, so app layers added on top stay above them):
  `poi-dot-z13`, `poi-glyph-z13`, `poi-dot-z14`, `poi-glyph-z14`, `poi-dot-z15`, `poi-glyph-z15`, `poi-label-major`, `poi-label`.
- The symbol glyphs need `fonts/Noto Sans Regular/9728-9983.pbf` (added).

## Code changes for the lead (3 small edits)

1. `map/StyleLoader.kt`, `load()`: after replacing `{DATA_DIR}`:
```kotlin
json.replace("{DATA_DIR}", dir).let { com.onpath.app.poi.stripPoisIfMissing(it, dataDir) }
```

2. `ui/MapHost.kt`, map click: check for a POI first, before adding a route point:
```kotlin
map.addOnMapClickListener { ll ->
    val poi = com.onpath.app.poi.findPoiAt(map, map.projection.toScreenLocation(ll))
    when {
        poi != null -> { vm.selectedPoi = poi; true }          // show details, do NOT add a route point
        vm.mode == Mode.PLAN -> { vm.addPoint(GeoPoint(ll.latitude, ll.longitude)); true }
        else -> false
    }
}
```

3. `AppViewModel`: `var selectedPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)`.
   In `MapScreen`, show the bottom sheet:
```kotlin
vm.selectedPoi?.let { poi ->
    ModalBottomSheet(onDismissRequest = { vm.selectedPoi = null }) {   // @OptIn(ExperimentalMaterial3Api::class)
        com.onpath.app.poi.PoiDetails(poi = poi, onClose = { vm.selectedPoi = null })
    }
}
```

## API (package `com.onpath.app.poi`)
- `Poi` (data class) with `Poi.fromFeature(feature)` and `Poi.fromProperties(map, lat, lon)`, plus `title()`, `openingHoursLines`, `cuisineLabel`, `osmUrl`.
- `PoiKinds.of(kind)`: labels (EN/DE), group colour, glyph.
- `PoiLinks` (pure URL builders, unit-tested) and `PoiIntents` (Google Maps app or browser, website, wikipedia, `ACTION_DIAL`).
- `findPoiAt(map, screenPoint, boxDp = 24f)`: queries all `poi-*` layers. The nearest hit wins. It never throws.
- `PoiDetails(poi, onClose)`: the Material 3 sheet content.
- Tests: `app/src/test/java/com/onpath/app/poi/PoiTest.kt`.
