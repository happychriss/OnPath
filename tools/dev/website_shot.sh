#!/usr/bin/env bash
# Website pictures on donald with the A-019 setup (docs/design/website-screens.md): one emulator, the app language
# per app, Android's status bar demo mode, the position by the emulator's GPS, the A-019 plan link.
#   tools/dev/website_shot.sh start            emulator OnPath_Play36 headless (port 5600), holds the emulator lock;
#                                              installs app/build/outputs/apk/debug/app-debug.apk (build it with
#                                              -Ponpath.e2e=true for the x86_64 libs) when INSTALL=1
#   tools/dev/website_shot.sh setup de|en      app language, demo status bar, GPS at the Vellau valley station
#   tools/dev/website_shot.sh plan             opens the A-019 plan link (Vellau Korblift → Leiteralm, Hike T2)
#   tools/dev/website_shot.sh gps <lon> <lat>  moves the emulator's position
#   tools/dev/website_shot.sh shot <file.png>  screenshot, unchanged PNG (adb exec-out screencap -p)
#   tools/dev/website_shot.sh stop             app language back to the system's, demo mode off, emulator off
# Drive the app between the steps with `adb -s emulator-5600 shell input …` or `uiautomator dump`. Never the Pixel.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
WORK=${WORK:-$HOME/onpath-cells-work}
SERIAL=emulator-5600
A=("$SDK/platform-tools/adb" -s "$SERIAL")
LINK='https://plan.onpath.earth/#p=AQUCAvCBugSuzocBqhD8AgMCHQBWZWxsYXUgS29yYmxpZnQgKFRhbHN0YXRpb24pAgoBTGVpdGVyYWxt'
case "${1:-}" in
  start)
    mkdir -p "$WORK/emu-tmp"
    export ANDROID_TMP=$WORK/emu-tmp TMPDIR=$WORK/emu-tmp
    ONPATH_AGENT=${ONPATH_AGENT:-website-shot} nohup "$ROOT/tools/dev/device.sh" emulator -- "$SDK/emulator/emulator" \
      -avd OnPath_Play36 -port 5600 -no-window -no-boot-anim -gpu swiftshader_indirect -no-audio -no-snapshot-save \
      -memory 4096 > "$WORK/emu-tmp/website-shot-emu.log" 2>&1 &
    for _ in $(seq 1 90); do
      [ "$("${A[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 4
    done
    "${A[@]}" shell getprop sys.boot_completed | grep -q 1 || { echo "emulator did not boot – $WORK/emu-tmp/website-shot-emu.log"; exit 1; }
    if [ "${INSTALL:-0}" = 1 ]; then "${A[@]}" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"; fi
    echo "emulator $SERIAL ready" ;;
  setup)
    lang=${2:?de|en}; loc=$([ "$lang" = de ] && echo de-DE || echo en-US)
    "${A[@]}" shell cmd locale set-app-locales com.onpath.app --locales "$loc"
    "${A[@]}" shell settings put global sysui_demo_allowed 1
    for c in "enter" "clock -e hhmm 0930" "network -e wifi show -e level 4 -e fully true -e mobile hide" \
             "battery -e level 100 -e plugged false" "notifications -e visible false"; do
      # shellcheck disable=SC2086
      "${A[@]}" shell am broadcast -a com.android.systemui.demo -e command $c > /dev/null
    done
    "${A[@]}" emu geo fix 11.1094 46.6955 > /dev/null   # Korblift Vellau, valley station
    echo "app language $loc, demo status bar, GPS at Vellau" ;;
  plan) "${A[@]}" shell am start -a android.intent.action.VIEW -d "$LINK" com.onpath.app > /dev/null; echo "plan link opened" ;;
  gps) "${A[@]}" emu geo fix "${2:?lon}" "${3:?lat}" > /dev/null ;;
  shot) "${A[@]}" exec-out screencap -p > "${2:?file.png}"; echo "${2}" ;;
  stop)
    "${A[@]}" shell cmd locale set-app-locales com.onpath.app --locales "" || true
    "${A[@]}" shell am broadcast -a com.android.systemui.demo -e command exit > /dev/null || true
    "${A[@]}" emu kill > /dev/null 2>&1 || true
    echo "emulator off" ;;
  *) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2 ;;
esac
