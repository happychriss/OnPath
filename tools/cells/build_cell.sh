#!/usr/bin/env bash
# Build one map cell (z10 tile) in format 1 – docs/design/map-cells.md §3/§4/§6, usage in tools/cells/README.md.
#
#   tools/cells/build_cell.sh Z X Y            e.g. 10 543 361 (Meran)
#
# Env (defaults fit the Docker image, paths inside the container):
#   SRC_PBF=/data/work/osm/trentino_alto_adige-latest.osm.pbf   regional extract covering cell + buffer
#   DATA_ROOT=/data          store root: cells are written to $DATA_ROOT/cells/f1/Z/X/Y/<osm_date>/
#   WORK=/data/work          caches (planetiler-sources) and temp folder work/cells/Z-X-Y (deleted on success)
#   PLANETILER_JAR=/opt/planetiler.jar  PLANETILER_SOURCES=$WORK/planetiler-sources  JAVA_XMX=2g
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
#   cut       osmium extract -b <cell + buffer> -s smart -S types=multipolygon,route
#   base      Planetiler z10-14, --bounds = cell; then only the cell's own tiles are kept
#   overlays  tools/extract_overlays.py on the buffered bbox (attributes from whole features), then clipped
#   pois      tools/extract_pois.py on the buffered bbox (bus-stop merge on the buffered set), then owner rule
#   terrain   tools/build_terrain.py for cell + one z12 tile ring; own z10-13 tiles kept
#   contours  tools/build_contours.py from the ring terrain (lines meet the neighbour); own tiles kept
#   clip      lines cut exactly at the border, points by the half-open owner rule (tools/cells/cells.py)
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
SRC_PBF=${SRC_PBF:-/data/work/osm/trentino_alto_adige-latest.osm.pbf}
DATA_ROOT=${DATA_ROOT:-/data}
WORK=${WORK:-/data/work}
PLANETILER_JAR=${PLANETILER_JAR:-/opt/planetiler.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
JAVA_XMX=${JAVA_XMX:-2g}
BUFFER_KM=${BUFFER_KM:-5}
ID="$Z-$X-$Y"
TMP="$WORK/cells/$ID"
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
DEST="$DATA_ROOT/cells/f1/$Z/$X/$Y"
VER="$DEST/$OSM_DATE"

rm -rf "$TMP"; mkdir -p "$TMP/logs" "$TMP/raw" "$TMP/out" "$TMP/planetiler"
: > "$STEPS"
T0=$(date +%s)
log "cell $ID  bbox=$CELL_BBOX  buffer=${BUFFER_KM} km  osm=$(basename "$SRC_PBF") ($OSM_DATE)  git=$GIT_REV"

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

run cut osmium extract -b "$BUF_BBOX" -s smart -S types=multipolygon,route --overwrite \
  -o "$TMP/cell.osm.pbf" "$RENUM/renum.osm.pbf"
log "    cell.osm.pbf $(du -h "$TMP/cell.osm.pbf" | cut -f1)"

# Planetiler: run inside TMP so its stray files stay there
run base bash -c "cd '$TMP' && exec java -Xmx$JAVA_XMX -jar '$PLANETILER_JAR' --osm-path='$TMP/cell.osm.pbf' \
  --output='$TMP/raw/base.pmtiles' --download --download_dir='$PLANETILER_SOURCES' --tmpdir='$TMP/planetiler' \
  --bounds='$CELL_BBOX' --minzoom=10 --maxzoom=14 --force"
rm -rf "$TMP/planetiler"
run base-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/base.pmtiles" "$TMP/out/base.pmtiles"

mkdir -p "$TMP/raw/overlays"
run overlays python3 "$TOOLS/extract_overlays.py" "$TMP/cell.osm.pbf" "$TMP/raw/overlays" "${BUF_ARGS[@]}"
run pois python3 "$TOOLS/extract_pois.py" "$TMP/cell.osm.pbf" "$TMP/raw/overlays" "${BUF_ARGS[@]}"
run pois-ids $PY remap-ids "$RENUM/idx" "$TMP/raw/overlays/pois.geojson"
run clip $PY clip "$Z" "$X" "$Y" "$TMP/raw/overlays" "$TMP/out"
sed 's/^/    /' "$TMP/logs/clip.log"

run terrain python3 "$TOOLS/build_terrain.py" "$TMP/raw/terrain.pmtiles" "${RING_ARGS[@]}" 13
run contours python3 "$TOOLS/build_contours.py" "$TMP/raw/terrain.pmtiles" "$TMP/raw/contours.pmtiles" "${INSET_ARGS[@]}"
run terrain-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/terrain.pmtiles" "$TMP/out/terrain.pmtiles"
run contours-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/contours.pmtiles" "$TMP/out/contours.pmtiles"
cat "$TMP"/logs/*-own.log | sed 's/^/    /'

for f in base.pmtiles terrain.pmtiles routes.geojson trails.geojson; do
  [ -s "$TMP/out/$f" ] || die "required file $f missing or empty"
done
run manifest $PY manifest "$Z" "$X" "$Y" "$TMP/out" "$OSM_DATE" "$GIT_REV" "cells/f1/$Z/$X/$Y/$OSM_DATE"

# build.json: timings + sizes (diagnostics only, not listed in the manifest)
python3 - "$STEPS" "$TMP/out" "$TMP/cell.osm.pbf" "$ID" "$(( $(date +%s) - T0 ))" > "$TMP/build.json" <<'PY'
import json, os, sys
steps, out, cut, cid, total = sys.argv[1:]
rows = [l.rstrip("\n").split("\t") for l in open(steps) if "\t" in l]
print(json.dumps({"cell": cid, "total_s": int(total), "cut_pbf_bytes": os.path.getsize(cut),
                  "steps": [{"step": n, "wall_s": float(t), "peak_rss_mb": round(int(m) / 1024)} for n, t, m in rows],
                  "files": {f: os.path.getsize(os.path.join(out, f)) for f in sorted(os.listdir(out))}}, indent=1))
PY

# publish: version folder complete first, then current.json (readers never see a half cell)
mkdir -p "$DEST"
rm -rf "$VER.tmp"; cp -r "$TMP/out" "$VER.tmp"; cp "$TMP/build.json" "$VER.tmp/build.json"
rm -rf "$VER"; mv "$VER.tmp" "$VER"
printf '{"version": "%s"}\n' "$OSM_DATE" > "$DEST/current.json.tmp" && mv "$DEST/current.json.tmp" "$DEST/current.json"
[ "${KEEP_WORK:-0}" = 1 ] || rm -rf "$TMP"

log "OK: cell $ID in $(( $(date +%s) - T0 ))s -> $VER ($(du -sh "$VER" | cut -f1))"
