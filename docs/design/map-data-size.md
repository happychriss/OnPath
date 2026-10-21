# Map data size – how to make it compact on the phone (proposal)

Status: **being implemented as "format 2"** (laptop, map rework 2026-10-20). Done: terrain + contours
(`--format 2`, §1a) and the overview map for the APK (§1b). Format 1 stays the default until the app reads all of it.
Measured 2026-10-19 (laptop) on the Bozen/Meran data (OSM of 17 Oct 2026), Planetiler 0.10.2, tippecanoe 2.79.0,
MapLibre Native Android 13.7.1. Scratch scripts were not committed; the method for each number is described below.

## Summary

| Data | Today per cell | Proposed per cell | Change | Who | Work |
| --- | --- | --- | --- | --- | --- |
| `terrain.pmtiles` | 19 MB | **≈ 1 MB** | z13 dropped (max zoom 12), heights rounded to 0.25 m, WebP lossless, off-by-one edge tiles fixed | pipeline | small |
| 6 GeoJSON overlays (paths, lowways, ways, trails, routes, trailnames) | 11.0 MB | **≈ 2.4 MB** | one vector `overlays.pmtiles` (tippecanoe), lowways = paths at z11–12 | pipeline + app | medium |
| `contours.pmtiles` | 1.1 MB | 1.1 MB | becomes a layer of `overlays.pmtiles` (one file less, same size) | pipeline + app | small |
| `base.pmtiles` | 6.1 MB | **≈ 4.4 MB** | drop unused layers + 80 label languages (−14 %), drop path/track lines that `paths` already draws (−15 %) | pipeline (+ style) | small |
| `pois.geojson` | 0.6 MB | 0.6 MB | keep for now (search + POI tap read it); join = merge lists on the phone | app | small |
| **Total per cell (unzipped)** | **38 MB** | **≈ 10 MB** | −74 % | | |
| Download per cell (zip) | 28 MB | ≈ 10 MB | tiles are already compressed, zip gains little | | |
| **3 × 3 cells (holiday area ~80 × 80 km)** | **≈ 340 MB** | **≈ 90 MB** | | | |
| Routing (`.rd5`) | 199 MB per 5° × 5° | **5–13 MB per 1° × 1°** (median 8 MB) | phone fetches only the 1° parts it needs (HTTP range) | app | medium |
| Routing for the 3 × 3 area around Meran | 199 MB | **31 MB** (4 parts) | | | |

"Today per cell" = the real z10 cell x543 y361 (Meran, 10.90–11.25 E, 46.56–46.80 N) built on the owner's server
"zo" with today's pipeline. "Proposed" = that number × the saving measured on the whole test region.
3 × 3 = 9 × one cell (Meran is a dense cell, so this is on the high side).

**Recommendation – in this order:**

1. **Terrain** (−18 MB per cell, −95 %). Pipeline only, no app change; one test on the phone that MapLibre draws a
   WebP hill shade. Biggest win for the least work.
2. **Overlays as one vector PMTiles** (−8.6 MB per cell, much less RAM, faster start). Needed anyway before cells can
   be joined: nine cells of GeoJSON would be ~100 MB parsed in memory, and GeoJSON files cannot be joined without
   rewriting them. Already on the backlog ("Overlays as PMTiles").
3. **Routing by 1° part** (199 MB → 8–31 MB). App only (`SegmentDownloader`), brouter.de stays the source.
4. Base map trims (−1.7 MB per cell) – two flags plus one filter step; do it together with 2.
5. Phone-side store for joined cells – decide after a short spike (see "Joining cells on the phone").

## 1. Terrain (`terrain.pmtiles`, Terrarium PNG z5–13)

Measured on today's `terrain.pmtiles` (433 tiles, 54.6 MB). Every variant re-encodes the same tiles:

