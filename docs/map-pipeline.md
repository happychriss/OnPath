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
```

## 1. Overview

```
region (name, bbox, OSM URL)
  │
  ├─ OSM extract (.osm.pbf) ──┬─ Planetiler ───────────────► base.pmtiles        (required)
  │                           ├─ extract_overlays.py ──────► routes.geojson      (required)
  │                           │                            ├► trails.geojson      (required)
  │                           │                            └► trailnames.geojson  (optional)
  │                           └─ extract_pois.py ──────────► pois.geojson        (optional)
  ├─ AWS Terrarium tiles ──── build_terrain.py ────────────► terrain.pmtiles     (required)
  │                           └─ build_contours.py ────────► contours.pmtiles    (optional)
  ├─ brouter.de segments4 ─── download ────────────────────► segments/E10_N45.rd5 (required; phone can download)
  └─ tools/themes/*.json ──── make_style.py --all ─────────► styles/onpath*.json (optional override;
                                                               the same styles are bundled in the APK)
  → onpath-data/ + onpath-data-<region>.zip + manifest.json + SHA256SUMS
```

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

Shown for the test region (bbox `10.85,46.35,11.60,46.85`, Bozen / Meran / Algund). `$D` = output
folder `out/<region>/onpath-data`. Times and sizes measured on the 4-core / 16 GB cloud container
(18 Oct 2026, OSM data of 17 Oct 2026).

| # | Step | Command (simplified) | Output | Time | Size |
| --- | --- | --- | --- | --- | --- |
| 1 | OSM extract | `curl -fL -C - -o region.osm.pbf $OSM_URL` | `work/osm/*.osm.pbf` | ~1 min | 130 MB |
| 2 | Planetiler jar | `curl -fL -o planetiler.jar …/v0.10.2/planetiler.jar` | `work/planetiler-0.10.2.jar` | <1 min | 89 MB |
| 3 | Base map | `java -Xmx6g -jar planetiler.jar --osm-path=region.osm.pbf --output=base.pmtiles --download --download_dir=work/planetiler-sources --bounds=$BBOX --maxzoom=14 --force` | `$D/base.pmtiles` | TIME_BASE | SIZE_BASE |
| 4 | Terrain | `python3 tools/build_terrain.py terrain.pmtiles 10.85 46.35 11.60 46.85 13` | `$D/terrain.pmtiles` | TIME_TERRAIN | SIZE_TERRAIN |
| 5 | Overlays | `python3 tools/extract_overlays.py region.osm.pbf $D 10.85 46.35 11.60 46.85` | `$D/routes.geojson`, `trails.geojson`, `trailnames.geojson` | TIME_OVERLAYS | SIZE_OVERLAYS |
| 6 | POIs | `python3 tools/extract_pois.py region.osm.pbf $D 10.85 46.35 11.60 46.85` | `$D/pois.geojson` | TIME_POIS | SIZE_POIS |
| 7 | Contours | `python3 tools/build_contours.py terrain.pmtiles contours.pmtiles 10.85 46.35 11.60 46.85` | `$D/contours.pmtiles` | TIME_CONTOURS | SIZE_CONTOURS |
| 8 | Styles (+ sprite) | `python3 tools/make_style.py --all` (sprite: `python3 tools/make_sprite.py`) | `out/<region>/styles/onpath*.json` | <1 s | ~100 KB each |
| 9 | Routing tiles | `curl -fL -C - -o segments/E10_N45.rd5 https://brouter.de/brouter/segments4/E10_N45.rd5` | `$D/segments/*.rd5` | ~1 min | 190 MB |
| 10 | Package | zip (`.pmtiles`/`.rd5` stored, GeoJSON deflated) + manifest + checksums | `onpath-data-<region>.zip` | TIME_ZIP | SIZE_ZIP |

Whole run from cached inputs: **TIME_TOTAL**. Zip without routing tiles (`--no-segments`): SIZE_ZIP_NOSEG.
Peak memory ~6 GB (Planetiler heap); temp disk ~5 GB plus the 1.4 GB Planetiler sources cache.

Notes per step:
- **3 Planetiler** writes temporary files (`--tmpdir`) and caches the side data (`--download_dir`);
  the script runs it inside `work/tmp/` so nothing lands in the current folder. The OpenMapTiles
  profile keeps `name:de`, which the style prefers (`name:de` → `name_de` → `name`).
- **4 Terrain** downloads every tile z5–13 of the bbox (16 threads, 4 retries). It does not depend on
  the OSM data, so the script only rebuilds it when the bbox or the script changes.
- **5 Overlays** do two passes over the PBF (relations, then ways with node locations, in memory –
  fine for regional extracts; for a whole country give the machine ≥ 16 GB).
- **7 Contours** read `terrain.pmtiles` only (no extra download).
- **8 Styles**: the script builds them from a copy of `tools/` in `work/tmp/stylebuild`, so a data build
  never changes the repo's bundled `app/src/main/assets/style/*.json`.

## 5. Where each map refinement lives

| Refinement | Data (pipeline) | Style (`tools/make_style.py` + modules) | App |
| --- | --- | --- | --- |
| **Trail names + route numbers** | `extract_overlays.py` → `trailnames.geojson`: `t="n"` trail name lines (German name: `name:de` or the German half of "DE - IT"; unnamed member ways get the hiking relation's name; same-name ways merged, simplified + Chaikin-smoothed; `tier` 1 = Waalweg/Höhenweg/≥2.5 km, 2 = normal, 3 = short or not a "…weg"; `len` m). `t="r"`: AVS route numbers of the way's hiking relations ("25", "24 · 25") | `tools/style_trail_names.py`: layers `trail-ref` (z14+, upright red numbers), `trail-name-t3/t2/t1` (z15/z14/z13, longer first) | optional, stripped by `OptionalSources` when missing |
| **Trail difficulty** | `trails.geojson`: `sac` 1–6 from `sac_scale`, `mtb` 0–6 from `mtb:scale` | `trails-*` layers: colour *and* dash by SAC (T1–2 / T3 / T4+), white casing | required |
| **Route bands** | `routes.geojson`: route relations `hiking/foot` → `hike`, `bicycle` → `bike`, `mtb` → `mtb` (+ `ref`, `name`) | `route_bands()`: pale opaque bands **below** roads/paths/trails; ids `routes-hike/-bike/-mtb` | toggled per activity (`MapController`) |
| **POIs + tiers** | `extract_pois.py`: 28 kinds (`classify()`), ways/areas → representative point; **bus de-dup**: same-name stops within 40 m merged to their midpoint (`MERGE_KINDS`) | `POI_TIERS` in `make_style.py`: `poi-tier1` (icon z12, dot until z13, label z14), `poi-tier2` (z14/z15), `poi-tier3` (z15/z16); sort key = kind rank, named first; see `docs/poi-categories.md` | Settings filter wraps every `poi-*` layer filter; tap lookup |
| **Railways + stations** | rails from base tiles (`transportation` class `rail`); stations = POI kind `train` (railway station/halt, no subway/funicular/tourist) | `rail-casing`, `rail` (from z8), `rail-hatch` (z12); `poi-station` top POI layer, icon z10, name z11 (`station.*` tokens) | station follows the POI filter (`train`) |
| **Sprite (POI icons)** | `tools/make_sprite.py` + `tools/icons/*.svg` → `app/src/main/assets/sprites/onpath{,@2x}.{json,png}` | `"sprite": "asset://sprites/onpath"`, images `poi-<kind>`, `poi-<kind>-small` | **APK asset** – a new kind/icon needs a new app build |
| **Themes / design tokens** | `tools/themes/classic.json`, `high-contrast.json` (`"extends"`), read by `tools/style_theme.py` | `make_style.py --all` → `onpath.json` (classic), `onpath-<theme>.json` | bundled in APK; `styles/` override in the data folder wins (`StyleLoader`) |
| **Contours** | `build_contours.py`: 50 m lines at z12, 20 m at z13 (MVT layer `contour`, props `ele`, `idx`=1 every 100 m) | `contour-*` layers | optional, toggle in Settings |
| **Hillshade / 3-D relief** | `terrain.pmtiles` (Terrarium) | `hillshade.*` tokens | required |
| **Base map** | Planetiler OpenMapTiles z0–14 | land cover, water, roads, paths, labels (German) | required |

Token reference and the "why" of the look: `docs/map-style-notes.md`. POI kinds and counts:
`docs/poi-categories.md`, `docs/poi-integration.md`.

## 6. Output layout and the phone

```
out/<region>/
  onpath-data/                  ← exactly the phone's data folder
    base.pmtiles  terrain.pmtiles  contours.pmtiles
    routes.geojson  trails.geojson  trailnames.geojson  pois.geojson
    segments/E10_N45.rd5        (not with --no-segments)
    styles/onpath*.json         (only with --with-styles)
  onpath-data-<region>.zip      ← the same, under "onpath-data/" in the zip
  manifest.json                 ← region, bbox, build date, OSM date, tool versions, per-file bytes + sha256
  SHA256SUMS                    ← `sha256sum -c SHA256SUMS` (run inside out/<region>/)
  styles/onpath*.json           ← always built, for pushing a new look without new data
```

`manifest.json` is deliberately not inside the zip: the importer only accepts known file types and
would list it as "skipped".

How the data reaches the phone (data folder `/sdcard/Android/data/com.onpath.app/files/onpath/`):
1. **Zip import on the phone** (no computer): copy the zip to the phone (download, chat app, USB), then
   *missing-data screen → "Import map data (.zip) from this phone"* or *Settings → Import*.
   `data/DataImporter.kt` maps entries by name only: `*.rd5` → `segments/`, `*.pmtiles` / `*.geojson`
   → top level, `styles/*.json` or `onpath*.json` → `styles/`; everything else is skipped. Each file is
   written to a temp file and renamed when complete, free space is checked first. Single files
   (e.g. just a new `pois.geojson` or `onpath-high-contrast.json`) can be imported the same way.
2. **adb push**: `adb push out/<region>/onpath-data/. /sdcard/Android/data/com.onpath.app/files/onpath/`
3. **Routing tiles from brouter.de on the phone**: missing-data screen → "Download routing data
   (≈ 200 MB, Wi-Fi)" (`data/SegmentDownloader.kt`, resumable). That is why `--no-segments` packages
   are fine and much smaller.

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

**Machine:** for regional packages a CX32/CPX31-class cloud server (4 vCPU, 8–16 GB RAM, 80+ GB disk)
is plenty (test region ≈ TIME_TOTAL). For whole countries (Italy, Austria, Germany): CCX33 / AX-line
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

## 10. Troubleshooting

- *Step skipped although data changed*: run with `--force` (or delete the output file). Outputs are
  rebuilt automatically when the OSM file, the tool script or the bbox changes.
- *Download interrupted*: just re-run – downloads resume (`.part` file); a partial file of an older
  server version is discarded.
- *Planetiler fails*: see `work/logs/<region>/planetiler.log`; usually RAM (`JAVA_XMX`) or disk.
- *No network for the inputs*: `--skip-download` uses only the cache (`work/`), plus `OSM_FILE=…`,
  `PLANETILER_JAR=…`, `PLANETILER_SOURCES=…` to point at existing files.
- *Phone shows the old look*: an old `styles/` override in the data folder wins over the APK style.
