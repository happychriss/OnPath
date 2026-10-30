#!/usr/bin/env bash
# Pre-build a whole area on this PC into the TEST bucket (owner 2026-10-30: all of Germany overnight) – the fast lane
# of publish_cells.sh (docs/design/map-hosting.md §7h) as a long, resumable run:
#
#   tools/dev/publish_area.sh <area dir>      reads <area dir>/cells.txt (z10 ids, tools/dev/area_cells.py) and
#                                             <area dir>/packages.txt (z7 region packages, built after the cells)
#
# - the code is origin/main at the start (git archive, pinned for the whole run – the manifest's "pipeline")
# - TEST only: credentials only via docker --env-file (cell_check_test_env refuses anything but onpath-maps-test)
# - resumable: at the start every id is looked up in the bucket (tools/dev/area_state.py in the job image) – a cell whose
#   published version has the current schema and an OSM date ≤ FRESH_DAYS old is skipped, also one a cloud job is
#   building; so a crashed or stopped run just starts again
# - PARALLEL containers (default 2), each BATCH cells one after the other (the job copies 1.4 GB of Planetiler
#   sources at every container start), 4 CPUs at a small CPU share / IO weight, 8 GiB (cell_lib.sh); a batch starts
#   only while MemAvailable ≥ MIN_FREE_GB (kept for the desktop) + PEAK_GB (one build's peak: the raw cut, ~4 GB) +
#   what the running containers may still grow to PEAK_GB, and while the disk has ≥ MIN_DISK_GB free
# - a failed cell is tried once more at the end of the queue, then listed in failed.txt; the run goes on
# - logs: <area dir>/run-YYYYMMDD.log, per batch logs/<label>-<n>.log, one-line status.txt (done / failed / left / ETA),
#   done.txt (id seconds bytes), failed.txt
# - stop: SIGTERM / systemctl --user stop <unit> → the containers of this run are killed, status "stopped"
# Env: R2_ENV_FILE, ONPATH_CELLS_WORK, PARALLEL=2, BATCH=5, MIN_FREE_GB=6, PEAK_GB=4, MIN_DISK_GB=40, FRESH_DAYS=30.
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# shellcheck source=cell_lib.sh
. "$HERE/cell_lib.sh"

AREA=$(cd "${1:?usage: publish_area.sh <area dir>}" && pwd) || exit 2
NAME=$(basename "$AREA")
PARALLEL=${PARALLEL:-2}
BATCH=${BATCH:-5}
MIN_FREE_GB=${MIN_FREE_GB:-6}
PEAK_GB=${PEAK_GB:-4}
MIN_DISK_GB=${MIN_DISK_GB:-40}
FRESH_DAYS=${FRESH_DAYS:-30}
LOG="$AREA/run-$(date +%Y%m%d).log"
STATUS="$AREA/status.txt"
Q="$AREA/queue.txt"
mkdir -p "$AREA/logs"
exec >> "$LOG" 2>&1
cell_log "=== start (pid $$, parallel $PARALLEL, batch $BATCH)"

ENVF=${R2_ENV_FILE:-$HOME/.config/onpath/r2-test.env}
cell_check_test_env "$ENVF"

# ------------------------------------------------------------------------------------ the code: origin/main, pinned
git -C "$REPO" fetch -q origin main || cell_die "git fetch failed"
REV=$(git -C "$REPO" rev-parse --short origin/main)
SRC="$CELL_WORK/main-$REV"
if [ ! -d "$SRC" ]; then
  rm -rf "$SRC.tmp"; mkdir -p "$SRC.tmp"
  git -C "$REPO" archive origin/main tools app/libs app/src/main/assets/brouter | tar -x -C "$SRC.tmp"
  mv "$SRC.tmp" "$SRC"
fi
cell_build_images "$SRC" "$REV" "main-$REV"
TAG="main-$REV"
CS=$(sed -n 's/^CELL_SCHEMA = \([0-9]*\).*/\1/p' "$SRC/tools/cells/cells.py")
PS=$(sed -n 's/^PACKAGE_SCHEMA = \([0-9]*\).*/\1/p' "$SRC/tools/cells/cells.py")
cell_log "pipeline $REV, cell schema $CS, package schema $PS"

