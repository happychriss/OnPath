#!/usr/bin/env bash
# Smoke test of the doorman API (docs/design/map-hosting.md §First deploy and smoke test).
#
#   tools/cloud/worker/smoke.sh                       local: wrangler dev (local D1 + R2 simulation, builds off)
#   ONPATH_APP_KEY=… tools/cloud/worker/smoke.sh https://maps-test.free-your-pictures.org
#
# Checks: health, 401 without key, request of the Meran cell (queued / building / ready – a real build starts on a
# deployed environment!), status, manifest (404 until ready). The key is read from the environment, never printed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
BASE=${1:-}
WRANGLER=${WRANGLER:-npx --yes wrangler@4.141.0}
CELL=${CELL:-10-543-361}
PID=
LOCAL=0

cleanup() {
  [ -n "$PID" ] && kill "$PID" 2>/dev/null || true
  [ "$LOCAL" = 1 ] || return 0
  if [ -f .dev.vars.smoke-backup ]; then mv .dev.vars.smoke-backup .dev.vars; else rm -f .dev.vars; fi
}
trap cleanup EXIT

if [ -z "$BASE" ]; then
  LOCAL=1
  KEY="dev-key-$(date +%s)-smoke"
  [ -f .dev.vars ] && mv .dev.vars .dev.vars.smoke-backup
  printf 'APP_KEYS=%s\n' "$KEY" > .dev.vars
  $WRANGLER d1 migrations apply DB --local >/dev/null
  $WRANGLER dev --local --port 8787 > /tmp/onpath-smoke-dev.log 2>&1 &
  PID=$!
  BASE=http://localhost:8787
  for _ in $(seq 1 60); do curl -fsS "$BASE/v1/health" >/dev/null 2>&1 && break; sleep 1; done
else
  KEY=${ONPATH_APP_KEY:?set ONPATH_APP_KEY (the app key of that environment)}
fi

fail=0
check() { # NAME EXPECTED_STATUS CURL_ARGS...
  local name=$1 want=$2; shift 2
  local code
  code=$(curl -sS -o /tmp/onpath-smoke-body -w '%{http_code}' "$@" || echo 000)
  if [ "$code" = "$want" ]; then echo "ok    $name ($code) $(head -c 300 /tmp/onpath-smoke-body)"; else
    echo "FAIL  $name: HTTP $code, expected $want – $(head -c 300 /tmp/onpath-smoke-body)"; fail=1; fi
}
H=(-H "X-OnPath-Key: $KEY" -H "X-OnPath-App: smoke")
check health          200 "$BASE/v1/health"
check "no key → 401"  401 "$BASE/v1/cells/status?ids=$CELL"
check request         200 "${H[@]}" -H 'content-type: application/json' -X POST \
  --data "{\"cells\":[\"$CELL\"],\"reason\":\"point\",\"formats\":[2]}" "$BASE/v1/cells/request"
check status          200 "${H[@]}" "$BASE/v1/cells/status?ids=$CELL"
# any cell is accepted now (the builder chooses the sources); an unasked cell is just "none"
check "unasked cell → none" 200 "${H[@]}" "$BASE/v1/cells/status?ids=10-398-405"
grep -q '"state":"none"' /tmp/onpath-smoke-body || { echo "FAIL  unasked cell not 'none'"; fail=1; }
IFS=- read -r Z X Y <<<"$CELL"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${H[@]}" "$BASE/v1/cells/$Z/$X/$Y/manifest" || true)
echo "info  manifest: HTTP $code (404 until the cell is built, then 200)"
if [ "$fail" = 0 ]; then echo "SMOKE OK"; else
  echo "SMOKE FAILED"; [ -f /tmp/onpath-smoke-dev.log ] && tail -20 /tmp/onpath-smoke-dev.log; exit 1
fi
