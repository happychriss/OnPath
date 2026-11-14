#!/usr/bin/env bash
# Tour suggestions, remote service: fetch routing-data tiles (BRouter rd5, 5°×5°) the computer at home does not have yet.
#   fetch_rd5.sh E15_N45 E15_N50 …        resumable, one after the other, low priority; never overwrites a complete file
# Tiles are named by their south-west corner: E5_N50 = lon 5…10, lat 50…55. Source: brouter.de (as the app downloads them).
set -u
SEG=${ONPATH_SEGMENTS:-$HOME/onpath-routing/segments}
mkdir -p "$SEG"
for t in "$@"; do
  f="$SEG/$t.rd5"
  if [ -s "$f" ]; then echo "da     $t $(stat -c %s "$f")"; continue; fi
  if nice -n 19 curl -fL -sS -C - --retry 3 --max-time 3000 -o "$f.part" "https://brouter.de/brouter/segments4/$t.rd5"; then
    mv "$f.part" "$f"; echo "geholt $t $(stat -c %s "$f")"
  else
    echo "FEHLER $t (unvollständig: $f.part bleibt liegen, ein neuer Lauf setzt fort)"
  fi
done
