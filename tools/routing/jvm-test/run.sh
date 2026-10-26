#!/usr/bin/env bash
# JVM smoke test of BRouterEngine.kt (real Kotlin code, Android Context/AssetManager stubbed).
# Usage: run.sh <segmentsDirContaining_E10_N45.rd5> [workDir]
# Needs a kotlin-compiler-embeddable jar (Gradle's lib dir has one); set GRADLE_LIB if not found.
# Env: KTTEST=<file.kt> runs another test main (default KtTest.kt; ViaTest.kt = via-point tests;
#      SnapTest.kt = plan points moved onto the way; TimeP4Test.kt = route times without / with way attributes; RerouterJvmTest.kt = re-routing while navigating, pass/fail);
#      IslandTest.kt = a point on a way without a connection (Köln Dom, needs E5_N50.rd5) still gets a route, pass/fail;
#      ItterPathTest.kt = bike routes stay on a compacted foot / cycle path (Hilden Itter, needs E5_N50.rd5), pass/fail;
#      FerryTest.kt = no route over a ferry that does not take bikes / people on foot (RAW_TAP=1, FERRY_CASES=<file>);
#      FerryHintTest.kt = routes over a Rhine ferry show the amber ferry hint (needs E5_N50.rd5), pass/fail;
#      HintInventory.kt = candidate route hints (ford, barriers, access, ...) counted on random routes (RAW_TAP=1);
#      SafetyTest.kt = trail safety (SAC / via_ferrata / bike limits) metres per class, SAFETY_TAG=label;
#      NO_VIA_CORRECTION=1 compiles the engine with BRouter's correctMisplacedViaPoints off (for comparison);
#      GpxCompare.kt = compare with a reference GPX line (another app's plan, a ride; see its header, needs RAW_TAP=1).
#      ASSETS_DIR=<dir with brouter/*.brf + lookups.dat> routes with other profiles (A/B; default: the app's assets);
#      JAVA_XMX=<heap> (default 192m, phone-like); RAW_TAP=1 hands the full BRouter way tags of every found track to
#      RawTap.kt (routing unchanged). Give every parallel run its own workDir: the profiles are copied there (files/).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
SEG=${1:?segments dir}; W=${2:-$(mktemp -d)}
# Prefer the Gradle version the wrapper uses, else the newest installed one (version sort, so 10.x > 9.x).
GV=$(sed -n 's#.*/gradle-\([0-9][0-9.]*\)-\(bin\|all\)\.zip.*#\1#p' "$ROOT/gradle/wrapper/gradle-wrapper.properties")
KCE=$(ls ~/.gradle/wrapper/dists/gradle-"$GV"-*/*/*/lib/kotlin-compiler-embeddable-*.jar 2>/dev/null | tail -1 || true)
[ -n "$KCE" ] || KCE=$(ls ~/.gradle/wrapper/dists/*/*/*/lib/kotlin-compiler-embeddable-*.jar | sort -V | tail -1)
L=${GRADLE_LIB:-$(dirname "$KCE")}
KCP=$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-[0-9]*.jar $L/kotlin-reflect-*.jar $L/kotlin-script-runtime-*.jar $L/kotlinx-coroutines-core-jvm-*.jar $L/annotations-*.jar $L/kotlin-daemon-embeddable-*.jar | tr '\n' ':')
STD=$(ls $L/kotlin-stdlib-[0-9]*.jar)
LIBS=$(ls "$ROOT"/app/libs/*.jar | tr '\n' ':')
mkdir -p "$W/stub" "$W/kt"
javac -d "$W/stub" $(find "$HERE/stubs" -name '*.java')
# BRouter's turn instructions are read from its own package (package-private fields): compile next to the jars
LIBS_EARLY=$(ls "$ROOT"/app/libs/*.jar | tr '\n' ':')
javac -cp "$LIBS_EARLY" -d "$W/stub" "$ROOT/app/src/main/java/btools/router/OnPathHints.java"
R="$ROOT/app/src/main/java/com/onpath/app/routing"
N="$ROOT/app/src/main/java/com/onpath/app/navigation"; D="$ROOT/app/src/main/java/com/onpath/app/data"
T=${KTTEST:-$HERE/KtTest.kt}; TN=$(basename "$T" .kt)
ASSETS=${ASSETS_DIR:-$ROOT/app/src/main/assets}
sed "s#/home/user/build/routing-test/segments#$SEG#; s#/home/user/build/routing-test/kt-files#$W/files#; s#/home/user/onpath/app/src/main/assets#$ASSETS#; s#@JVMTEST_DIR@#$HERE#" "$T" > "$W/$TN.kt"
cp "$R/BRouterEngine.kt" "$W/BRouterEngine.kt"
EXTRA=()
if [ -n "${RAW_TAP:-}" ]; then
  sed -i 's#^\(\s*\)val els = track.nodes$#&\n\1RawTap.tap(track)#' "$W/BRouterEngine.kt"
  grep -q 'RawTap.tap(track)' "$W/BRouterEngine.kt"
  EXTRA+=("$HERE/RawTap.kt")
fi
if [ -n "${NO_VIA_CORRECTION:-}" ]; then
  sed -i 's#kv\["correctMisplacedViaPoints"\] = "1"#kv["correctMisplacedViaPoints"] = "0"#' "$W/BRouterEngine.kt"
  grep -q 'correctMisplacedViaPoints"\] = "0"' "$W/BRouterEngine.kt"
fi
rm -rf "$W/kt"; mkdir -p "$W/kt"
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$STD:$W/stub:$LIBS" -d "$W/kt" "$R/RoutingContract.kt" "$R/Rd5Parts.kt" "$R/BRouterWayStats.kt" "$R/WaypointSnap.kt" "$W/BRouterEngine.kt" \
  "$R/StraightLineEngine.kt" "$R/RouteTimeLimit.kt" "$R/TurnDetector.kt" "$R/Junctions.kt" "$N/JunctionCues.kt" "$D/Geo.kt" "$ROOT/app/src/main/java/com/onpath/app/time/PaceModel.kt" "$ROOT/app/src/main/java/com/onpath/app/time/RouteTimes.kt" "$N/NavLine.kt" "$N/RerouteTargets.kt" "$N/Rerouter.kt" \
  "$R/Planner.kt" "$R/RouteSummary.kt" "$ROOT/app/src/main/java/com/onpath/app/ui/RouteWarnings.kt" "$D/LastActivity.kt" \
  "$HERE/ProblemsStub.kt" ${EXTRA[@]+"${EXTRA[@]}"} "$W/$TN.kt"
java -Xmx${JAVA_XMX:-192m} -cp "$STD:$W/stub:$LIBS:$W/kt" "${TN}Kt"
