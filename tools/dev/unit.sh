#!/usr/bin/env bash
# Start the Remote Control host of this computer in this checkout (docs/roles/start.md, tested on donald 2026-11-01):
#   tools/dev/unit.sh run <name>      e.g. donald, laptop
# A plain `claude remote-control` hosts up to 32 sessions; restarting it reconnects every session of the folder.
# Spawn mode "worktree" (press w once in the folder, saved per folder): every new session gets its own git worktree.
# Runs through `script` so keys like w work and a copy of the output lands in ~/.local/state/onpath-unit/<name>.log.
# Exit 3 = this folder is not trusted yet: run `claude` in it once, accept, /exit, then start again.
# Env: ONPATH_PERMISSION_MODE (→ --permission-mode for the hosted sessions; the owner chose auto).
set -uo pipefail
cmd=${1:-}; NAME=${2:-}
DIR=$(cd "$(dirname "$0")/../.." && pwd)
STATE=${XDG_STATE_HOME:-$HOME/.local/state}/onpath-unit
mkdir -p "$STATE"
die() { echo "unit: $*" >&2; exit 2; }
need_name() { [ -n "$NAME" ] || die "$cmd needs a name, e.g. donald or laptop"; case "$NAME" in *[!a-z0-9-]*) die "name: a-z 0-9 - only" ;; esac; }

run() {
  need_name; cd "$DIR" || exit 2
  command -v claude >/dev/null || die "claude CLI not found"
  local log=$STATE/$NAME.log mode=() rc
  [ -n "${ONPATH_PERMISSION_MODE:-}" ] && mode=(--permission-mode "$ONPATH_PERMISSION_MODE")
  echo "$(date '+%F %T') host start in $DIR" >> "$log"
  script -qefa -c "claude remote-control --remote-control-session-name-prefix $NAME ${mode[*]}" "$log"; rc=$?
  if tail -n 40 "$log" | grep -q "Workspace not trusted"; then
    echo "unit: this folder is not trusted yet – run \`claude\` in $DIR once, accept, /exit, then start again" | tee -a "$log"
    exit 3
  fi
  exit $rc
}

case "$cmd" in
  run) run ;;
  -h|--help|"") sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) die "unknown command $cmd (run)" ;;
esac
