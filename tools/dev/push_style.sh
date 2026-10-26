#!/usr/bin/env bash
# Put a map style on the phone as the developer test style and restart OnPath: map tweaks reach the phone in about a
# minute, without a release (docs/map-style-notes.md "Test style on the phone").
#
#   tools/dev/push_style.sh                  the classic theme of this checkout (tools/make_style.py, format 2)
#   tools/dev/push_style.sh THEME.json       a theme file, e.g. {"extends": "classic", "street.town.edge.color.z17": "#7d766d"}
#   tools/dev/push_style.sh STYLE.json       a ready MapLibre style (a JSON with "layers"), e.g. a variant from a render test
#   tools/dev/push_style.sh --remove         delete the test style (the bundled style shows after the restart)
#   tools/dev/push_style.sh --check          only ask the phone whether a push may restart OnPath now (exit 0 = yes)
#   --force (before the other arguments)     skip the tour check – only when you know nobody records
#
# The restart stops a running tour recording / navigation, so the script refuses while OnPath's RecordingService runs,
# and also when adb gives no usable answer (unknown = refuse, never "not running").
#
# The app uses <data dir>/dev/style.json (StyleLoader.DEV_STYLE) only while its developer tools are shown: debug builds,
# or after 7 taps on the version in Settings → About. Settings → Diagnostics → Developer tools shows "Test style active"
# with Remove and "Reload map style". A style MapLibre rejects is reported in the problem list and the normal style
# loads. Only adb push / rm and an app restart – nothing else on the phone changes.
# Env: ANDROID_SERIAL picks the phone when several are connected.
set -euo pipefail

PKG=com.onpath.app
DEST=/sdcard/Android/data/$PKG/files/onpath/dev/style.json
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

FORCE=0
if [[ "${1:-}" == "--force" ]]; then
  FORCE=1
  shift
fi

# exit 3 unless OnPath can be restarted now: no RecordingService (tour recording / navigation), and a real answer
not_recording() {
  if [[ $FORCE == 1 ]]; then
    echo "--force: not checking for a running tour" >&2
    return 0
  fi
  local out
  if ! out=$(adb shell dumpsys activity services "$PKG" 2>/dev/null) || [[ -z "${out//[[:space:]]/}" ]]; then
    echo "ERROR: no answer from the phone (adb) whether OnPath records a tour – not restarting it." >&2
    echo "       Check 'adb devices'; --force skips this check." >&2
    exit 3
  fi
  if grep -q "RecordingService" <<<"$out"; then
    echo "ERROR: OnPath is recording / navigating a tour right now – a restart would interrupt it. Try after the tour." >&2
    exit 3
  fi
}

restart() {
  not_recording
  adb shell am force-stop "$PKG"
  adb shell am start -n "$PKG/$PKG.MainActivity" >/dev/null
  echo "OnPath restarted"
}

if [[ "${1:-}" == "--check" ]]; then
  not_recording
  echo "OnPath is not recording: a style push may restart it"
  exit 0
fi

not_recording
if [[ "${1:-}" == "--remove" ]]; then
  adb shell rm -f "$DEST"
  echo "test style removed"
  restart
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
style="$tmp/style.json"

if [[ $# -eq 0 ]]; then
  python3 "$ROOT/tools/make_style.py" --format 2 --out "$style"
elif python3 -c 'import json, sys; sys.exit(0 if "layers" in json.load(open(sys.argv[1])) else 1)' "$1"; then
  cp "$1" "$style"
else
  python3 "$ROOT/tools/make_style.py" --theme "$(realpath "$1")" --format 2 --out "$style"
fi

# the app checks this too (and falls back), but a wrong file is quicker to see here
python3 - "$style" <<'EOF'
import json, sys
s = json.load(open(sys.argv[1]))
fmt = s.get("metadata", {}).get("onpath:format", 1)
if fmt != 2:
    sys.exit(f"{sys.argv[1]}: style for map format {fmt}; the phone's map cells are format 2")
print(f"style: {len(s['layers'])} layers")
EOF

adb shell mkdir -p "$(dirname "$DEST")"
adb push "$style" "$DEST" >/dev/null
# written by the shell user: the app must read it and may delete it (Developer tools → Remove)
adb shell chmod 0777 "$(dirname "$DEST")"
adb shell chmod 0666 "$DEST"
echo "pushed to $DEST"
restart