| Variant | Size | vs today |
| --- | --- | --- |
| Today: PNG z5–13 | 54.6 MB | – |
| PNG re-compressed (optimize) | 54.6 MB | 0 % |
| WebP lossless, exact | 37.7 MB | −31 % |
| WebP lossless, heights rounded to 0.25 m | 16.7 MB | −69 % |
| WebP lossless, heights rounded to 1 m | 8.9 MB | −84 % |
| PNG, heights rounded to 1 m | 15.0 MB | −73 % |
| **max zoom 12 + 0.25 m + WebP** | **6.6 MB** | **−88 %** |
| max zoom 12 + 1 m + WebP | 4.0 MB | −93 % |

Why it is safe:
- **z13 adds almost nothing.** The AWS tiles at z13 (13 m pixels) are upsampled from ~30 m source data here.
  Upsampling our z12 tile and comparing it with the real z13 tile: height difference 0.7 m RMS (median tile,
  p90 1.5 m), slope difference 0.8° (p90 1.3°). z13 is 69 % of the file. MapLibre over-zooms z12 like it already
  over-zooms z13 today.
- **The blue channel is noise.** Terrarium stores height = R·256 + G + B/256 − 32768; B holds 1/256 m fractions that
  the hill shade cannot show but that PNG/WebP cannot compress. Hill shade computed from 0.25 m heights differs by
  < 5 % brightness on 0 % of pixels (mean change 0.001); 1 m shows a faint grain on flat valley floors. → 0.25 m.
- **WebP works in MapLibre Native Android:** images (also `raster-dem`) are decoded with Android's
  `BitmapFactory` (found in `libmaplibre.so` 13.7.1), which reads lossless WebP since Android 4.2 (minSdk 26).
  *Not yet tested on the phone* – one test file before the pipeline switches.
- The app reads terrain only for drawing (elevations for the profile come from BRouter), so nothing else changes.

Per cell: today 19 MB. Part of that is a bug: `build_terrain.py` `tile_range()` also takes the next tile row/column
when the bbox edge lies exactly on a tile edge (it does for a z10 cell), so a cell carries 9 × 9 instead of 8 × 8
z13 tiles; z5–9 parents also go into each cell. Proposed cell = z10–12 only (1 + 4 + 16 tiles) ≈ **1 MB**; z5–9
belong to the overview map shipped with the app.

Contours must keep their 20 m lines: `build_contours.py` should read the z13 AWS tiles (or a temporary z13 file),
not the shipped terrain. Not measured: 512 px tiles (small gain expected), dropping flat/ocean tiles (no ocean in
the Alps; PMTiles already de-duplicates identical tiles).

### 1a. Result: terrain format 2 is implemented (2026-10-20, laptop)

`tools/build_terrain.py --format 2` (= `--webp --round 0.25 --maxzoom 12`), `--cell Z/X/Y` (only a cell's own
z10–12 tiles), `--cache DIR`; `tile_range()` edge bug fixed (max edges exclusive); `build_contours.py aws …`
reads the original AWS z12/z13 tiles; `tools/build_region.sh --format 2` uses both. Measured with the real tools:

| Data | Format 1 | Format 2 | Change |
| --- | --- | --- | --- |
| Test region terrain (10.85–11.60 E, 46.35–46.85 N, z5–13 → z5–12) | 54.6 MB (433 tiles) | **6.6 MB** (127 tiles) | −88 % |
| Meran cell 10/543/361 terrain, own tiles only (z10–13 → z10–12) | 10.8 MB (85 tiles; 19 MB in the zo build with the edge bug + ring + z5–9) | **1.0 MB** (21 tiles) | −91 % (−95 % vs zo) |
| Contours (region) | 3.2 MB | 3.2 MB, **byte-identical** (`aws` source vs format-1 terrain, same bbox) | 0 |
| Small region build `--format 2` (11.00–11.40 E, 46.50–46.70 N) | – | terrain 2.5 MB, contours 0.65 MB, whole run 1 m 56 s | |

