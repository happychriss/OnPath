#!/usr/bin/env bash
# Crash hunt on the emulator (night job 2026-11-12): one `monkey` run against the installed debug app, then everything
# a crash leaves behind – the crash log buffer, FATAL / ANR lines, dropbox entries since the run began and the app's own
# problem log (WARN / ERROR). Never a phone: only emulator-* serials are taken.
#   tools/dev/crash_hunt.sh <seed> <events> [more monkey args…]      e.g. 1003 5000 --pct-rotation 8 --pct-appswitch 8
# Setup before the first run (see app/src/androidTest/…/e2e/CrashHuntSetup.kt): build + install app and test APK with
# -Ponpath.e2e=true, `pm clear`, run the CrashHuntSetup class (tiny map package, real routing when E10_N45.rd5 is in
# /data/local/tmp, a saved plan, developer tools unlocked), then start the app, Developer tools → Demo ride, Let's go.
# Env: SERIAL=<adb serial> (default: the running emulator), OUT=<dir> (default ~/onpath-cells-work/hunt/runs/seed-<seed>).
set -uo pipefail
seed=${1:?seed}; events=${2:?events}; shift 2
ADB=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
SERIAL=${SERIAL:-$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}')}
case "$SERIAL" in emulator-*) ;; *) echo "crash_hunt: no emulator (serial '$SERIAL') – never a phone"; exit 2 ;; esac
A=("$ADB" -s "$SERIAL")
OUT=${OUT:-$HOME/onpath-cells-work/hunt/runs/seed-$seed}; mkdir -p "$OUT"
"${A[@]}" logcat -c; "${A[@]}" logcat -b crash -c
start=$(date +%s)
devstart=$(date -d @"$("${A[@]}" shell date +%s | tr -d '\r')" '+%Y-%m-%d %H:%M:%S')
echo "== seed $seed, $events events, extra: $* (device time $devstart)"
"${A[@]}" shell monkey -p com.onpath.app --pct-syskeys 0 --throttle 150 --ignore-crashes --ignore-timeouts \
  --ignore-security-exceptions -s "$seed" -v "$@" "$events" > "$OUT/monkey.txt" 2>&1
rc=$?
echo "monkey rc=$rc, $(( $(date +%s) - start ))s"
grep -E "CRASH|ANR|NOT RESPONDING|Monkey finished|Events injected|System appears|crashed" "$OUT/monkey.txt" | head -20
"${A[@]}" logcat -b crash -d > "$OUT/crash.txt" 2>&1
"${A[@]}" logcat -d > "$OUT/logcat.txt" 2>&1
echo "-- crash buffer: $(grep -c "FATAL EXCEPTION" "$OUT/crash.txt") fatal"
grep -E "FATAL|ANR in|AndroidRuntime: *at" "$OUT/logcat.txt" | sort | uniq -c | sort -rn | head -10
"${A[@]}" shell dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash > "$OUT/dropbox.txt" 2>&1
echo "-- dropbox entries since the start:"
grep -E "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+ data_app" "$OUT/dropbox.txt" | awk -v s="$devstart" '$1" "$2 >= s' | head
"${A[@]}" shell run-as com.onpath.app cat files/problems.json > "$OUT/problems.json" 2>/dev/null
python3 - "$OUT/problems.json" <<'PY'
import json, sys
try: items = json.load(open(sys.argv[1]))["problems"]
except Exception as e: print("problems.json:", e); sys.exit()
bad = [p for p in items if str(p.get("level", "")).upper() in ("ERROR", "WARN")]
print("-- problems.json: %d entries, %d WARN/ERROR" % (len(items), len(bad)))
for p in bad[:30]: print("  ", p.get("level"), p.get("area"), "|", str(p.get("message"))[:160], "| x%s" % p.get("count", 1))
PY
echo "-- app alive: $("${A[@]}" shell pidof com.onpath.app)  recording: $("${A[@]}" shell dumpsys activity services com.onpath.app 2>/dev/null | grep -c RecordingService)"
