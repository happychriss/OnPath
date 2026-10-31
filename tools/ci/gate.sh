#!/usr/bin/env bash
# The local test gate before a push (owner 2026-10-31 "D": shorter builds) – agents run this instead of the full
# `./gradlew testDebugUnitTest verifyPaparazziDebug`.
#
# It tests what the change can break: the files changed against origin/main (commits + working tree + new files) pick
# the tests (tools/ci/risk_tests.py --gate): the core-feature packages always, the changed packages, the rule tests;
# build files → everything. The screenshot check (verifyPaparazziDebug) runs only when a package with screenshot tests
# (ui/, search/), the texts or the snapshots changed. Nothing Gradle reads changed (docs, web, most of tools/) → no
# Gradle at all. A release (only the version line + notes) runs nothing when unit.yml is green on the base on GitHub
# (release.yml tests again before it publishes). The full suite + screenshots still run in CI after every push (unit.yml).
#
# Gradle runs under the machine-wide lock (~/onpath-cells-work/gradle.lock, donald: one build at a time) with
# --max-workers=2, nice + ionice. The daemon stays warm and ends itself after 10 idle minutes (gradle.properties) –
# no `./gradlew --stop` afterwards.
#
# Usage: tools/ci/gate.sh [--full] [--base REF] [--no-ci-skip] [--dry-run] [-- extra Gradle args/tasks]
#   --full        everything: all unit tests of both modules + the screenshot check
#   --base REF    compare with REF instead of origin/main (the merge base is used)
#   --no-ci-skip  a release runs its selected tests even when CI is green
#   --dry-run     print what would run, run nothing
#   -- …          passed on to Gradle, e.g. `-- assembleDebug`
# Exit code: Gradle's (0 = green).
set -uo pipefail

FULL=0; DRY=0; CISKIP=1; BASE=origin/main; EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --full) FULL=1 ;;
    --dry-run) DRY=1 ;;
    --no-ci-skip) CISKIP=0 ;;
    --base) BASE="${2:?--base needs a ref}"; shift ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "gate: unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

# Extra Gradle tasks (not only options such as -P…) make Gradle run even when no tests are picked
EXTRA_TASKS=0
for a in "${EXTRA[@]}"; do case "$a" in -*) ;; *) EXTRA_TASKS=1 ;; esac; done

ROOT=$(git rev-parse --show-toplevel) || exit 2
cd "$ROOT" || exit 2
BASE_SHA=$(git merge-base "$BASE" HEAD) || { echo "gate: no merge base with $BASE" >&2; exit 2; }
T0=$(date +%s)

mapfile -t CHANGED < <({ git diff --name-only --no-renames "$BASE_SHA"; git ls-files --others --exclude-standard; } | sort -u)
# Only the version line of app/build.gradle.kts (a release) is not a build change
for i in "${!CHANGED[@]}"; do
  if [ "${CHANGED[$i]}" = app/build.gradle.kts ]; then
    other=$(git diff -U0 "$BASE_SHA" -- app/build.gradle.kts | grep -E '^[-+]' | grep -vE '^(\+\+\+|---) ' | grep -vc 'appVersion')
    [ "$other" = 0 ] && CHANGED[$i]="app/build.gradle.kts#version"
  fi
done

eval "$(python3 tools/ci/risk_tests.py --gate "${CHANGED[@]}")" || { echo "gate: risk_tests.py failed" >&2; exit 2; }
[ "$FULL" = 1 ] && { mode=full; paparazzi=1; why="--full"; }

echo "gate: base $(git rev-parse --short "$BASE_SHA") ($BASE) · ${#CHANGED[@]} changed file(s) · $mode – $why"