# ------------------------------------------------------------------------------------ what is left (resume)
state() {  # ids… → "<id> <state> <version>" from the TEST bucket
  docker run --rm --network host --user "$(id -u):$(id -g)" --env-file "$ENVF" -v "$HERE:/dev-tools:ro" \
    --entrypoint python3 "onpath-cell-job:$TAG" -I /dev-tools/area_state.py /onpath/tools/cloud "$FRESH_DAYS" "$CS" "$PS" "$@"
}
mapfile -t ALL < <(cat "$AREA/cells.txt" "$AREA/packages.txt" 2>/dev/null | sed '/^\s*$/d')
for c in "${ALL[@]}"; do cell_norm "$c" >/dev/null; done
state "${ALL[@]}" > "$AREA/state.txt" || cell_die "bucket state could not be read"
[ "$(wc -l < "$AREA/state.txt")" = "${#ALL[@]}" ] || cell_die "bucket state incomplete"
# cells first (grouped by z7 block: neighbours share extracts and terrain tiles), then the packages
awk '$2 != "current" && $2 != "cloud-building" {print $1}' "$AREA/state.txt" \
  | awk -F- '{printf "%d %d %d %d %s\n", ($1 == 7), ($1 == 7 ? $2 : int($2 / 8)), ($1 == 7 ? $3 : int($3 / 8)), $2 * 10000 + $3, $0}' \
  | sort -n -k1,1 -k2,2 -k3,3 -k4,4 | awk '{print $5}' > "$Q"
: > "$AREA/attempts.txt"
touch "$AREA/done.txt" "$AREA/failed.txt"
TOTAL=$(wc -l < "$Q")
cell_log "$(awk '{print $2}' "$AREA/state.txt" | sort | uniq -c | tr '\n' ' ')→ $TOTAL to build"
RUN_START=$(date +%s)
DONE0=$(wc -l < "$AREA/done.txt")

