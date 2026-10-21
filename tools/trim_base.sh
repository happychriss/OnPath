#!/usr/bin/env bash
# Format 2 base map trim (docs/design/map-data-size.md §3): drop the base map's path / track lines (and their name
# lines) at z13-14 – overlays.pmtiles layer "paths" draws them there, and every style layer that used them reads the
# overlay in the format-2 style. z0-12 stay untouched (track-low / path-low draw the base map's lines at z12).
# Small streets (class minor / service) go too at z13-14, from "transportation" only: overlays layer "streets" draws
# them (smoothed); their names stay in "transportation_name" (road-label).
#
#   tools/trim_base.sh IN.pmtiles OUT.pmtiles
#
# Planetiler itself already leaves out the unused layers and languages in format 2:
#   --exclude_layers=housenumber,poi,aeroway,aerodrome_label --languages=de
# Env: TILE_JOIN=tile-join (tippecanoe >= 2.17; 2.79.0 used).
set -euo pipefail
[ $# -eq 2 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
TILE_JOIN=${TILE_JOIN:-tile-join}
command -v "$TILE_JOIN" >/dev/null || { echo "tile-join missing (comes with tippecanoe)" >&2; exit 1; }
DROP='["all",[">=","$zoom",13],["in","class","path","track"]]'
DROP_LINES='["all",[">=","$zoom",13],["in","class","path","track","minor","service"]]'
# The "park" layer's name points go too (all zooms): no style layer draws park names, and the only park layer is the
# dashed outline – a point reaching a line layer makes MapLibre Native log "Invalid geometry in line layer".
PARK_POINTS='["==","$type","Point"]'
rm -f "$2.tmp.pmtiles"
"$TILE_JOIN" --force --quiet --no-tile-size-limit \
  -j "{\"transportation\":[\"none\",$DROP_LINES],\"transportation_name\":[\"none\",$DROP],\"park\":[\"none\",$PARK_POINTS]}" \
  -o "$2.tmp.pmtiles" "$1"
mv -f "$2.tmp.pmtiles" "$2"
