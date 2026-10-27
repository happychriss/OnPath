# Shared by tools/dev/push_cell.sh and tools/dev/publish_cells.sh (source it, do not run it): build map cells on this
# PC with the cloud builder image (tools/cells/Dockerfile + tools/cloud/Dockerfile.job) and job_build.py, so a local
# cell is made by exactly the code and tools the Cloud Run job uses (docs/design/map-hosting.md §7h).
#
# Work folder (not in the repo – OSM extracts, Planetiler sources, caches, outputs): $ONPATH_CELLS_WORK,
# default ~/onpath-cells-work. The owner works on this PC: images are built with make -j4, cells run with 4 CPUs at a
# small CPU share / IO weight (like nice) and 8 GiB, like the cloud job.

CELL_WORK=${ONPATH_CELLS_WORK:-$HOME/onpath-cells-work}

cell_log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
cell_die() { cell_log "ERROR: $*"; exit 1; }

# "10-531-342" or "10/531/342" -> "10-531-342" (only z10 cells)
cell_norm() {
  local c=${1//\//-}
  [[ "$c" =~ ^10-[0-9]{1,4}-[0-9]{1,4}$ ]] || cell_die "bad cell id '$1' (expected e.g. 10-531-342)"
  echo "$c"
}

# cell_build_images SRC_DIR REV TAG: base image (cached by Docker) + job image onpath-cell-job:TAG from SRC_DIR (a
# checkout or an exported tree: needs tools/, app/libs/, app/src/main/assets/brouter/). REV = manifest "pipeline".
cell_build_images() {
  local src=$1 rev=$2 tag=$3
  command -v docker >/dev/null || cell_die "docker is missing"
  mkdir -p "$CELL_WORK/docker" "$CELL_WORK/logs"
  # the cloud's base Dockerfile, only with make -j4 instead of all cores
  sed 's/-j"$(nproc)"/-j4/' "$src/tools/cells/Dockerfile" > "$CELL_WORK/docker/Dockerfile.base"
  cell_log "building the builder image (log: $CELL_WORK/logs/docker-$tag.log)"
  docker build --network host -t onpath-cell-builder:local -f "$CELL_WORK/docker/Dockerfile.base" "$src/tools/cells" \
    > "$CELL_WORK/logs/docker-$tag.log" 2>&1 || { tail -20 "$CELL_WORK/logs/docker-$tag.log" >&2; cell_die "base image build failed"; }
  docker build --network host --build-arg BASE=onpath-cell-builder:local --build-arg PIPELINE_REV="$rev" \
    --build-context brouterlibs="$src/app/libs" --build-context brouter="$src/app/src/main/assets/brouter" \
    -t "onpath-cell-job:$tag" -f "$src/tools/cloud/Dockerfile.job" "$src/tools" \
    >> "$CELL_WORK/logs/docker-$tag.log" 2>&1 || { tail -20 "$CELL_WORK/logs/docker-$tag.log" >&2; cell_die "job image build failed"; }
}

# cell_run_job TAG LABEL ENV_FILE OUT_DIR CELL... : one container builds the cells one after the other.
#   ENV_FILE empty -> no upload: job_build.py writes the R2 keys into OUT_DIR (R2_LOCAL_DIR)
#   ENV_FILE set   -> upload to that R2 bucket (credentials only via --env-file, never on the command line)
# Source extracts and Planetiler sources are shared in $CELL_WORK/src (job_build.py locks downloads with O_EXCL files,
# fine for containers on one machine); every LABEL gets its own work folder.
cell_run_job() {
  local tag=$1 label=$2 envfile=$3 out=$4; shift 4
  mkdir -p "$CELL_WORK/src" "$CELL_WORK/work-$label" "$CELL_WORK/store-$label" "$CELL_WORK/logs"
  local args=(--rm --name "onpath-cell-$label-$$" --cpus 4 --memory 8g --cpu-shares 64 --blkio-weight 10
    --user "$(id -u):$(id -g)" --network host
    -e CELLS="$*" -e FORMAT=2 -e SRC_DIR=/src -e WORK=/work -e DATA_ROOT=/store -e OMP_NUM_THREADS=1 -e JAVA_XMX=2g
    -e HOME=/tmp -e CLOUD_RUN_EXECUTION="donald-$label-$(date +%Y%m%d%H%M%S)"
    -v "$CELL_WORK/src:/src" -v "$CELL_WORK/work-$label:/work" -v "$CELL_WORK/store-$label:/store")
  if [ -n "$envfile" ]; then
    args+=(--env-file "$envfile")
  else
    mkdir -p "$out"
    args+=(-e R2_LOCAL_DIR=/r2 -v "$out:/r2")
  fi
  cell_log "building $* (log: $CELL_WORK/logs/run-$label.log)"
  docker run "${args[@]}" "onpath-cell-job:$tag" > "$CELL_WORK/logs/run-$label.log" 2>&1 \
    || { tail -30 "$CELL_WORK/logs/run-$label.log" >&2; cell_die "build of $* failed"; }
  # job_build.py exits 0 also when a cell failed (that is the doorman's business in the cloud) – not here
  grep -q "done: $# */ *$# cells ready" "$CELL_WORK/logs/run-$label.log" \
    || { grep -E "failed|ERROR" "$CELL_WORK/logs/run-$label.log" | tail -10 >&2; cell_die "not every cell of $* was built"; }
}
