#!/usr/bin/env bash
# Fast lane (docs/design/map-hosting.md §7h): build map cells on this PC from origin/main and publish them into the
# TEST R2 bucket exactly like the Cloud Run job does (job_build.py: files, manifest, then current.json, status file).
# The doorman takes a published current version as it is ("adopt", tools/cloud/worker/src/queue.js) the next time the
# cell is asked for or waits in its queue – the phones get it through the normal API, no Cloud Run build.
#
#   tools/dev/publish_cells.sh 10-531-342 10-531-341 …     build + upload (≤ 2 cells at once, low priority)
#   tools/dev/publish_cells.sh --dry-run 10-531-342        everything but the upload (into ~/onpath-cells-work/dry-run)
#
# Rules (owner):
#   - the code is origin/main (git archive), never local changes: a dirty working tree is refused (use push_cell.sh
#     to try local changes on the phone); the manifest's "pipeline" is the origin/main commit, never "+dirty"
#   - TEST only: the credentials come from ~/.config/onpath/r2-test.env (0600, outside the repo; R2 API token with
#     Object Read & Write on bucket onpath-maps-test only) via docker --env-file – never printed, never on a command
#     line; anything but R2_BUCKET=onpath-maps-test, or an R2_ENDPOINT override, is refused
#   - a cell a cloud job is building right now (its status file says "building", < 70 min old) is skipped
# Env: R2_ENV_FILE (default ~/.config/onpath/r2-test.env), ONPATH_CELLS_WORK (work folder).
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# shellcheck source=cell_lib.sh
. "$HERE/cell_lib.sh"

DRY=0
[ "${1:-}" = "--dry-run" ] && { DRY=1; shift; }
[ $# -ge 1 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
CELLS=()
for c in "$@"; do CELLS+=("$(cell_norm "$c")"); done

# ---------------------------------------------------------------------------------------- the credentials: test only
ENVF=${R2_ENV_FILE:-$HOME/.config/onpath/r2-test.env}
[ -f "$ENVF" ] || cell_die "$ENVF missing (R2 token for the TEST bucket, see map-hosting.md §7h)"
[ "$(stat -c %a "$ENVF")" = 600 ] || cell_die "$ENVF must be mode 0600"
envval() { sed -n "s/^$1=//p" "$ENVF" | tail -1; }   # used only for comparisons below, never printed
[ "$(envval R2_BUCKET)" = onpath-maps-test ] || cell_die "$ENVF is not for the bucket onpath-maps-test – refused (test only)"
[ -z "$(envval R2_ENDPOINT)" ] || cell_die "$ENVF sets R2_ENDPOINT – refused (the endpoint comes from the account id)"
[ -z "$(envval R2_LOCAL_DIR)" ] || cell_die "$ENVF sets R2_LOCAL_DIR – refused"
for k in R2_ACCOUNT_ID R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY; do
  [ -n "$(envval $k)" ] || cell_die "$ENVF has no $k"
done
grep -qE '^[A-Z0-9_]+=["'"'"']' "$ENVF" && cell_die "$ENVF: no quotes around values (docker --env-file keeps them)"

# ---------------------------------------------------------------------------------------- the code: origin/main
[ -z "$(git -C "$REPO" status --porcelain)" ] || cell_die "the working tree has changes – commit/stash them first (or use push_cell.sh to try them)"
git -C "$REPO" fetch -q origin main
REV=$(git -C "$REPO" rev-parse --short origin/main)
SRC="$CELL_WORK/main-$REV"
if [ ! -d "$SRC" ]; then
  rm -rf "$SRC.tmp"; mkdir -p "$SRC.tmp"
  git -C "$REPO" archive origin/main tools app/libs app/src/main/assets/brouter | tar -x -C "$SRC.tmp"
  mv "$SRC.tmp" "$SRC"
fi
cell_build_images "$SRC" "$REV" "main-$REV"

# ---------------------------------------------------------------------------------------- skip cells the cloud builds
if [ $DRY = 0 ]; then
  mapfile -t BUSY < <(python3 -I - "$SRC/tools/cloud" "$ENVF" "${CELLS[@]}" <<'PY'
import calendar, json, sys, time
sys.path.insert(0, sys.argv[1])
import r2
env = {}
for line in open(sys.argv[2]):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        env[k] = v
store = r2.from_env(env)
for c in sys.argv[3:]:
    raw = store.get_bytes(f"status/f2/{c}.json")
    s = json.loads(raw) if raw else {}
    t = calendar.timegm(time.strptime(s["updated"], "%Y-%m-%dT%H:%M:%SZ")) if s.get("updated") else 0
    if s.get("state") == "building" and time.time() - t < 70 * 60 and not str(s.get("execution", "")).startswith("donald-"):
        print(c)
PY
  )
  for b in "${BUSY[@]}"; do
    cell_log "skipped $b: a cloud job is building it right now"
    CELLS=("${CELLS[@]/$b}")
  done
  mapfile -t CELLS < <(printf '%s\n' "${CELLS[@]}" | sed '/^$/d')
  [ ${#CELLS[@]} -ge 1 ] || cell_die "nothing left to build"
fi

# ---------------------------------------------------------------------------------------- build (+ upload), 2 at once
OUT="$CELL_WORK/dry-run"
ENVARG=$ENVF
[ $DRY = 1 ] && ENVARG=""
A=(); B=()
for i in "${!CELLS[@]}"; do if (( i % 2 == 0 )); then A+=("${CELLS[$i]}"); else B+=("${CELLS[$i]}"); fi; done
T0=$(date +%s)
rm -f "$CELL_WORK"/logs/run-pub-*.log
cell_run_job "main-$REV" pub-a "$ENVARG" "$OUT" "${A[@]}" &
PA=$!
PB=""
if [ ${#B[@]} -gt 0 ]; then cell_run_job "main-$REV" pub-b "$ENVARG" "$OUT" "${B[@]}" & PB=$!; fi
rc=0
wait $PA || rc=1
[ -z "$PB" ] || wait "$PB" || rc=1
[ $rc = 0 ] || cell_die "some cells failed – see $CELL_WORK/logs/run-pub-*.log"
grep -h "ready (" "$CELL_WORK"/logs/run-pub-*.log | sed 's/^/  /' >&2 || true
if [ $DRY = 1 ]; then
  cell_log "dry run: ${#CELLS[@]} cell(s) built in $(( $(date +%s) - T0 )) s into $OUT (pipeline $REV) – nothing uploaded"
else
  cell_log "published ${#CELLS[@]} cell(s) to onpath-maps-test in $(( $(date +%s) - T0 )) s (pipeline $REV)." \
    "The doorman takes them at the next request for them (or within a minute if queued); phones get them by the next refresh."
fi
