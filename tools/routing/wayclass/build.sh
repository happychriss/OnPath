#!/usr/bin/env bash
# Build the way classifier (WayClass.java) into ONE runnable jar with the BRouter classes it needs.
#
#   tools/routing/wayclass/build.sh [OUT.jar] [LIBS_DIR]
#
#   OUT.jar   default tools/routing/wayclass/build/wayclass.jar (git-ignored)
#   LIBS_DIR  folder with brouter-expressions / -util / -codec jars (default app/libs – the jars the app routes with)
# Needs a JDK (javac + jar); JAVA_HOME is used when set. Then run it with any Java 17+ runtime:
#   java -jar wayclass.jar --profiles app/src/main/assets/brouter --selftest
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT=${1:-$HERE/build/wayclass.jar}
LIBS=${2:-$ROOT/app/libs}
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAR=${JAVA_HOME:+$JAVA_HOME/bin/}jar
command -v "$JAVAC" >/dev/null || { echo "javac missing (a JDK 17+ is needed, or set JAVA_HOME)" >&2; exit 1; }
DEPS=()
for m in expressions util codec; do
  f=$(ls "$LIBS"/brouter-$m-*.jar 2>/dev/null | head -1)
  [ -n "$f" ] || { echo "brouter-$m jar missing in $LIBS" >&2; exit 1; }
  DEPS+=("$f")
done
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" "$(dirname "$OUT")"
CP=$(IFS=:; echo "${DEPS[*]}")
"$JAVAC" --release 17 -nowarn -cp "$CP" -d "$TMP/classes" "$HERE/WayClass.java"
for f in "${DEPS[@]}"; do (cd "$TMP/classes" && "$JAR" xf "$f" btools); done
printf 'Main-Class: WayClass\n' > "$TMP/manifest.txt"
"$JAR" cfm "$OUT.tmp" "$TMP/manifest.txt" -C "$TMP/classes" .
mv -f "$OUT.tmp" "$OUT"
echo "$OUT ($(du -h "$OUT" | cut -f1))"
