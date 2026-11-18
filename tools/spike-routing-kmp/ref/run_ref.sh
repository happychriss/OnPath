#!/usr/bin/env bash
# The spike's routes on today's Java engine (app/libs jars) → fixtures/reference.txt + out/java-times.txt.
# Usage: ref/run_ref.sh <segmentsDir with E5_N50.rd5 + E10_N45.rd5> [reps=3] [dump=fixtures/reference.txt]
# Needs a JDK (javac): JAVA_BIN=<dir with java + javac>, default Gradle's JDK 21.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd); ROOT=$(cd "$HERE/../.." && pwd)
SEG=${1:?segments dir}; REPS=${2:-3}; DUMP=${3:-$HERE/fixtures/reference.txt}
JB=${JAVA_BIN:-$(ls -d ~/.gradle/jdks/*21*/bin 2>/dev/null | head -1)}
CP=$(ls "$ROOT"/app/libs/brouter-*.jar | tr '\n' ':')
mkdir -p "$HERE/out/refclasses"
"$JB/javac" -nowarn -cp "$CP" -d "$HERE/out/refclasses" "$HERE/ref/btools/router/RefMain.java"
"$JB/java" -Xmx${JAVA_XMX:-192m} -cp "$CP$HERE/out/refclasses" btools.router.RefMain "$SEG" "$ROOT/app/src/main/assets/brouter" \
  "${ROUTES:-$HERE/routes.txt}" "$DUMP" "$HERE/out/java-times.txt" "$REPS"
cat "$HERE/out/java-times.txt"
