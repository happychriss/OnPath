#!/usr/bin/env bash
# Brings main's newer app code into web-spike (docs/design/web-planner.md §14): the engine compiles the app's files in
# place, so shared logic updates itself; this script merges, builds and tests, and stops before pushing when anything
# needs a person (a conflict, a red test, a mirrored app file that changed). Run from a clean web-spike checkout:
#   web/parity/merge-main.sh [segmentsDir] [cellStoreDir]      (PUSH=1 pushes when everything is green)
# Exit codes: 0 = merged + green (or nothing new), 2 = merge conflict, 3 = build / tests red, 4 = mirrors to adjust.
set -euo pipefail
cd "$(dirname "$0")/../.."
SEG=${1:-/home/user/build/routing-test/segments}
CELLS=${2:-/home/user/build/cell/store}
git fetch -q origin main web-spike
[ "$(git rev-parse --abbrev-ref HEAD)" = web-spike ] || { echo "not on web-spike"; exit 1; }
git merge -q --ff-only origin/web-spike 2>/dev/null || true
if git merge-base --is-ancestor origin/main HEAD; then echo "web-spike already has main ($(git rev-parse --short origin/main))"; exit 0; fi
NEW=$(git log --oneline HEAD..origin/main | wc -l)
echo "merging $NEW main commit(s) into web-spike"
git diff --stat HEAD...origin/main -- app strings | tail -1
if ! git merge --no-edit -m "Merge origin/main into web-spike (main's newer app code)" origin/main; then
  git diff --name-only --diff-filter=U; echo "merge conflict – resolve by hand"; exit 2
fi
REPORT=${REPORT:-web/engine/build/parity-report.md}
mkdir -p "$(dirname "$REPORT")"
PARITY=0; node web/parity/check.mjs --report "$REPORT" || PARITY=$?
run() { echo "== $*"; "$@" || { echo "RED: $*"; exit 3; }; }
run ./gradlew -q -p web/engine test generateWasmGC copyWasmGCRuntime overviewStyle
run node web/app/test/sync-engine.mjs
run node web/app/test/gpx-engine.mjs
run ./gradlew -q -p web/engine runJvm -Psegments="$SEG"
( cd web/app && run npm run build )
for t in browser-compare ui-smoke share-smoke edit-smoke parity-smoke sync-smoke import-smoke overview-smoke; do
  ( cd web/app && run node "test/$t.mjs" "$SEG" )
done
( cd web/app && run node test/search-smoke.mjs "$SEG" "$CELLS" )
( cd web/host && run node --test )
if [ "$PARITY" != 0 ]; then echo "mirrored app files changed – see $REPORT; adjust the web, then node web/parity/check.mjs --update"; exit 4; fi
if [ "${PUSH:-0}" = 1 ]; then git push -q origin HEAD:web-spike && echo "pushed"; else echo "green – push with: git push origin HEAD:web-spike"; fi
