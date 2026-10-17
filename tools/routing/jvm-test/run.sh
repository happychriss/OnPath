#!/usr/bin/env bash
# JVM smoke test of BRouterEngine.kt (real Kotlin code, Android Context/AssetManager stubbed).
# Usage: run.sh <segmentsDirContaining_E10_N45.rd5> [workDir]
# Needs a kotlin-compiler-embeddable jar (Gradle's lib dir has one); set GRADLE_LIB if not found.
# Env: KTTEST=<file.kt> runs another test main (default KtTest.kt; ViaTest.kt = via-point tests);
#      NO_VIA_CORRECTION=1 compiles the engine with BRouter's correctMisplacedViaPoints off (for comparison).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
SEG=${1:?segments dir}; W=${2:-$(mktemp -d)}
L=${GRADLE_LIB:-$(dirname "$(ls ~/.gradle/wrapper/dists/*/*/*/lib/kotlin-compiler-embeddable-*.jar | tail -1)")}
KCP=$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-[0-9]*.jar $L/kotlin-reflect-*.jar $L/kotlin-script-runtime-*.jar $L/kotlinx-coroutines-core-jvm-*.jar $L/annotations-*.jar $L/kotlin-daemon-embeddable-*.jar | tr '\n' ':')
STD=$(ls $L/kotlin-stdlib-[0-9]*.jar)
LIBS=$(ls "$ROOT"/app/libs/*.jar | tr '\n' ':')
mkdir -p "$W/stub" "$W/kt"
javac -d "$W/stub" $(find "$HERE/stubs" -name '*.java')
R="$ROOT/app/src/main/java/com/onpath/app/routing"
T=${KTTEST:-$HERE/KtTest.kt}; TN=$(basename "$T" .kt)
sed "s#/home/user/build/routing-test/segments#$SEG#; s#/home/user/build/routing-test/kt-files#$W/files#; s#/home/user/onpath/app/src/main/assets#$ROOT/app/src/main/assets#" "$T" > "$W/$TN.kt"
cp "$R/BRouterEngine.kt" "$W/BRouterEngine.kt"
if [ -n "${NO_VIA_CORRECTION:-}" ]; then
  sed -i 's#kv\["correctMisplacedViaPoints"\] = "1"#kv["correctMisplacedViaPoints"] = "0"#' "$W/BRouterEngine.kt"
  grep -q 'correctMisplacedViaPoints"\] = "0"' "$W/BRouterEngine.kt"
fi
rm -rf "$W/kt"; mkdir -p "$W/kt"
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$STD:$W/stub:$LIBS" -d "$W/kt" "$R/RoutingContract.kt" "$R/BRouterWayStats.kt" "$W/BRouterEngine.kt" "$W/$TN.kt"
java -Xmx192m -cp "$STD:$W/stub:$LIBS:$W/kt" "${TN}Kt"
