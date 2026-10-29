#!/usr/bin/env bash
# Build one region package (z7 tile, map zooms 7-9) – docs/design/map-cells.md §20. Called by build_cell.sh for a
# z7 id (so tools/cloud/job_build.py, push_cell.sh and publish_cells.sh need nothing else):
#
#   tools/cells/build_cell.sh 7 66 42          (Düsseldorf / Ruhr / Hilden; same as tools/cells/build_package.sh 7 66 42)
#
# A package is what a region view at z7-9 needs and the world overview in the APK (z0-5) lacks: main roads, towns,
# forests, water, rivers, railways, borders, long-distance routes and relief – for ~200 x 200 km (8 x 8 cells).
# Measured (laptop, 2026-10-26): ≈ 2.7 MB median per package (≤ 3.3 MB), Germany ≈ 32 MB.
#
# Same env as build_cell.sh (SRC_PBF, CUT, DATA_ROOT, WORK, PLANETILER_JAR, PLANETILER_SOURCES, TERRARIUM_CACHE,
# JAVA_XMX, TIPPECANOE, TILE_JOIN, KEEP_WORK); PKG_BUFFER_KM=10 (the cut buffer – BUFFER_KM is the cells' 5 km).
#
# Steps (timed like build_cell.sh; summary in <version>/build.json):
#   cut        osmium extract -b <package + 10 km> -s smart -S types=multipolygon,route,boundary (per source),
#              several sources merged + time-filter, then renumbered (small ids for Planetiler)
#   base       Planetiler OpenMapTiles z7-9, --bounds = package, same layer / language trim as the cells
#   base-own   only the package's own z7-9 tiles
#   base-trim  landcover "farmland" dropped (≈ 23 % of z8-9, not drawn at these zooms) – tile-join filter
#   routes     long-distance hiking / cycle routes (net >= 3, package_routes.py) -> tippecanoe z7-9, layer "routes"
#   routes-own only the package's own tiles -> overlays.pmtiles (the cells' source "overlays", layer "routes")
#   shade      build_hillshade.py z7-9 (the overview's look: its z7 equals the overview's z7), every tile written
#              (--keep-flat): drawn opaque right above the overview, it hides the overview's fills inside the package
#   manifest   manifest.json (kind "package", schema cells.py PACKAGE_SCHEMA), then version folder + current.json
set -euo pipefail

