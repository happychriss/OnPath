#!/usr/bin/env bash
# BRouter alone on the desktop JVM and on the Pixel's own runtime (dalvikvm in adb shell) – nothing is installed into the
# app, the app's data is not touched; the bench lives in /data/local/tmp/onpath-bench and is deleted afterwards.
# Usage: tools/routing/phone-bench/run.sh <dir with E5_N50.rd5> [jvm|phone|both] [memoryclass]
# The phone part only through tools/dev/device.sh pixel (one agent per device), e.g.
#   tools/dev/device.sh pixel -- tools/routing/phone-bench/run.sh ~/onpath-cells-work/bug01/seg phone
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
SEG=${1:?dir with E5_N50.rd5}; WHAT=${2:-both}; MEM=${3:-64}
W=$(mktemp -d)
mkdir -p "$W/cls" "$W/dex" "$W/data"
LIBS=$(ls "$ROOT"/app/libs/*.jar | tr '\n' ':')
javac --release 11 -cp "$LIBS" -d "$W/cls" "$HERE/PhoneBench.java"
cp "$ROOT/app/src/main/assets/brouter/touring.brf" "$ROOT/app/src/main/assets/brouter/lookups.dat" "$W/data/"
ln -s "$(cd "$SEG" && pwd)/E5_N50.rd5" "$W/data/E5_N50.rd5"
if [ "$WHAT" != phone ]; then
  echo "== desktop JVM (-Xmx192m, phone-like heap)"
  java -Xmx192m -cp "$LIBS:$W/cls" PhoneBench "$W/data" "$MEM"
fi
if [ "$WHAT" != jvm ]; then
  SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)
  [ -n "$SDK" ] || SDK=$(sed -n 's/^sdk.dir=//p' /home/development/Projects/work/onpath-app/local.properties)
  D8=$(ls "$SDK"/build-tools/*/d8 | sort -V | tail -1)
  "$D8" --release --min-api 26 --output "$W/dex" "$W/cls/PhoneBench.class" "$ROOT"/app/libs/*.jar
  S=${ANDROID_SERIAL:?set ANDROID_SERIAL (the Pixel)}
  P=/data/local/tmp/onpath-bench
  adb -s "$S" shell "rm -rf $P && mkdir -p $P"
  adb -s "$S" push "$W"/dex/*.dex "$W/data/touring.brf" "$W/data/lookups.dat" "$P/" > /dev/null
  adb -s "$S" push "$(readlink -f "$W/data/E5_N50.rd5")" "$P/E5_N50.rd5" > /dev/null
  CP=$(cd "$W/dex" && ls *.dex | sed "s#^#$P/#" | tr '\n' ':')
  echo "== Pixel ($(adb -s "$S" shell getprop ro.product.model | tr -d '\r'), dalvikvm, -Xmx256m)"
  adb -s "$S" shell "dalvikvm -Xmx256m -cp ${CP%:} PhoneBench $P $MEM" || true
  adb -s "$S" shell "rm -rf $P"
fi
rm -rf "$W"
