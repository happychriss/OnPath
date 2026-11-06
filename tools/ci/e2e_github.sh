#!/usr/bin/env bash
# Run the E2E tests on GitHub's emulator from any session (cloud sessions have no KVM; donald runs them locally with
# tools/dev/device.sh). Dispatches .github/workflows/e2e.yml by REST (`gh issue`/GraphQL is blocked in cloud sessions).
#   tools/ci/e2e_github.sh [--tests Class[,Class#method]] [--ref main] [--wait] [--out DIR]
#     --tests  only these classes (empty = all but the Release-Rundgang, ~20 min; the Rundgang: com.onpath.app.e2e.ReleaseRundgang)
#     --wait   poll every 60 s until the run ends, then download the report "e2e-report" into --out
#              (default ${TMPDIR:-/tmp}/e2e-<run id>) and list the failed tests
# Exit: 0 dispatched / green, 1 red or cancelled, 2 usage or GitHub error.
# Note: one E2E queue per ref (concurrency group): a new run waits for the running one and cancels a still pending one
# – a dispatched run can cancel a waiting push run and vice versa (docs/project-status.md).
# Without gh: the GitHub MCP tool actions_run_trigger (workflow e2e.yml, ref main, inputs {"tests": "…"}) does the same.
set -uo pipefail
REPO=${ONPATH_REPO:-happychriss/OnPath}
TESTS="" REF=main WAIT=0 OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tests) TESTS=${2:?}; shift ;;
    --ref) REF=${2:?}; shift ;;
    --wait) WAIT=1 ;;
    --out) OUT=${2:?}; shift ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "e2e_github: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
command -v gh >/dev/null || { echo "e2e_github: no gh – use the GitHub MCP tool actions_run_trigger (see --help)" >&2; exit 2; }
api() { gh api "$@" 2>&1; }

since=$(date -u -d '-30 seconds' +%FT%TZ)
if ! err=$(api -X POST "repos/$REPO/actions/workflows/e2e.yml/dispatches" -f ref="$REF" -f "inputs[tests]=$TESTS"); then
  echo "e2e_github: dispatch refused: $err" >&2; exit 2
fi
# find the run (the API lists it after a few seconds)
id=""
for _ in $(seq 1 20); do
  sleep 5
  id=$(gh api "repos/$REPO/actions/workflows/e2e.yml/runs?event=workflow_dispatch&branch=$REF&per_page=5" \
    --jq "[.workflow_runs[] | select(.created_at >= \"$since\")] | sort_by(.created_at) | last | .id // empty" 2>/dev/null)
  [ -n "$id" ] && break
done
[ -n "$id" ] || { echo "e2e_github: dispatched, but no run found – look at Actions → E2E" >&2; exit 2; }
url="https://github.com/$REPO/actions/runs/$id"
echo "e2e_github: run $url (${TESTS:-all tests}, $REF)"
[ "$WAIT" = 1 ] || exit 0

t0=$(date +%s)
while :; do
  st=$(gh api "repos/$REPO/actions/runs/$id" --jq '.status + " " + (.conclusion // "")' 2>/dev/null) || st="unknown "
  case "$st" in completed*) break ;; esac
  sleep 60
done
concl=${st#completed }
echo "e2e_github: $concl after $(( ($(date +%s) - t0) / 60 )) min – $url"

OUT=${OUT:-${TMPDIR:-/tmp}/e2e-$id}
art=$(gh api "repos/$REPO/actions/runs/$id/artifacts" --jq '.artifacts[] | select(.name == "e2e-report") | .id' 2>/dev/null | head -1)
if [ -n "$art" ]; then
  mkdir -p "$OUT" && gh api "repos/$REPO/actions/artifacts/$art/zip" > "$OUT/report.zip" 2>/dev/null &&
    (cd "$OUT" && unzip -qo report.zip && rm report.zip) && echo "e2e_github: report in $OUT (screenshots: e2e-out/, logcat on failure)"
  python3 - "$OUT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
n = bad = 0
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    try: root = ET.parse(f).getroot()
    except ET.ParseError: continue
    for tc in root.iter("testcase"):
        n += 1
        fail = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        if fail is not None:
            bad += 1
            msg = (fail.get("message") or fail.text or "").strip().splitlines()
            print("  ✗ %s#%s: %s" % (tc.get("classname"), tc.get("name"), msg[0][:200] if msg else ""))
print("e2e_github: %d test case(s), %d failed" % (n, bad))
PY
else
  echo "e2e_github: no e2e-report artifact (the run failed before the tests – see $url)"
fi
[ "$concl" = success ]