[ $# -eq 3 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
Z=$1 X=$2 Y=$3
[ "$Z" = 7 ] || { echo "region packages are z7 tiles (map-cells.md §20)" >&2; exit 2; }
for v in "$X" "$Y"; do [[ "$v" =~ ^[0-9]+$ ]] && [ "$v" -lt 128 ] || { echo "bad package x/y" >&2; exit 2; }; done

CELLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TOOLS=$(cd "$CELLS/.." && pwd)
REPO=$(cd "$TOOLS/.." && pwd)
FORMAT=${FORMAT:-2}
[ "$FORMAT" = 2 ] || { echo "region packages exist in format 2 only" >&2; exit 2; }
SRC_PBF=${SRC_PBF:-/data/work/osm/trentino_alto_adige-latest.osm.pbf}
DATA_ROOT=${DATA_ROOT:-/data}
WORK=${WORK:-/data/work}
PLANETILER_JAR=${PLANETILER_JAR:-/opt/planetiler.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
TERRARIUM_CACHE=${TERRARIUM_CACHE:-$WORK/terrarium}
JAVA_XMX=${JAVA_XMX:-2g}
PKG_BUFFER_KM=${PKG_BUFFER_KM:-10}
TIPPECANOE=${TIPPECANOE:-tippecanoe}; TILE_JOIN=${TILE_JOIN:-tile-join}
MAXZ=9
# Hill shade: the overview (tools/build_overview.sh) is shaded with strength 0.6 at its top zoom 7 and level gain 1.3;
# here the top zoom is 9, so 0.6 / 1.3² at z9 gives exactly the overview's strength at z7 (no tone step at the edge)
SHADE_STRENGTH=0.355
ID="$Z-$X-$Y"
TMP="$WORK/cells/f$FORMAT-$ID"
PY="python3 $CELLS/cells.py"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
STEPS="$TMP/steps.tsv"
run() {
  local name=$1; shift
  log "==> $name"
  /usr/bin/time -f "$name	%e	%M" -a -o "$STEPS" "$@" > "$TMP/logs/$name.log" 2>&1 \
    || { tail -20 "$TMP/logs/$name.log" >&2; die "step $name failed (log: $TMP/logs/$name.log)"; }
  log "    $(tail -1 "$STEPS" | awk -F'\t' '{printf "%s s, peak %.0f MB", $2, $3/1024}')"
}
trap 'rc=$?; [ $rc -ne 0 ] && log "FAILED: package $ID (temp kept in $TMP)" >&2' EXIT

read -r -a SRCS <<<"$SRC_PBF"
[ ${#SRCS[@]} -ge 1 ] || die "SRC_PBF is empty"
for f in "${SRCS[@]}"; do [ -s "$f" ] || die "SRC_PBF $f not found"; done
[ -s "$PLANETILER_JAR" ] || die "planetiler jar missing: $PLANETILER_JAR"
command -v osmium >/dev/null || die "osmium-tool missing"
command -v "$TIPPECANOE" >/dev/null && command -v "$TILE_JOIN" >/dev/null || die "tippecanoe + tile-join missing"

PKG_BBOX=$($PY bbox "$Z" "$X" "$Y")
INSET_BBOX=$($PY bbox "$Z" "$X" "$Y" --inset)
BUF_BBOX=$($PY bbox "$Z" "$X" "$Y" --buffer-km "$PKG_BUFFER_KM")
OSM_DATE=$(python3 - "${SRCS[@]}" <<'PY'
import os, sys, time, osmium
dates = []
for p in sys.argv[1:]:
    r = osmium.io.Reader(p, osmium.osm.osm_entity_bits.NOTHING)
    h = r.header()
    d = (h.get("osmosis_replication_timestamp") or h.get("timestamp") or "")[:10]
    r.close()
    dates.append(d or time.strftime("%Y-%m-%d", time.gmtime(os.path.getmtime(p))))
print(min(dates))
PY
)
GIT_REV=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)
[ -n "$(git -C "$REPO" status --porcelain -- tools 2>/dev/null)" ] && GIT_REV="$GIT_REV+dirty"
DEST="$DATA_ROOT/cells/f$FORMAT/$Z/$X/$Y"
VERSION=$($PY version-name "$OSM_DATE" --package)
VER="$DEST/$VERSION"

rm -rf "$TMP"; mkdir -p "$TMP/logs" "$TMP/raw" "$TMP/out" "$TMP/planetiler"
: > "$STEPS"
T0=$(date +%s)
log "package $ID  bbox=$PKG_BBOX  buffer=${PKG_BUFFER_KM} km  osm=$(for f in "${SRCS[@]}"; do basename "$f"; done | paste -sd+) ($OSM_DATE)  git=$GIT_REV"
IFS=, read -r -a INSET_ARGS <<<"$INSET_BBOX"

# ---------------------------------------------------------------------------------------- cut
CUT_ARGS=(-b "$BUF_BBOX" -s smart -S types=multipolygon,route,boundary --overwrite)
CUTS=()
for i in "${!SRCS[@]}"; do
  run cut osmium extract "${CUT_ARGS[@]}" -o "$TMP/cut-$i.osm.pbf" "${SRCS[$i]}"
  CUTS+=("$TMP/cut-$i.osm.pbf")
done
if [ ${#CUTS[@]} -gt 1 ]; then
  run merge bash -c "osmium merge --overwrite -o '$TMP/merged.osm.pbf' ${CUTS[*]} \
    && osmium time-filter --overwrite -o '$TMP/raw-cut.osm.pbf' '$TMP/merged.osm.pbf'"
  rm -f "$TMP/merged.osm.pbf" "${CUTS[@]}"
else
  mv "${CUTS[0]}" "$TMP/raw-cut.osm.pbf"
fi
run renumber osmium renumber --overwrite -o "$TMP/pkg.osm.pbf" "$TMP/raw-cut.osm.pbf"
rm -f "$TMP/raw-cut.osm.pbf"
log "    pkg.osm.pbf $(du -h "$TMP/pkg.osm.pbf" | cut -f1)"

# ---------------------------------------------------------------------------------------- base map z7-9
run base bash -c "cd '$TMP' && exec java -Xmx$JAVA_XMX -jar '$PLANETILER_JAR' --osm-path='$TMP/pkg.osm.pbf' \
  --output='$TMP/raw/base.pmtiles' --download --download_dir='$PLANETILER_SOURCES' --tmpdir='$TMP/planetiler' \
  --bounds='$PKG_BBOX' --minzoom=$Z --maxzoom=$MAXZ --force \
  --exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de"
rm -rf "$TMP/planetiler"
run base-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/base.pmtiles" "$TMP/raw/base-own.pmtiles" --minzoom "$Z" --maxzoom "$MAXZ"
# farmland is ≈ 23 % of z8-9 and not what a region view needs; park name points go like in trim_base.sh (a point in
# the park outline's line layer makes MapLibre Native log "Invalid geometry in line layer")
run base-trim "$TILE_JOIN" --force --quiet --no-tile-size-limit \
  -j '{"landcover":["!=","class","farmland"],"park":["!=","$type","Point"]}' \
  -o "$TMP/out/base.pmtiles" "$TMP/raw/base-own.pmtiles"

# ---------------------------------------------------------------------------------------- long-distance routes
run routes-filter osmium tags-filter --overwrite -o "$TMP/raw/routes.osm.pbf" "$TMP/pkg.osm.pbf" \
  r/route=hiking,foot,bicycle
run routes python3 "$CELLS/package_routes.py" "$TMP/raw/routes.osm.pbf" "$TMP/raw/routes.ndjson"
if [ -s "$TMP/raw/routes.ndjson" ]; then
  run routes-tiles "$TIPPECANOE" -r1 --no-feature-limit --no-tile-size-limit --simplify-only-low-zooms --buffer=8 \
    --force --quiet --no-progress-indicator -P -l routes -Z "$Z" -z "$MAXZ" --clip-bounding-box="$BUF_BBOX" \
    -o "$TMP/raw/overlays.pmtiles" "$TMP/raw/routes.ndjson"
  run routes-own $PY tiles "$Z" "$X" "$Y" "$TMP/raw/overlays.pmtiles" "$TMP/out/overlays.pmtiles" --minzoom "$Z" --maxzoom "$MAXZ"
else
  log "    no long-distance routes in this package"
fi

# ---------------------------------------------------------------------------------------- relief
run shade python3 "$TOOLS/build_hillshade.py" "$TMP/out/hillshade.pmtiles" --bbox "${INSET_ARGS[@]}" \
  --minzoom "$Z" --maxzoom "$MAXZ" --strength "$SHADE_STRENGTH" --keep-flat --workers 2 --cache "$TERRARIUM_CACHE"
sed -n 's/^  z/    shade z/p' "$TMP/logs/shade.log"

for f in base.pmtiles hillshade.pmtiles; do
  [ -s "$TMP/out/$f" ] || die "required file $f missing or empty"
done
python3 - "$TMP/sources.json" "${SRCS[@]}" <<'PY'
import json, os, sys, time, osmium
out, files = sys.argv[1], sys.argv[2:]
try:
    meta = json.loads(os.environ.get("SRC_META") or "[]")
except ValueError:
    meta = []
res = []
for i, p in enumerate(files):
    r = osmium.io.Reader(p, osmium.osm.osm_entity_bits.NOTHING)
    h = r.header()
    d = (h.get("osmosis_replication_timestamp") or h.get("timestamp") or "")[:10]
    r.close()
    m = meta[i] if i < len(meta) and isinstance(meta[i], dict) else {}
    res.append({"id": m.get("id") or os.path.basename(p).replace(".osm.pbf", ""),
                "date": d or time.strftime("%Y-%m-%d", time.gmtime(os.path.getmtime(p))),
                "url": m.get("url") or None})
json.dump(res, open(out, "w"))
PY
run manifest $PY manifest "$Z" "$X" "$Y" "$TMP/out" "$OSM_DATE" "$GIT_REV" "cells/f$FORMAT/$Z/$X/$Y/$VERSION" \
  --format "$FORMAT" --version "$VERSION" --sources "$TMP/sources.json"

python3 - "$STEPS" "$TMP/out" "$TMP/pkg.osm.pbf" "$ID" "$(( $(date +%s) - T0 ))" > "$TMP/build.json" <<'PY'
import json, os, sys
steps, out, cut, cid, total = sys.argv[1:]
rows = [l.rstrip("\n").split("\t") for l in open(steps) if "\t" in l]
files = {n: os.path.getsize(os.path.join(out, n)) for n in sorted(os.listdir(out))}
print(json.dumps({"cell": cid, "kind": "package", "format": 2, "total_s": int(total), "cut_pbf_bytes": os.path.getsize(cut),
                  "steps": [{"step": n, "wall_s": float(t), "peak_rss_mb": round(int(m) / 1024)} for n, t, m in rows],
                  "files": files}, indent=1))
PY

mkdir -p "$DEST"
rm -rf "$VER.tmp"; cp -r "$TMP/out" "$VER.tmp"; cp "$TMP/build.json" "$VER.tmp/build.json"
rm -rf "$VER"; mv "$VER.tmp" "$VER"
printf '{"version": "%s"}\n' "$VERSION" > "$DEST/current.json.tmp" && mv "$DEST/current.json.tmp" "$DEST/current.json"
[ "${KEEP_WORK:-0}" = 1 ] || rm -rf "$TMP"

log "OK: package $ID in $(( $(date +%s) - T0 ))s -> $VER ($(du -sh "$VER" | cut -f1))"
