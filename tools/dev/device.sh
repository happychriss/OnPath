#!/usr/bin/env bash
# One agent per device (owner 2026-11-01: a session may split work over 3–4 sub-agents on the same computer). The Pixel
# on USB and the emulator each get a machine-wide lock, like Gradle's (`gate.sh`, ~/onpath-cells-work/gradle.lock):
#   tools/dev/device.sh pixel    [--try] [--max SEC] -- <command…>   run <command> while holding the Pixel
#   tools/dev/device.sh emulator [--try] [--max SEC] -- <command…>   … the emulator (start, connectedAndroidTest, Rundgang)
#   tools/dev/device.sh status                                        who holds what, since when
# Default: wait for the device (a line says who has it). --try: busy → exit 75 at once, so the agent does other work
# first. --max SEC: give up after SEC seconds (exit 75). Exit: the command's, 75 = device busy, 3 = refused, 2 = usage.
# Pixel extra (docs/owner-preferences.md "The owner's phone"): an install command (adb install / installDebug) is refused
# while OnPath records a tour on the phone (RecordingService running) – exit 3.
# Env: ONPATH_AGENT = a short name for the status line (default: folder + branch), WORK (default ~/onpath-cells-work).
set -uo pipefail
dev=${1:-}; shift || true
WORK=${WORK:-$HOME/onpath-cells-work}
usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; }
who() { echo "${ONPATH_AGENT:-$(basename "$PWD")@$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo -)} (pid $$)"; }

if [ "$dev" = status ]; then
  for d in pixel emulator gradle; do
    f=$WORK/$d.lock
    if [ -f "$f" ] && command -v flock >/dev/null && ! flock -n "$f" true 2>/dev/null; then
      echo "$d: busy – $(cat "$f.info" 2>/dev/null || echo "held (no info)")"
    else echo "$d: free"; fi
  done
  exit 0
fi
case "$dev" in pixel|emulator) ;; -h|--help|"") usage; exit 0 ;; *) echo "device: pixel | emulator | status" >&2; exit 2 ;; esac

TRY=0 MAX=0
while [ $# -gt 0 ]; do
  case "$1" in
    --try) TRY=1 ;;
    --max) MAX=${2:?}; shift ;;
    --) shift; break ;;
    *) break ;;
  esac
  shift
done
[ $# -gt 0 ] || { echo "device: no command after --" >&2; exit 2; }

if [ "$dev" = pixel ] && [[ " $* " =~ (adb[^|]*[[:space:]]install|installDebug|installRelease) ]]; then
  if command -v adb >/dev/null; then
    if adb -d shell dumpsys activity services com.onpath.app 2>/dev/null | grep -q RecordingService; then
      echo "device: refused – a tour is recording on the Pixel (RecordingService). No install now (owner rule)."; exit 3
    fi
  else echo "device: ⚠ adb not found – could not check for a running tour"; fi
fi

mkdir -p "$WORK" 2>/dev/null
LOCK=$WORK/$dev.lock
if ! command -v flock >/dev/null; then echo "device: ⚠ flock missing – running without the $dev lock"; exec "$@"; fi
# an adb server started by the command would inherit fd 8 and keep the lock after we end: start it before
command -v adb >/dev/null && { adb start-server >/dev/null 2>&1 || true; } # expected: adb missing → the command fails itself
exec 8>>"$LOCK"
if ! flock -n 8; then
  holder=$(cat "$LOCK.info" 2>/dev/null || echo "another agent")
  if [ "$TRY" = 1 ]; then echo "device: $dev busy – $holder"; exit 75; fi
  echo "device: waiting for the $dev – $holder"
  if [ "$MAX" -gt 0 ]; then flock -w "$MAX" 8 || { echo "device: $dev still busy after ${MAX}s – giving up"; exit 75; }
  else flock 8; fi
fi
echo "$(who) since $(date '+%H:%M:%S'): $*" > "$LOCK.info"
# the command may take the same lock itself (release_rundgang.sh): tell it we hold it, else it waits for us forever
export ONPATH_DEVICE_HELD="$dev"
"$@"; rc=$?
: > "$LOCK.info"
exit $rc