# ------------------------------------------------------------------------------------ queue + status (flock)
LOCK="$AREA/.lock"
pop() {  # n → up to n ids of one kind (cells or packages) from the queue head
  local n=$1
  (
    flock 9
    local first kind out=()
    first=$(head -1 "$Q")
    [ -n "$first" ] || exit 0
    kind=${first%%-*}
    while IFS= read -r c; do
      [ "${c%%-*}" = "$kind" ] && [ ${#out[@]} -lt "$n" ] && out+=("$c")
    done < "$Q"
    printf '%s\n' "${out[@]}"
    grep -vxF -f <(printf '%s\n' "${out[@]}") "$Q" > "$Q.tmp"; mv "$Q.tmp" "$Q"
  ) 9>"$LOCK"
}
note() {  # file line (locked append)
  ( flock 9; echo "$2" >> "$1" ) 9>"$LOCK"
}
retry_or_fail() {  # id reason
  local c=$1 why=$2 n
  n=$( ( flock 9; echo "$c" >> "$AREA/attempts.txt"; grep -cxF "$c" "$AREA/attempts.txt" ) 9>"$LOCK")
  if [ "$n" -lt 2 ]; then
    cell_log "$c: $why – queued again"
    note "$Q" "$c"
  else
    cell_log "$c: $why – FAILED (twice)"
    note "$AREA/failed.txt" "$c $why"
  fi
}
write_status() {
  (
    flock 9
    local done failed left running avg eta now
    now=$(date +%s)
    done=$(( $(wc -l < "$AREA/done.txt") - DONE0 ))
    failed=$(wc -l < "$AREA/failed.txt")
    running=$(cat "$AREA"/running-* 2>/dev/null | wc -l)
    left=$(( $(wc -l < "$Q") + running ))
    if [ "$done" -gt 0 ]; then
      avg=$(( (now - RUN_START) / done ))   # wall seconds per cell with PARALLEL containers
      eta=$(date -d "@$(( now + left * avg ))" '+%a %H:%M')
    else
      eta="?"
    fi
    echo "$(date '+%F %H:%M') $NAME: done $done / failed $failed / left $left (running $running) / ETA $eta – pipeline $REV, $(df -BG --output=avail "$CELL_WORK" | tail -1 | tr -d ' ') free, $(awk '/MemAvailable/ {printf "%.0f", $2/1048576}' /proc/meminfo)G RAM free" > "$STATUS.tmp"
    mv "$STATUS.tmp" "$STATUS"
  ) 9>"$LOCK"
}

# ------------------------------------------------------------------------------------ one worker = one container slot
need_mb() {  # RAM a new container needs free: what is kept for the desktop + its own peak + what the running ones may
  # still grow to their peak (docker stats = usage without page cache; the raw cut peaks at ~4 GB for seconds)
  local ids
  ids=$(docker ps -q --filter "label=onpath-area=$NAME")
  { [ -n "$ids" ] && docker stats --no-stream --format '{{.MemUsage}}' $ids; } 2>/dev/null | awk -v keep="$MIN_FREE_GB" -v peak="$PEAK_GB" '
    { v = $1; if (v ~ /GiB/) { sub(/GiB/, "", v); v *= 1024 } else if (v ~ /MiB/) sub(/MiB/, "", v); else v = 0
      g = peak * 1024 - v; if (g > 0) grow += g }
    END { printf "%d\n", keep * 1024 + peak * 1024 + grow }'
}

gate() {  # wait until there is room for one more container
  local said=0 avail need disk
  while true; do
    disk=$(df -BG --output=avail "$CELL_WORK" | tail -1 | tr -dc 0-9)
    if [ "$disk" -lt "$MIN_DISK_GB" ]; then cell_log "only ${disk} GB disk left (< $MIN_DISK_GB) – worker stops"; return 1; fi
    avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    need=$(need_mb)
    [ "$avail" -ge "$need" ] && return 0
    [ $said = 1 ] || cell_log "waiting: $((avail / 1024)) GB RAM available, $((need / 1024)) GB needed for one more container"
    said=1
    sleep 60
  done
}

worker() {
  local label="$NAME-$1" n=0 cells t0 dt blog c line sz per dpid rc i
  # the gate lock is held from the RAM check until this worker's container runs (the next check then sees it)
  exec 8>"$AREA/.gate"
  while true; do
    flock 8
    gate || { flock -u 8; return 0; }
    mapfile -t cells < <(pop "$BATCH")
    [ ${#cells[@]} -gt 0 ] || { flock -u 8; cell_log "$label: queue empty"; return 0; }
    n=$((n + 1))
    blog="$AREA/logs/$label-$(date +%m%d%H%M%S).log"
    printf '%s\n' "${cells[@]}" > "$AREA/running-$1"
    write_status
    rm -rf "$CELL_WORK/work-$label/cells" "$CELL_WORK/store-$label/cells"
    mkdir -p "$CELL_WORK/src" "$CELL_WORK/work-$label" "$CELL_WORK/store-$label"
    cell_log "$label: building ${cells[*]} (log $blog)"
    t0=$(date +%s)
    # same container as cell_lib.sh cell_run_job (the cloud job's limits), plus a label to stop this run's containers
    timeout --kill-after=60 $(( ${#cells[@]} * 2700 )) docker run --rm --name "onpath-cell-$label" \
      --label "onpath-area=$NAME" --cpus 4 --memory 8g --cpu-shares 64 --blkio-weight 10 \
      --user "$(id -u):$(id -g)" --network host \
      -e CELLS="${cells[*]}" -e FORMAT=2 -e SRC_DIR=/src -e WORK=/work -e DATA_ROOT=/store -e OMP_NUM_THREADS=1 \
      -e JAVA_XMX=2g -e HOME=/tmp -e CLOUD_RUN_EXECUTION="donald-$label-$(date +%Y%m%d%H%M%S)" \
      -v "$CELL_WORK/src:/src" -v "$CELL_WORK/work-$label:/work" -v "$CELL_WORK/store-$label:/store" \
      --env-file "$ENVF" "onpath-cell-job:$TAG" > "$blog" 2>&1 8>&- &
    dpid=$!
    for i in $(seq 1 30); do
      [ -n "$(docker ps -q --filter "name=^onpath-cell-$label\$")" ] && break
      sleep 1
    done
    flock -u 8
    wait "$dpid"
    rc=$?
    docker rm -f "onpath-cell-$label" >/dev/null 2>&1 || true   # after a timeout
    dt=$(( $(date +%s) - t0 ))
    per=$(( dt / ${#cells[@]} ))
    for c in "${cells[@]}"; do
      line=$(grep -m1 "cell $c: ready (" "$blog")
      if [ -n "$line" ]; then
        sz=$(sed -n 's/.*, \([0-9]*\) bytes).*/\1/p' <<< "$line")
        note "$AREA/done.txt" "$c $per $sz $(date +%H:%M)"
        cell_log "$c: ready ($(( sz / 1048576 )) MB)"
      else
        why=$(grep -E "cell $c: " "$blog" | grep -vE 'sources|ready' | tail -1 | cut -c1-160)
        retry_or_fail "$c" "${why:-container exit $rc}"
      fi
    done
    cell_log "$label: batch of ${#cells[@]} in ${dt} s (exit $rc), peak RAM per step $(grep -o 'peak [0-9]* MB' "$blog" | sort -k2 -n | tail -1)"
    rm -f "$AREA/running-$1"
    rm -rf "$CELL_WORK/work-$label/cells" "$CELL_WORK/store-$label/cells"   # outputs are uploaded; failed temp folders go
    write_status
  done
}

# ------------------------------------------------------------------------------------ run, stop cleanly
PIDS=()
stop() {
  cell_log "stop requested – killing this run's containers"
  kill "${PIDS[@]}" 2>/dev/null
  docker ps -q --filter "label=onpath-area=$NAME" | xargs -r docker kill >/dev/null 2>&1
  rm -f "$AREA"/running-*
  write_status; sed -i 's/^\(\S* \S* \)/\1STOPPED /' "$STATUS"
  exit 143
}
trap stop TERM INT HUP
rm -f "$AREA"/running-*
write_status
for i in $(seq 1 "$PARALLEL"); do
  worker "$i" &
  PIDS+=($!)
  sleep 20   # the two containers do not start with the same downloads at the same second
done
wait "${PIDS[@]}"
write_status
cell_log "=== finished: $(cat "$STATUS")"
[ -s "$AREA/failed.txt" ] && cell_log "failed: $(tr '\n' ';' < "$AREA/failed.txt")"
exit 0