- Format 1 output is unchanged (same 433 tiles, only the PMTiles metadata gained `format`).
- **Hill shade before/after** (maplibre-gl JS in headless Chrome, 400 × 400 px @2×, full style and hill shade only;
  images in the laptop scratchpad `t2/render/img/`, `hs-cmp-*.png` side by side): mean brightness difference
  0.07–4.9 / 255, p99 ≤ 11, > 12 levels on ≤ 0.5 % of pixels (z11–15, Texelgruppe and Etsch valley floor).
  Visible difference: at z12–14 the z12 relief is a little softer – small "blobs" of the z13 data (upsampling
  artefacts) are gone; ridges and valleys are the same. Valley floors (0.25 m rounding): no visible change (0.17).
- **WebP on MapLibre Native Android 13.7.1 – what is verified:** from the source of the tag `android-v13.7.1`,
  every raster/raster-dem tile image is decoded by `platform/android/src/image.cpp` → `BitmapFactory.decodeByteArray`
  (no format check; Android decodes lossless WebP since 4.2, minSdk 26); `pmtiles_file_source.cpp` ignores the
  header's `tile_type` (only MLT is special) and supports compression none/gzip. **Not yet verified on the phone**
  (the phone was not connected) – the headless check uses maplibre-gl JS, which proves the files, not Native.
  Phone test: push a format-2 `terrain.pmtiles` under another name and point a debug style at it.

### 1b. Result: overview map (z0–9) for the APK (2026-10-20, laptop)

Built with `tools/build_overview.sh` for the Alps (5.0–17.0 E, 43.5–48.5 N): **base z0–8 4.5 MB + hill shade z5–9
(WebP lossless, heights 4 m) 9.2 MB = 13.7 MB; APK 33.4 → 47.6 MB.** Base z0–9 would be 16 MB (25 MB together),
not worth one zoom level. Optional in the build (`-Ponpath.overviewAssets=DIR`), drawn below z10 only
(details: map-pipeline.md §9b). Rendered headlessly (maplibre-gl JS, merged style): z6 Alps, z7 Tirol, z9 Innsbruck
(no detailed data) and Meran, z10 Meran (detailed) look as expected; z11 Innsbruck is empty (no cell).
**Not yet verified on the phone:** `pmtiles://asset://…` on Native – from the 13.7.1 source the PMTiles source
passes the inner URL to the resource loader, and `AssetManagerFileSource` serves `asset://` with byte ranges
(`AAssetManager_open(…, AASSET_MODE_RANDOM)`, `AAsset_seek64`), so it should work; fallback if not: copy the two
files to internal storage once and use `pmtiles://file://`.

## 2. Overlays: GeoJSON → one vector `overlays.pmtiles`

Test region (10.70–11.60 E, 46.30–46.85 N), today's `extract_overlays.py` output, then tippecanoe
(`-r1 --no-feature-limit --no-tile-size-limit --simplify-only-low-zooms --buffer=8`, one run per layer, then
`tile-join`). Extent 4096 at z14 = 0.4 m grid, finer than the 0.5 m simplification the files already have.

| Layer | Zooms in tiles | GeoJSON | Vector tiles |
| --- | --- | --- | --- |
| paths (+ replaces lowways at z11–12) | 11–14 | 19.1 MB + 9.0 MB | 6.5 MB |
| trails | 13–14 | 5.9 MB | 1.4 MB |
| ways | 13–14 | 7.9 MB | 1.9 MB |
| routes | 9–14 | 6.8 MB | 3.3 MB |
| trailnames | 13–14 | 4.4 MB | 1.8 MB |
| pois (test only) | 10–14 | 1.8 MB | 1.9 MB |
| **one joined file** | | **54.9 MB** | **13.6 MB (−75 %)** |
| same with max zoom 15 | | | 17.5 MB (not worth it) |

- Phone storage −75 %; RAM: MapLibre only loads the tiles in view instead of parsing every GeoJSON at start
  (not measured on the phone).
