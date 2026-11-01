---
paths:
  - "tools/**"
  - "docs/map-pipeline.md"
  - "docs/design/map-hosting.md"
---
# Map data pipeline, cells, cloud hosting (loads when you touch tools/)

| Path | What |
| --- | --- |
| `tools/` | Data pipeline (Python/Java): terrain, contours, overlays, POIs, style generator, routing jar build + JVM test |
| `tools/cells/`, `tools/cloud/` | Map cells (`build_cell.sh`, zo: `run.sh`); cloud hosting: Cloud Run Job image + `job_build.py`, Cloudflare Worker doorman (`worker/`), `gcp-setup.sh`; deployed by `.github/workflows/maps-deploy.yml` – see `docs/design/map-hosting.md` |
| `tools/dev/` | Map cells built on this PC with the cloud job image: `push_cell.sh <cell>` (working tree → straight onto the phone, dev loop), `publish_cells.sh <cells…>` (origin/main → TEST R2 fast lane, the doorman adopts them) – see `docs/design/map-hosting.md` §7h |

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
| `overlays.pmtiles` | **format 2** (`build_region.sh --format 2`): `extract_overlays.py --tiles --smooth` + `tools/build_overlays.sh` (tippecanoe) – routes, trails, ways, paths (+ former lowways), streets (small streets, smoothed), trailnames as source-layers of one vector file; base map then trimmed by `tools/trim_base.sh` (no path / track / small-street lines at z13–14) | yes in format 2, replaces the overlay `.geojson` files (app: `DataFiles.formatOf`, style `assets/style/f2/`; schema `docs/map-pipeline.md` 5b) |
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
