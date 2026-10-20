#!/usr/bin/env bash
# Build the overview map shipped in the APK (docs/design/map-cells.md §7, docs/map-pipeline.md "Overview"):
#   overview/base.pmtiles     Planetiler OpenMapTiles z0-$BASE_MAXZOOM (roads, places, water, borders, land cover)
#   overview/terrain.pmtiles  Terrarium hill shade z5-$TERRAIN_MAXZOOM, WebP lossless, heights rounded to $TERRAIN_ROUND m
# The app draws them below zoom 10 (map/Overview.kt); cells / region packages draw from zoom 10.
#
#   tools/build_overview.sh [OUT_DIR]          default ./overview-assets  ->  OUT_DIR/overview/*.pmtiles
#   then build the app with  ./gradlew assembleDebug -Ponpath.overviewAssets=OUT_DIR
#
# Env:
#   BBOX=5.0,43.5,17.0,48.5          the Alps: Nice/Genoa to Vienna/Ljubljana, Lyon/Geneva to Munich/Salzburg
#   OSM_URL=https://download.geofabrik.de/europe/alps-latest.osm.pbf   (2.3 GB; covers the bbox)
#   OSM_FILE=/path/x.osm.pbf         use this extract instead of downloading
#   WORK=./work                      caches: OSM extract, planetiler jar + sources, AWS terrain tiles
#   PLANETILER_JAR, PLANETILER_SOURCES, PLANETILER_VERSION=0.10.2, JAVA_XMX=8g
#   BASE_MAXZOOM=8  TERRAIN_MAXZOOM=9  TERRAIN_ROUND=4   (see docs/map-pipeline.md for the measured sizes)
set -euo pipefail

TOOLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=$(mkdir -p "${1:-./overview-assets}/overview" && cd "${1:-./overview-assets}" && pwd)
WORK=$(mkdir -p "${WORK:-./work}" && cd "${WORK:-./work}" && pwd)
BBOX=${BBOX:-5.0,43.5,17.0,48.5}
OSM_URL=${OSM_URL:-https://download.geofabrik.de/europe/alps-latest.osm.pbf}
PLANETILER_VERSION=${PLANETILER_VERSION:-0.10.2}
PLANETILER_JAR=${PLANETILER_JAR:-$WORK/planetiler-$PLANETILER_VERSION.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
JAVA_XMX=${JAVA_XMX:-8g}
BASE_MAXZOOM=${BASE_MAXZOOM:-8}
TERRAIN_MAXZOOM=${TERRAIN_MAXZOOM:-9}
TERRAIN_ROUND=${TERRAIN_ROUND:-4}
IFS=, read -r MINLON MINLAT MAXLON MAXLAT <<<"$BBOX"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
mkdir -p "$WORK/osm" "$WORK/tmp" "$PLANETILER_SOURCES"

if [ -n "${OSM_FILE:-}" ]; then
  OSM=$OSM_FILE
else
  OSM="$WORK/osm/$(basename "$OSM_URL")"
  if [ ! -s "$OSM" ]; then
    log "downloading $OSM_URL"
    curl -fL --retry 5 --retry-delay 10 -C - -o "$OSM.part" "$OSM_URL"
    mv -f "$OSM.part" "$OSM"
  fi
fi
if [ ! -s "$PLANETILER_JAR" ]; then
  curl -fL -o "$PLANETILER_JAR.part" \
    "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar"
  mv -f "$PLANETILER_JAR.part" "$PLANETILER_JAR"
fi

log "base z0-$BASE_MAXZOOM from $(basename "$OSM")"
# Layers the style never reads are left out (docs/design/map-data-size.md §3); German names are what the style shows.
( cd "$WORK/tmp" && java -Xmx"$JAVA_XMX" -jar "$PLANETILER_JAR" --osm-path="$OSM" \
    --output="$WORK/tmp/overview-base.pmtiles" --download --download_dir="$PLANETILER_SOURCES" \
    --tmpdir="$WORK/tmp/planetiler-overview" --bounds="$BBOX" --maxzoom="$BASE_MAXZOOM" \
    --exclude_layers=housenumber,poi,aeroway,aerodrome_label,building --languages=de --force )
mv -f "$WORK/tmp/overview-base.pmtiles" "$OUT/overview/base.pmtiles"
rm -rf "$WORK/tmp/planetiler-overview"

log "terrain z5-$TERRAIN_MAXZOOM (WebP lossless, $TERRAIN_ROUND m)"
python3 "$TOOLS/build_terrain.py" "$OUT/overview/terrain.pmtiles" "$MINLON" "$MINLAT" "$MAXLON" "$MAXLAT" \
  --minzoom 5 --maxzoom "$TERRAIN_MAXZOOM" --webp --round "$TERRAIN_ROUND" --cache "$WORK/terrarium"

du -h "$OUT"/overview/*.pmtiles
log "OK – build the app with -Ponpath.overviewAssets=$OUT"
