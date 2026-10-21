#!/usr/bin/env bash
# Build one map cell (z10 tile) – docs/design/map-cells.md §3/§4/§6 (format 1), §18 (format 2); usage in
# tools/cells/README.md.
#
#   tools/cells/build_cell.sh Z X Y            e.g. 10 543 361 (Meran)
#
# Env (defaults fit the Docker image, paths inside the container):
#   FORMAT=2                 cell format: 2 (default; docs/design/map-data-size.md) or 1 (GeoJSON overlays, PNG terrain)
#   SRC_PBF=/data/work/osm/trentino_alto_adige-latest.osm.pbf   regional extract covering cell + buffer
#   DATA_ROOT=/data          store root: cells are written to $DATA_ROOT/cells/f$FORMAT/Z/X/Y/<osm_date>/
#   WORK=/data/work          caches (planetiler-sources, terrarium) and temp folder work/cells/Z-X-Y (deleted on success)
#   PLANETILER_JAR=/opt/planetiler.jar  PLANETILER_SOURCES=$WORK/planetiler-sources  JAVA_XMX=2g
#   TERRARIUM_CACHE=$WORK/terrarium   AWS Terrarium PNGs (format 2: terrain + contours read the same tiles)
#   TIPPECANOE=tippecanoe TILE_JOIN=tile-join   (format 2)
#   BUFFER_KM=5              osmium cut buffer (complete features at the edge)
#   KEEP_WORK=1              keep the temp folder (raw unclipped files, for the seam check)
#
# Before the cut the source is renumbered once (cached next to it until the source changes): osmium's smart
# strategy keeps bit sets over the whole id range – with today's OSM node ids (~13e9) that is ~4 GB even for a small
# extract; on dense ids (1..N) the cut needs ~170 MB. POI ids are mapped back to OSM ids afterwards; the base map's
# feature ids are the renumbered ones (the app does not use them).
#
# Steps (each timed, peak RAM via /usr/bin/time; summary in <version>/build.json, not part of the manifest):
#   renumber  osmium renumber -i (only when the source changed; ~6 s, ~300 MB)
#   cut       osmium extract -b <cell + buffer> -s smart -S types=multipolygon,route,boundary
#   base      Planetiler z10-14, --bounds = cell; only the cell's own tiles are kept
#             format 2: --exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de, then trim_base.sh
#   overlays  format 1: tools/extract_overlays.py on the buffered bbox (attributes from whole features), then clipped
#             format 2: extract_overlays.py --tiles --no-geojson --smooth on the buffered bbox -> build_overlays.sh
#                       (tippecanoe on the buffered data, --clip = buffered bbox) -> only the cell's own z>=10 tiles
#   pois      tools/extract_pois.py on the buffered bbox (bus-stop merge on the buffered set), then owner rule
#   addresses format 2: tools/extract_addresses.py --owner on the cut, bbox = cell -> addresses/10-X-Y.sqlite
#   terrain   format 1: build_terrain.py for cell + one z12 tile ring (z5-13 PNG), own z10-13 tiles kept
#             format 2: build_terrain.py --cell 10/X/Y --format 2 (WebP z10-12, own tiles only)
#   contours  format 1: build_contours.py from the ring terrain; format 2: build_contours.py aws (AWS z12/z13,
#             one tile ring read); own tiles kept; format 2 keeps contours.pmtiles separate (same file as the region)
#   clip      GeoJSON: lines cut exactly at the border, points by the half-open owner rule (tools/cells/cells.py)
#   manifest  manifest.json (sizes, sha256), then <version> folder renamed into place and current.json switched
# The steps call the same tools as tools/build_region.sh (which stays the region / zip build).
set -euo pipefail