rc=0
if [ "$python" = 1 ] || [ "$FULL" = 1 ]; then
  echo "gate: tools/ci → python3 -m unittest test_risk_tests, bash -n tools/ci/*.sh"
  if [ "$DRY" = 0 ]; then
    (cd tools/ci && python3 -m unittest -q test_risk_tests) || rc=1
    for s in tools/ci/*.sh; do bash -n "$s" || rc=1; done
  fi
fi

# A release: unit.yml green on the newest run that covers the base (only docs etc. pushed after it) → nothing to do
ci_green() {
  command -v gh >/dev/null || { echo "gate: gh not found – cannot check CI"; return 1; }
  local runs sha status concl between
  runs=$(gh run list --workflow unit.yml --branch main --limit 30 --json headSha,status,conclusion \
    --jq '.[] | "\(.headSha) \(.status) \(.conclusion)"' 2>/dev/null) || { echo "gate: gh run list failed"; return 1; }
  while read -r sha status concl; do
    [ -n "$sha" ] || continue
    git merge-base --is-ancestor "$sha" "$BASE_SHA" 2>/dev/null || continue
    mapfile -t between < <(git diff --name-only --no-renames "$sha" "$BASE_SHA")
    if [ "${#between[@]}" -gt 0 ] && ! python3 tools/ci/risk_tests.py --gate "${between[@]}" | grep -qx 'mode=skip'; then
      echo "gate: app changes after the last unit.yml run (${sha:0:8}) – CI has not tested the base"; return 1
    fi
    [ "$status" = completed ] || { echo "gate: unit.yml on ${sha:0:8} is still $status"; return 1; }
    [ "$concl" = cancelled ] && continue
    [ "$concl" = success ] && { echo "gate: unit.yml green on ${sha:0:8}"; return 0; }
    echo "gate: unit.yml on ${sha:0:8} is $concl"; return 1
  done <<< "$runs"
  echo "gate: no unit.yml run found for the base"; return 1
}
if [ "$release" = 1 ] && [ "$mode" != full ] && [ "$CISKIP" = 1 ]; then
  if ci_green; then
    echo "gate: release only (version line + notes) on a CI-green base → no local Gradle run (release.yml tests before publishing)"
    [ "$EXTRA_TASKS" = 0 ] && { echo "gate: ran nothing · $(( $(date +%s) - T0 )) s · rc=$rc"; exit $rc; }
    mode=extra
  fi
fi

if [ "$mode" = skip ] && [ "$EXTRA_TASKS" = 0 ]; then
  echo "gate: no Gradle run · $(( $(date +%s) - T0 )) s · rc=$rc"
  exit $rc
fi

case "$mode" in
  full) TASKS=(testDebugUnitTest verifyPaparazziDebug) ;;
  select)
    TASKS=(:app:testDebugUnitTest)
    set -f; for p in $patterns; do TASKS+=(--tests "$p"); done; set +f
    [ "$paparazzi" = 1 ] && TASKS+=(:app:verifyPaparazziDebug)
    TASKS+=(:strings:testDebugUnitTest) ;;
  *) TASKS=() ;;
esac
TASKS+=("${EXTRA[@]}")

# donald: Android Studio's JDK and the SDK when the shell has none (worktrees have no local.properties)
JBR=$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null && [ -x "$JBR/bin/java" ]; then export JAVA_HOME=$JBR; fi
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
  export ANDROID_HOME=$HOME/Android/Sdk
fi
WRAP=()
command -v nice >/dev/null && WRAP+=(nice -n 19)
command -v ionice >/dev/null && WRAP+=(ionice -c3)
CMD=("${WRAP[@]}" ./gradlew --max-workers=2 "${TASKS[@]}")
LOCK=${ONPATH_GRADLE_LOCK:-$HOME/onpath-cells-work/gradle.lock}
[ -d "$(dirname "$LOCK")" ] && command -v flock >/dev/null || LOCK=""

echo "gate: screenshots $([ "$paparazzi" = 1 ] && echo "checked (verifyPaparazziDebug)" || echo "not affected – skipped")"
echo "gate: running ${CMD[*]}${LOCK:+ (under the lock $LOCK)}"
[ "$DRY" = 1 ] && exit $rc

# One Gradle build at a time on the machine: the lock is held on fd 9 while Gradle runs
WAIT=0
if [ -n "$LOCK" ]; then
  exec 9>>"$LOCK"
  if ! flock -n 9; then
    echo "gate: waiting for the Gradle lock (another build runs)…"
    w0=$(date +%s); flock 9; WAIT=$(( $(date +%s) - w0 ))
  fi
fi
G0=$(date +%s)
"${CMD[@]}"; grc=$?
G=$(( $(date +%s) - G0 ))
[ -n "$LOCK" ] && exec 9>&-
[ "$grc" = 0 ] || rc=$grc
n=$(cat app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o '<testsuite [^>]*tests="[0-9]*"' | grep -o 'tests="[0-9]*"' | tr -dc '0-9\n' | awk '{s+=$1} END {print s+0}')
echo "gate: $([ "$rc" = 0 ] && echo PASSED || echo FAILED) · $mode · $n app tests · screenshots $([ "$paparazzi" = 1 ] && echo checked || echo skipped) · Gradle ${G} s, lock wait ${WAIT} s, total $(( $(date +%s) - T0 )) s"
[ "$grc" != 0 ] && [ -d app/build/paparazzi/failures ] && echo "gate: screenshot differences in app/build/paparazzi/failures/"
exit $rc