- Download: zipped GeoJSON is about as small (11 MB) – the win is on the phone, not on the wire.
- POIs: no gain as tiles (points repeat at every zoom). Keep `pois.geojson`; the search (`OfflineIndex`) and POI tap
  read it. `trailnames` is also read by the search → the pipeline adds a small name list (name + one point) per cell,
  or the app keeps reading `trailnames.geojson` (0.8 MB per cell) until then.
- Style: GeoJSON sources become `source-layer`s of one vector source; layer ids stay the same. App:
  `DataFiles`, `OptionalSources`, `StyleLoader`, search index (laptop).
- Contours can be one more source-layer in the same file (saves a source, not bytes).
- Tooling: tippecanoe is not in the pipeline yet; it builds from source in ~1 min (needs `libsqlite3-dev`).
  A Planetiler custom profile would avoid the new tool but is more work.

## 3. Base map (`base.pmtiles`, Planetiler OpenMapTiles z0–14)

What the style (`onpath.json`) reads from `base`: boundary, building (z14+), landcover, landuse, mountain_peak, park,
place, transportation, transportation_name, water, water_name, waterway – fields `name`, `name:de`, `name_de`,
`class`, `subclass`, `brunnel`, `surface`, `access`, `bicycle`, `foot`, `service`, `ref`, `ele`, `intermittent`,
`admin_level`, `maritime`. **Never read:** `poi` (we have our own POIs), `housenumber`, `aeroway`,
`aerodrome_label`, and 80 of the 81 `name:xx` languages. (The search also reads `place` and `mountain_peak` from base.)

Same bbox, Planetiler run three times:

| Variant | Size | vs default |
| --- | --- | --- |
| Default (today's command) | 28.7 MB | – |
| `--exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de` | 24.7 MB | −14 % |
| + `--simplify_tolerance_at_max_zoom=0.25 --min_feature_size_at_max_zoom=0.25` | 23.7 MB | −18 % (only −4 % more, coarser roads – **not** recommended) |
| exclude + drop `transportation` path/track at z13–14 (re-encoded tiles) | ≈ 21.0 MB | −27 % |

- Path/track lines in base z13–14 are drawn twice today: `paths` draws them from z13 and base lines are only a
  fallback when `paths` is missing. Dropping them saves 18 % of z13 and 20 % of z14. With `paths` inside the same
  cell package the fallback is no longer needed. Tool: `tile-join -j` feature filter, or a tiny post-filter script.
- Landcover is the biggest layer (35–55 % of z12–14) – no cheap switch found; leave it.
- Base z0–9 (~3 % of the region file, but whole low-zoom tiles in every cell) → overview map, not in cells.

## 4. Contours

1.1 MB per cell (z12: 50 m, z13: 20 m). Already compact. Proposal: keep as is, put into `overlays.pmtiles` as layer
`contour`, build from the z13 AWS tiles on the server even when the shipped terrain stops at z12.

## 5. Duplicated data

| Duplicate | Today | Proposal |
| --- | --- | --- |
| `lowways` vs `paths` | same ways, two files (9 MB + 19 MB region) | paths at z11–12 in tiles replace lowways (counted above) |
| base path/track lines vs `paths` | both in the package | drop from base z13–14 (−15 % base) |
| base `poi` layer vs `pois.geojson` | base POIs never drawn | exclude layer (part of −14 %) |
| `trails` / `ways` vs `paths` | same geometry, different properties | could become properties of `paths` (one line instead of three) – not measured, needs style work |
| `routes` merged + `majoronly` pieces | major routes stored twice | small; leave |

## 6. Routing (`.rd5`)

BRouter's `.rd5` (one per 5° × 5°) is 25 separate 1° × 1° parts (checked in `brouter-mapaccess` 1.7.11
`PhysicalFile` / `OsmFile`):
- the first 200 bytes are 25 offsets (index = lon offset × 5 + lat offset); each part is one contiguous byte range;
  offsets inside a part are relative, so a part can be moved;
