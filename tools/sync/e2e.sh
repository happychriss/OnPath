#!/bin/bash
# End-to-end test of the app's plan sync (plansync/: HTTP client, pass, routes ⇄ items) against the real mailbox
# (web/host/src/sync.js + its D1 migrations) running in Node on this machine – no phone, no Cloudflare.
#
#   tools/sync/e2e.sh [ref]      ref = where sync.js comes from when web/host is not in this checkout (default origin/web-spike)
#
# Copies sync.js and the migrations to app/build/sync-e2e/, then runs SyncMailboxE2ETest (it starts
# tools/sync/mailbox-server.mjs with Node ≥ 22.5 – node:sqlite – and skips itself when Node or sync.js is missing).
set -euo pipefail
cd "$(dirname "$0")/../.."
ref=${1:-origin/web-spike}
out=app/build/sync-e2e
rm -rf "$out"; mkdir -p "$out/src" "$out/migrations"
if [ -f web/host/src/sync.js ]; then
  cp web/host/src/sync.js "$out/src/"
  cp web/host/migrations/*.sql "$out/migrations/"
else
  git show "$ref:web/host/src/sync.js" > "$out/src/sync.js"
  git ls-tree --name-only "$ref" web/host/migrations/ | while read -r f; do git show "$ref:$f" > "$out/migrations/$(basename "$f")"; done
fi
echo "mailbox: $(wc -l < "$out/src/sync.js") lines of sync.js, $(ls "$out/migrations" | wc -l) migration(s); node $(node --version)"
./gradlew :app:testDebugUnitTest --tests 'com.onpath.app.plansync.SyncMailboxE2ETest' --rerun
python3 - <<'EOF'
import xml.etree.ElementTree as ET
t = ET.parse("app/build/test-results/testDebugUnitTest/TEST-com.onpath.app.plansync.SyncMailboxE2ETest.xml").getroot()
print(f"SyncMailboxE2ETest: {t.get('tests')} tests, {t.get('failures')} failed, {t.get('errors')} errors, {t.get('skipped')} skipped")
raise SystemExit(1 if int(t.get('skipped')) or int(t.get('failures')) or int(t.get('errors')) else 0)
EOF
