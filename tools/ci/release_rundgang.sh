#!/usr/bin/env bash
# Release-Rundgang (owner 2026-10-31, docs/release-rundgang.md): the fixed list of real user flows played in ONE
# emulator with the demo ride before every release – about 5 min on donald, no human. A failed flow = no release.
#
# Usage: tools/ci/release_rundgang.sh [--no-build]
#   - uses a running emulator (adb serial emulator-*) or starts one headless (4 GB, temp on disk) and kills it after
#   - builds app + test APK with -Ponpath.e2e=true inside the machine's Gradle lock, --max-workers=2
#   - pushes the real routing data (E10_N45.rd5) to /data/local/tmp, runs only com.onpath.app.e2e.ReleaseRundgang
#   - screenshots into ~/onpath-cells-work/rundgang/<date-time>/, prints a pass / fail table; exit 1 when a flow failed
# Env: SERIAL=<adb serial> (else the running emulator), AVD=<name> (default Rundgang, created when missing),
#      RD5=<path to E10_N45.rd5> (default ~/onpath-routing/segments/E10_N45.rd5, else downloaded from brouter.de),
#      JAVA_HOME (default Android Studio's JBR). Never runs on a real phone: only emulator-* serials are taken.
#      FLOWS=<class or class#method,…> runs only these (debugging one flow).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${WORK:-$HOME/onpath-cells-work}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
ADB="$SDK/platform-tools/adb"
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr}
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$WORK/avd}
export ANDROID_TMP=${ANDROID_TMP:-$WORK/emu-tmp}
export TMPDIR=$ANDROID_TMP
AVD=${AVD:-Rundgang}
SUITE=${FLOWS:-com.onpath.app.e2e.ReleaseRundgang}
OUT="$WORK/rundgang/$(date +%Y-%m-%d_%H%M)"
mkdir -p "$OUT" "$ANDROID_AVD_HOME" "$ANDROID_TMP"
BUILD=1; [ "${1:-}" = "--no-build" ] && BUILD=0
T0=$(date +%s)

# ---- the emulator: a running one, else our own (exactly one on the machine) ----
STARTED=""
SERIAL=${SERIAL:-$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}')}
if [ -z "$SERIAL" ]; then
  if [ ! -d "$ANDROID_AVD_HOME/$AVD.avd" ]; then
    IMG="system-images;android-35;google_apis_playstore;x86_64"
    echo "Creating AVD $AVD ($IMG)"
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMG" -d pixel_6 >/dev/null
  fi
  PORT=5590
  SERIAL="emulator-$PORT"
  echo "Starting emulator $AVD ($SERIAL, headless, 4 GB)"
  "$SDK/emulator/emulator" -avd "$AVD" -port $PORT -memory 4096 -no-window -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect -no-audio -camera-back none > "$OUT/emulator.log" 2>&1 &
  STARTED=$SERIAL
  trap '"$ADB" -s "$STARTED" emu kill >/dev/null 2>&1 || true' EXIT
fi
case "$SERIAL" in emulator-*) ;; *) echo "Not an emulator: $SERIAL – the Rundgang never runs on a phone"; exit 2 ;; esac
A=("$ADB" -s "$SERIAL")

# ---- build (meanwhile the emulator boots) ----
if [ $BUILD = 1 ]; then
  echo "Building (Gradle lock, --max-workers=2)…"
  (cd "$ROOT" && flock "$WORK/gradle.lock" nice -n 19 ionice -c3 ./gradlew --max-workers=2 -Ponpath.e2e=true -q \
    assembleDebug assembleDebugAndroidTest) > "$OUT/build.log" 2>&1 || { tail -30 "$OUT/build.log"; exit 3; }
fi

echo "Waiting for $SERIAL to boot…"
"${A[@]}" wait-for-device
for _ in $(seq 1 180); do [ "$("${A[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
"${A[@]}" shell settings put global window_animation_scale 0 >/dev/null
"${A[@]}" shell settings put global transition_animation_scale 0 >/dev/null
"${A[@]}" shell settings put global animator_duration_scale 0 >/dev/null

# ---- APKs + routing data ----
"${A[@]}" install -r -t "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"${A[@]}" install -r -t "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
RD5=${RD5:-$HOME/onpath-routing/segments/E10_N45.rd5}
if [ "$("${A[@]}" shell ls -l /data/local/tmp/E10_N45.rd5 2>/dev/null | awk '{print $5}')" != "$(stat -c %s "$RD5" 2>/dev/null || echo x)" ]; then
  if [ ! -f "$RD5" ]; then RD5="$WORK/rundgang/E10_N45.rd5"; [ -f "$RD5" ] || curl -fsSL --retry 3 -o "$RD5" https://brouter.de/brouter/segments4/E10_N45.rd5; fi
  echo "Pushing routing data $(du -h "$RD5" | cut -f1)"
  "${A[@]}" push "$RD5" /data/local/tmp/E10_N45.rd5 >/dev/null && "${A[@]}" shell chmod 644 /data/local/tmp/E10_N45.rd5
fi

# ---- the flows ----
"${A[@]}" shell rm -rf /sdcard/e2e
"${A[@]}" shell mkdir -p /sdcard/e2e
echo "Running $SUITE…"
T1=$(date +%s)
"${A[@]}" shell am instrument -w -r -e class "$SUITE" com.onpath.app.test/androidx.test.runner.AndroidJUnitRunner \
  > "$OUT/instrument.txt" 2>&1 || true
T2=$(date +%s)
"${A[@]}" pull /sdcard/e2e "$OUT/" >/dev/null 2>&1 || true
"${A[@]}" logcat -d > "$OUT/logcat.txt" 2>/dev/null || true

# ---- pass / fail table ----
RC=0
python3 - "$OUT/instrument.txt" "$((T2 - T1))" "$((T2 - T0))" <<'PY' || RC=$?
import re, sys, time
lines = open(sys.argv[1], errors="replace").read().splitlines()
rows, cur, start = [], {}, {}
for l in lines:
    m = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", l)
    if m: cur[m.group(1)] = m.group(2); continue
    m = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", l)
    if m:
        code, t = int(m.group(1)), cur.get("test", "?")
        if code == 1: start[t] = time.time()
        else:
            stack = cur.get("stack", "")
            why = stack.splitlines()[0][:110] if stack else ""
            rows.append((t, {0: "PASS", -2: "FAIL", -1: "ERROR", -3: "IGNORED", -4: "SKIPPED"}.get(code, str(code)), why))
        if code != 1: cur = {}
        else: cur = {k: v for k, v in cur.items() if k in ("class", "test")}
crash = [l for l in lines if l.startswith("INSTRUMENTATION_RESULT: shortMsg") or "Process crashed" in l]
print("\nRelease-Rundgang")
print("%-52s %s" % ("flow", "result"))
for t, r, why in rows: print("%-52s %-8s %s" % (t, r, why))
failed = [r for r in rows if r[1] in ("FAIL", "ERROR")]
ok = rows and not failed and not crash
if crash: print("CRASH:", " ".join(crash)[:200])
print("\n%d flows: %d passed, %d failed, %d ignored · flows %d min %d s · total %d min" % (len(rows),
      sum(r[1] == "PASS" for r in rows), len(failed), sum(r[1] == "IGNORED" for r in rows),
      int(sys.argv[2]) // 60, int(sys.argv[2]) % 60, int(sys.argv[3]) // 60))
print("RESULT:", "PASS – release may go" if ok else "FAIL – no release")
sys.exit(0 if ok else 1)
PY
echo "Screenshots + log: $OUT"
exit $RC
