#!/usr/bin/env bash
# Build the world overview map shipped in the APK (docs/design/map-cells.md §7, docs/map-pipeline.md §9b):
#   overview/base.pmtiles     world z0-$BASE_MAXZOOM, OpenMapTiles schema (like the cells, one style): coast, oceans,
#                             big lakes and rivers, ice, borders, country and city names – Natural Earth + water
#                             polygons only (Planetiler on an EMPTY OSM file), places added by overview_merge.py
#   overview/terrain.pmtiles  Terrarium hill shade of the world z0-$TERRAIN_MAXZOOM, WebP lossless, sea floor flat,
#                             heights rounded to $TERRAIN_ROUND m
# The app draws them below zoom 10 (map/Overview.kt), over-zoomed past their last level; cells draw from zoom 10.
# No OSM download: a world OSM run (planet, ~80 GB) is far beyond the APK budget anyway (§9b).
#
#   tools/build_overview.sh [OUT_DIR]          default ./overview-assets  ->  OUT_DIR/overview/*.pmtiles
#   then build the app with  ./gradlew assembleDebug -Ponpath.overviewAssets=OUT_DIR
#   release builds: upload OUT_DIR/overview/{base,terrain}.pmtiles + OUT_DIR/SHA256SUMS to a new R2 folder overview/<date>/
#   and point the repository variable ONPATH_OVERVIEW_URL at it (.github/workflows/release.yml downloads them)
#
# Needs Java 21 and python3 with mapbox-vector-tile, shapely, pmtiles, osmium, numpy, pillow (the pipeline venv).
# Env:
#   WORK=./work                      caches: planetiler jar + sources (Natural Earth, water polygons ~1 GB), AWS tiles
#   PLANETILER_JAR, PLANETILER_SOURCES, PLANETILER_VERSION=0.10.2, JAVA_XMX=4g
#   BASE_MAXZOOM=5  TERRAIN_MAXZOOM=6  TERRAIN_ROUND=32   (sizes and the choice: docs/map-pipeline.md §9b)
set -euo pipefail

TOOLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=$(mkdir -p "${1:-./overview-assets}/overview" && cd "${1:-./overview-assets}" && pwd)
WORK=$(mkdir -p "${WORK:-./work}" && cd "${WORK:-./work}" && pwd)
PLANETILER_VERSION=${PLANETILER_VERSION:-0.10.2}
PLANETILER_JAR=${PLANETILER_JAR:-$WORK/planetiler-$PLANETILER_VERSION.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
JAVA_XMX=${JAVA_XMX:-4g}
BASE_MAXZOOM=${BASE_MAXZOOM:-5}
TERRAIN_MAXZOOM=${TERRAIN_MAXZOOM:-6}
TERRAIN_ROUND=${TERRAIN_ROUND:-32}
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
mkdir -p "$WORK/tmp" "$PLANETILER_SOURCES"

if [ ! -s "$PLANETILER_JAR" ]; then
  curl -fL -o "$PLANETILER_JAR.part" \
    "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar"
  mv -f "$PLANETILER_JAR.part" "$PLANETILER_JAR"
fi

log "base z0-$BASE_MAXZOOM (Planetiler on an empty OSM file: Natural Earth + water polygons)"
rm -f "$WORK/tmp/empty.osm.pbf"  # osmium does not overwrite
python3 -c 'import osmium, sys; osmium.SimpleWriter(sys.argv[1]).close()' "$WORK/tmp/empty.osm.pbf"
# layers the style never reads are left out (docs/design/map-data-size.md §3); German names are what the style shows
( cd "$WORK/tmp" && java -Xmx"$JAVA_XMX" -jar "$PLANETILER_JAR" --osm-path="$WORK/tmp/empty.osm.pbf" \
    --output="$WORK/tmp/overview-raw.pmtiles" --download --download_dir="$PLANETILER_SOURCES" \
    --tmpdir="$WORK/tmp/planetiler-overview" --bounds=world --maxzoom="$BASE_MAXZOOM" \
    --exclude_layers=housenumber,poi,aeroway,aerodrome_label,building --languages=de --force )
rm -rf "$WORK/tmp/planetiler-overview"
NE="$WORK/tmp/natural_earth_vector.sqlite"
[ -s "$NE" ] || unzip -o -j -q "$PLANETILER_SOURCES/natural_earth_vector.sqlite.zip" '*natural_earth_vector.sqlite' -d "$WORK/tmp"
log "base: places (+ z6+ borders / lakes / rivers when BASE_MAXZOOM > 5)"
python3 "$TOOLS/overview_merge.py" "$WORK/tmp/overview-raw.pmtiles" "$NE" "$OUT/overview/base.pmtiles"

log "terrain z0-$TERRAIN_MAXZOOM (WebP lossless, $TERRAIN_ROUND m, sea floor flat)"
python3 "$TOOLS/build_terrain.py" "$OUT/overview/terrain.pmtiles" -180 -85 180 85 \
  --minzoom 0 --maxzoom "$TERRAIN_MAXZOOM" --webp --round "$TERRAIN_ROUND" --floor 0 --cache "$WORK/terrarium"

( cd "$OUT/overview" && sha256sum base.pmtiles terrain.pmtiles > ../SHA256SUMS )  # outside overview/: not an APK asset
du -h "$OUT"/overview/*.pmtiles
log "OK – build the app with -Ponpath.overviewAssets=$OUT"
