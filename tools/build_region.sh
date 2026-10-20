#!/usr/bin/env bash
# Build the complete OnPath offline map package for one region (docs/map-pipeline.md).
#
#   tools/build_region.sh [options] [REGION [BBOX [OSM_URL]]]
#
#   REGION   short name, used for folder and zip names           (env REGION, default south-tyrol)
#   BBOX     minlon,minlat,maxlon,maxlat                           (env BBOX)
#   OSM_URL  .osm.pbf extract that covers the bbox                 (env OSM_URL)
#   Region "south-tyrol" has built-in BBOX / OSM_URL defaults (the test region Bozen / Meran / Algund).
#
# Options:
#   --out DIR         output folder (env OUT, default ./out/$REGION)
#   --work DIR        cache / temp folder: OSM extract, planetiler.jar + sources, rd5 cache, logs
#                     (env WORK, default ./work)
#   --skip-download   use only what is already cached (no OSM / planetiler / rd5 downloads;
#                     terrain tiles still come from AWS if terrain.pmtiles has to be built)
#   --no-segments     leave the BRouter routing tiles (*.rd5, ~200 MB each) out of the zip
#                     (the phone can download them itself from brouter.de)
#   --no-contours     skip contours.pmtiles (optional for the app)
#   --no-pois         skip pois.geojson (optional for the app)
#   --no-addresses    skip addresses.sqlite (offline address search; optional for the app)
#   --with-styles     put styles/onpath*.json into the zip (they then override the APK's bundled styles!)
#   --sprite          regenerate the POI sprite in the repo (app/src/main/assets/sprites; needs cairosvg)
#   --format 1|2      map package format (env FORMAT, default 1). 1 = overlays as GeoJSON files (what the app
#                     ships today); 2 = overlays as ONE overlays.pmtiles (tippecanoe + tile-join needed, env
#                     TIPPECANOE / TILE_JOIN), smoothed paths, base map without unused layers / languages and
#                     without path / track lines at z13-14 (docs/design/map-data-size.md, docs/map-pipeline.md)
#   --no-smooth       format 2 without path smoothing (extract_overlays.py --smooth is the format-2 default)
#   --force           rebuild every step even if its output is up to date
#   -h, --help        this text
#
# Env overrides (reuse local files instead of downloading):
#   OSM_FILE=/path/region.osm.pbf        use this extract, never download
#   PLANETILER_JAR=/path/planetiler.jar  use this jar (default $WORK/planetiler-$PLANETILER_VERSION.jar)
#   PLANETILER_VERSION=0.10.2            release downloaded from GitHub when no jar is given
#   PLANETILER_SOURCES=DIR               Planetiler's water / Natural Earth downloads (~1.4 GB, cached)
#   JAVA_XMX=6g  TERRAIN_MAXZOOM=13  OSM_MAX_AGE_DAYS=6  SEGMENTS_MAX_AGE_DAYS=30
#
# Idempotent: every step is skipped when its output is newer than its inputs (OSM file, the tool
# script) and the bbox did not change. Outputs are written to a temp name and renamed when complete.
#
# Result in $OUT:
#   onpath-data/                 the files exactly as the app expects them in .../files/onpath/
#   onpath-data-$REGION.zip      the same, zipped (import on the phone: Settings → Import map data)
#   manifest.json                region, bbox, date, versions, size + sha256 of every file and the zip
#   SHA256SUMS                   sha256sum -c compatible
set -euo pipefail

TOOLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$TOOLS/.." && pwd)

