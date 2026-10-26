#!/usr/bin/env bash
# Dev loop for the map pipeline (docs/design/map-hosting.md §7h): build ONE map cell on this PC from the CURRENT
# working tree (uncommitted changes included) and put it straight into OnPath on the phone – no cloud involved.
#
#   tools/dev/push_cell.sh 10-531-342          (Hilden; ANDROID_SERIAL picks the phone when several are attached)
#   tools/dev/push_cell.sh 7-66-42             a region package (z7-9 around Düsseldorf, map-cells.md §20) – same way
#   PUSH_ONLY=<dir> tools/dev/push_cell.sh 10-531-342   push an already built version folder again (no build)
#
# How it reaches the map: the cell is pushed as a complete download (cells/download/<cell>/<version>/ with
# manifest.json) – the same folder MapCells writes after a verified download. At the next start, MapCells.recover()
# joins it into a new map store generation (CellStore.apply; the previous generation stays until the map loaded the
# new one). The script force-stops OnPath, pushes into "<version>.part" (never read by the app), renames it in one
# step, and starts OnPath again. Other installed cells are not touched.
#
# Version: "<osm_date>-dev<MMDDHHMM>-s<schema>" – never the name of a server version (CellApi.isDevVersion). Revert:
#   - automatic after a day: a route through the area asks the server for a newer version of the cells along it
#     (CellTriggers.freshenAlong – also the restored plan's route at every start); for a dev cell the app ignores that
#     answer for MapCells.DEV_KEEP_MS (24 h) after it was joined, then loads the server's version instead
#   - at once: Settings → Offline maps → delete the area; it loads again from the server the next time it is needed
# Phone detail (Pixel 8, 2026-10-26): folders adb creates are owned by "shell" (mode 2770) – the app cannot list them,
# so the script opens them (777 / 666) before the app starts; the app then joins and deletes them as usual.
# Needs: docker, adb, the phone with OnPath installed (debug or release build of com.onpath.app).
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# shellcheck source=cell_lib.sh
. "$HERE/cell_lib.sh"

[ $# -eq 1 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
CELL=$(cell_norm "$1")
IFS=- read -r Z X Y <<<"$CELL"
PKG=com.onpath.app
PHONE_CELLS=/sdcard/Android/data/$PKG/files/onpath/cells
command -v adb >/dev/null || cell_die "adb is missing"
adb get-state >/dev/null 2>&1 || cell_die "no phone attached (adb get-state); set ANDROID_SERIAL when several are attached"
adb shell pm path "$PKG" >/dev/null 2>&1 || cell_die "OnPath ($PKG) is not installed on the phone"
# never stop a running tour (the app is force-stopped below)
adb shell dumpsys activity services "$PKG" 2>/dev/null | grep -q "RecordingService" \
  && cell_die "OnPath is recording a tour right now – not stopping it; try again after the tour"

if [ -n "${PUSH_ONLY:-}" ]; then
  VDIR=$PUSH_ONLY
else
  REV=$(git -C "$REPO" rev-parse --short HEAD)
  [ -n "$(git -C "$REPO" status --porcelain -- tools app/libs app/src/main/assets/brouter)" ] && REV="$REV+dirty"
  cell_build_images "$REPO" "$REV" dev
  OUT="$CELL_WORK/dev-out/$CELL-$(date +%Y%m%d%H%M%S)"
  T0=$(date +%s)
  cell_run_job dev dev "" "$OUT" "$CELL"
  cell_log "built in $(( $(date +%s) - T0 )) s (pipeline $REV)"
  CUR="$OUT/cells/f2/$Z/$X/$Y/current.json"
  [ -s "$CUR" ] || cell_die "no current.json in $OUT"
  VDIR="$OUT/cells/f2/$Z/$X/$Y/$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$CUR")"
fi
[ -s "$VDIR/manifest.json" ] || cell_die "no manifest.json in $VDIR"

# dev copy: same files, manifest with the dev version (sizes + sha256 unchanged; urls only need to be well-formed)
STAGE="$CELL_WORK/dev-push/$CELL"
rm -rf "$STAGE"; mkdir -p "$STAGE"
DEV=$(python3 - "$VDIR" "$STAGE" "$(date +%m%d%H%M)" <<'PY'
import json, os, shutil, sys
src, stage, stamp = sys.argv[1:]
m = json.load(open(os.path.join(src, "manifest.json")))
ver = f"{m['osm_date']}-dev{stamp}-s{m.get('schema', 1)}"
old = m["version"]
dst = os.path.join(stage, ver)
for f in m["files"]:
    os.makedirs(os.path.dirname(os.path.join(dst, f["name"])), exist_ok=True)
    shutil.copyfile(os.path.join(src, f["name"]), os.path.join(dst, f["name"]))
    f["url"] = f["url"].replace(f"/{old}/", f"/{ver}/")
m["version"] = ver
m["dev"] = "built on this PC by tools/dev/push_cell.sh, not a server version"
json.dump(m, open(os.path.join(dst, "manifest.json"), "w"), indent=1, ensure_ascii=False)
print(ver)
PY
)
SIZE=$(du -sh "$STAGE/$DEV" | cut -f1)
cell_log "pushing $CELL as version $DEV ($SIZE)"

DL="$PHONE_CELLS/download/$CELL"
adb shell am force-stop "$PKG"
adb shell "mkdir -p '$DL' && rm -rf '$DL/$DEV.part' '$DL/$DEV'"
adb push "$STAGE/$DEV/." "$DL/$DEV.part/" >/dev/null
# folders adb creates belong to "shell" with mode 2770: the app could not even list them (checked on the Pixel 8,
# 2026-10-26 – the cell was silently not joined). Open them for the app, which reads, renames and deletes them.
adb shell "chmod 777 '$DL' && find '$DL/$DEV.part' -type d -exec chmod 777 {} + && find '$DL/$DEV.part' -type f -exec chmod 666 {} +"
adb shell "mv '$DL/$DEV.part' '$DL/$DEV'"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
cell_log "OnPath started – it joins $CELL ($DEV) into the map at start (unlock the phone if it is locked)"
# the app deletes the download folder once the cell is joined
for _ in $(seq 1 60); do
  sleep 2
  adb shell "[ -e '$DL/$DEV' ]" 2>/dev/null || { cell_log "joined: $CELL shows version $DEV (Settings → Offline maps)"; exit 0; }
done
cell_log "WARN: $DL/$DEV still on the phone after 2 min – is OnPath on screen? Check Settings → Diagnostics"
exit 1
