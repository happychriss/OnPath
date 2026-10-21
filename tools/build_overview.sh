#!/usr/bin/env bash
# Build the overview map shipped in the APK (docs/design/map-cells.md §7, docs/map-pipeline.md §9b):
#   overview/world.pmtiles          Natural Earth world z0-5 (coast, water, borders, country / city names), with a
#                                   hole at z5 where base.pmtiles has data; the app over-zooms it up to z9
#   overview/base.pmtiles           Planetiler OpenMapTiles z5-$BASE_MAXZOOM of the Alps (roads, places, water,
#                                   borders, land cover), cut to the area: bbox AND the OSM extract's polygon
#   overview/terrain-world.pmtiles  Terrarium hill shade of the world z0-4, sea floor flat, heights rounded to 16 m
#   overview/terrain.pmtiles        Terrarium hill shade z5-$TERRAIN_MAXZOOM of the bbox, rounded to $TERRAIN_ROUND m
# The app draws them below zoom 10 (map/Overview.kt); cells / region packages draw from zoom 10.
#
#   tools/build_overview.sh [OUT_DIR]          default ./overview-assets  ->  OUT_DIR/overview/*.pmtiles
#   then build the app with  ./gradlew assembleDebug -Ponpath.overviewAssets=OUT_DIR
#
# Needs Java 21 and python3 with mapbox-vector-tile, shapely, pmtiles, osmium, numpy, pillow (the pipeline venv).
# Env:
#   BBOX=5.0,43.5,17.0,48.5          the Alps: Nice/Genoa to Vienna/Ljubljana, Lyon/Geneva to Munich/Salzburg
#   OSM_URL=https://download.geofabrik.de/europe/alps-latest.osm.pbf   (2.3 GB; covers the bbox)
#   OSM_FILE=/path/x.osm.pbf         use this extract instead of downloading
#   POLY_URL=<OSM_URL without -latest.osm.pbf>.poly   the extract's boundary (Geofabrik publishes it); outside it
#                                    the extract has no OSM data, so the world file shows there instead
#   POLY_FILE=/path/x.poly           use this polygon; POLY_FILE=none: bbox only (only for an extract that fills it)
#   WORK=./work                      caches: OSM extract, planetiler jar + sources, AWS terrain tiles
#   PLANETILER_JAR, PLANETILER_SOURCES, PLANETILER_VERSION=0.10.2, JAVA_XMX=8g
#   BASE_MAXZOOM=8  TERRAIN_MAXZOOM=9  TERRAIN_ROUND=4  WORLD_MAXZOOM=5  (sizes: docs/map-pipeline.md §9b)
set -euo pipefail

TOOLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=$(mkdir -p "${1:-./overview-assets}/overview" && cd "${1:-./overview-assets}" && pwd)
WORK=$(mkdir -p "${WORK:-./work}" && cd "${WORK:-./work}" && pwd)
BBOX=${BBOX:-5.0,43.5,17.0,48.5}
OSM_URL=${OSM_URL:-https://download.geofabrik.de/europe/alps-latest.osm.pbf}
POLY_URL=${POLY_URL:-${OSM_URL%-latest.osm.pbf}.poly}
PLANETILER_VERSION=${PLANETILER_VERSION:-0.10.2}
PLANETILER_JAR=${PLANETILER_JAR:-$WORK/planetiler-$PLANETILER_VERSION.jar}
PLANETILER_SOURCES=${PLANETILER_SOURCES:-$WORK/planetiler-sources}
JAVA_XMX=${JAVA_XMX:-8g}
BASE_MAXZOOM=${BASE_MAXZOOM:-8}
TERRAIN_MAXZOOM=${TERRAIN_MAXZOOM:-9}
TERRAIN_ROUND=${TERRAIN_ROUND:-4}
WORLD_MAXZOOM=${WORLD_MAXZOOM:-5}
IFS=, read -r MINLON MINLAT MAXLON MAXLAT <<<"$BBOX"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
mkdir -p "$WORK/osm" "$WORK/tmp" "$PLANETILER_SOURCES"
# OpenMapTiles layers the style never reads (docs/design/map-data-size.md §3); German names are what the style shows
EXCLUDE=housenumber,poi,aeroway,aerodrome_label,building

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
if [ "${POLY_FILE:-}" = none ]; then
  POLY_ARGS=()
else
  if [ -z "${POLY_FILE:-}" ]; then
    POLY_FILE="$WORK/osm/$(basename "$POLY_URL")"
    [ -s "$POLY_FILE" ] || curl -fL --retry 5 -o "$POLY_FILE" "$POLY_URL"
  fi
  POLY_ARGS=(--poly "$POLY_FILE")
fi
if [ ! -s "$PLANETILER_JAR" ]; then
  curl -fL -o "$PLANETILER_JAR.part" \
    "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar"
  mv -f "$PLANETILER_JAR.part" "$PLANETILER_JAR"
fi

log "Alps base z0-$BASE_MAXZOOM from $(basename "$OSM")"
( cd "$WORK/tmp" && java -Xmx"$JAVA_XMX" -jar "$PLANETILER_JAR" --osm-path="$OSM" \
    --output="$WORK/tmp/overview-alps.pmtiles" --download --download_dir="$PLANETILER_SOURCES" \
    --tmpdir="$WORK/tmp/planetiler-overview" --bounds="$BBOX" --maxzoom="$BASE_MAXZOOM" \
    --exclude_layers="$EXCLUDE" --languages=de --force )
rm -rf "$WORK/tmp/planetiler-overview"
log "Alps base: z$WORLD_MAXZOOM-$BASE_MAXZOOM, cut to the area"
python3 "$TOOLS/overview_merge.py" clip "$WORK/tmp/overview-alps.pmtiles" "$OUT/overview/base.pmtiles" \
  --bbox "$BBOX" "${POLY_ARGS[@]}" --minzoom "$WORLD_MAXZOOM"

log "world z0-$WORLD_MAXZOOM (Natural Earth + water polygons: Planetiler on an empty OSM file)"
python3 -c 'import osmium, sys; osmium.SimpleWriter(sys.argv[1]).close()' "$WORK/tmp/empty.osm.pbf"
( cd "$WORK/tmp" && java -Xmx"$JAVA_XMX" -jar "$PLANETILER_JAR" --osm-path="$WORK/tmp/empty.osm.pbf" \
    --output="$WORK/tmp/overview-world.pmtiles" --download --download_dir="$PLANETILER_SOURCES" \
    --tmpdir="$WORK/tmp/planetiler-world" --bounds=world --maxzoom="$WORLD_MAXZOOM" \
    --exclude_layers="$EXCLUDE" --languages=de --force )
rm -rf "$WORK/tmp/planetiler-world"
NE="$WORK/tmp/natural_earth_vector.sqlite"
[ -s "$NE" ] || unzip -o -j -q "$PLANETILER_SOURCES/natural_earth_vector.sqlite.zip" '*natural_earth_vector.sqlite' -d "$WORK/tmp"
python3 "$TOOLS/overview_merge.py" world "$WORK/tmp/overview-world.pmtiles" "$NE" "$OUT/overview/world.pmtiles" \
  --bbox "$BBOX" "${POLY_ARGS[@]}"

log "terrain world z0-4 (WebP lossless, 16 m, sea floor flat)"
python3 "$TOOLS/build_terrain.py" "$OUT/overview/terrain-world.pmtiles" -180 -85 180 85 \
  --minzoom 0 --maxzoom 4 --webp --round 16 --floor 0 --cache "$WORK/terrarium"
log "terrain z5-$TERRAIN_MAXZOOM (WebP lossless, $TERRAIN_ROUND m)"
python3 "$TOOLS/build_terrain.py" "$OUT/overview/terrain.pmtiles" "$MINLON" "$MINLAT" "$MAXLON" "$MAXLAT" \
  --minzoom 5 --maxzoom "$TERRAIN_MAXZOOM" --webp --round "$TERRAIN_ROUND" --cache "$WORK/terrarium"

du -h "$OUT"/overview/*.pmtiles
log "OK – build the app with -Ponpath.overviewAssets=$OUT"
