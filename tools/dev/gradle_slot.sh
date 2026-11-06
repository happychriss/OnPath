#!/usr/bin/env bash
# Gradle slots per computer (owner 2026-11-05: "the agents block each other although they need not").
# Three Gradle builds may run side by side (each ~6 GB with its Kotlin daemon; donald has 30 GB and 32 cores – owner
# 2026-11-06: "mehr rausholen"), but only ONE while the emulator runs (tools/dev/device.sh holds ~/onpath-cells-work/emulator.lock then). Slot 1 is the old
# ~/onpath-cells-work/gradle.lock, so scripts that still `flock gradle.lock` keep working and count as one slot.
#   tools/dev/gradle_slot.sh -- ./gradlew :app:recordPaparazziDebug --tests 'com.onpath.app.ui.XScreenshotTest'
#   source tools/dev/gradle_slot.sh; gradle_slot_take   # → fd 9 holds a slot, $GRADLE_SLOT_WAIT = seconds waited
# Env: ONPATH_GRADLE_SLOTS (default 3), WORK (default ~/onpath-cells-work). No lock dir or no flock → no slots at all
# (cloud sessions, CI). Exit: the command's.
WORK=${WORK:-$HOME/onpath-cells-work}

_gradle_slot_count() {
  local n=${ONPATH_GRADLE_SLOTS:-3}
  # the emulator is running (its lock is held) → one slot only: emulator + two builds do not fit in RAM
  if [ -f "$WORK/emulator.lock" ] && ! flock -n "$WORK/emulator.lock" true 2>/dev/null; then n=1; fi
  echo "$n"
}

# Takes a free slot on fd 9 (waits, checking every 2 s, and says once who it waits for). Sets GRADLE_SLOT (path or "")
# and GRADLE_SLOT_WAIT (seconds).
gradle_slot_take() {
  GRADLE_SLOT="" GRADLE_SLOT_WAIT=0
  [ -d "$WORK" ] && command -v flock >/dev/null || return 0
  local t0 said=0 n i f
  t0=$(date +%s)
  while :; do
    n=$(_gradle_slot_count)
    for i in $(seq 1 "$n"); do
      f="$WORK/gradle.lock"; [ "$i" -gt 1 ] && f="$WORK/gradle.lock.$i"
      exec 9>>"$f"
      if flock -n 9; then
        GRADLE_SLOT=$f GRADLE_SLOT_WAIT=$(( $(date +%s) - t0 ))
        echo "${ONPATH_AGENT:-$(basename "$PWD")} since $(date '+%H:%M:%S')" > "$f.info" 2>/dev/null
        return 0
      fi
      exec 9>&-
    done
    if [ "$said" = 0 ]; then
      echo "gradle: all $n slot(s) busy – waiting ($(cat "$WORK"/gradle.lock*.info 2>/dev/null | tr '\n' ';' | cut -c1-160))" >&2
      said=1
    fi
    sleep 2
  done
}

gradle_slot_release() { [ -n "${GRADLE_SLOT:-}" ] && { : > "$GRADLE_SLOT.info" 2>/dev/null; exec 9>&-; }; GRADLE_SLOT=""; }

# run as a command: gradle_slot.sh -- <command…>
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  [ "${1:-}" = -- ] && shift
  [ $# -gt 0 ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
  gradle_slot_take
  "$@"; rc=$?
  gradle_slot_release
  exit $rc
fi