[ $# -eq 3 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
Z=$1 X=$2 Y=$3
[ "$Z" = 10 ] || { echo "only z10 cells are defined (map-cells.md §2)" >&2; exit 2; }
for v in "$X" "$Y"; do [[ "$v" =~ ^[0-9]+$ ]] || { echo "bad cell x/y" >&2; exit 2; }; done

CELLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TOOLS=$(cd "$CELLS/.." && pwd)
REPO=$(cd "$TOOLS/.." && pwd)
FORMAT=${FORMAT:-2}
[[ "$FORMAT" =~ ^[12]$ ]] || { echo "FORMAT must be 1 or 2" >&2; exit 2; }
SRC_PBF=${SRC_PBF:-/data/work/osm/trentino_alto_adige-latest.osm.pbf}
DATA_ROOT=${DATA_ROOT:-/data}
WORK=${WORK:-/data/work}
PLANETILER_JAR=${PLANETILER_JAR:-/opt/planetiler.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
TERRARIUM_CACHE=${TERRARIUM_CACHE:-$WORK/terrarium}
JAVA_XMX=${JAVA_XMX:-2g}
BUFFER_KM=${BUFFER_KM:-5}
TIPPECANOE=${TIPPECANOE:-tippecanoe}; TILE_JOIN=${TILE_JOIN:-tile-join}; export TIPPECANOE TILE_JOIN
ID="$Z-$X-$Y"
TMP="$WORK/cells/f$FORMAT-$ID"
PY="python3 $CELLS/cells.py"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
STEPS="$TMP/steps.tsv"
# run NAME CMD... : run one command, log wall time + peak RSS (KB) to steps.tsv; output to $TMP/logs/NAME.log
run() {
  local name=$1; shift
  log "==> $name"
  /usr/bin/time -f "$name	%e	%M" -a -o "$STEPS" "$@" > "$TMP/logs/$name.log" 2>&1 \
    || { tail -20 "$TMP/logs/$name.log" >&2; die "step $name failed (log: $TMP/logs/$name.log)"; }
  log "    $(tail -1 "$STEPS" | awk -F'\t' '{printf "%s s, peak %.0f MB", $2, $3/1024}')"
}
trap 'rc=$?; [ $rc -ne 0 ] && log "FAILED: cell $ID (temp kept in $TMP)" >&2' EXIT

[ -s "$SRC_PBF" ] || die "SRC_PBF=$SRC_PBF not found"
[ -s "$PLANETILER_JAR" ] || die "planetiler jar missing: $PLANETILER_JAR"
command -v osmium >/dev/null || die "osmium-tool missing"
if [ "$FORMAT" = 2 ]; then
  command -v "$TIPPECANOE" >/dev/null && command -v "$TILE_JOIN" >/dev/null \
    || die "format 2 needs tippecanoe + tile-join (in the image since tools/cells/Dockerfile builds them)"
fi

CELL_BBOX=$($PY bbox "$Z" "$X" "$Y")                              # exact (for --bounds and clipping)
INSET_BBOX=$($PY bbox "$Z" "$X" "$Y" --inset)                     # tile-range tools: no neighbour tiles
BUF_BBOX=$($PY bbox "$Z" "$X" "$Y" --buffer-km "$BUFFER_KM")
RING_BBOX=$($PY bbox "$Z" "$X" "$Y" --ring-z12 --inset)
OSM_DATE=$(python3 - "$SRC_PBF" <<'PY'
import sys, osmium
r = osmium.io.Reader(sys.argv[1], osmium.osm.osm_entity_bits.NOTHING)
h = r.header()
print((h.get("osmosis_replication_timestamp") or h.get("timestamp") or "")[:10])
r.close()
PY
)
[ -n "$OSM_DATE" ] || OSM_DATE=$(date -u -r "$SRC_PBF" +%Y-%m-%d)
GIT_REV=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)
[ -n "$(git -C "$REPO" status --porcelain -- tools 2>/dev/null)" ] && GIT_REV="$GIT_REV+dirty"
DEST="$DATA_ROOT/cells/f$FORMAT/$Z/$X/$Y"
# version folder = OSM date + content schema ("2026-10-20-s2", cells.py CELL_SCHEMA): a rebuild for a newer style is
# a new version, not an overwrite of an immutable folder the app and caches already have
VERSION=$($PY version-name "$OSM_DATE")
VER="$DEST/$VERSION"

rm -rf "$TMP"; mkdir -p "$TMP/logs" "$TMP/raw" "$TMP/out" "$TMP/planetiler"
: > "$STEPS"
T0=$(date +%s)
log "cell $ID  format $FORMAT  bbox=$CELL_BBOX  buffer=${BUFFER_KM} km  osm=$(basename "$SRC_PBF") ($OSM_DATE)  git=$GIT_REV"

IFS=, read -r -a CELL_ARGS <<<"$CELL_BBOX"
IFS=, read -r -a BUF_ARGS <<<"$BUF_BBOX"
IFS=, read -r -a INSET_ARGS <<<"$INSET_BBOX"
IFS=, read -r -a RING_ARGS <<<"$RING_BBOX"

# renumbered source, shared by all cells of one source (rebuilt when the source is newer)
RENUM="$WORK/osm/$(basename "$SRC_PBF" .osm.pbf).renum"
if [ ! -s "$RENUM/renum.osm.pbf" ] || [ "$SRC_PBF" -nt "$RENUM/renum.osm.pbf" ]; then
  rm -rf "$RENUM.tmp"; mkdir -p "$RENUM.tmp/idx"
  run renumber osmium renumber -i "$RENUM.tmp/idx" -o "$RENUM.tmp/renum.osm.pbf" "$SRC_PBF"
  rm -rf "$RENUM"; mv "$RENUM.tmp" "$RENUM"
fi

# boundary relations complete too: the address index needs whole municipality polygons
run cut osmium extract -b "$BUF_BBOX" -s smart -S types=multipolygon,route,boundary --overwrite \
  -o "$TMP/cell.osm.pbf" "$RENUM/renum.osm.pbf"
log "    cell.osm.pbf $(du -h "$TMP/cell.osm.pbf" | cut -f1)"

# ---------------------------------------------------------------------------------------- base map
PT_TRIM=""
[ "$FORMAT" = 2 ] && PT_TRIM="--exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de"
# Planetiler: run inside TMP so its stray files stay there
run base bash -c "cd '$TMP' && exec java -Xmx$JAVA_XMX -jar '$PLANETILER_JAR' --osm-path='$TMP/cell.osm.pbf' \
  --output='$TMP/raw/base.pmtiles' --download --download_dir='$PLANETILER_SOURCES' --tmpdir='$TMP/planetiler' \
  --bounds='$CELL_BBOX' --minzoom=10 --maxzoom=14 --force $PT_TRIM"
rm -rf "$TMP/planetiler"
if [ "$FORMAT" = 2 ]; then
  run base-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/base.pmtiles" "$TMP/raw/base-own.pmtiles"
  run base-trim "$TOOLS/trim_base.sh" "$TMP/raw/base-own.pmtiles" "$TMP/out/base.pmtiles"
else
  run base-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/base.pmtiles" "$TMP/out/base.pmtiles"
fi

# ---------------------------------------------------------------------------------- overlays + pois
if [ "$FORMAT" = 2 ]; then
  mkdir -p "$TMP/raw/otiles" "$TMP/raw/pois"
  run overlays python3 "$TOOLS/extract_overlays.py" "$TMP/cell.osm.pbf" "$TMP/raw/otiles" "${BUF_ARGS[@]}" \
    --tiles "$TMP/raw/otiles" --no-geojson --smooth
  run overlays-tiles "$TOOLS/build_overlays.sh" "$TMP/raw/otiles" "$TMP/raw/overlays.pmtiles" --clip "$BUF_BBOX"
  run overlays-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/overlays.pmtiles" "$TMP/out/overlays.pmtiles"
  run pois python3 "$TOOLS/extract_pois.py" "$TMP/cell.osm.pbf" "$TMP/raw/pois" "${BUF_ARGS[@]}"
  run pois-ids $PY remap-ids "$RENUM/idx" "$TMP/raw/pois/pois.geojson"
  run clip $PY clip "$Z" "$X" "$Y" "$TMP/raw/pois" "$TMP/out"
  mkdir -p "$TMP/out/addresses"
  # streets / places by the owner rule (point inside the cell); municipalities from the whole boundaries of the cut
  run addresses python3 "$TOOLS/extract_addresses.py" "$TMP/cell.osm.pbf" "$TMP/out/addresses/$ID.sqlite" \
    "${CELL_ARGS[@]}" --owner
else
  mkdir -p "$TMP/raw/overlays"
  run overlays python3 "$TOOLS/extract_overlays.py" "$TMP/cell.osm.pbf" "$TMP/raw/overlays" "${BUF_ARGS[@]}"
  run pois python3 "$TOOLS/extract_pois.py" "$TMP/cell.osm.pbf" "$TMP/raw/overlays" "${BUF_ARGS[@]}"
  run pois-ids $PY remap-ids "$RENUM/idx" "$TMP/raw/overlays/pois.geojson"
  run clip $PY clip "$Z" "$X" "$Y" "$TMP/raw/overlays" "$TMP/out"
fi
sed 's/^/    /' "$TMP/logs/clip.log"

# ------------------------------------------------------------------------------- terrain + contours
if [ "$FORMAT" = 2 ]; then
  # only the cell's own z10-12 tiles (WebP lossless, 0.25 m); the AWS PNGs stay in the cache for the contours
  run terrain python3 "$TOOLS/build_terrain.py" "$TMP/out/terrain.pmtiles" --cell "$Z/$X/$Y" --format 2 \
    --cache "$TERRARIUM_CACHE"
  # 50 m lines at z12, 20 m at z13 from the original AWS tiles (+ one tile ring read, so lines meet the neighbour)
  run contours python3 "$TOOLS/build_contours.py" aws "$TMP/raw/contours.pmtiles" "${CELL_ARGS[@]}" \
    --cache "$TERRARIUM_CACHE"
else
  run terrain python3 "$TOOLS/build_terrain.py" "$TMP/raw/terrain.pmtiles" "${RING_ARGS[@]}" 13
  run contours python3 "$TOOLS/build_contours.py" "$TMP/raw/terrain.pmtiles" "$TMP/raw/contours.pmtiles" "${INSET_ARGS[@]}"
  run terrain-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/terrain.pmtiles" "$TMP/out/terrain.pmtiles"
fi
run contours-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/contours.pmtiles" "$TMP/out/contours.pmtiles"
cat "$TMP"/logs/*-own.log | sed 's/^/    /'

if [ "$FORMAT" = 2 ]; then REQ="base.pmtiles terrain.pmtiles overlays.pmtiles"
else REQ="base.pmtiles terrain.pmtiles routes.geojson trails.geojson"; fi
for f in $REQ; do
  [ -s "$TMP/out/$f" ] || die "required file $f missing or empty"
done
run manifest $PY manifest "$Z" "$X" "$Y" "$TMP/out" "$OSM_DATE" "$GIT_REV" "cells/f$FORMAT/$Z/$X/$Y/$VERSION" \
  --format "$FORMAT" --version "$VERSION"

# build.json: timings + sizes (diagnostics only, not listed in the manifest)
python3 - "$STEPS" "$TMP/out" "$TMP/cell.osm.pbf" "$ID" "$(( $(date +%s) - T0 ))" "$FORMAT" > "$TMP/build.json" <<'PY'
import json, os, sys
steps, out, cut, cid, total, fmt = sys.argv[1:]
rows = [l.rstrip("\n").split("\t") for l in open(steps) if "\t" in l]
files = {}
for root, _, names in os.walk(out):
    for n in names:
        p = os.path.join(root, n)
        files[os.path.relpath(p, out)] = os.path.getsize(p)
print(json.dumps({"cell": cid, "format": int(fmt), "total_s": int(total), "cut_pbf_bytes": os.path.getsize(cut),
                  "steps": [{"step": n, "wall_s": float(t), "peak_rss_mb": round(int(m) / 1024)} for n, t, m in rows],
                  "files": dict(sorted(files.items()))}, indent=1))
PY

# publish: version folder complete first, then current.json (readers never see a half cell)
mkdir -p "$DEST"
rm -rf "$VER.tmp"; cp -r "$TMP/out" "$VER.tmp"; cp "$TMP/build.json" "$VER.tmp/build.json"
rm -rf "$VER"; mv "$VER.tmp" "$VER"
printf '{"version": "%s"}\n' "$VERSION" > "$DEST/current.json.tmp" && mv "$DEST/current.json.tmp" "$DEST/current.json"
[ "${KEEP_WORK:-0}" = 1 ] || rm -rf "$TMP"

log "OK: cell $ID (format $FORMAT) in $(( $(date +%s) - T0 ))s -> $VER ($(du -sh "$VER" | cut -f1))"
