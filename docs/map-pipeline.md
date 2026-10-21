# OnPath map data pipeline

How a region becomes the offline map package on the phone: from a region definition (name, bbox, OSM
extract URL) to `onpath-data-<region>.zip`. Everything runs offline on one Linux machine (laptop, cloud
container or a Hetzner server); only the inputs are downloaded.

**One command:** `tools/build_region.sh` runs every step below in order, skips steps whose output is
up to date, and writes the zip, a `manifest.json` and `SHA256SUMS`. The per-step commands are listed so
each step can also be run, debugged or changed on its own.

```
tools/build_region.sh south-tyrol                       # test region, built-in bbox + OSM URL
tools/build_region.sh dolomites 11.5,46.2,12.3,46.8 \
    https://download.openstreetmap.fr/extracts/europe/italy/trentino_alto_adige-latest.osm.pbf
tools/build_region.sh --help                            # all options / env overrides
tools/build_region.sh --format 2 south-tyrol            # format 2 (docs/design/map-data-size.md), see below
```

**Format 2** (`--format 2`, one switch for every file; format 1 stays the default until map cells are live):
terrain as WebP lossless, heights rounded to 0.25 m, max zoom 12 (test region 54.6 → 6.6 MB, Meran cell
10.8 → 1.0 MB); contours built from the original AWS z12/z13 tiles (cached in `$WORK/terrarium`), so they stay
identical to format 1; overlays as `overlays.pmtiles` and a trimmed base map (below).

## 1. Overview

```
region (name, bbox, OSM URL)
  │
  ├─ OSM extract (.osm.pbf) ──┬─ Planetiler ───────────────► base.pmtiles        (required)
  │                           ├─ extract_overlays.py ──────► routes.geojson      (required)
  │                           │                            ├► trails.geojson      (required)
  │                           │                            ├► ways.geojson        (optional)
  │                           │                            ├► lowways.geojson     (optional)
  │                           │                            ├► paths.geojson       (optional, 0.7.0)
  │                           │                            └► trailnames.geojson  (optional)
  │                           ├─ extract_pois.py ──────────► pois.geojson        (optional)
  │                           └─ extract_addresses.py ─────► addresses.sqlite    (optional, offline address search)
  ├─ AWS Terrarium tiles ──── build_terrain.py ────────────► terrain.pmtiles     (required)
  │                           └─ build_contours.py ────────► contours.pmtiles    (optional)
  ├─ brouter.de segments4 ─── download ────────────────────► segments/E10_N45.rd5 (required; phone can download)
  └─ tools/themes/*.json ──── make_style.py --all ─────────► styles/onpath*.json (optional override;
                                                               the same styles are bundled in the APK)
  → onpath-data/ + onpath-data-<region>.zip + manifest.json + SHA256SUMS
```

**Format 2** (`--format 2`, design: `docs/design/map-data-size.md`): the six overlay GeoJSON files become ONE vector
tile file `overlays.pmtiles` (`extract_overlays.py --tiles --smooth` → `tools/build_overlays.sh`, tippecanoe), and
the base map is trimmed (`--exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de`, then
`tools/trim_base.sh` drops path / track and small-street lines at z13–14). Details: section 5b. Format 1 stays the default until map
cells are live (owner Q9); the app reads both.

"Required" = the app's missing-data screen (`data/DataFiles.kt`) asks for it. Optional files may be
missing: `map/OptionalSources.kt` removes every style source whose local file is absent, together with
its layers, so the app never breaks on a partial package (robustness rule R-02).

## 2. Tools and versions

| Tool | Version used | Used for | Install (Ubuntu 24.04 / Debian 12) |
| --- | --- | --- | --- |
| Java | OpenJDK 21 | Planetiler | `apt install openjdk-21-jre-headless` |
| Planetiler | 0.10.2 (`planetiler-dist`, released 2026-03) | base tiles, OpenMapTiles schema | `https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar` (the script downloads it) |
| Python | 3.11+ (3.13 used) | all `tools/*.py` | `apt install python3 python3-venv` |
| osmium (pyosmium) | 4.3.1 | overlays, POIs | `pip install osmium` |
| shapely | 2.2.0 | overlays, POIs, contours | `pip install shapely` |
| pmtiles | 3.8.1 | terrain + contour PMTiles writer | `pip install pmtiles` |
| numpy | 2.5 | contours | `pip install numpy` |
| pillow | 12.3 | contours (PNG decode), sprite | `pip install pillow` |
| contourpy | 1.4.0 | contours | `pip install contourpy` |
| mapbox-vector-tile | 2.2.0 | contours (MVT encode) | `pip install mapbox-vector-tile` |
| cairosvg | 2.9.1 (+ `libcairo2`) | sprite only (`make_sprite.py`) | `apt install libcairo2 && pip install cairosvg` |
| curl, unzip, sha256sum | any | downloads, checksums | `apt install curl unzip coreutils` |
| tippecanoe + tile-join | 2.79.0 (≥ 2.17 for `.pmtiles`) | **format 2 only**: `overlays.pmtiles`, base trim | `apt install build-essential libsqlite3-dev zlib1g-dev && git clone https://github.com/felt/tippecanoe && make -j` (~1 min; or env `TIPPECANOE` / `TILE_JOIN`) |
| Node 22 + maplibre-gl JS | – | **preview only** (headless style rendering) | not needed for the pipeline |

