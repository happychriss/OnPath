#!/usr/bin/env bash
# Builds the BRouter routing-core jars for OnPath (app/libs/) and copies the
# routing profiles into app/src/main/assets/brouter/.
# Plain javac (no Gradle, no external deps): brouter-util, -codec, -expressions,
# -mapaccess, -core, compiled with --release 11 (D8 compatible).
# Usage: tools/routing/build_brouter_jars.sh [BROUTER_SRC_DIR]
set -euo pipefail
SRC=${1:-/home/user/build/brouter}
HERE=$(cd "$(dirname "$0")/../.." && pwd)
VER=$(grep -oE "version = '[^']+'" "$SRC/buildSrc/src/main/groovy/brouter.version-conventions.gradle" | cut -d"'" -f2)
VER=${VER:-unknown}
WORK=$(mktemp -d)
CP=""
mkdir -p "$HERE/app/libs"
rm -f "$HERE"/app/libs/brouter-*.jar
for m in util codec expressions mapaccess core; do
  mkdir -p "$WORK/$m"
  javac --release 11 -nowarn -encoding UTF-8 -cp "$CP" -d "$WORK/$m" \
    $(find "$SRC/brouter-$m/src/main/java" -name '*.java')
  jar --create --file "$HERE/app/libs/brouter-$m-$VER.jar" -C "$WORK/$m" .
  CP="$CP:$WORK/$m"
done
A="$HERE/app/src/main/assets/brouter"
mkdir -p "$A"
for f in lookups.dat fastbike-verylowtraffic.brf trekking.brf mtb.brf hiking-mountain.brf; do
  cp "$SRC/misc/profiles2/$f" "$A/$f"
done
cp "$SRC/LICENSE" "$A/LICENSE-brouter.txt"
rm -rf "$WORK"
ls -la "$HERE/app/libs" "$A"
