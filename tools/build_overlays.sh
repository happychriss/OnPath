#!/usr/bin/env bash
# Format 2 (docs/design/map-data-size.md §2): turn the overlay tile inputs of extract_overlays.py --tiles into ONE
# vector tile file overlays.pmtiles (MVT, tippecanoe), one source-layer per former GeoJSON file.
#
#   tools/build_overlays.sh IN_DIR OUT.pmtiles [--clip minlon,minlat,maxlon,maxlat] [--extra LAYER_FILE.pmtiles ...]
#
#   IN_DIR   folder with paths/streets/trails/ways/routes/trailnames .ndjson (extract_overlays.py ... --tiles IN_DIR;
#            streets.ndjson may be empty or missing, e.g. a cell without streets; barriers.ndjson + wayclass.json
#            only with extract_overlays.py --wayclass)
#   --clip   cut every feature at this bbox (tippecanoe --clip-bounding-box); a cell build passes its buffered bbox
#            and keeps only its own tiles afterwards (tools/cells/cells.py tiles)
#   --extra  more vector tile files joined in as they are (e.g. contours.pmtiles with layer "contour" later)
#
# Env: TIPPECANOE=tippecanoe  TILE_JOIN=tile-join (tippecanoe >= 2.17 for .pmtiles; 2.79.0 used)
#
# Layers and zooms (schema: docs/map-pipeline.md "Overlays as vector tiles"):
#   paths       z11-14  z11-12 = coarse copy of every path / track (former lowways.geojson), z13-14 = exact ways
#                       (per-feature zoom range from extract_overlays.py)
#   streets     z13-14  small streets (OpenMapTiles minor / service), the base map has none there (trim_base.sh)
#   trails      z13-14  ways       z13-14  trailnames z13-14  routes z9-14
#   barriers    z14     barrier nodes that block an activity (--wayclass; optional)
# With router classes (wayclass.json) the tile file's description says "onpath-wayclass:<profile hash>".
# Coordinates: extent 4096 (z14 grid ~0.4 m here), no simplification at z14 (--simplify-only-low-zooms), no feature
# dropping (-r1, no feature / tile size limit), small buffer (8 px of 256) for line joins and labels at tile edges.
set -euo pipefail

[ $# -ge 2 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
IN=$1 OUT=$2; shift 2
CLIP=() EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --clip) IFS=, read -r a b c d <<<"$2"; CLIP=(--clip-bounding-box="$a,$b,$c,$d"); shift ;;
    --extra) EXTRA+=("$2"); shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
TIPPECANOE=${TIPPECANOE:-tippecanoe}
TILE_JOIN=${TILE_JOIN:-tile-join}
command -v "$TIPPECANOE" >/dev/null || { echo "tippecanoe missing (build it: see docs/map-pipeline.md)" >&2; exit 1; }
command -v "$TILE_JOIN" >/dev/null || { echo "tile-join missing (comes with tippecanoe)" >&2; exit 1; }

TMP=$(mktemp -d "${OUT%.pmtiles}.parts.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
COMMON=(-r1 --no-feature-limit --no-tile-size-limit --simplify-only-low-zooms --buffer=8 --force --quiet
        -P --no-progress-indicator "${CLIP[@]}")
layer() {  # NAME MINZOOM MAXZOOM
  local f="$IN/$1.ndjson"
  [ -s "$f" ] || { echo "$f missing or empty" >&2; exit 1; }
  "$TIPPECANOE" "${COMMON[@]}" -l "$1" -Z "$2" -z "$3" -o "$TMP/$1.pmtiles" "$f"
}
layer paths 11 14
STREETS=()
if [ -s "$IN/streets.ndjson" ]; then layer streets 13 14; STREETS=("$TMP"/streets.pmtiles); fi
layer trails 13 14
layer ways 13 14
layer trailnames 13 14
layer routes 9 14
BARRIERS=()
if [ -s "$IN/barriers.ndjson" ]; then layer barriers 14 14; BARRIERS=("$TMP"/barriers.pmtiles); fi
DESC=()
if [ -s "$IN/wayclass.json" ]; then
  DESC=(-N "onpath-wayclass:$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["profiles"])' "$IN/wayclass.json")")
fi
rm -f "$OUT.tmp.pmtiles"
"$TILE_JOIN" --force --quiet --no-tile-size-limit -n "OnPath overlays" -A "© OpenStreetMap contributors" "${DESC[@]}" \
  -o "$OUT.tmp.pmtiles" "$TMP"/paths.pmtiles "${STREETS[@]}" "$TMP"/trails.pmtiles "$TMP"/ways.pmtiles "$TMP"/trailnames.pmtiles \
  "$TMP"/routes.pmtiles "${BARRIERS[@]}" "${EXTRA[@]}"
mv -f "$OUT.tmp.pmtiles" "$OUT"