- a part with length 0 means "no data" (`OsmFile` returns early) – BRouter does not fail;
- an optional tail (113 bytes in `E10_N45.rd5`) holds the creation time, a checksum of the 200-byte index and one checksum per part;
  without the tail BRouter skips the checks.

So the phone can fetch the 200-byte index plus only the needed parts with HTTP range requests from brouter.de and
write its own `E10_N45.rd5` with empty parts elsewhere (recompute the index checksum with BRouter's own `Crc32`,
which is already in the APK). Sizes of the 1° parts of `E10_N45.rd5`: 4.8–12.8 MB, median 8.3 MB.
A z10 cell touches 1–2 parts (rarely 4); the 3 × 3 area around Meran touches 4 = 31 MB instead of 199 MB.
Not verified: that brouter.de answers range requests (the downloader already resumes, so very likely); that the
divisor/elevation fields need the tail (keep the tail to be safe). Adding a part later = rewrite the local file.

## 7. Joining cells on the phone

Rule that makes joining easy: **a cell carries only tiles z ≥ 10**. Every such tile lies in exactly one z10 cell,
so joining is copying tiles – no feature merging, no seams. z0–9 come from the overview map shipped in the app.
The server must build each cell from data reaching past the cell edge (Planetiler already reads the whole extract;
`extract_overlays.py` / contours must clip with a margin and cut tiles to the cell, e.g. tippecanoe
`--clip-bounding-box`) and must not add neighbour tiles (terrain off-by-one above).

| Option | How | For | Against |
| --- | --- | --- | --- |
| A. **MBTiles** per layer (`base`, `terrain`, `overlays`) | insert a cell's tiles in one SQLite transaction; replace a cell = delete its tile range + insert | incremental, crash-safe, no big rewrite; MapLibre 13.7.1 has `MBTilesFileSource` (`mbtiles://`, absolute path) | not used by us yet: needs a device test – does the map see new tiles without restart, speed, gzip handling |
| B. **PMTiles rewrite** | merge the cell into a new file, then rename | format we already use and trust | a full copy per cell (~90 MB for 3 × 3), needs a small PMTiles writer in Kotlin, app restarts after import today |
| C. One PMTiles per cell and layer | a style source per cell | no merging | layers × cells – style grows with every cell; no |

Recommendation: a one-day spike for A on the phone; if it fails, B. The download format per cell can stay three
small PMTiles (or MBTiles) in a zip either way.
**Decided 2026-10-21: B** – one PMTiles per layer, rewritten as a new store generation (new paths, atomic rename);
the full copy costs ~1 s for 2 cells + region on the laptop. Why not A: MapLibre's bundled SQLite reading while
Android's SQLite writes the same file in one process is a known corruption risk, and a style reload is needed anyway.
Details: map-cells.md §19.

## Risks

- WebP raster-dem and MBTiles are not yet proven on the phone – test both before the pipeline switches.
- Overlays to tiles touches the style and three app classes; ship it in one release with the pipeline change, and keep
  `OptionalSources` able to strip the new source when missing.
- 0.25 m / z12 terrain: if the owner sees a difference in the 3-D relief, z13 with WebP exact (−31 %) is the fallback.
- Range requests depend on brouter.de's server; fallback stays the whole 5° file.

## Who does what

| Step | Pipeline (cloud) | App (laptop) |
| --- | --- | --- |
| 1 Terrain | `build_terrain.py`: max zoom 12, B rounded to 0.25 m, WebP, fix `tile_range` edge; `build_contours.py` from z13 | test WebP hill shade on the phone; none else |
| 2 Overlays | tippecanoe step, `overlays.pmtiles`, style sources → `source-layer` (`make_style.py`), search name list | `DataFiles`, `OptionalSources`, `StyleLoader`, `OfflineIndex` |
| 3 Routing | – | `SegmentDownloader`: index + needed 1° parts, local rd5 writer |
| 4 Base | two Planetiler flags + path/track filter | none (style already falls back to `paths`) |
| 5 Join store | cells = z10–14 tiles only | spike MBTiles, then cell store + overview map |