# ---------------------------------------------------------------------------------------------- args
SKIP_DOWNLOAD=0; NO_SEGMENTS=0; NO_CONTOURS=0; NO_POIS=0; NO_ADDRESSES=0; WITH_STYLES=0; SPRITE=0; FORCE=0
FORMAT=${FORMAT:-1}; SMOOTH=1
POS=()
usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift ;;
    --work) WORK=$2; shift ;;
    --region) REGION=$2; shift ;;
    --bbox) BBOX=$2; shift ;;
    --osm-url) OSM_URL=$2; shift ;;
    --skip-download) SKIP_DOWNLOAD=1 ;;
    --no-segments) NO_SEGMENTS=1 ;;
    --no-contours) NO_CONTOURS=1 ;;
    --no-pois) NO_POIS=1 ;;
    --no-addresses) NO_ADDRESSES=1 ;;
    --with-styles) WITH_STYLES=1 ;;
    --sprite) SPRITE=1 ;;
    --force) FORCE=1 ;;
    --format) FORMAT=$2; shift ;;
    --no-smooth) SMOOTH=0 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
    *) POS+=("$1") ;;
  esac
  shift
done
[ ${#POS[@]} -ge 1 ] && REGION=${POS[0]}
[ ${#POS[@]} -ge 2 ] && BBOX=${POS[1]}
[ ${#POS[@]} -ge 3 ] && OSM_URL=${POS[2]}

REGION=${REGION:-south-tyrol}
if [ "$REGION" = south-tyrol ]; then
  BBOX=${BBOX:-10.70,46.30,11.60,46.85}
  OSM_URL=${OSM_URL:-https://download.openstreetmap.fr/extracts/europe/italy/trentino_alto_adige-latest.osm.pbf}
fi
: "${BBOX:?BBOX missing (minlon,minlat,maxlon,maxlat)}"
[[ "$REGION" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { echo "REGION must be lower-case letters, digits, - or _" >&2; exit 2; }
IFS=, read -r MINLON MINLAT MAXLON MAXLAT <<<"$BBOX"
for v in "$MINLON" "$MINLAT" "$MAXLON" "$MAXLAT"; do
  [[ "$v" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] || { echo "bad BBOX '$BBOX' (want minlon,minlat,maxlon,maxlat)" >&2; exit 2; }
done
BBOX_ARGS=("$MINLON" "$MINLAT" "$MAXLON" "$MAXLAT")
[[ "$FORMAT" =~ ^[12]$ ]] || { echo "--format must be 1 or 2" >&2; exit 2; }

OUT=$(mkdir -p "${OUT:-./out/$REGION}" && cd "${OUT:-./out/$REGION}" && pwd)
WORK=$(mkdir -p "${WORK:-./work}" && cd "${WORK:-./work}" && pwd)
DATA="$OUT/onpath-data"
PLANETILER_VERSION=${PLANETILER_VERSION:-0.10.2}
PLANETILER_JAR=${PLANETILER_JAR:-$WORK/planetiler-$PLANETILER_VERSION.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
JAVA_XMX=${JAVA_XMX:-6g}
TERRAIN_MAXZOOM=${TERRAIN_MAXZOOM:-13}
OSM_MAX_AGE_DAYS=${OSM_MAX_AGE_DAYS:-6}
SEGMENTS_MAX_AGE_DAYS=${SEGMENTS_MAX_AGE_DAYS:-30}
BROUTER_URL=https://brouter.de/brouter/segments4
LOGS="$WORK/logs/$REGION"
mkdir -p "$DATA" "$LOGS" "$WORK/osm" "$WORK/segments" "$WORK/tmp"

T0=$(date +%s)
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
step() { STEP_NAME=$1; STEP_T=$(date +%s); log "==> $1"; }
done_step() { log "    done: $STEP_NAME ($(( $(date +%s) - STEP_T ))s)${1:+ – $1}"; }
size() { du -h "$1" | cut -f1; }
trap 'rc=$?; [ $rc -ne 0 ] && log "FAILED in step: ${STEP_NAME:-setup} (logs: $LOGS)" >&2' EXIT

# up_to_date OUTPUT DEP... : output exists, is not empty, is newer than every dep, bbox unchanged
STAMP="$DATA/.bbox"
# the stamp holds the bbox (format 1, as before) or "bbox format2[ nosmooth]": a format switch rebuilds everything
STAMP_VAL=$BBOX
[ "$FORMAT" = 2 ] && STAMP_VAL="$BBOX format2$([ "$SMOOTH" = 0 ] && echo " nosmooth" || true)"
BBOX_CHANGED=0
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" != "$STAMP_VAL" ]; then
  log "bbox / format changed ($(cat "$STAMP") -> $STAMP_VAL): rebuilding everything"; BBOX_CHANGED=1
fi
up_to_date() {
  local out=$1; shift
  [ "$FORCE" = 0 ] && [ "$BBOX_CHANGED" = 0 ] && [ -s "$out" ] || return 1
  local d; for d in "$@"; do [ "$out" -nt "$d" ] || return 1; done
}

# fetch URL DEST MAX_AGE_DAYS : download with resume; skip when DEST is younger than MAX_AGE_DAYS or
# the remote file (size + Last-Modified) did not change. A stale partial download is discarded.
fetch() {
  local url=$1 dest=$2 max_age=$3 part="$2.part" meta remote len
  if [ -s "$dest" ] && [ -z "$(find -L "$dest" -mtime +"$max_age" 2>/dev/null)" ]; then
    log "    cached: $(basename "$dest") ($(size "$dest"), younger than $max_age days)"; return 0
  fi
  if [ "$SKIP_DOWNLOAD" = 1 ]; then
    [ -s "$dest" ] && { log "    --skip-download: using cached $(basename "$dest")"; return 0; }
    die "--skip-download but $dest is missing"
  fi
  remote=$(curl -sSIL --retry 3 --max-time 60 "$url" | tr -d '\r' \
    | awk 'tolower($1)=="content-length:"{l=$2} tolower($1)=="last-modified:"{$1="";m=$0} END{print l"|"m}')
  len=${remote%%|*}
  if [ -s "$dest" ] && [ -f "$dest.meta" ] && [ "$(cat "$dest.meta")" = "$remote" ]; then
    touch "$dest"; log "    unchanged on server: $(basename "$dest")"; return 0
  fi
  if [ -f "$part" ] && [ "$(cat "$part.meta" 2>/dev/null)" != "$remote" ]; then rm -f "$part"; fi
  echo "$remote" > "$part.meta"
  if [ -n "$len" ] && [ -f "$part" ] && [ "$(stat -c %s "$part")" = "$len" ]; then :; else
    log "    downloading $url${len:+ ($(( len / 1048576 )) MB)}"
    curl -fL $([ -t 2 ] || echo -sS) --retry 5 --retry-delay 10 --connect-timeout 30 -C - -o "$part" "$url" \
      || die "download failed: $url (re-run to resume)"
  fi
  if [ -n "$len" ] && [ "$(stat -c %s "$part")" != "$len" ]; then
    die "incomplete download $part ($(stat -c %s "$part") of $len bytes); re-run to resume"
  fi
  mv -f "$part" "$dest"; mv -f "$part.meta" "$dest.meta"
}

log "OnPath region build: $REGION  bbox=$BBOX"
log "    out=$OUT  work=$WORK"

# --------------------------------------------------------------------------------------- 0. checks
step "check tools"
command -v java >/dev/null || die "java missing (need Java 21: apt install openjdk-21-jre-headless)"
JV=$(java -version 2>&1 | awk -F'"' '/version/{print $2; exit}')
[ "${JV%%.*}" -ge 21 ] 2>/dev/null || die "Java 21+ needed for Planetiler $PLANETILER_VERSION (found $JV)"
command -v curl >/dev/null || die "curl missing"
command -v sha256sum >/dev/null || die "sha256sum missing"
PYMODS="osmium shapely pmtiles numpy PIL"
TIPPECANOE=${TIPPECANOE:-tippecanoe}; TILE_JOIN=${TILE_JOIN:-tile-join}; export TIPPECANOE TILE_JOIN
if [ "$FORMAT" = 2 ]; then
  command -v "$TIPPECANOE" >/dev/null && command -v "$TILE_JOIN" >/dev/null \
    || die "format 2 needs tippecanoe + tile-join >= 2.17 (build from github.com/felt/tippecanoe, needs libsqlite3-dev; or set TIPPECANOE / TILE_JOIN)"
fi
[ "$NO_CONTOURS" = 0 ] && PYMODS="$PYMODS contourpy mapbox_vector_tile"
[ "$SPRITE" = 1 ] && PYMODS="$PYMODS cairosvg"
python3 -c "import ${PYMODS// /,}" 2>/dev/null \
  || die "Python packages missing: pip install osmium shapely pmtiles numpy pillow contourpy mapbox-vector-tile (cairosvg for --sprite)"
done_step "java $JV, python $(python3 -c 'import sys;print(sys.version.split()[0])')"

# ---------------------------------------------------------------------------------- 1. OSM extract
step "OSM extract"
if [ -n "${OSM_FILE:-}" ]; then
  [ -s "$OSM_FILE" ] || die "OSM_FILE=$OSM_FILE not found"
  OSM=$(cd "$(dirname "$OSM_FILE")" && pwd)/$(basename "$OSM_FILE")
  log "    using OSM_FILE $OSM"
else
  : "${OSM_URL:?OSM_URL missing (an .osm.pbf extract covering the bbox, e.g. from download.openstreetmap.fr)}"
  OSM="$WORK/osm/$(basename "${OSM_URL%%\?*}")"
  fetch "$OSM_URL" "$OSM" "$OSM_MAX_AGE_DAYS"
fi
# data date from the PBF header (replication timestamp), else the file date
OSM_DATE=$(python3 - "$OSM" 2>/dev/null <<'PY' || true
import sys, osmium
r = osmium.io.Reader(sys.argv[1], osmium.osm.osm_entity_bits.NOTHING)
h = r.header()
print(h.get("osmosis_replication_timestamp") or h.get("timestamp") or "")
r.close()
PY
)
[ -n "$OSM_DATE" ] || OSM_DATE=$(date -u -r "$OSM" +%Y-%m-%dT%H:%M:%SZ)
done_step "$(size "$OSM"), OSM data of $OSM_DATE"

# ------------------------------------------------------------------------------- 2. planetiler.jar
step "planetiler.jar"
if [ ! -s "$PLANETILER_JAR" ]; then
  fetch "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar" \
    "$PLANETILER_JAR" 100000
fi
PT_VER=$(unzip -p "$PLANETILER_JAR" META-INF/maven/com.onthegomap.planetiler/planetiler-dist/pom.properties 2>/dev/null \
  | sed -n 's/^version=//p' || true)
done_step "$PLANETILER_JAR (version ${PT_VER:-unknown})"

# --------------------------------------------------------------------------- 3. base map (Planetiler)
step "base.pmtiles (Planetiler, OpenMapTiles schema, z0-14$([ "$FORMAT" = 2 ] && echo ", format 2 trims"))"
# format 2 (map-data-size.md §3): no layers / languages the app never reads, and (trim_base.sh) no path / track
# lines at z13-14 – the overlay's "paths" draws them there
PT_TRIM=()
[ "$FORMAT" = 2 ] && PT_TRIM=(--exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de)
if up_to_date "$DATA/base.pmtiles" "$OSM" "$PLANETILER_JAR"; then
  log "    up to date"
else
  mkdir -p "$PLANETILER_SOURCES" "$WORK/tmp/planetiler"
  DL=(--download)
  [ "$SKIP_DOWNLOAD" = 1 ] && DL=()
  rm -f "$WORK/tmp/base.pmtiles"
  # run inside WORK so Planetiler's stray files (data/tile_weights...) do not land in the cwd
  ( cd "$WORK/tmp" && java -Xmx"$JAVA_XMX" -jar "$PLANETILER_JAR" \
      --osm-path="$OSM" --output="$WORK/tmp/base.pmtiles" "${DL[@]}" \
      --download_dir="$PLANETILER_SOURCES" --tmpdir="$WORK/tmp/planetiler" \
      --bounds="$BBOX" --maxzoom=14 --force "${PT_TRIM[@]}" ) > "$LOGS/planetiler.log" 2>&1 \
    || { tail -20 "$LOGS/planetiler.log" >&2; die "Planetiler failed (see $LOGS/planetiler.log)"; }
  if [ "$FORMAT" = 2 ]; then
    "$TOOLS/trim_base.sh" "$WORK/tmp/base.pmtiles" "$WORK/tmp/base-trim.pmtiles" > "$LOGS/trim_base.log" 2>&1 \
      || { tail -5 "$LOGS/trim_base.log" >&2; die "trim_base.sh failed"; }
    mv -f "$WORK/tmp/base-trim.pmtiles" "$WORK/tmp/base.pmtiles"
  fi
  mv -f "$WORK/tmp/base.pmtiles" "$DATA/base.pmtiles"
  rm -rf "$WORK/tmp/planetiler"
fi
done_step "$(size "$DATA/base.pmtiles")"

# ------------------------------------------------------------------------------------ 4. terrain
step "terrain.pmtiles (AWS Terrarium tiles, z5-$TERRAIN_MAXZOOM)"
if [ -s "$DATA/terrain.pmtiles" ] && up_to_date "$DATA/terrain.pmtiles" "$TOOLS/build_terrain.py"; then
  log "    up to date"
else
  python3 "$TOOLS/build_terrain.py" "$WORK/tmp/terrain.pmtiles" "${BBOX_ARGS[@]}" "$TERRAIN_MAXZOOM" \
    > "$LOGS/terrain.log" 2>&1 || { tail -5 "$LOGS/terrain.log" >&2; die "terrain download failed"; }
  mv -f "$WORK/tmp/terrain.pmtiles" "$DATA/terrain.pmtiles"
fi
done_step "$(size "$DATA/terrain.pmtiles")"

# ---------------------------------------------------- 5. overlays: routes, trails, trail names
if [ "$FORMAT" = 2 ]; then
step "overlays.pmtiles (paths, trails, ways, routes, trailnames as vector tiles$([ "$SMOOTH" = 1 ] && echo ", smoothed"))"
if up_to_date "$DATA/overlays.pmtiles" "$OSM" "$TOOLS/extract_overlays.py" "$TOOLS/build_overlays.sh"; then
  log "    up to date"
else
  rm -rf "$WORK/tmp/overlays"; mkdir -p "$WORK/tmp/overlays/tiles"
  python3 "$TOOLS/extract_overlays.py" "$OSM" "$WORK/tmp/overlays" "${BBOX_ARGS[@]}" --no-geojson \
    --tiles "$WORK/tmp/overlays/tiles" $([ "$SMOOTH" = 1 ] && echo --smooth) 2>&1 \
    | tee "$LOGS/overlays.log" | sed 's/^/    /'
  "$TOOLS/build_overlays.sh" "$WORK/tmp/overlays/tiles" "$WORK/tmp/overlays.pmtiles" >> "$LOGS/overlays.log" 2>&1 \
    || { tail -5 "$LOGS/overlays.log" >&2; die "build_overlays.sh failed"; }
  mv -f "$WORK/tmp/overlays.pmtiles" "$DATA/overlays.pmtiles"
  rm -rf "$WORK/tmp/overlays"
fi
# a format-2 package carries no format-1 overlay files (the app would ignore them anyway)
rm -f "$DATA"/{routes,trails,ways,lowways,paths,trailnames}.geojson
done_step "$(size "$DATA/overlays.pmtiles")"
else
step "overlays (routes, trails, ways, lowways, trailnames, paths .geojson)"
if up_to_date "$DATA/routes.geojson" "$OSM" "$TOOLS/extract_overlays.py" \
   && up_to_date "$DATA/trails.geojson" "$OSM" "$TOOLS/extract_overlays.py" \
   && { [ -s "$DATA/trailnames.geojson" ] || ! grep -q trailnames "$TOOLS/extract_overlays.py"; }; then
  log "    up to date"
else
  rm -rf "$WORK/tmp/overlays"; mkdir -p "$WORK/tmp/overlays"
  python3 "$TOOLS/extract_overlays.py" "$OSM" "$WORK/tmp/overlays" "${BBOX_ARGS[@]}" 2>&1 \
    | tee "$LOGS/overlays.log" | sed 's/^/    /'
  for f in "$WORK"/tmp/overlays/*.geojson; do mv -f "$f" "$DATA/"; done
fi
[ -s "$DATA/routes.geojson" ] && [ -s "$DATA/trails.geojson" ] || die "routes/trails.geojson missing"
done_step "routes $(size "$DATA/routes.geojson"), trails $(size "$DATA/trails.geojson")$( \
  [ -s "$DATA/ways.geojson" ] && echo ", ways $(size "$DATA/ways.geojson")")$( \
  [ -s "$DATA/trailnames.geojson" ] && echo ", trailnames $(size "$DATA/trailnames.geojson")")"
rm -f "$DATA/overlays.pmtiles"  # back to format 1: the app would otherwise pick format 2
fi

# ----------------------------------------------------------------------------------------- 6. POIs
step "pois.geojson"
if [ "$NO_POIS" = 1 ]; then
  rm -f "$DATA/pois.geojson"; done_step "skipped (--no-pois)"
else
  if up_to_date "$DATA/pois.geojson" "$OSM" "$TOOLS/extract_pois.py"; then
    log "    up to date"
  else
    rm -rf "$WORK/tmp/pois"; mkdir -p "$WORK/tmp/pois"
    python3 "$TOOLS/extract_pois.py" "$OSM" "$WORK/tmp/pois" "${BBOX_ARGS[@]}" 2>&1 \
      | tee "$LOGS/pois.log" | tail -4 | sed 's/^/    /'
    mv -f "$WORK/tmp/pois/pois.geojson" "$DATA/pois.geojson"
  fi
  done_step "$(size "$DATA/pois.geojson")"
fi

# --------------------------------------------------------------- 6b. offline addresses (optional)
step "addresses.sqlite (streets, house numbers, towns for the offline search)"
if [ "$NO_ADDRESSES" = 1 ]; then
  rm -f "$DATA/addresses.sqlite"; done_step "skipped (--no-addresses)"
else
  if up_to_date "$DATA/addresses.sqlite" "$OSM" "$TOOLS/extract_addresses.py"; then
    log "    up to date"
  else
    python3 "$TOOLS/extract_addresses.py" "$OSM" "$WORK/tmp/addresses.sqlite" "${BBOX_ARGS[@]}" 2>&1 \
      | tee "$LOGS/addresses.log" | tail -2 | sed 's/^/    /'
    mv -f "$WORK/tmp/addresses.sqlite" "$DATA/addresses.sqlite"
  fi
  done_step "$(size "$DATA/addresses.sqlite")"
fi

# ------------------------------------------------------------------------------------- 7. contours
step "contours.pmtiles (50 m at z12, 20 m at z13)"
if [ "$NO_CONTOURS" = 1 ]; then
  rm -f "$DATA/contours.pmtiles"; done_step "skipped (--no-contours)"
else
  if up_to_date "$DATA/contours.pmtiles" "$DATA/terrain.pmtiles" "$TOOLS/build_contours.py"; then
    log "    up to date"
  else
    python3 "$TOOLS/build_contours.py" "$DATA/terrain.pmtiles" "$WORK/tmp/contours.pmtiles" "${BBOX_ARGS[@]}" \
      > "$LOGS/contours.log" 2>&1 || { tail -5 "$LOGS/contours.log" >&2; die "contours failed"; }
    mv -f "$WORK/tmp/contours.pmtiles" "$DATA/contours.pmtiles"
  fi
  done_step "$(size "$DATA/contours.pmtiles")"
fi

# ------------------------------------------------------------------------------ 8. sprite + styles
step "styles (all themes) and sprite"
if [ "$SPRITE" = 1 ]; then
  python3 "$TOOLS/make_sprite.py" | sed 's/^/    /'
  log "    sprite regenerated in app/src/main/assets/sprites (part of the APK – commit and rebuild the app)"
else
  # file dates are meaningless after a git clone: compare the last commits instead
  ct() { git -C "$REPO" log -1 --format=%ct -- "$@" 2>/dev/null || echo 0; }
  if [ -z "$(git -C "$REPO" status --porcelain -- tools/icons tools/make_sprite.py 2>/dev/null)" ] \
     && [ "$(ct tools/icons tools/make_sprite.py)" -le "$(ct app/src/main/assets/sprites)" ]; then
    log "    sprite is up to date with tools/icons"
  else
    log "    WARNING: icons / make_sprite.py changed after the sprite – run with --sprite (APK asset)"
  fi
fi
# Build the styles from a copy of tools/, so the repo's bundled assets are not touched by a data build.
SB="$WORK/tmp/stylebuild"
rm -rf "$SB"; mkdir -p "$SB/tools"
cp "$TOOLS"/make_style.py "$TOOLS"/style_*.py "$SB/tools/"; cp -r "$TOOLS/themes" "$SB/tools/"
python3 "$SB/tools/make_style.py" --all | sed "s#$SB/tools/../##; s/^/    /"
rm -rf "$OUT/styles"; mkdir -p "$OUT/styles"
# the styles for this package's format (format 2: style/f2/, metadata onpath:format 2 – the app skips an override of the other format)
STYLE_SUB=$([ "$FORMAT" = 2 ] && echo /f2 || true)
cp "$SB"/app/src/main/assets/style$STYLE_SUB/onpath*.json "$OUT/styles/"
rm -rf "$DATA/styles"
if [ "$WITH_STYLES" = 1 ]; then
  cp -r "$OUT/styles" "$DATA/styles"
  done_step "$(ls "$OUT/styles" | tr '\n' ' ')– included in the package (--with-styles)"
else
  done_step "$(ls "$OUT/styles" | tr '\n' ' ')– in $OUT/styles, not in the package (use --with-styles)"
fi

# ---------------------------------------------------------------------------- 9. routing segments
step "BRouter routing tiles (segments/*.rd5)"
# 5x5 degree tiles, named after the south-west corner (same rule as SegmentDownloader.tileName)
SEGS=$(python3 - "${BBOX_ARGS[@]}" <<'PY'
import math, sys
x0, y0, x1, y1 = map(float, sys.argv[1:5])
def nm(lo, la):
    return f"{'W' if lo < 0 else 'E'}{abs(lo)}_{'S' if la < 0 else 'N'}{abs(la)}.rd5"
print(" ".join(nm(lo, la) for lo in range(int(math.floor(x0 / 5) * 5), int(math.floor(x1 / 5) * 5) + 1, 5)
                         for la in range(int(math.floor(y0 / 5) * 5), int(math.floor(y1 / 5) * 5) + 1, 5)))
PY
)
rm -rf "$DATA/segments"
if [ "$NO_SEGMENTS" = 1 ]; then
  done_step "left out (--no-segments); needed: $SEGS (phone: Settings → download routing data)"
else
  mkdir -p "$DATA/segments"
  for s in $SEGS; do
    fetch "$BROUTER_URL/$s" "$WORK/segments/$s" "$SEGMENTS_MAX_AGE_DAYS"
    ln -f "$(readlink -f "$WORK/segments/$s")" "$DATA/segments/$s" 2>/dev/null || cp -L "$WORK/segments/$s" "$DATA/segments/$s"
  done
  done_step "$SEGS ($(size "$DATA/segments"))"
fi

# ------------------------------------------------------------------------------- 10. zip + manifest
step "package onpath-data-$REGION.zip + manifest.json"
echo "$STAMP_VAL" > "$STAMP"
ZIP="$OUT/onpath-data-$REGION.zip"
GIT_REV=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)
[ -n "$(git -C "$REPO" status --porcelain -- tools 2>/dev/null)" ] && GIT_REV="$GIT_REV+dirty"
python3 - "$OUT" "$DATA" "$ZIP" "$REGION" "$BBOX" "${OSM_URL:-}" "$OSM" "$OSM_DATE" "${PT_VER:-unknown}" \
  "$GIT_REV" "$TERRAIN_MAXZOOM" "$FORMAT" <<'PY'
import hashlib, json, os, sys, time, zipfile
out, data, zpath, region, bbox, osm_url, osm, osm_date, pt_ver, git_rev, tz, fmt = sys.argv[1:]
fmt = int(fmt)
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()
files = []
for root, _, names in os.walk(data):
    for n in sorted(names):
        if n.startswith("."):
            continue
        p = os.path.join(root, n)
        files.append(os.path.relpath(p, data))
files.sort(key=lambda r: (r.count("/"), r))
# package.json goes onto the phone with the data: the app compares each file's size with it and says which files
# come from another package (mixed imports leave gaps at the old package's edge)
built = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
pkg = {"format": fmt, "region": region, "bbox": [float(v) for v in bbox.split(",")], "built": built,
       "osm_date": osm_date,
       "files": {r: os.path.getsize(os.path.join(data, r)) for r in files if not r.startswith("segments/")}}
with open(os.path.join(data, "package.json"), "w") as f:
    json.dump(pkg, f, indent=1)
files = [r for r in files if r != "package.json"] + ["package.json"]
tmp = zpath + ".tmp"
with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
    for rel in files:
        # pmtiles / rd5 are already compressed: store them (faster, and the phone unpacks quicker)
        comp = zipfile.ZIP_STORED if rel.endswith((".pmtiles", ".rd5")) else zipfile.ZIP_DEFLATED
        z.write(os.path.join(data, rel), "onpath-data/" + rel, compress_type=comp, compresslevel=6)
os.replace(tmp, zpath)
req = {"base.pmtiles", "terrain.pmtiles"} | ({"overlays.pmtiles"} if fmt == 2 else {"routes.geojson", "trails.geojson"})
manifest = {
    "format": fmt,
    "region": region,
    "bbox": [float(v) for v in bbox.split(",")],
    "built": built,
    "osm": {"url": osm_url or None, "file": os.path.basename(osm), "date": osm_date},
    "tools": {"planetiler": pt_ver, "onpath_git": git_rev, "terrain_maxzoom": int(tz)},
    "attribution": "© OpenStreetMap contributors (ODbL), © OpenMapTiles; terrain: Mapzen/AWS Terrarium "
                   "(SRTM, Copernicus, others); routing data: brouter.de (OSM, ODbL)",
    "files": [{"path": "onpath-data/" + r, "bytes": os.path.getsize(os.path.join(data, r)),
               "sha256": sha(os.path.join(data, r)),
               "required": r in req or r.startswith("segments/")} for r in files],
    "zip": {"path": os.path.basename(zpath), "bytes": os.path.getsize(zpath), "sha256": sha(zpath)},
}
with open(os.path.join(out, "manifest.json"), "w") as f:
    json.dump(manifest, f, indent=1, ensure_ascii=False)
with open(os.path.join(out, "SHA256SUMS"), "w") as f:
    for e in manifest["files"]:
        f.write(f"{e['sha256']}  {e['path']}\n")
    f.write(f"{manifest['zip']['sha256']}  {manifest['zip']['path']}\n")
PY
done_step "$(size "$ZIP")"

log "OK: $REGION built in $(( ($(date +%s) - T0) / 60 ))m$(( ($(date +%s) - T0) % 60 ))s"
log "    package : $ZIP ($(size "$ZIP"))"
log "    manifest: $OUT/manifest.json   checksums: $OUT/SHA256SUMS"
log "    phone   : adb push $DATA/. /sdcard/Android/data/com.onpath.app/files/onpath/"
log "              or copy the zip to the phone and use Settings → Import map data"
