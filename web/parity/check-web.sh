#!/usr/bin/env bash
# The planner's checks on this checkout (docs/design/web-planner.md §14), locally – the same build and tests as
# .github/workflows/web.yml, before pushing a web change or after an app change to a file the web compiles or mirrors.
# Since the one-branch merge (2026-11-06) app and planner are both on main, so there is nothing to merge any more.
#   web/parity/check-web.sh [segmentsDir] [cellStoreDir]
# Exit codes: 0 = green, 3 = build / tests red, 4 = mirrors to adjust (web/parity.json).
set -euo pipefail
cd "$(dirname "$0")/../.."
SEG=${1:-/home/user/build/routing-test/segments}
CELLS=${2:-/home/user/build/cell/store}
REPORT=${REPORT:-web/engine/build/parity-report.md}
mkdir -p "$(dirname "$REPORT")"
PARITY=0; node web/parity/check.mjs --report "$REPORT" || PARITY=$?
run() { echo "== $*"; "$@" || { echo "RED: $*"; exit 3; }; }
run tools/dev/gradle_slot.sh -- ./gradlew -q -p web/engine test generateWasmGC copyWasmGCRuntime overviewStyle
run node web/app/test/texts-check.mjs
run node web/app/test/pinmenu-check.mjs
run node web/app/test/sync-engine.mjs
run node web/app/test/gpx-engine.mjs
run tools/dev/gradle_slot.sh -- ./gradlew -q -p web/engine runJvm parityJvm -Psegments="$SEG"
run node web/app/test/plan-parity.mjs "$SEG"
( cd web/app && run npm run build )
for t in browser-compare ui-smoke share-smoke edit-smoke snap-smoke version-smoke activity-look-smoke parity-smoke sync-smoke sync-rate-smoke import-smoke overview-smoke stop-smoke sort-smoke zuletzt-smoke phonelink-smoke center-smoke; do
  ( cd web/app && run node "test/$t.mjs" "$SEG" )
done
( cd web/app && run node test/search-smoke.mjs "$SEG" "$CELLS" )
( cd web/app && run node test/poipin-smoke.mjs "$SEG" "$CELLS" )
( cd web/host && run node --test )
# the app leads (web-planner.md §14.2): adopt each change in the web, re-run this script, push – do not wait for a person
if [ "$PARITY" != 0 ]; then echo "mirrored app files changed – see $REPORT; adopt them in the web (the app leads), then node web/parity/check.mjs --update"; exit 4; fi
echo "green"