One-line Python setup: `pip install osmium shapely pmtiles numpy pillow contourpy mapbox-vector-tile cairosvg`.

## 3. Inputs, data sources and licences

| Input | Source | Licence / attribution |
| --- | --- | --- |
| OSM extract (`.osm.pbf`) | `https://download.openstreetmap.fr/extracts/…` (e.g. `europe/italy/trentino_alto_adige-latest.osm.pbf`, updated daily) | ODbL – "© OpenStreetMap contributors" |
| Planetiler side data (downloaded by Planetiler `--download`, ~1.4 GB, cached): water polygons (osmdata.openstreetmap.de), Natural Earth (naciscdn.org), lake centerlines (GitHub) | as listed | OSM data ODbL; Natural Earth public domain |
| Vector tile schema | OpenMapTiles (Planetiler's built-in profile) | "© OpenMapTiles" (CC-BY 4.0 schema) |
| Terrain | AWS Open Data Terrain Tiles, Terrarium PNG: `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` | "Terrain: Mapzen / AWS Open Data (SRTM, Copernicus, others)" – see the dataset's attribution list |
| Routing tiles | `https://brouter.de/brouter/segments4/<E10_N45>.rd5` (5°×5° tiles, ~weekly) | derived from OSM (ODbL); BRouter MIT |
| POI pictograms | Maki (Mapbox), Temaki (Rapid) – copied in `tools/icons/` | CC0; `assets/sprites/LICENSE-icons.txt` |
| Fonts | Noto Sans glyph PBFs in `app/src/main/assets/fonts/` | OFL |

The app shows "© OpenStreetMap contributors, © OpenMapTiles, Terrarium (Mapzen/AWS)" on the map and the
full list in Settings. Google content is never fetched, stored or drawn (link-out to Google Maps only).

**Blocked hosts:** from the cloud build machines `download.geofabrik.de` and `overpass-api.de` are not
reachable – use `download.openstreetmap.fr`. Reachable: download.openstreetmap.fr, brouter.de, AWS S3,
osmdata.openstreetmap.de, naciscdn.org, github.com / raw.githubusercontent.com. On a Hetzner server
Geofabrik works too (`https://download.geofabrik.de/europe/italy/nord-est-latest.osm.pbf` etc.).

## 4. The steps (what `build_region.sh` runs)

Shown for the test region (bbox `10.70,46.30,11.60,46.85`, Bozen / Meran / Algund / Ultental). `$D` = output
folder `out/<region>/onpath-data`. Times and sizes measured on the 4-core / 16 GB cloud container
(18 Oct 2026, OSM data of 17 Oct 2026).

| # | Step | Command (simplified) | Output | Time | Size |
| --- | --- | --- | --- | --- | --- |
| 1 | OSM extract | `curl -fL -C - -o region.osm.pbf $OSM_URL` | `work/osm/*.osm.pbf` | ~1 min | 130 MB |
| 2 | Planetiler jar | `curl -fL -o planetiler.jar …/v0.10.2/planetiler.jar` | `work/planetiler-0.10.2.jar` | <1 min | 93 MB |
| 3 | Base map | `java -Xmx6g -jar planetiler.jar --osm-path=region.osm.pbf --output=base.pmtiles --download --download_dir=work/planetiler-sources --bounds=$BBOX --maxzoom=14 --force` | `$D/base.pmtiles` | 9–14 min | 23 MB |
| 4 | Terrain | `python3 tools/build_terrain.py terrain.pmtiles 10.70 46.30 11.60 46.85 13` (format 2: `… --format 2 --cache $WORK/terrarium`) | `$D/terrain.pmtiles` | 25 s | 55 MB (format 2: 6.6 MB) |
| 5 | Overlays | `python3 tools/extract_overlays.py region.osm.pbf $D 10.70 46.30 11.60 46.85` | `$D/routes.geojson`, `trails.geojson`, `ways.geojson`, `lowways.geojson`, `trailnames.geojson`, `paths.geojson` | 2 min | South Tyrol bbox 2026-10-19: 6.8 + 5.9 + 7.9 + 9.0 + 4.4 + 19.1 MB |
| 6 | POIs | `python3 tools/extract_pois.py region.osm.pbf $D 10.70 46.30 11.60 46.85` | `$D/pois.geojson` | 3 min | 1.8 MB |
| 6b | Addresses | `python3 tools/extract_addresses.py region.osm.pbf $D/addresses.sqlite 10.70 46.30 11.60 46.85` | `$D/addresses.sqlite` (SQLite: town, street, house, word; schema in the script) | 1 min | 2.5 MB (Meran z10 cell: 0.6 MB) |
| 7 | Contours | `python3 tools/build_contours.py terrain.pmtiles contours.pmtiles 10.70 46.30 11.60 46.85` (format 2: `aws` instead of `terrain.pmtiles`, plus `--cache $WORK/terrarium`) | `$D/contours.pmtiles` | 70 s | 3.2 MB |
| 8 | Styles (+ sprite) | `python3 tools/make_style.py --all` (sprite: `python3 tools/make_sprite.py`) | `out/<region>/styles/onpath*.json` | <1 s | ~64 KB each |
| 9 | Routing tiles | `curl -fL -C - -o segments/E10_N45.rd5 https://brouter.de/brouter/segments4/E10_N45.rd5` | `$D/segments/*.rd5` | ~10 s | 199 MB |
| 10 | Package | zip (`.pmtiles`/`.rd5` stored, GeoJSON deflated) + manifest + checksums | `onpath-data-<region>.zip` | 45 s | 283 MB (270 MiB) |

Whole run from cached inputs: **21 min (Planetiler 14 min of it); a re-run with nothing changed takes ~25 s (only the zip is rebuilt)**. Zip without routing tiles (`--no-segments`): 85 MB (81 MiB).
Peak memory: the Planetiler heap (`JAVA_XMX`, default 6g); temp disk a few GB plus the 1.4 GB Planetiler sources cache (`work/planetiler-sources`). Planetiler took 9 min in an earlier run and 14 min in the measured one (shared 4-core machine).

Notes per step:
- **3 Planetiler** writes temporary files (`--tmpdir`) and caches the side data (`--download_dir`);
  the script runs it inside `work/tmp/` so nothing lands in the current folder. The OpenMapTiles
  profile keeps `name:de`, which the style prefers (`name:de` → `name_de` → `name`).
- **4 Terrain** downloads every tile z5–13 of the bbox (16 threads, 6 tries with back-off). It does not depend on
  the OSM data, so the script only rebuilds it when the bbox, the script or the format changes. Tile ranges treat
  the bbox's east/south edge as exclusive (a bbox ending exactly on a tile edge – a z10 cell – takes no extra
  column/row). Other modes: `--format 2` (= `--webp --round 0.25 --maxzoom 12`), `--cell 10/543/361` (only the
  cell's own tiles z10–max), `--cache DIR` (keep the AWS PNGs; contours read the same folder). WebP needs Pillow
  with WebP support (the normal wheels have it).
- **5 Overlays** do two passes over the PBF (relations, then ways with node locations, in memory –
  fine for regional extracts; for a whole country give the machine ≥ 16 GB).
- **7 Contours** read `terrain.pmtiles` (format 1, no extra download) or, with source `aws`, the original AWS
  z12/z13 PNGs (format 2: the shipped terrain stops at z12 but the 20 m lines need z13). Both give byte-identical
  tiles for the same bbox. One extra tile ring is read so lines run through to the neighbour.
- **8 Styles**: the script builds them from a copy of `tools/` in `work/tmp/stylebuild`, so a data build
  never changes the repo's bundled `app/src/main/assets/style/*.json`.

## 5. Where each map refinement lives

| Refinement | Data (pipeline) | Style (`tools/make_style.py` + modules) | App |
| --- | --- | --- | --- |
| **Trail names + route numbers** | `extract_overlays.py` → `trailnames.geojson`: `t="n"` trail name lines (German name: `name:de` or the German half of "DE - IT"; unnamed member ways get the hiking relation's name; same-name ways merged, simplified + Chaikin-smoothed; `tier` 1 = Waalweg/Höhenweg/≥2.5 km, 2 = normal, 3 = short or not a "…weg"; `len` m). `t="r"`: AVS route numbers of the way's hiking relations ("25", "24 · 25") | `tools/style_trail_names.py`: layers `trail-ref` (z14+, upright red numbers), `trail-name-t3/t2/t1` (z15/z14/z13, longer first) | optional, stripped by `OptionalSources` when missing |
| **Trail difficulty** | `trails.geojson`: `sac` 0–6 from `sac_scale`, `mtb` "0"–"6" from `mtb:scale`, `vis` 0–5 from `trail_visibility` (see "Way quality fields") | `trails-*` layers: rhythm by SAC (solid / dots / dot-dash), faint trails sparser + lighter, no underlay (0.7.0); `trails-sac-label` (T, hiking), `trails-mtb-label` (S, MTB) | required |
| **Paths and tracks (0.7.0)** | `paths.geojson`: every path / footway / bridleway / track (+ ways with sac_scale), exact geometry; `k` t/p, `sac`, `vis`, `st` (steps), `tt` (tracktype), `pv` (paved track) | `paths-own` (black line), `track-own-single` (< z14.5 brown dashed), `track-own-compact/medium/loose/soft` (two dotted brown edges, spacing by `tt`), `track-own-paved` (like a street) | optional; without it the base map's lines are drawn (`path-base`, `track-base` carry metadata `onpath:fallback-for: paths`, kept by `OptionalSources` only then) |
| **Way quality** | `ways.geojson`: `k`, `tt`, `surf`, `smooth` (see "Way quality fields") | `surface-loose-*` (bike activities) | optional, stripped by `OptionalSources` when missing; toggled per activity (`MapController.setOverlay`) |
| **Route bands** | `routes.geojson`: route relations `hiking/foot` → `hike`, `bicycle` → `bike`, `mtb` → `mtb`, **merged per kind into continuous lines** (the band is transparent); major routes (net ≥ 2) again as original pieces with `majoronly`, `ref`, `name`, `net` | `route_bands()`: transparent band #168403 at 40 % **below** roads/paths/trails (excludes `majoronly`); pink highlight + name from the `majoronly` pieces; ids `routes-hike/-bike/-mtb` | toggled per activity (`MapController`) |
| **POIs + tiers** | `extract_pois.py`: 28 kinds (`classify()`), ways/areas → representative point; **bus de-dup**: same-name stops within 40 m merged to their midpoint (`MERGE_KINDS`) | `POI_TIERS` in `make_style.py`: `poi-tier1` (icon z12, dot until z13, label z14), `poi-tier2` (z14/z15), `poi-tier3` (z15/z16); sort key = kind rank, named first; see `docs/poi-categories.md` | Settings filter wraps every `poi-*` layer filter; tap lookup |
| **Railways + stations** | rails from base tiles (`transportation` class `rail`); stations = POI kind `train` (railway station/halt, no subway/funicular/tourist) | `rail-casing`, `rail` (from z8), `rail-hatch` (z12); `poi-station` top POI layer, icon z10, name z11 (`station.*` tokens) | station follows the POI filter (`train`) |
| **Sprite (POI icons)** | `tools/make_sprite.py` + `tools/icons/*.svg` → `app/src/main/assets/sprites/onpath{,@2x}.{json,png}` | `"sprite": "asset://sprites/onpath"`, images `poi-<kind>`, `poi-<kind>-small` | **APK asset** – a new kind/icon needs a new app build |
| **Design tokens** | `tools/themes/classic.json` (the one style since 0.7.0; rationale `docs/design/map-colour-analysis.md`), read by `tools/style_theme.py`; proposals in `tools/themes-proposals/` | `make_style.py` → `onpath.json` | bundled in APK; `styles/` override in the data folder wins (`StyleLoader`); extra themes only as overrides |
| **Ground symbols (0.7.0)** | `tools/make_sprite.py` `PATTERNS` (wetland, farmland, rock, sand tiles in the sprite) | `landcover-<cls>-pattern` (fill-pattern, z14+) | bundled sprite |
| **Contours** | `build_contours.py`: 50 m lines at z12, 20 m at z13 (MVT layer `contour`, props `ele`, `idx`=1 every 100 m) | `contour-*` layers | optional, toggle in Settings |
| **Hillshade / 3-D relief** | `terrain.pmtiles` (Terrarium) | `hillshade.*` tokens | required |
| **Base map** | Planetiler OpenMapTiles z0–14 | land cover, water, roads, paths, labels (German) | required |

### Way quality fields (stable names – other code may read them)
`trails.geojson` and `ways.geojson` keep a near-exact geometry (0.5 m simplification, 5 decimals, source
`maxzoom` 16 / `tolerance` 0.25) because they are drawn right over the base map's own path / track lines; the
earlier 3 m simplification showed as a second line beside the path at z17+.

| File | Key | Values | From OSM |
| --- | --- | --- | --- |
| trails | `sac` | 0 none, 1 hiking … 6 difficult_alpine_hiking (always present) | `sac_scale` |
| trails | `mtb` | "0" … "6" (first character, so "2+" → "2") | `mtb:scale` |
| trails | `vis` | 0 excellent, 1 good, 2 intermediate, 3 bad, 4 horrible, 5 no (only if tagged) | `trail_visibility` |
| ways | `k` | "t" track, "p" path / footway / bridleway, "c" cycleway | `highway` |
| ways | `tt` | 1–5 (tracks only) | `tracktype=gradeN` |
| ways | `surf` | 1 paved (asphalt, concrete, paving_stones, sett, …), 2 compact (compacted, fine_gravel), 3 gravel / stony (gravel, pebblestone, rock, stone, scree), 4 natural soft (dirt, ground, earth, mud, grass, sand, woodchips, grass_paver) – only if tagged; generic `unpaved` is left out | `surface` |
| ways | `smooth` | 0 excellent, 1 good, 2 intermediate, 3 bad, 4 very_bad, 5 horrible, 6 very_horrible, 7 impassable (only if tagged) | `smoothness` |

`trails.geojson` holds ways with `sac`, `mtb` or `vis` ≥ 3. `ways.geojson` holds track / path / footway /
cycleway / bridleway ways (no sidewalks / crossings) with a tracktype, `surf` ≥ 3 or `smooth` ≥ 3 – ways the map
draws differently; it does not list every surface.

## 5b. Overlays as vector tiles (format 2)

`tools/build_region.sh --format 2` (and later the cell builder, step 5 of the map rework) runs:

```
python3 tools/extract_overlays.py region.osm.pbf OUT bbox… --no-geojson --tiles TILES --smooth   # *.ndjson per layer
tools/build_overlays.sh TILES overlays.pmtiles [--clip minlon,minlat,maxlon,maxlat] [--extra contours.pmtiles]
java -jar planetiler.jar … --exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de
tools/trim_base.sh base-raw.pmtiles base.pmtiles      # tile-join -j: no class path/track/minor/service lines at z>=13
                                                      # (names stay), no park name points
```

`build_overlays.sh` runs tippecanoe once per layer (`-r1 --no-feature-limit --no-tile-size-limit
--simplify-only-low-zooms --buffer=8`, extent 4096 = 0.4 m grid at z14) and joins them with `tile-join`. A cell build
passes its buffered bbox as `--clip` and keeps only its own z ≥ 10 tiles (`tools/cells/cells.py tiles`).
Contours stay in `contours.pmtiles` for now (terrain work); `--extra contours.pmtiles` joins them as layer `contour`
once the style reads them from `overlays`.

**Schema of `overlays.pmtiles`** (one source-layer per former GeoJSON file; props as in section 5 unless listed):

| Layer | Zooms | Content |
| --- | --- | --- |
| `paths` | 11–12 | former `lowways.geojson`: every path / track coarse (~8 m), only `k` "t" / "p" |
| `paths` | 13–14 | every way of `paths.geojson` with its props (`k` t/p, `sac`, `vis`, `st`, `tt`, `pv`) **plus** ways the style used to read from the base map, `k` = "x" (never drawn as a path line): cycleways, steps, pedestrian streets / areas, sidewalks / crossings, via ferratas, roads with cycle lanes or `oneway:bicycle`. Extra props (only when set): `sub` OSM highway unless path / track (footway, bridleway, cycleway, steps, pedestrian, via_ferrata, or a road class), `tn` 1 tunnel, `ac` 1 access=no/private, `foot` / `bicycle` (OSM values), `uv` 1 unpaved cycleway (OpenMapTiles' unpaved list), `ow` 1 / −1 oneway, `owb` oneway:bicycle (0 no, 1 yes, −1), `cwl` / `cwr` cycle infrastructure left / right (`l` lane, `t` track, `s` shared lane, `b` bus lane, `o` opposite), `vf` via ferrata (via_ferrata_scale 1–6, 0 = not graded) |
| `streets` | 13–14 | small streets (owner 2026-10-21), smoothed: OpenMapTiles classes `minor` (highway residential, living_street, unclassified, road) and `service` – the trimmed base map has none at z13–14; tertiary and bigger stay in the base map. OpenMapTiles attribute names so the style keeps its filters: `class`, `brunnel` bridge / tunnel, `service` (OSM value), `access` "no" (access no / private), `oneway` 1 / −1, `foot` / `bicycle`. Driveways / parking aisles / drive-throughs only at z14. Street names stay in the base map's `transportation_name`. Optional (a cell without streets has no such layer) |
| `trails` | 13–14 | as `trails.geojson` |
| `ways` | 13–14 | as `ways.geojson` |
| `routes` | 9–14 | as `routes.geojson` (empty `ref` / `name` dropped) |
| `trailnames` | 13–14 | as `trailnames.geojson` (the app's search reads them from here when the GeoJSON is missing) |
| (`contour`) | 12–13 | later: the layer of `contours.pmtiles` |

**Smoothing (`--smooth`, default for format 2, owner 2026-10-20, stronger 2026-10-21):** every bend ≥ 15°
(`--smooth-angle`, was 20°) of a path / track / cycleway / steps / pedestrian way (or any way with `sac_scale`) and, in
format 2, of every small street (layer `streets`) becomes a short quadratic curve that stays within 3 m
(`--smooth-dev`, was 1.5 m) of the OSM line (cut ≤ 45 % of each leg, ≤ 20 m). Way ends and junction nodes (shared with
any other highway – also where a small street meets a base-map road) stay exact, so the network stays connected and
small streets still meet the unsmoothed tertiary+ roads; a way is smoothed whole before any clipping, so neighbouring
cells compute the same line. Tertiary and bigger roads are not smoothed (the base map draws them). Route bands use the
same 0.5 m / smoothed geometry as the way below them – the former 3 m simplification put the band ~3 m beside the path
at z16+. Smoothed lines keep 6 decimals.

**Sizes** (test region 10.70–11.60 E / 46.30–46.85 N, OSM of 17 Oct 2026; cell = z10 10/543/361 Meran, own z ≥ 10 tiles):

| | Format 1 | Format 2 | Change |
| --- | --- | --- | --- |
| Overlays, region | 53.1 MB GeoJSON (paths 19.1, lowways 9.0, ways 7.9, routes 6.8, trails 5.9, trailnames 4.4) | **14.2 MB** `overlays.pmtiles` (paths 6.0, routes 3.9, ways 1.7, trails 1.4, trailnames 1.2); 11.3 MB without smoothing | −73 % |
| Overlays, Meran cell | 11.0 MB GeoJSON (map-cells.md §3) | **2.8 MB** (2.2 MB without smoothing) | −75 % |
| Base map, region | 28.7 MB | **21.1 MB** (24.7 after the Planetiler flags, then −15 % path/track z13–14) | −27 % |
| Base map, Meran cell | 5.3 MB | **3.6 MB** | −31 % |
| Build time (region) | overlays 45 s | overlays ~100 s (smoothing) + tippecanoe 10 s; base trim 5 s | |

Smoothing costs ~3 MB (region) because curves have more vertices and route bands now follow the exact path.

Small streets + stronger smoothing (2026-10-21, package `out-f2c`): `overlays.pmtiles` 14.2 → **16.5 MB** (layer
`streets` ~2.0 MB, 15° / 3 m smoothing +0.3 MB), base map 21.0 → **19.9 MB** (no minor / service lines at z13–14);
net +1.3 MB for the region. Overlay build ~80 s.

Token reference and the "why" of the look: `docs/map-style-notes.md`. POI kinds and counts:
`docs/poi-categories.md`, `docs/poi-integration.md`.

## 6. Output layout and the phone

```
out/<region>/
  onpath-data/                  ← exactly the phone's data folder
    base.pmtiles  terrain.pmtiles  contours.pmtiles
    routes.geojson  trails.geojson  ways.geojson  trailnames.geojson  pois.geojson
    (format 2: overlays.pmtiles instead of the overlay .geojson files; package.json / manifest "format": 2)
    segments/E10_N45.rd5        (not with --no-segments)
    styles/onpath*.json         (only with --with-styles)
  onpath-data-<region>.zip      ← the same, under "onpath-data/" in the zip
  manifest.json                 ← region, bbox, build date, OSM date, tool versions, per-file bytes + sha256
  SHA256SUMS                    ← `sha256sum -c SHA256SUMS` (run inside out/<region>/)
  styles/onpath*.json           ← always built, for pushing a new look without new data
```

Defaults are `./out/<region>` and `./work` relative to the current folder – run the script from outside
the repo (or pass `--out` / `--work`), so map data never ends up next to tracked files.

`manifest.json` is deliberately not inside the zip: the importer only accepts known file types and
would list it as "skipped".

How the data reaches the phone (data folder `/sdcard/Android/data/com.onpath.app/files/onpath/`):
1. **Zip import on the phone** (no computer): copy the zip to the phone (download, chat app, USB), then
   *missing-data screen → "Import map data (.zip) from this phone"* or *Settings → Import*.
   `data/DataImporter.kt` maps entries by name only: `*.rd5` → `segments/`, `*.pmtiles` / `*.geojson`
   → top level, `styles/*.json` or `onpath*.json` → `styles/`; everything else is skipped. Each file is
   written to a temp file and renamed when complete, free space is checked first. Single files
   (e.g. just a new `pois.geojson` or a style override `onpath-<name>.json`) can be imported the same way.
2. **adb push**: `adb push out/<region>/onpath-data/. /sdcard/Android/data/com.onpath.app/files/onpath/`
3. **Routing tiles from brouter.de on the phone**: missing-data screen → "Download routing data
   (≈ 200 MB, Wi-Fi)" (`data/SegmentDownloader.kt`, resumable). That is why `--no-segments` packages
   are fine and much smaller.

**Test downloads (`downloads` branch on GitHub):** the South Tyrol package built with `--no-segments`, split in
two because GitHub refuses files over 100 MB: `onpath-data-south-tyrol-part1-terrain.zip` (`terrain.pmtiles`) and
`onpath-data-south-tyrol-part2-map.zip` (every other file), each with the same `package.json`, plus the build's
`manifest.json`, a `SHA256SUMS` of the two zips and a README. Rebuilt 2026-10-20 (OSM data of 2026-10-19):
69 MB + 43 MB. Same entry order and compression as the build zip (`.pmtiles` stored, the rest deflated).

## 7. Add a new region

1. Pick a bbox (`minlon,minlat,maxlon,maxlat`; e.g. from bboxfinder.com) – keep it regional: Planetiler,
   overlays and terrain scale with the area (terrain z13 ≈ 2 × the tiles per doubling of area).
2. Pick an extract that **covers** the bbox on download.openstreetmap.fr (or Geofabrik from a server).
   The scripts clip to the bbox; a larger extract only costs time.
3. Run `tools/build_region.sh <name> <bbox> <osm-url>`; check the log and `out/<name>/manifest.json`.
4. Routing tiles follow automatically (all 5° tiles the bbox touches).
5. The style has the test region's centre as default camera (`make_style.py`, `"center"`); the app moves
   the camera to the GPS position / data anyway, so no change is needed.

## 8. Change the look

1. Edit tokens in `tools/themes/classic.json` (or add `tools/themes/<new>.json` with
   `"extends": "classic"` and a few overrides). Layer structure/order is in `tools/make_style.py`,
   trail-name labels in `tools/style_trail_names.py`.
2. `python3 tools/make_style.py --all` → `app/src/main/assets/style/onpath*.json` (bundled in the next
   APK). Check with the headless MapLibre harness (see CLAUDE.md).
3. To update phones **without a new APK**: push the style files into the data folder's `styles/`
   (`adb push app/src/main/assets/style/onpath.json /sdcard/Android/data/com.onpath.app/files/onpath/styles/`)
   or import the `.json` file on the phone (Settings → Import). The override wins over the bundled
   style – delete it again to go back to the APK's style. `build_region.sh` also writes
   `out/<region>/styles/` and, with `--with-styles`, puts them into the zip.
4. New POI kinds or icons: also `tools/make_sprite.py` (`--sprite` in the script) – the sprite is an APK
   asset, so this needs an app build; add the kind to `poi/PoiKinds.kt` too.

## 9. Server (Hetzner) runbook

**Use `tools/hetzner/` first** (README there): one command on the laptop creates a separate Hetzner server that
builds regions **on demand** (100–200 km square around a searched place or your position, from Geofabrik
extracts), serves the zips over HTTPS and refreshes the regions in use. The manual setup below is the fallback /
background for whole-country builds.

**Machine:** for regional packages a CX32/CPX31-class cloud server (4 vCPU, 8–16 GB RAM, 80+ GB disk)
is plenty (test region ≈ 20 min). For whole countries (Italy, Austria, Germany): CCX33 / AX-line
(8+ cores, 32–64 GB RAM, NVMe 250 GB+), `JAVA_XMX=24g`; the Python overlay/POI passes keep node
locations in memory, so RAM matters more than cores.

**Setup (Ubuntu 24.04):**
```bash
apt update && apt install -y openjdk-21-jre-headless python3 python3-venv curl unzip git libcairo2
useradd -m onpath && su - onpath
git clone https://github.com/happychriss/onpath.git
python3 -m venv ~/venv && ~/venv/bin/pip install osmium shapely pmtiles numpy pillow contourpy mapbox-vector-tile cairosvg
echo 'export PATH=$HOME/venv/bin:$PATH' >> ~/.profile
mkdir -p ~/work /srv/onpath   # /srv/onpath = static web root (owned by onpath)
```

**Run (one region):**
```bash
cd ~/onpath && git pull --ff-only
WORK=~/work tools/build_region.sh south-tyrol --out /srv/onpath/south-tyrol
```

**Monthly cron** (`crontab -e` as user `onpath`; first Sunday 03:17, one region after another):
```cron
17 3 1-7 * 0  cd $HOME/onpath && git pull -q --ff-only && . $HOME/.profile && \
  for r in "south-tyrol" ; do WORK=$HOME/work tools/build_region.sh "$r" --out /srv/onpath/$r || echo "FAILED $r"; done \
  >> $HOME/work/cron.log 2>&1
```
For more regions keep a small list file (`name bbox url` per line) and loop over it. The OSM extract is
re-downloaded only when older than `OSM_MAX_AGE_DAYS` (6) and changed on the server; rd5 tiles after
`SEGMENTS_MAX_AGE_DAYS` (30); Planetiler's 1.4 GB side data is kept in `work/planetiler-sources`.

**Static hosting layout** (nginx/Caddy on `/srv/onpath`, or rsync to a Hetzner Storage Box / bucket):
```
/srv/onpath/<region>/onpath-data-<region>.zip     ← what users download
/srv/onpath/<region>/manifest.json                ← what an app/updater reads first (date, sizes, sha256)
/srv/onpath/<region>/SHA256SUMS
/srv/onpath/<region>/styles/onpath*.json          ← optional style-only updates
/srv/onpath/regions.json                           ← index, e.g.:
    jq -s '[.[] | {region, bbox, built, zip}]' /srv/onpath/*/manifest.json > /srv/onpath/regions.json
```
Remove `out/<region>/onpath-data/` from the web root if only the zip should be served
(`rsync --exclude onpath-data/`). The zip is replaced atomically (written as `.tmp`, then renamed),
so a download never sees a half-written file.

**Checksums:** verify on the server with `cd /srv/onpath/<region> && sha256sum -c SHA256SUMS`;
after a download compare `sha256sum onpath-data-<region>.zip` with `manifest.json → zip.sha256`.

**What is not automated:** choosing bbox/extract for a new region; visually checking the result
(headless render or a phone); publishing a new APK when sprite/style changes must be bundled; the
importer does not verify checksums yet (the app could read `manifest.json` later).

## 9b. Overview map (in the APK, optional)

The app can ship a small **overview** for the Alps so a place without downloaded data is never blank at low zoom
(map-cells.md §7). `tools/build_overview.sh [OUT_DIR]` builds `OUT_DIR/overview/base.pmtiles` (Planetiler,
`--maxzoom=8 --exclude_layers=housenumber,poi,aeroway,aerodrome_label,building --languages=de`) and
`OUT_DIR/overview/terrain.pmtiles` (`build_terrain.py --minzoom 5 --maxzoom 9 --webp --round 4`), bbox
`5.0,43.5,17.0,48.5` (Nice/Genoa – Vienna/Ljubljana, Lyon/Geneva – Munich/Salzburg: the whole Alpine arc plus the
cities people travel from), source `https://download.geofabrik.de/europe/alps-latest.osm.pbf` (2.3 GB).
Measured 2026-10-20 on the laptop (16 threads): Planetiler 4 m 40 s, peak RAM 6.6 GB (`JAVA_XMX=8g`).

| Variant | base | terrain | together | APK |
| --- | --- | --- | --- | --- |
| **base z0–8 + terrain z5–9, heights 4 m (default)** | 4.5 MB | 9.2 MB | **13.7 MB** | 33.4 → **47.6 MB** |
| base z0–9 + terrain z5–9, 4 m | 16 MB | 9.2 MB | 25 MB | ≈ 59 MB |
| terrain z5–9 with 1 m / 2 m / 8 m heights | | 13.9 / 11.8 / 7.2 MB | | |

Heights 4 m vs 1 m at z7/z9: hill shade differs by 0.1–0.2 / 255 (invisible; a z9 pixel is ~200 m). Base z9 would
add 11.5 MB for one zoom level that the over-zoomed z8 covers well enough (roads, places, water, borders).

App side (`map/Overview.kt`, called by `StyleLoader`): the APK includes the files only when built with
`-Ponpath.overviewAssets=OUT_DIR` (or env `ONPATH_OVERVIEW_ASSETS`) – map data is not committed; without it the
style is unchanged. With it, every `base` / `terrain` layer that starts below z10 gets a copy `<id>~overview` on
source `overview` / `overview-terrain` (`pmtiles://asset://overview/…`) drawn below z10, and the original starts
at z10. MapLibre cannot draw a layer only where another source has no data, so the split is by zoom: no doubled
hill shade, lines or labels, the same look, and cells (z ≥ 10 only) fit exactly. Consequences: below z10 the map
always shows the overview, even where a region package has its own low zooms (same OpenMapTiles data, so this
looks the same inside the Alps; outside the overview bbox low zooms are empty); above z10 where no data is on the
phone the map is empty (background colour) – the planned "Overview only – set a point" hint covers that.
PMTiles assets are stored uncompressed (`noCompress`): MapLibre's `AssetManagerFileSource` reads byte ranges with
`AAsset_seek64`, which would otherwise inflate from the start for every tile.

To build the full overview on zo/Hetzner: `WORK=~/work tools/build_overview.sh ~/overview-assets` (needs Java 21,
the Python venv, ~10 GB RAM, ~3 GB disk for the extract and Planetiler's sources).

## 10. Troubleshooting

- *Step skipped although data changed*: run with `--force` (or delete the output file). Outputs are
  rebuilt automatically when the OSM file, the tool script or the bbox changes.
- *Download interrupted*: just re-run – downloads resume (`.part` file); a partial file of an older
  server version is discarded.
- *Planetiler fails*: see `work/logs/<region>/planetiler.log`; usually RAM (`JAVA_XMX`) or disk.
- *No network for the inputs*: `--skip-download` uses only the cache (`work/`), plus `OSM_FILE=…`,
  `PLANETILER_JAR=…`, `PLANETILER_SOURCES=…` to point at existing files.
- *Phone shows the old look*: an old `styles/` override in the data folder wins over the APK style.
